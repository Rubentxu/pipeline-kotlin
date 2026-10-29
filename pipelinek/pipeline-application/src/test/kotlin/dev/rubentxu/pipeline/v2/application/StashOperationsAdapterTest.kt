package dev.rubentxu.pipeline.v2.application

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
 * The containment guard of the stash adapter.
 *
 * Two guarantees are under test, and they pull in opposite directions, which is
 * why they share one fixture:
 *
 * - A restore whose destination does not exist yet, inside a workspace reached
 *   through a symbolic link, is allowed. The destination of a restore is by
 *   definition absent before the copy, so resolving it as a whole path is not
 *   possible; the guard has to decide on a path that still has to be created.
 * - A restore whose destination leaves the workspace through a symbolic link
 *   inside it is refused. The link is the attack: the name looks local while
 *   the bytes land elsewhere.
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

    private fun adapter(workspace: Path, controlRoot: Path) = StashOperationsAdapter(
        runIdString = "run-1",
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
}
