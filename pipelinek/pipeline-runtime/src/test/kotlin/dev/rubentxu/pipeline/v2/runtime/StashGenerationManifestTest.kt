package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.StashCreated
import dev.rubentxu.pipeline.v2.events.StashRestored
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The generation contract of the stash adapter (PKH-351).
 *
 * The stash reuses the capture port of 339 — every row digested and sized by reading
 * it back FROM the stored target — but stays the run's temporary tier: no durable
 * artifact is published. Four guarantees are under test:
 *
 * - A published reference names a generation whose tree is COMPLETE: the manifest and
 *   the pointer are written only after every capture verified, so a reader never
 *   resolves a half-staged tree.
 * - Repeating a name mints an explicit generation instead of deleting in place: the
 *   snapshot a reader already resolved keeps its bytes and its manifest unchanged.
 * - A restash that fails leaves the reference where it was: the last complete
 *   generation stays the answer, and the refusal leaves no unreferenced generation
 *   behind.
 * - The stash never crosses into the durable artifact tier: no `ArtifactArchived`
 *   event, nothing under the retention directory.
 */
class StashGenerationManifestTest {

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

    private fun prepare(): RecordingSink {
        Files.createDirectories(workspace)
        Files.createDirectories(controlRoot)
        return RecordingSink()
    }

    private fun adapter(sink: EventSink) =
        StashOperationsAdapter(
            runIdString = "run-1",
            stageIdentity = StageIdentity(name = "stage-a", index = 0),
            controlDirRoot = controlRoot,
            eventSink = sink,
            executionLocation = ExecutionLocation(
                workspace = WorkspaceLease.Managed(workspace),
                cwd = workspace,
            ),
        )

    /** The directory a stash name owns inside the run's local store. */
    private fun nameDir(name: String): Path =
        controlRoot.resolve("stashes").resolve("run-1").resolve(name)

    private fun currentGeneration(name: String): String =
        Files.readString(nameDir(name).resolve(StashStoreLayout.CURRENT_POINTER)).trim()

    @Test
    fun `a published reference names a generation whose manifest matches the stored bytes`() {
        val sink = prepare()
        Files.createDirectories(workspace.resolve("src/deep"))
        Files.writeString(workspace.resolve("src/a.txt"), "contenido-a")
        Files.writeString(workspace.resolve("src/deep/b.txt"), "contenido-b")

        val stashed = adapter(sink).stash(StashInput(name = "files", includes = "src/**"))
        assertInstanceOf(StashSuccess::class.java, stashed)

        assertEquals("gen-1", currentGeneration("files"), "the first stash publishes generation 1")
        val genDir = nameDir("files").resolve("gen-1")
        val manifest = StashManifest.readFrom(genDir)
        assertEquals("files", manifest.name)
        assertEquals(1, manifest.generation)
        assertEquals(setOf("src/a.txt", "src/deep/b.txt"), manifest.rows.map { it.relPath }.toSet())
        for (row in manifest.rows) {
            val stored = genDir.resolve(StashStoreLayout.SNAPSHOT_DIR).resolve(row.relPath)
            assertTrue(Files.isRegularFile(stored), "the snapshot must hold '${row.relPath}'")
            assertEquals(Sha256.ofFile(stored), row.sha256, "the row digest is read back from the store")
            assertEquals(Files.size(stored), row.size, "the row size is read back from the store")
        }

        // The success event declares exactly the rows the manifest states.
        val created = sink.appended.filterIsInstance<StashCreated>().single()
        assertEquals(
            manifest.rows.sortedBy { it.relPath }.map { Triple(it.relPath, it.sha256, it.size) },
            created.files.sortedBy { it.relPath }.map { Triple(it.relPath, it.sha256, it.sizeBytes) },
        )
        // Nothing loose survives outside the generation directory.
        assertFalse(Files.exists(nameDir("files").resolve("src")))
    }

    @Test
    fun `restashing the same name mints an explicit generation and keeps the resolved snapshot intact`() {
        val sink = prepare()
        Files.createDirectories(workspace.resolve("src"))
        Files.writeString(workspace.resolve("src/a.txt"), "uno")
        assertInstanceOf(StashSuccess::class.java, adapter(sink).stash(StashInput("files", "src/**")))

        // A reader resolves the reference before the restash happens.
        val firstGen = nameDir("files").resolve("gen-1")
        val firstManifest = StashManifest.readFrom(firstGen)

        Files.writeString(workspace.resolve("src/a.txt"), "dos")
        assertInstanceOf(StashSuccess::class.java, adapter(sink).stash(StashInput("files", "src/**")))
        assertEquals("gen-2", currentGeneration("files"), "the repeat mints a new generation")

        // The generation the reader resolved is immutable: same manifest, same bytes.
        assertEquals(firstManifest, StashManifest.readFrom(firstGen))
        assertEquals("uno", Files.readString(firstGen.resolve(StashStoreLayout.SNAPSHOT_DIR).resolve("src/a.txt")))

        // A consumer reading the reference now gets the new snapshot.
        val restored = adapter(sink).unstash(UnstashInput(name = "files"))
        assertInstanceOf(StashRestoredResult::class.java, restored)
        assertEquals("dos", Files.readString(workspace.resolve("src/a.txt")))
    }

    @Test
    fun `a restash that fails keeps the reference on the last complete generation`() {
        val sink = prepare()
        Files.createDirectories(workspace.resolve("src"))
        Files.writeString(workspace.resolve("src/a.txt"), "uno")
        assertInstanceOf(StashSuccess::class.java, adapter(sink).stash(StashInput("files", "src/**")))

        // The restash matches nothing: the typed refusal happens before any staging.
        Files.delete(workspace.resolve("src/a.txt"))
        val failed = adapter(sink).stash(StashInput("files", "src/**"))
        val failure = assertInstanceOf(StashFailed::class.java, failed)
        assertEquals(FailureKind.SCRIPT, failure.failureKind, "the empty match stays a SCRIPT refusal")

        assertEquals("gen-1", currentGeneration("files"), "the reference never moves off a complete generation")
        val leaked = Files.list(nameDir("files")).use { stream ->
            stream.map { it.fileName.toString() }.toList()
        }
        assertEquals(
            listOf("CURRENT", "gen-1").sorted(),
            leaked.sorted(),
            "a failed restash leaves no unreferenced generation behind",
        )

        val restored = adapter(sink).unstash(UnstashInput(name = "files"))
        assertInstanceOf(StashRestoredResult::class.java, restored)
        assertEquals("uno", Files.readString(workspace.resolve("src/a.txt")))
    }

    @Test
    fun `a symlink in the matched set is refused before any generation is staged`() {
        val sink = prepare()
        Files.createDirectories(workspace.resolve("src"))
        Files.writeString(workspace.resolve("src/ok.txt"), "ok")
        // The link must resolve to a real file, or the glob never matches it and the
        // guard under test is not reached.
        Files.writeString(tempDir.resolve("outside.txt"), "fuera")
        Files.createSymbolicLink(workspace.resolve("src/link-out"), tempDir.resolve("outside.txt"))

        val failed = adapter(sink).stash(StashInput("files", "src/**"))
        val failure = assertInstanceOf(StashFailed::class.java, failed)
        assertEquals(FailureKind.SCRIPT, failure.failureKind)
        assertTrue(failure.message.contains("symlink"), "the refusal names the symlink, was: ${failure.message}")
        assertFalse(
            Files.exists(nameDir("files").resolve(StashStoreLayout.CURRENT_POINTER)),
            "a refused stash publishes no reference",
        )
        assertFalse(
            Files.exists(nameDir("files").resolve("gen-1")),
            "a refused stash leaves no staged generation",
        )
    }

    @Test
    fun `a stash never publishes the durable artifact tier`() {
        val sink = prepare()
        Files.createDirectories(workspace.resolve("src"))
        Files.writeString(workspace.resolve("src/a.txt"), "contenido")

        assertInstanceOf(StashSuccess::class.java, adapter(sink).stash(StashInput("files", "src/**")))

        assertTrue(sink.appended.any { it is StashCreated }, "the stash emits its own event")
        assertFalse(
            sink.appended.any { it is ArtifactArchived },
            "the stash tier never emits the durable artifact event",
        )
        assertFalse(
            Files.exists(controlRoot.resolve("artefacts")),
            "the stash writes no retention directory",
        )
    }

    @Test
    fun `a store with an orphan generation and no pointer is refused, never restored as flat content`() {
        val sink = prepare()
        Files.createDirectories(workspace.resolve("src"))
        // The disk state an abnormal death mid-first-capture leaves: a generation tree
        // staged, no manifest, no CURRENT. The name was never published, so restoring it
        // as flat legacy content would present truncated bytes under gen-N paths.
        val orphanSnapshot = nameDir("files").resolve("gen-2").resolve(StashStoreLayout.SNAPSHOT_DIR)
        Files.createDirectories(orphanSnapshot)
        Files.writeString(orphanSnapshot.resolve("a.txt"), "truncado")

        val result = adapter(sink).unstash(UnstashInput(name = "files"))

        val failure = assertInstanceOf(StashFailed::class.java, result)
        assertEquals(FailureKind.SCRIPT, failure.failureKind)
        assertFalse(
            sink.appended.any { it is StashRestored },
            "a generation that was never published cannot produce a restore event",
        )
        assertFalse(Files.exists(workspace.resolve("gen-2")), "no orphan tree leaked into the workspace")
    }

    @Test
    fun `a stash name that cannot name a directory is refused and leaves no store behind`() {
        val sink = prepare()
        Files.writeString(workspace.resolve("a.txt"), "x")

        val failed = adapter(sink).stash(StashInput(name = "..", includes = "*.txt"))
        val failure = assertInstanceOf(StashFailed::class.java, failed)
        assertEquals(FailureKind.USER, failure.failureKind)
        assertFalse(
            Files.exists(controlRoot.resolve("stashes")),
            "a refused name must not create the store",
        )
    }
}
