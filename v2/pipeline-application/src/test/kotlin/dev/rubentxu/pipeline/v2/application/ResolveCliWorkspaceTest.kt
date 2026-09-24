package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * WU-RP-053 cut5 forward-port (M1 follow-up): unit tests for [resolveCliWorkspace].
 *
 * The CLI workspace default changed in cut5: when --workspace is NOT passed, the
 * workspaceBase defaults to the SCRIPT'S PARENT DIRECTORY (the invoking
 * directory), not the state directory. This implements the operator's PROJECT
 * mode default — bare scripts get their own directory as workspace, no absolute
 * REPO_ROOT dance required in scripts.
 *
 * When --workspace IS passed explicitly, the explicit path wins (Jenkins-familiar
 * single-workspace semantics).
 */
class ResolveCliWorkspaceTest {

    @Test
    fun `explicit workspace wins`() {
        val script = Paths.get("/tmp/proj/sub/nested.pipeline.kts")
        val resolved = resolveCliWorkspace("/tmp/explicit-ws", script)
        assertEquals("/tmp/explicit-ws", resolved.toString())
    }

    @Test
    fun `null workspace defaults to script parent directory`() {
        val script = Paths.get("/tmp/proj/bare.pipeline.kts")
        val resolved = resolveCliWorkspace(null, script)
        assertEquals("/tmp/proj", resolved.toString())
    }

    @Test
    fun `null workspace defaults to nested script parent directory`() {
        val script = Paths.get("/tmp/proj/nested/nested.pipeline.kts")
        val resolved = resolveCliWorkspace(null, script)
        assertEquals("/tmp/proj/nested", resolved.toString())
    }
}
