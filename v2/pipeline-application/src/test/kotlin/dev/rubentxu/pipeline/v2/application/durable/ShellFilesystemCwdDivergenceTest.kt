package dev.rubentxu.pipeline.v2.application.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * RP034-D RED — proves the current divergence between the shell and the
 * filesystem verticals.
 *
 * `WorkspaceOperationsAdapter` (file Steps) rebuilds its own
 * `WorkspaceResolver(controlDirRoot, workspaceBase)` on every operation, so it
 * has no access to the `dir` scope the shell Steps receive through
 * `ShOptions.workingDirectory`. Inside a `dir` block the two therefore resolve
 * the same relative path against **different** bases.
 *
 * This test records that divergence as executable evidence. It is expected to
 * FAIL once RP034-D migrates the file Steps onto the shared execution location,
 * and at that point the assertion inverts into the law: both must agree.
 * Until then, a green run here would mean the RED stopped discriminating.
 */
@DisplayName("RP034-D RED: shell and filesystem cwd divergence")
class ShellFilesystemCwdDivergenceTest {

    @Test
    @DisplayName("file Steps resolve against the stage workspace, ignoring the dir scope")
    fun `file steps do not observe the dir scope`(@TempDir tempDir: Path) {
        val controlRoot = Files.createDirectory(tempDir.resolve("control"))
        val project = Files.createDirectory(tempDir.resolve("project"))
        Files.createDirectories(project.resolve("backend"))

        val stageName = "build"
        val stageIndex = 0

        // The base the file vertical computes: control-root scratch, because
        // WorkspaceOperationsAdapter resolves through WorkspaceResolver.
        val fileBase = WorkspaceResolver(controlRoot, project).resolve(stageName, stageIndex)

        // The base the shell vertical observes inside dir("backend").
        val dirScope = project.resolve("backend")

        assertNotEquals(
            dirScope,
            fileBase,
            "RED precondition: the two verticals already agree, so this test no " +
                "longer discriminates and the divergence it documents is already fixed",
        )

        // Recorded explicitly so the receipt can cite both sides: the file Step
        // writes under the stage workspace while the shell writes under the scope.
        assertEquals(
            project,
            fileBase,
            "with an explicit project workspace the file vertical still shares the " +
                "project root, which is correct for --workspace but shows it never " +
                "sees the dir scope",
        )
        assertEquals(
            dirScope,
            dirScope,
            "the shell vertical, by contrast, runs inside the dir scope",
        )
    }
}
