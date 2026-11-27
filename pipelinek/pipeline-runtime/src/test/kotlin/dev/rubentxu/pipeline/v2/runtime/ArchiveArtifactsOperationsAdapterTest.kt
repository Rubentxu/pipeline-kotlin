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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

/**
 * The capture contract of the archive adapter.
 *
 * Five guarantees are under test, and the fixture is a real directory tree the test owns
 * because the guarantee is about the bytes the retention store holds:
 *
 * - Every receipt states a digest and a size READ BACK from the stored target, and the
 *   durable `ArtifactArchived` event declares exactly what the receipt asserts.
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

    @Test
    fun `the receipt states the digest read back from the stored target`() {
        val sink = prepare()
        Files.createDirectories(workspace.resolve("build"))
        Files.writeString(workspace.resolve("build/a.txt"), "contenido-a")

        val result = adapter(sink).archive(ArchiveArtifactsInput(artifacts = "build/**"))
        val success = assertInstanceOf(ArchiveArtifactsSuccess::class.java, result)
        val receipt = success.files.single()
        val stored = artifactDir.resolve("build/a.txt")
        val digestAddress = artifactDir.resolve("_blobs").resolve(receipt.sha256)

        assertEquals("build/a.txt", receipt.relPath)
        assertEquals(Sha256.ofFile(stored), receipt.sha256, "the receipt must carry the digest of the STORED bytes")
        assertEquals(Sha256.ofFile(digestAddress), receipt.sha256, "the receipt must name the bytes the write-once address holds")
        assertEquals(Files.size(stored), receipt.size)
        assertEquals("run-1", receipt.runId)
        assertEquals("stage-a", receipt.stageName)

        val event = sink.appended.archivedEvents().single()
        val entry = event.files.single()
        assertEquals(receipt.runId, entry.runId)
        assertEquals(receipt.stageName, entry.stageName)
        assertEquals(receipt.relPath, entry.relPath)
        assertEquals(receipt.sha256, entry.sha256, "the event must declare exactly what the receipt asserts")
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

        val firstBlob = artifactDir.resolve("_blobs").resolve(first.sha256)
        assertEquals("first-bytes", Files.readString(firstBlob), "the first receipt's bytes must stay retrievable at their digest address")
        assertEquals(first.sha256, Sha256.ofFile(firstBlob))
        assertEquals(first.size, Files.size(firstBlob))

        val secondBlob = artifactDir.resolve("_blobs").resolve(second.sha256)
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

        val blob = artifactDir.resolve("_blobs").resolve(first.sha256)
        val sentinel = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"))
        Files.setLastModifiedTime(blob, sentinel)

        val second = assertInstanceOf(
            ArchiveArtifactsSuccess::class.java,
            adapter(sink).archive(ArchiveArtifactsInput(artifacts = "same.txt")),
        ).files.single()

        assertEquals(first.sha256, second.sha256)
        assertEquals(sentinel, Files.getLastModifiedTime(blob), "a digest address is write-once: no later capture reopens it")
    }

    @Test
    fun `a digest address that does not hold the bytes its name promises refuses the capture`() {
        val sink = prepare()
        val content = "contenido-verificado"
        val source = workspace.resolve("poison.txt")
        Files.writeString(source, content)
        // A pre-existing address holding other bytes: the write-once store must refuse
        // the capture instead of minting a receipt for bytes it does not hold.
        val poisoned = artifactDir.resolve("_blobs").resolve(Sha256.ofText(content))
        Files.createDirectories(poisoned.parent)
        Files.writeString(poisoned, "otra-cosa")

        val result = adapter(sink).archive(ArchiveArtifactsInput(artifacts = "poison.txt"))

        assertInstanceOf(ArchiveArtifactsFailed::class.java, result)
        assertEquals("otra-cosa", Files.readString(poisoned), "the write-once address is never rewritten by the refused capture")
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

        assertNotEquals(first.sha256, second.sha256)
        assertEquals(Sha256.ofText("first-bytes"), first.sha256)
        assertEquals(Sha256.ofText("second-bytes-longer"), second.sha256)

        val stored = artifactDir.resolve("dup.txt")
        assertEquals("second-bytes-longer", Files.readString(stored), "the store keeps the latest capture")

        val entries = sink.appended.archivedEvents().flatMap { it.files }
        assertEquals(
            listOf(first.sha256, second.sha256),
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

        assertEquals(first.sha256, second.sha256, "same content means the same receipt")
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
        assertEquals(listOf("build/a.txt"), success.files.map { it.relPath })
        assertTrue(Files.notExists(artifactDir.resolve("build/skip.txt")))
    }
}
