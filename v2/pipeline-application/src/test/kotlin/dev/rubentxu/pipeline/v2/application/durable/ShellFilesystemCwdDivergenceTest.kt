package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.workspace.PathAnchor
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver
import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * RP034-D — file Steps observe the `dir` scope the shell observes.
 *
 * This replaces the earlier divergence RED, which compared `WorkspaceResolver`
 * directly and therefore stopped exercising the code path the migration
 * actually changed. A RED that no longer discriminates is worse than none: it
 * reports success while proving nothing.
 *
 * The law, stated once, is that a relative path written inside `dir("backend")`
 * lands under `<root>/backend` for the file vertical exactly as it does for the
 * shell vertical, and that the workspace root is untouched by the scope.
 */
@DisplayName("RP034-D file Steps share the shell cwd")
class ShellFilesystemCwdDivergenceTest {

    @Test
    @DisplayName("a dir scope moves the file base, and the workspace root stays put")
    fun `file steps observe the dir scope`(@TempDir tempDir: Path) {
        val controlRoot = Files.createDirectory(tempDir.resolve("control"))
        val project = Files.createDirectory(tempDir.resolve("project"))

        // The location the runtime would hand the adapter for a step inside
        // dir("backend"): same lease root, advanced cwd.
        val scopedLocation = ShOptionsExecutionLocationAdapter.from(
            workspaceRoot = project,
            scopedWorkingDirectory = project.resolve("backend"),
            fallbackRoot = project,
        )

        assertEquals(
            project,
            scopedLocation.workspace.root,
            "the workspace root must not follow the scope (INV-WS-001)",
        )

        val scopedFile = WorkspacePathResolver.resolve(scopedLocation, PathAnchor.CURRENT_DIRECTORY, "out.txt")
        val shellCwd = scopedLocation.cwd

        assertEquals(
            shellCwd.resolve("out.txt"),
            (scopedFile as PathResolution.Resolved).path,
            "the file vertical must resolve against the same cwd the shell runs in",
        )

        // And at the top of the run both agree on the root, which is the
        // precondition that makes the scoped case meaningful.
        val topLevel = ShOptionsExecutionLocationAdapter.from(project, null, project)
        assertEquals(topLevel.workspace.root, topLevel.cwd)
        assertEquals(
            project.resolve("out.txt"),
            (WorkspacePathResolver.resolve(topLevel, PathAnchor.CURRENT_DIRECTORY, "out.txt") as PathResolution.Resolved).path,
        )
    }

    @Test
    @DisplayName("confinement still applies to file Steps after the migration")
    fun `file steps stay confined`(@TempDir tempDir: Path) {
        val project = Files.createDirectory(tempDir.resolve("project"))
        val scoped = ShOptionsExecutionLocationAdapter.from(
            workspaceRoot = project,
            scopedWorkingDirectory = project.resolve("backend"),
            fallbackRoot = project,
        )

        assertEquals(
            true,
            WorkspacePathResolver.resolve(scoped, PathAnchor.CURRENT_DIRECTORY, "../../escape.txt") is PathResolution.Rejected,
            "a file Step must not escape the workspace via the scope",
        )
    }
}
