package dev.rubentxu.pipeline.v2.application.cli

import dev.rubentxu.pipeline.v2.application.WorkspaceMode
import dev.rubentxu.pipeline.v2.application.resolveCliWorkspaceBase
import dev.rubentxu.pipeline.v2.application.parseCliArgs
import dev.rubentxu.pipeline.v2.application.WorkspaceModeUnsupportedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * WU-RP-053-FOLLOWUP: pure-function tests for the workspace resolver and the CLI parser
 * of the new `--workspace-mode` flag. No I/O, no fixtures, fully deterministic.
 *
 * These are the canonical proof that the opt-in flag does not change the legacy
 * default (the 7 UATs stay green) and that `project` mode wires the workspace to
 * the script's parent directory (Jenkins-parity, cut5 PROJECT mode).
 */
class WorkspaceModeCliTest {

    // ----------------------------------------------------------------------
    // resolveCliWorkspaceBase: pure resolver behaviour
    // ----------------------------------------------------------------------

    @Test
    fun `resolver explicit workspace wins over project mode`() {
        val explicit = "/tmp/explicit"
        val script = Paths.get("/tmp/project/bare.pipeline.kts")
        val resolved = resolveCliWorkspaceBase(
            explicitWorkspace = explicit,
            scriptPath = script,
            mode = WorkspaceMode.Project,
        )
        assertNotNull(resolved)
        assertEquals(Paths.get("/tmp/explicit").toAbsolutePath(), resolved)
    }

    @Test
    fun `resolver project mode uses script parent directory`() {
        val script = Paths.get("/tmp/project/bare.pipeline.kts")
        val resolved = resolveCliWorkspaceBase(
            explicitWorkspace = null,
            scriptPath = script,
            mode = WorkspaceMode.Project,
        )
        assertNotNull(resolved)
        assertEquals(Paths.get("/tmp/project").toAbsolutePath(), resolved)
    }

    @Test
    fun `resolver legacy mode returns null when no explicit workspace`() {
        val script = Paths.get("/tmp/project/bare.pipeline.kts")
        val resolved = resolveCliWorkspaceBase(
            explicitWorkspace = null,
            scriptPath = script,
            mode = WorkspaceMode.Legacy,
        )
        // Caller (runCanonicalPipeline) interprets null as `<controlDirRoot>/workspace`.
        assertNull(resolved)
    }

    @Test
    fun `resolver explicit workspace wins over legacy mode`() {
        val script = Paths.get("/tmp/project/bare.pipeline.kts")
        val resolved = resolveCliWorkspaceBase(
            explicitWorkspace = "/srv/explicit",
            scriptPath = script,
            mode = WorkspaceMode.Legacy,
        )
        assertNotNull(resolved)
        assertEquals(Paths.get("/srv/explicit").toAbsolutePath(), resolved)
    }

    // ----------------------------------------------------------------------
    // WorkspaceMode.parse: fail-closed value validation
    // ----------------------------------------------------------------------

    @Test
    fun `WorkspaceMode parse accepts project`() {
        assertEquals(WorkspaceMode.Project, WorkspaceMode.parse("project"))
    }

    @Test
    fun `WorkspaceMode parse accepts legacy`() {
        assertEquals(WorkspaceMode.Legacy, WorkspaceMode.parse("legacy"))
    }

    @Test
    fun `WorkspaceMode parse rejects unknown values`() {
        assertNull(WorkspaceMode.parse("dev"))
        assertNull(WorkspaceMode.parse(""))
        assertNull(WorkspaceMode.parse("PROJECT")) // case-sensitive: no silent coercion
    }

    // ----------------------------------------------------------------------
    // parseCliArgs: end-to-end flag wiring
    // ----------------------------------------------------------------------

    @Test
    fun `parseCliArgs default workspaceMode is Legacy`() {
        val cfg = parseCliArgs(arrayOf("run", "--db", "/tmp/db.sqlite", "script.pipeline.kts"))
        assertNotNull(cfg)
        assertEquals(WorkspaceMode.Legacy, cfg!!.workspaceMode)
    }

    @Test
    fun `parseCliArgs --workspace-mode project is honoured`() {
        val cfg = parseCliArgs(
            arrayOf(
                "run",
                "--db",
                "/tmp/db.sqlite",
                "--workspace-mode",
                "project",
                "script.pipeline.kts",
            )
        )
        assertNotNull(cfg)
        assertEquals(WorkspaceMode.Project, cfg!!.workspaceMode)
    }

    @Test
    fun `parseCliArgs --workspace-mode legacy is honoured`() {
        val cfg = parseCliArgs(
            arrayOf(
                "run",
                "--db",
                "/tmp/db.sqlite",
                "--workspace-mode",
                "legacy",
                "script.pipeline.kts",
            )
        )
        assertNotNull(cfg)
        assertEquals(WorkspaceMode.Legacy, cfg!!.workspaceMode)
    }

    @Test
    fun `parseCliArgs --workspace-mode invalid throws typed exception`() {
        val ex = assertThrows(WorkspaceModeUnsupportedException::class.java) {
            parseCliArgs(
                arrayOf(
                    "run",
                    "--db",
                    "/tmp/db.sqlite",
                    "--workspace-mode",
                    "bad-value",
                    "script.pipeline.kts",
                )
            )
        }
        assert(ex.message!!.contains("workspace-mode"))
        assert(ex.message!!.contains("bad-value"))
    }
}
