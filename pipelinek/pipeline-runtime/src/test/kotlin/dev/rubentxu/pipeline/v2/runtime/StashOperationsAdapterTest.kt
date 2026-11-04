package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.events.NullEventSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The containment and naming guards of the stash adapter.
 *
 * Three guarantees are under test. The first two pull in opposite directions,
 * which is why they share one fixture:
 *
 * - A restore whose destination does not exist yet, inside a workspace reached
 *   through a symbolic link, is allowed. The destination of a restore is by
 *   definition absent before the copy, so resolving it as a whole path is not
 *   possible; the guard has to decide on a path that still has to be created.
 * - A restore whose destination leaves the workspace through a symbolic link
 *   inside it is refused. The link is the attack: the name looks local while
 *   the bytes land elsewhere.
 * - A run identity that cannot name the stash directory is refused, with the
 *   reason stated, and leaves no durable location behind. The stash directory
 *   is named after the run, so a name that cannot carry the run would put two
 *   runs in one directory — on a volume that folds case, the same identity in
 *   upper case and in lower case
 *   are the same directory.
 *
 * The fixture reaches the workspace through a symbolic link because that is the
 * shape that separates the two coordinate systems a path can be compared in:
 * the one the caller wrote, and the one the filesystem resolves to. A guard that
 * mixes them rejects honest work; a guard that ignores them lets bytes out.
 */
class StashOperationsAdapterTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * A workspace whose path crosses a symbolic link, plus the directory the
     * link points at. The caller sees the first, the filesystem sees the second.
     */
    private fun linkedWorkspace(): Pair<Path, Path> {
        val real = tempDir.resolve("real-workspace")
        Files.createDirectories(real)
        val link = tempDir.resolve("workspace-link")
        Files.createSymbolicLink(link, real)
        return link.resolve("ws") to real.resolve("ws")
    }

    private fun adapter(workspace: Path, controlRoot: Path, runId: String = "run-1") =
        StashOperationsAdapter(
            runIdString = runId,
            stageIdentity = StageIdentity(name = "stage-a", index = 0),
            controlDirRoot = controlRoot,
            eventSink = NullEventSink,
            executionLocation = ExecutionLocation(
                workspace = WorkspaceLease.Managed(workspace),
                cwd = workspace,
            ),
        )

    @Test
    fun `restores a destination that does not exist yet inside a workspace reached through a link`() {
        val (workspace, realWorkspace) = linkedWorkspace()
        val controlRoot = tempDir.resolve("control")
        Files.createDirectories(controlRoot)

        Files.createDirectories(realWorkspace.resolve("src"))
        Files.writeString(realWorkspace.resolve("src/lpr104.txt"), "contenido")

        val stash = adapter(workspace, controlRoot)
            .stash(StashInput(name = "files", includes = "src/**"))
        assertInstanceOf(StashSuccess::class.java, stash)

        // The producer's files are gone: the destination of the restore does not
        // exist at the moment the guard has to judge it.
        Files.delete(realWorkspace.resolve("src/lpr104.txt"))
        Files.delete(realWorkspace.resolve("src"))

        val restored = adapter(workspace, controlRoot)
            .unstash(UnstashInput(name = "files"))

        assertInstanceOf(StashRestoredResult::class.java, restored)
        assertEquals(
            "contenido",
            Files.readString(realWorkspace.resolve("src/lpr104.txt")),
            "the restored file must carry the stashed bytes",
        )
    }

    @Test
    fun `refuses a restore whose destination leaves the workspace through a link`() {
        val workspace = tempDir.resolve("ws")
        Files.createDirectories(workspace)
        val controlRoot = tempDir.resolve("control")
        Files.createDirectories(controlRoot)

        val outside = tempDir.resolve("outside")
        Files.createDirectories(outside)

        // A stash whose content was shaped by hand, the way a tampered archive
        // would arrive: one entry below a name that is a link out of the
        // workspace.
        val stashed = controlRoot.resolve("stashes/run-1/files/link-out")
        Files.createDirectories(stashed)
        Files.writeString(stashed.resolve("payload.txt"), "fuera del workspace")

        Files.createSymbolicLink(workspace.resolve("link-out"), outside)

        val restored = adapter(workspace, controlRoot)
            .unstash(UnstashInput(name = "files"))

        val failure = assertInstanceOf(StashFailed::class.java, restored)
        assertEquals(FailureKind.SCRIPT, failure.failureKind)
        assertFalse(
            Files.exists(outside.resolve("payload.txt")),
            "no byte may land outside the workspace",
        )
        assertTrue(
            failure.message.contains("escapes restoreRoot"),
            "the refusal must name the containment it enforces, was: ${failure.message}",
        )
    }

    @Test
    fun `refuses a stash whose run identity cannot name the stash directory`() {
        val workspace = tempDir.resolve("ws")
        Files.createDirectories(workspace)
        val controlRoot = tempDir.resolve("control")
        Files.createDirectories(controlRoot)

        // The stash directory is named after the run, so a run identity that
        // cannot name it has no place to write: the same identity in upper case
        // and in lower case would be ONE directory on a volume that folds case.
        val stashed = adapter(workspace, controlRoot, runId = "RUN-1")
            .stash(StashInput(name = "files", includes = "src/**"))

        val failure = assertInstanceOf(StashFailed::class.java, stashed)
        assertEquals(FailureKind.USER, failure.failureKind)
        assertTrue(
            failure.message.contains("cannot name a durable location"),
            "the refusal must name the reason it refuses, was: ${failure.message}",
        )
        assertFalse(
            Files.exists(controlRoot.resolve("stashes")),
            "a refused identity must not leave a durable location behind",
        )
    }

    @Test
    fun `refuses an unstash whose run identity cannot name the stash directory`() {
        val workspace = tempDir.resolve("ws")
        Files.createDirectories(workspace)
        val controlRoot = tempDir.resolve("control")
        Files.createDirectories(controlRoot)

        // A stash that a nameable identity left behind, so the refusal cannot be
        // mistaken for "there is no such stash".
        Files.createDirectories(controlRoot.resolve("stashes/run-1/files"))
        Files.writeString(controlRoot.resolve("stashes/run-1/files/payload.txt"), "contenido")

        val restored = adapter(workspace, controlRoot, runId = "RUN-1")
            .unstash(UnstashInput(name = "files"))

        val failure = assertInstanceOf(StashFailed::class.java, restored)
        assertEquals(FailureKind.USER, failure.failureKind)
        assertTrue(
            failure.message.contains("cannot name a durable location"),
            "the refusal must name the reason it refuses, was: ${failure.message}",
        )
        assertFalse(
            Files.exists(workspace.resolve("payload.txt")),
            "a refused identity must not restore a byte",
        )
    }
}
