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
                    assertEquals(receipt.blobId.value, Sha256.ofText(String(bytes.bytes, Charsets.UTF_8)), "worker $worker: the receipt's digest is the stored content")
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
}
