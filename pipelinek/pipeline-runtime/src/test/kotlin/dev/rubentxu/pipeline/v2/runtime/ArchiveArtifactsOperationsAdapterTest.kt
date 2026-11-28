package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed
import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.Collections
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The capture contract of the archive adapter.
 *
 * Five guarantees are under test, and the fixture is a real directory tree the test owns
 * because the guarantee is about the bytes the retention store holds:
 *
 * - Every receipt states a digest and a size READ BACK from the stored target, and the
 *   durable `ArtifactArchived` event declares exactly what the receipt asserts.
 * - Every receipt carries an identity minted per capture, independent of the visible
 *   name and of the bytes: two captures under one visible name — or over the same
 *   bytes — are two captures, each resolving to its own digest address.
 * - The bytes a receipt names stay retrievable and untouched: a later capture under the
 *   same visible name lands on its own digest address and never mutates the write-once
 *   copy an earlier receipt names.
 * - Re-archiving identical bytes is idempotent and does not reopen the stored file: the
 *   truncate-and-write window is only for bytes that actually changed.
 * - Neither a source reached through a symbolic link outside the workspace, nor a link
 *   planted at the target position or inside the retention tree, moves bytes out of the
 *   authorized roots. The refusal is fail-closed: no success event on a violated capture.
 * - The legacy envelope is preserved: an empty match with `allowEmptyArchive` is one
 *   empty event, without it a typed failure, and `excludes` keeps a file out of the capture.
 * - Under a live race — the source flipped between whole versions while captures run —
 *   every emitted receipt keeps its labels true: its address holds the bytes its digest
 *   names at its size, `openCapture` answers them back, the durable event repeats them,
 *   and each capture carries its own minted identity. A raced source is refused, never
 *   succeeded into a receipt the store cannot answer.
 * - The minted identity is PUBLIC: it travels on the durable event entry, the step's
 *   wire output and the recorded handle, two same-name captures stay distinguishable by
 *   it after the whole workspace is erased, and each identity recovers exactly its own
 *   bytes from the retention tree.
 * - The read-back is ONE authorized opening: the delivered bytes are byte-for-byte the
 *   bytes that single opening digested — a node swapped in after it delivers nothing —
 *   a link grown at the address before the opening is refused even when its bytes fit
 *   the receipt, and a receipt naming more bytes than the contract's ceiling is refused,
 *   never read. The swap windows are call order through the read seam, never a timed race.
 */
class ArchiveArtifactsOperationsAdapterTest {

    @TempDir
    lateinit var tempDir: Path

    /** Keeps every appended event so a row can assert on what the sink was told. */
    private class RecordingSink : EventSink {
        val appended = mutableListOf<DomainEvent>()
        override fun append(event: DomainEvent) {
            appended.add(event)
        }
        override fun eventsFor(runId: String): Sequence<DomainEvent> = appended.asSequence()
    }

    private val workspace: Path get() = tempDir.resolve("ws")
    private val controlRoot: Path get() = tempDir.resolve("control")
    private val artifactDir: Path get() = controlRoot.resolve("artefacts/run-1/stage-a")

    private fun prepare(): RecordingSink {
        Files.createDirectories(workspace)
        Files.createDirectories(controlRoot)
        return RecordingSink()
    }

    private fun adapter(sink: EventSink) =
        ArchiveArtifactsOperationsAdapter(
            runIdString = "run-1",
            stageIdentity = StageIdentity(name = "stage-a", index = 0),
            controlDirRoot = controlRoot,
            eventSink = sink,
            executionLocation = ExecutionLocation(
                workspace = WorkspaceLease.Managed(workspace),
                cwd = workspace,
            ),
        )

    private fun List<DomainEvent>.archivedEvents(): List<ArtifactArchived> = filterIsInstance<ArtifactArchived>()

    // ---- the verified read-back of a capture (openCapture) ----
    //
    // The read-back is the half of the capture law the publisher consumes: the bytes it
    // returns must be the bytes the receipt names, read from the write-once address —
    // never the workspace's current answer. Every guard the write applied holds the read:
    // an address that holds nothing, a link planted at the address, a size or a digest
    // that diverges from the receipt, each refuses the read instead of handing out bytes
    // under suspicion.

    @Test
    fun `openCapture returns the captured bytes their receipt names, never the workspace's current answer`() {
        val sink = prepare()
        Files.createDirectories(workspace.resolve("build"))
        Files.writeString(workspace.resolve("build/a.txt"), "captured-content")
        val receipt = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "build/**")),
        ).files.single()
        Files.writeString(workspace.resolve("build/a.txt"), "changed-live-bytes")

        val read = adapter(sink).openCapture(receipt)

        val bytes = assertInstanceOf(CapturedBytes::class.java, read)
        assertEquals("captured-content", String(bytes.bytes, Charsets.UTF_8), "the read answers about the CAPTURE")
    }

    @Test
    fun `an address that holds no capture refuses the read-back`() {
        val sink = prepare()
        Files.createDirectories(workspace.resolve("build"))
        Files.writeString(workspace.resolve("build/a.txt"), "captured-content")
        val receipt = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "build/**")),
        ).files.single()

        val read = adapter(sink).openCapture(receipt.copy(blobId = BlobId(Sha256.ofText("bytes-never-captured"))))

        val refusal = assertInstanceOf(CapturedBytesRefusal::class.java, read)
        assertTrue(refusal.message.contains(receipt.name.value), refusal.message)
        assertTrue(refusal.message.contains("holds no capture"), refusal.message)
    }

    @Test
    fun `a receipt naming a size the stored bytes do not occupy refuses the read-back`() {
        val sink = prepare()
        Files.createDirectories(workspace.resolve("build"))
        Files.writeString(workspace.resolve("build/a.txt"), "captured-content")
        val receipt = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "build/**")),
        ).files.single()

        val read = adapter(sink).openCapture(receipt.copy(size = receipt.size + 1))

        val refusal = assertInstanceOf(CapturedBytesRefusal::class.java, read)
        assertTrue(refusal.message.contains((receipt.size + 1).toString()), refusal.message)
        assertTrue(refusal.message.contains(receipt.size.toString()), refusal.message)
    }

    @Test
    fun `an address holding bytes that digest to another identity refuses the read-back`() {
        val sink = prepare()
        Files.createDirectories(workspace.resolve("build"))
        Files.writeString(workspace.resolve("build/a.txt"), "captured-content")
        val receipt = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "build/**")),
        ).files.single()
        val blob = artifactDir.resolve("_blobs").resolve(receipt.blobId.value)
        // Same size, different content: the size guard passes, the identity guard must not.
        Files.writeString(blob, "z".repeat(receipt.size.toInt()))

        val read = adapter(sink).openCapture(receipt)

        val refusal = assertInstanceOf(CapturedBytesRefusal::class.java, read)
        assertTrue(refusal.message.contains("digesting to"), refusal.message)
        assertTrue(refusal.message.contains(receipt.blobId.value), refusal.message)
    }

    @Test
    fun `a link planted at the digest address refuses the read-back instead of being followed`() {
        val sink = prepare()
        Files.createDirectories(workspace.resolve("build"))
        Files.writeString(workspace.resolve("build/a.txt"), "captured-content")
        val receipt = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "build/**")),
        ).files.single()
        val blob = artifactDir.resolve("_blobs").resolve(receipt.blobId.value)
        Files.delete(blob)
        Files.createSymbolicLink(blob, workspace.resolve("build/a.txt").toAbsolutePath())

        val read = adapter(sink).openCapture(receipt)

        val refusal = assertInstanceOf(CapturedBytesRefusal::class.java, read)
        assertTrue(refusal.message.contains("not a regular stored file"), refusal.message)
    }

    @Test
    fun `the receipt states the digest read back from the stored target`() {
        val sink = prepare()
        Files.createDirectories(workspace.resolve("build"))
        Files.writeString(workspace.resolve("build/a.txt"), "contenido-a")

        val result = adapter(sink).archive(ArchiveArtifactsInput(artifacts = "build/**"))
        val success = assertInstanceOf(ArchiveArtifactsSuccess::class.java, result)
        val receipt = success.files.single()
        val stored = artifactDir.resolve("build/a.txt")
        val digestAddress = artifactDir.resolve("_blobs").resolve(receipt.blobId.value)

        assertEquals("build/a.txt", receipt.name.value)
        assertEquals(Sha256.ofFile(stored), receipt.blobId.value, "the receipt must carry the digest of the STORED bytes")
        assertEquals(Sha256.ofFile(digestAddress), receipt.blobId.value, "the receipt must name the bytes the write-once address holds")
        assertEquals(Files.size(stored), receipt.size)
        assertEquals("run-1", receipt.runId)
        assertEquals("stage-a", receipt.stageName)

        val event = sink.appended.archivedEvents().single()
        val entry = event.files.single()
        assertEquals(receipt.runId, entry.runId)
        assertEquals(receipt.stageName, entry.stageName)
        assertEquals(receipt.name.value, entry.relPath)
        assertEquals(receipt.blobId.value, entry.sha256, "the event must declare exactly what the receipt asserts")
        assertEquals(receipt.size, entry.size)
    }

    @Test
    fun `a later capture with the same visible name never mutates the bytes an earlier receipt names`() {
        val sink = prepare()
        val source = workspace.resolve("dup.txt")
        Files.writeString(source, "first-bytes")
        val first = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "dup.txt")),
        ).files.single()

        Files.writeString(source, "second-bytes-longer")
        val second = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "dup.txt")),
        ).files.single()

        val firstBlob = artifactDir.resolve("_blobs").resolve(first.blobId.value)
        assertEquals("first-bytes", Files.readString(firstBlob), "the first receipt's bytes must stay retrievable at their digest address")
        assertEquals(first.blobId.value, Sha256.ofFile(firstBlob))
        assertEquals(first.size, Files.size(firstBlob))

        val secondBlob = artifactDir.resolve("_blobs").resolve(second.blobId.value)
        assertEquals("second-bytes-longer", Files.readString(secondBlob), "the second capture lands on its own digest address")
        assertEquals("second-bytes-longer", Files.readString(artifactDir.resolve("dup.txt")), "the visible name keeps the latest capture")
    }

    @Test
    fun `re-capturing identical bytes never rewrites the digest address that already holds them`() {
        val sink = prepare()
        Files.writeString(workspace.resolve("same.txt"), "mismo-contenido")
        val first = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "same.txt")),
        ).files.single()

        val blob = artifactDir.resolve("_blobs").resolve(first.blobId.value)
        val sentinel = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"))
        Files.setLastModifiedTime(blob, sentinel)

        val second = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "same.txt")),
        ).files.single()

        assertEquals(first.blobId, second.blobId)
        assertNotEquals(first.captureId, second.captureId, "same bytes, same name, still two captures")
        assertEquals(sentinel, Files.getLastModifiedTime(blob), "a digest address is write-once: no later capture reopens it")
    }

    @Test
    fun `a digest address that does not hold the bytes its name promises refuses the capture`() {
        val sink = prepare()
        val content = "contenido-verificado"
        val source = workspace.resolve("poison.txt")
        Files.writeString(source, content)
        // A pre-existing address holding OTHER bytes of the SAME size: only the digest
        // read-back can tell them apart, and the write-once store must refuse the
        // capture instead of minting a receipt for bytes it does not hold.
        val poisoned = artifactDir.resolve("_blobs").resolve(Sha256.ofText(content))
        Files.createDirectories(poisoned.parent)
        Files.writeString(poisoned, "XXXXXXXXXXXXXXXXXXXX")

        val result = adapter(sink).archive(ArchiveArtifactsInput(artifacts = "poison.txt"))

        assertInstanceOf(ArchiveArtifactsFailed::class.java, result)
        assertEquals("XXXXXXXXXXXXXXXXXXXX", Files.readString(poisoned), "the write-once address is never rewritten by the refused capture")
    }

    @Test
    fun `a link planted at a digest address refuses the capture and never writes through it`() {
        val sink = prepare()
        val content = "contenido-direccional"
        Files.writeString(workspace.resolve("addr.txt"), content)
        // The link points at bytes OUTSIDE the store whose digest matches the capture:
        // only the no-follow regular-file check can refuse it, so a receipt is never
        // minted over bytes the retention tree does not hold.
        Files.writeString(controlRoot.resolve("fuera.txt"), content)
        Files.createDirectories(artifactDir.resolve("_blobs"))
        Files.createSymbolicLink(
            artifactDir.resolve("_blobs").resolve(Sha256.ofText(content)),
            controlRoot.resolve("fuera.txt"),
        )

        val result = adapter(sink).archive(ArchiveArtifactsInput(artifacts = "addr.txt"))

        val address = artifactDir.resolve("_blobs").resolve(Sha256.ofText(content))
        assertInstanceOf(ArchiveArtifactsFailed::class.java, result)
        assertTrue(Files.isSymbolicLink(address), "the planted address link must not be written through")
    }

    @Test
    fun `a link planted at the digest zone refuses the capture and stages nothing outside the store`() {
        val sink = prepare()
        Files.createDirectories(artifactDir)
        Files.createDirectories(controlRoot.resolve("outside"))
        Files.createSymbolicLink(artifactDir.resolve("_blobs"), controlRoot.resolve("outside"))
        Files.writeString(workspace.resolve("ok.txt"), "contenido-normal")

        val result = adapter(sink).archive(ArchiveArtifactsInput(artifacts = "ok.txt"))

        assertInstanceOf(ArchiveArtifactsFailed::class.java, result)
        assertTrue(
            Files.notExists(controlRoot.resolve("outside").resolve("ok.txt")),
            "no byte may land outside the store through the planted zone link",
        )
    }

    @Test
    fun `a capture whose visible name falls in the reserved digest zone is refused and stores nothing`() {
        val sink = prepare()
        Files.writeString(workspace.resolve("_blobs"), "no-archivo-esto")
        val bare = adapter(sink).archive(ArchiveArtifactsInput(artifacts = "_blobs"))
        assertInstanceOf(ArchiveArtifactsFailed::class.java, bare)
        assertTrue(Files.notExists(artifactDir), "nothing may be stored by a refused capture")

        Files.delete(workspace.resolve("_blobs"))
        Files.createDirectories(workspace.resolve("_blobs"))
        Files.writeString(workspace.resolve("_blobs/hex"), "no-archivo-esto-tampoco")
        val nested = adapter(sink).archive(ArchiveArtifactsInput(artifacts = "_blobs/**"))
        assertInstanceOf(ArchiveArtifactsFailed::class.java, nested)
        assertTrue(Files.notExists(artifactDir), "nothing may be stored by a refused capture")
    }

    @Test
    fun `archiving the same name twice with different bytes keeps both recorded identities`() {
        val sink = prepare()
        val source = workspace.resolve("dup.txt")
        Files.writeString(source, "first-bytes")
        val first = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "dup.txt")),
        ).files.single()

        Files.writeString(source, "second-bytes-longer")
        val second = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "dup.txt")),
        ).files.single()

        assertNotEquals(first.blobId, second.blobId)
        assertEquals(Sha256.ofText("first-bytes"), first.blobId.value)
        assertEquals(Sha256.ofText("second-bytes-longer"), second.blobId.value)

        val stored = artifactDir.resolve("dup.txt")
        assertEquals("second-bytes-longer", Files.readString(stored), "the store keeps the latest capture")

        val entries = sink.appended.archivedEvents().flatMap { it.files }
        assertEquals(
            listOf(first.blobId.value, second.blobId.value),
            entries.map { it.sha256 },
            "each event keeps the identity of the bytes its capture verified",
        )
    }

    @Test
    fun `re-archiving identical bytes keeps the stored file closed and returns an identical receipt`() {
        val sink = prepare()
        Files.writeString(workspace.resolve("same.txt"), "mismo-contenido")
        val first = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "same.txt")),
        ).files.single()

        // A sentinel modification time is the observable record of the file being reopened:
        // a capture that recognizes identical stored bytes must not truncate-and-write again.
        val stored = artifactDir.resolve("same.txt")
        val sentinel = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"))
        Files.setLastModifiedTime(stored, sentinel)

        val second = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "same.txt")),
        ).files.single()

        assertEquals(first.blobId.value, second.blobId.value, "same content means the same bytes identity")
        assertEquals(first.size, second.size)
        assertEquals(sentinel, Files.getLastModifiedTime(stored), "identical stored bytes must not be rewritten")
    }

    @Test
    fun `a source symlink that leaves the workspace is refused and its bytes never enter the store`() {
        val sink = prepare()
        val outside = tempDir.resolve("outside")
        Files.createDirectories(outside)
        Files.writeString(outside.resolve("secret.txt"), "no-archivar")
        Files.createSymbolicLink(workspace.resolve("escape.txt"), outside.resolve("secret.txt"))

        val result = adapter(sink).archive(ArchiveArtifactsInput(artifacts = "escape.txt"))

        assertInstanceOf(ArchiveArtifactsFailed::class.java, result, "a source outside the workspace fails closed")
        assertTrue(sink.appended.none { it is ArtifactArchived }, "a violated capture emits no success event")
        assertTrue(sink.appended.any { it is ArtifactArchiveFailed })
        assertTrue(Files.notExists(artifactDir.resolve("escape.txt")), "the escaped bytes must not be stored")
    }

    @Test
    fun `a symbolic link stored at the target position is refused before bytes are written through it`() {
        val sink = prepare()
        val outside = tempDir.resolve("outside")
        Files.createDirectories(outside)
        val victim = outside.resolve("victim.txt")
        Files.writeString(victim, "original")

        Files.createDirectories(artifactDir)
        Files.createSymbolicLink(artifactDir.resolve("x.txt"), victim)
        Files.writeString(workspace.resolve("x.txt"), "nuevo-contenido")

        val result = adapter(sink).archive(ArchiveArtifactsInput(artifacts = "x.txt"))

        assertInstanceOf(ArchiveArtifactsFailed::class.java, result, "a link at the target position fails closed")
        assertEquals("original", Files.readString(victim), "bytes must never be written through a stored link")
        assertTrue(sink.appended.none { it is ArtifactArchived })
    }

    @Test
    fun `a link planted inside the retention tree cannot move captured bytes outside it`() {
        val sink = prepare()
        val escapeDir = tempDir.resolve("escape-dir")
        Files.createDirectories(escapeDir)

        Files.createDirectories(artifactDir.resolve("sub"))
        Files.delete(artifactDir.resolve("sub"))
        Files.createSymbolicLink(artifactDir.resolve("sub"), escapeDir)

        Files.createDirectories(workspace.resolve("sub"))
        Files.writeString(workspace.resolve("sub/y.txt"), "viaja")

        val result = adapter(sink).archive(ArchiveArtifactsInput(artifacts = "sub/**"))

        assertInstanceOf(ArchiveArtifactsFailed::class.java, result, "a planted directory link fails closed")
        assertTrue(Files.notExists(escapeDir.resolve("y.txt")), "no byte may land through the planted link")
        assertTrue(sink.appended.none { it is ArtifactArchived })
    }

    @Test
    fun `allowEmptyArchive turns an empty match into one empty event instead of a failure`() {
        val sink = prepare()

        val result = adapter(sink).archive(
            ArchiveArtifactsInput(artifacts = "nothing/**", allowEmptyArchive = true),
        )

        val success = assertInstanceOf(ArchiveArtifactsSuccess::class.java, result)
        assertTrue(success.files.isEmpty())
        assertEquals(1, sink.appended.archivedEvents().size, "legacy emits the event with zero entries")
        assertTrue(sink.appended.archivedEvents().single().files.isEmpty())
    }

    @Test
    fun `an empty match without allowEmptyArchive is a typed failure with the failed event`() {
        val sink = prepare()

        val result = adapter(sink).archive(ArchiveArtifactsInput(artifacts = "nothing/**"))

        val failed = assertInstanceOf(ArchiveArtifactsFailed::class.java, result)
        assertEquals(dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT, failed.failureKind)
        assertTrue(sink.appended.any { it is ArtifactArchiveFailed })
        assertTrue(sink.appended.none { it is ArtifactArchived })
    }

    @Test
    fun `excludes keeps a matched file out of the capture`() {
        val sink = prepare()
        Files.createDirectories(workspace.resolve("build"))
        Files.writeString(workspace.resolve("build/a.txt"), "a")
        Files.writeString(workspace.resolve("build/skip.txt"), "b")

        val result = adapter(sink).archive(
            ArchiveArtifactsInput(artifacts = "build/**", excludes = "build/skip.txt"),
        )

        val success = assertInstanceOf(ArchiveArtifactsSuccess::class.java, result)
        assertEquals(listOf("build/a.txt"), success.files.map { it.name.value })
        assertTrue(Files.notExists(artifactDir.resolve("build/skip.txt")))
    }

    @Test
    fun `two captures with the same visible name are never confusable by identity`() {
        val sink = prepare()
        val source = workspace.resolve("dup.txt")
        Files.writeString(source, "first-bytes")
        val first = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "dup.txt")),
        ).files.single()

        Files.writeString(source, "second-bytes-longer")
        val second = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "dup.txt")),
        ).files.single()

        // The visible name is shared, and it decides nothing: the two receipts stay
        // distinguishable by their minted capture identities and by their digest
        // identities, and each resolves to ITS OWN digest address.
        assertEquals(first.name, second.name, "the scenario: one visible name, two captures")
        assertNotEquals(first.captureId, second.captureId, "the capture identity is minted per capture, never per name")
        assertNotEquals(first.blobId, second.blobId, "different bytes are different digest identities")

        val firstAddress = artifactDir.resolve("_blobs").resolve(first.blobId.value)
        val secondAddress = artifactDir.resolve("_blobs").resolve(second.blobId.value)
        assertEquals("first-bytes", Files.readString(firstAddress), "the first receipt resolves to its own bytes")
        assertEquals("second-bytes-longer", Files.readString(secondAddress), "the second receipt resolves to its own bytes")
        assertEquals(Sha256.ofText("first-bytes"), Sha256.ofFile(firstAddress))
        assertEquals(Sha256.ofText("second-bytes-longer"), Sha256.ofFile(secondAddress))
    }

    @Test
    fun `captures of identical bytes share the digest identity and never the capture identity`() {
        val sink = prepare()
        Files.writeString(workspace.resolve("a.txt"), "mismos-bytes")
        Files.writeString(workspace.resolve("b.txt"), "mismos-bytes")

        val receipts = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "*.txt")),
        ).files.sortedBy { it.name.value }

        assertEquals(2, receipts.size)
        val (namedA, namedB) = receipts
        assertNotEquals(namedA.name, namedB.name, "different visible names, one archive call")
        assertEquals(namedA.blobId, namedB.blobId, "the digest identifies the bytes both captures secured")
        assertNotEquals(namedA.captureId, namedB.captureId, "two captures under two names are two captures")

        // Both receipts resolve to the same write-once address, and that address holds
        // the bytes: the content identity is shared, the capture identity is not.
        val address = artifactDir.resolve("_blobs").resolve(namedA.blobId.value)
        assertEquals(address, artifactDir.resolve("_blobs").resolve(namedB.blobId.value))
        assertEquals("mismos-bytes", Files.readString(address))
    }

    @Test
    fun `captures of a concurrently rewritten source never emit a receipt the store cannot answer`() {
        // The race certification of the receipt identity. A real thread flips the source
        // between two WHOLE versions (atomic renames, so the file is never half-written)
        // while eight workers capture the same visible name. For every possible
        // interleaving the law is the same: a success names bytes the digest address
        // actually holds — `openCapture` answers them back, at the receipt's size, as
        // exactly one whole version — and its durable event repeats the receipt's own
        // labels. A capture whose bytes diverged mid-flight is an honest typed refusal
        // with a failed event and no success event, never a receipt that lies.
        val versionA = "capture-race-A".repeat(256)
        val versionB = "capture-race-BB".repeat(512)
        prepare()
        val source = workspace.resolve("race.txt")
        Files.writeString(source, versionA)

        val workerCount = 8
        val roundsPerWorker = 5
        val start = CountDownLatch(1)
        val finished = CountDownLatch(workerCount)
        val flipStop = AtomicBoolean(false)
        val flipper = Thread {
            val staged = workspace.resolve(".flip.tmp")
            var flip = false
            while (!flipStop.get()) {
                Files.writeString(staged, if (flip) versionA else versionB)
                Files.move(staged, source, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                flip = !flip
                // The margin the law needs: a whole version stands still long enough
                // for a capture to finish between two flips. An attempt publishes or it
                // refuses — the outcome itself says whether the machine gave the margin.
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000)
            }
        }.also(Thread::start)
        val outcomes = Collections.synchronizedList(
            mutableListOf<Pair<ArchiveArtifactsResult, RecordingSink>>(),
        )
        val threads = (0 until workerCount).map {
            Thread {
                try {
                    start.await()
                    repeat(roundsPerWorker) {
                        val sink = RecordingSink()
                        outcomes += adapter(sink).archive(ArchiveArtifactsInput(artifacts = "race.txt")) to sink
                    }
                } finally {
                    finished.countDown()
                }
            }.also(Thread::start)
        }
        start.countDown()
        assertTrue(finished.await(60, TimeUnit.SECONDS), "every capture must return")
        flipStop.set(true)
        threads.forEach { it.join(10_000) }
        flipper.join(10_000)

        var successes = 0
        val captureIds = mutableSetOf<String>()
        for ((worker, pair) in outcomes.withIndex()) {
            val (outcome, sink) = pair
            when (outcome) {
                is ArchiveArtifactsSuccess -> {
                    successes++
                    val receipt = outcome.files.single()
                    // The label law, measured after the store is still: the read-back
                    // answers the receipt's own bytes, or the success is a lie.
                    val read = adapter(RecordingSink()).openCapture(receipt)
                    val bytes = assertInstanceOf(
                        CapturedBytes::class.java,
                        read,
                        "worker $worker: a success receipt must be answerable from its digest address",
                    )
                    assertEquals(receipt.size, bytes.bytes.size.toLong(), "worker $worker: the receipt's size is the stored size")
                    assertEquals(
                        receipt.blobId.value,
                        Sha256.ofText(String(bytes.bytes, Charsets.UTF_8)),
                        "worker $worker: the receipt's digest is the stored content",
                    )
                    val capturedText = String(bytes.bytes, Charsets.UTF_8)
                    assertTrue(
                        capturedText == versionA || capturedText == versionB,
                        "worker $worker: the capture is one whole version, never a mix of the raced states",
                    )
                    // The durable record repeats the receipt's labels, not the workspace's
                    // state at event time.
                    val events = sink.appended.archivedEvents()
                    assertEquals(1, events.size, "worker $worker: the success has exactly its archived event")
                    val entry = events.single().files.single()
                    assertEquals(receipt.blobId.value, entry.sha256, "worker $worker: the event names the receipt's digest")
                    assertEquals(receipt.size, entry.size, "worker $worker: the event names the receipt's size")
                    captureIds += receipt.captureId.value
                }
                is ArchiveArtifactsFailed -> {
                    assertTrue(outcome.message.isNotBlank(), "worker $worker: a raced refusal names its reason")
                    assertEquals(
                        0,
                        sink.appended.archivedEvents().size,
                        "worker $worker: a refused capture never emits a success event",
                    )
                }
            }
        }
        assertEquals(successes, captureIds.size, "two captures are two captures even under the race: no identity is reused")
        // The store's identity law, read once the race is still: a digest address is a
        // name of content, and the race of writes never leaves an address holding bytes
        // it does not name — a poisoned address would make every later honest capture
        // of that content a refusal, and no receipt truth survives that.
        val blobDir = artifactDir.resolve("_blobs")
        if (Files.exists(blobDir, LinkOption.NOFOLLOW_LINKS)) {
            Files.list(blobDir).use { addresses ->
                addresses
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .forEach { address ->
                        assertEquals(
                            address.fileName.toString(),
                            Sha256.ofFile(address),
                            "the digest address '${address.fileName}' holds bytes it does not name",
                        )
                    }
            }
        }
        if (successes == 0) {
            Assumptions.abort<Any>(
                "the flipper left no capture room to finish between two flips: on this saturated machine " +
                    "the label law had no margin to be exercised — the row measures nothing and is repaid " +
                    "on a free machine",
            )
        }
    }

    // ---- the public capture identity and the single opening (PKH-395) ----

    /**
     * The adapter with the read window made into a call order: [beforeOpen] acts on the
     * digest address immediately BEFORE the single authorized opening, [afterRead]
     * immediately after its bytes were consumed and before they are delivered. The
     * check-then-use window the read-back law closes cannot be timed with a second
     * thread against production code — it is exercised here by overriding the seam that
     * owns the opening, so every swap lands exactly where the law says nothing may.
     */
    private inner class ReadWindowAdapter(
        eventSink: EventSink,
        private val beforeOpen: (Path) -> Unit = {},
        private val afterRead: (Path) -> Unit = {},
    ) : ArchiveArtifactsOperationsAdapter(
        runIdString = "run-1",
        stageIdentity = StageIdentity(name = "stage-a", index = 0),
        controlDirRoot = controlRoot,
        eventSink = eventSink,
        executionLocation = ExecutionLocation(
            workspace = WorkspaceLease.Managed(workspace),
            cwd = workspace,
        ),
    ) {
        override fun readStoredBytesOnce(blob: Path, expectedSize: Long): ByteArray {
            beforeOpen(blob)
            val delivered = super.readStoredBytesOnce(blob, expectedSize)
            afterRead(blob)
            return delivered
        }
    }

    @Test
    fun `two same-name captures surface distinct public capture identities on the durable event`() {
        val sink = prepare()
        val source = workspace.resolve("dup.txt")
        Files.writeString(source, "first-bytes")
        val first = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "dup.txt")),
        ).files.single()

        Files.writeString(source, "second-bytes-longer")
        val second = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "dup.txt")),
        ).files.single()

        val entries = sink.appended.archivedEvents().flatMap { it.files }
        assertEquals(2, entries.size, "one archived event per capture, each with its entry")
        val identities = entries.map { it.captureId }
        assertTrue(
            identities.none { it == null },
            "a capture the adapter minted never reaches the durable channel anonymous",
        )
        assertEquals(2, identities.distinct().size, "two captures under one name are two public identities")

        // The pairing is the law's shape: each durable row pairs ITS capture identity with
        // the digest of the receipt that capture minted — never the other capture's.
        assertEquals(
            mapOf(
                first.captureId.value to first.blobId.value,
                second.captureId.value to second.blobId.value,
            ),
            entries.associate { it.captureId!! to it.sha256 },
            "each public identity stays paired with the bytes of its own capture",
        )
    }

    @Test
    fun `public capture identities recover exactly their own bytes after the workspace is erased`() {
        val sink = prepare()
        val source = workspace.resolve("dup.txt")
        Files.writeString(source, "bytes-of-A")
        val first = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "dup.txt")),
        ).files.single()

        Files.writeString(source, "bytes-of-B-longer")
        val second = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "dup.txt")),
        ).files.single()

        // The whole workspace is erased: whatever recovery remains must come from the
        // retention tree alone, keyed by the identities the public event carried.
        Files.walk(workspace).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        assertTrue(Files.notExists(workspace), "the fixture erased the workspace, not one file")

        val receipts = listOf(first, second).associateBy { it.captureId.value }
        val publishedIds = sink.appended.archivedEvents().flatMap { it.files }.map { it.captureId }
        assertEquals(
            receipts.keys,
            publishedIds.filterNotNull().toSet(),
            "every public identity the event names resolves to exactly one receipt",
        )

        val recovered = receipts.mapValues { (_, receipt) ->
            val bytes = assertInstanceOf(
                CapturedBytes::class.java,
                adapter(RecordingSink()).openCapture(receipt),
                "the capture must be answerable from retention with no workspace at all",
            )
            Sha256.ofBytes(bytes.bytes)
        }

        assertEquals(
            receipts.mapValues { it.value.blobId.value },
            recovered,
            "each identity recovers exactly its own bytes, never the other capture's",
        )
        assertEquals(2, recovered.values.distinct().size, "two homonymous captures stay two recoveries")
    }

    @Test
    fun `a node swapped after the authorized read never replaces the delivered bytes`() {
        val sink = prepare()
        Files.writeString(workspace.resolve("swap.txt"), "verified-window-bytes")
        val receipt = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "swap.txt")),
        ).files.single()
        // A same-size node planted at the address after the read: every content-computed
        // guard it passes by construction, so only a delivery that reopens the PATH could
        // ever mistake its bytes for the receipt's answer.
        val planted = tempDir.resolve("after-the-read")
        Files.writeString(planted, "X".repeat(receipt.size.toInt()))

        val read = ReadWindowAdapter(
            RecordingSink(),
            afterRead = { blob ->
                Files.delete(blob)
                Files.createSymbolicLink(blob, planted)
            },
        ).openCapture(receipt)

        val bytes = assertInstanceOf(CapturedBytes::class.java, read)
        assertEquals(
            receipt.blobId.value,
            Sha256.ofBytes(bytes.bytes),
            "the delivered bytes are exactly the bytes the one authorized opening read",
        )
        assertNotEquals(
            receipt.blobId.value,
            Sha256.ofFile(planted),
            "the swapped node holds other bytes: a path-reopening delivery would hand them out",
        )
    }

    @Test
    fun `an address turned into a link before the authorized open is refused even when the link holds the receipt bytes`() {
        val sink = prepare()
        Files.writeString(workspace.resolve("twin.txt"), "twin-bytes-outside")
        val receipt = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "twin.txt")),
        ).files.single()
        // The twin holds the IDENTICAL bytes outside the retention tree: size and digest
        // computed over content fit the receipt perfectly, so nothing but opening the
        // address without following the link can keep the read inside the authorized roots.
        val twin = tempDir.resolve("twin-target")
        Files.writeString(twin, "twin-bytes-outside")

        val read = ReadWindowAdapter(
            RecordingSink(),
            beforeOpen = { blob ->
                Files.delete(blob)
                Files.createSymbolicLink(blob, twin)
            },
        ).openCapture(receipt)

        assertInstanceOf(
            CapturedBytesRefusal::class.java,
            read,
            "a link grown at the address after the guards is refused at the open, never read through",
        )
    }

    @Test
    fun `a receipt naming more bytes than the read-back ceiling is refused rather than read`() {
        val sink = prepare()
        Files.writeString(workspace.resolve("cap.txt"), "capped")
        val receipt = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "cap.txt")),
        ).files.single()

        val read = adapter(sink).openCapture(
            receipt.copy(size = ArchiveArtifactsOperations.MAX_READ_BACK_BYTES + 1),
        )

        val refusal = assertInstanceOf(CapturedBytesRefusal::class.java, read)
        assertTrue(
            refusal.message.contains(ArchiveArtifactsOperations.MAX_READ_BACK_BYTES.toString()),
            "the refusal names the ceiling the contract states: ${refusal.message}",
        )
        assertTrue(
            refusal.message.contains(receipt.name.value),
            "the refusal names the capture it refuses: ${refusal.message}",
        )
    }
}
