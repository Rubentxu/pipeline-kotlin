package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout

/**
 * WU-RP-041 / S3: filesystem-containment law pins (pure projections, no engine).
 *
 * Pins the EXACT declared semantics of the LOCAL runner profile (threat model
 * docs/v2/07-uat/WU_RP_041_RUNNER_ISOLATION_THREAT_MODEL.md §1):
 *   1. a relative `dir` path that escapes the workspace is a TYPED schema
 *      rejection before any effect (fail closed);
 *   2. an in-workspace relative path is projected and stays inside the workspace
 *      after normalization;
 *   3. these are projections, not a jail: the model of record states writes
 *      outside the workspace are REPORTED best-effort, never silently allowed
 *      (SB-S-002 proves the runtime side; this pin freezes the pure-decision side).
 */
@Timeout(30)
class RunnerIsolationProjectionLawTest {

    private fun dirBlock(path: String) = BlockStepNode(
        id = StepId("law/dir-0"),
        pluginStepId = PluginStepId("core.dir"),
        payload = VersionedStepPayload("dsl-v1", """{"kind":"dir","path":"$path"}"""),
        body = emptyList(),
    )

    private fun options(workspace: Path) = ShOptions(
        workspaceRoot = workspace,
        captureStdout = false,
        timeoutMs = null,
        env = emptyMap(),
        sandbox = SandboxConfig.LOCAL,
    )

    @Test
    fun `relative dir path escaping the workspace is a typed rejection before any effect`(@TempDir tempDir: Path) {
        val ws = tempDir.resolve("ws")
        val projection = dirBlock("../outside").projectScopedBody(
            dev.rubentxu.pipeline.v2.domain.step.BodyContextProjection.WorkingDirectory,
            options(ws),
        )
        assertTrue(projection is BodyExecutionProjection.InvalidInput, "got: $projection")
        assertTrue(
            (projection as BodyExecutionProjection.InvalidInput).detail.contains("escapes the workspace"),
            projection.detail,
        )
    }

    @Test
    fun `in-workspace relative dir path stays inside the workspace after normalization`(@TempDir tempDir: Path) {
        val ws = tempDir.resolve("ws")
        val projection = dirBlock("nested/sub").projectScopedBody(
            dev.rubentxu.pipeline.v2.domain.step.BodyContextProjection.WorkingDirectory,
            options(ws),
        )
        val scope = (projection as BodyExecutionProjection.Scope).scope
        val target = (scope as BlockShellScope.Directory).target
        assertTrue(
            target.startsWith(ws.normalize()),
            "projected cwd must remain inside the workspace; got $target",
        )
    }

    @Test
    fun `dot-dot-traversal that normalizes back inside the workspace is accepted`(@TempDir tempDir: Path) {
        val ws = tempDir.resolve("ws")
        val projection = dirBlock("a/../b").projectScopedBody(
            dev.rubentxu.pipeline.v2.domain.step.BodyContextProjection.WorkingDirectory,
            options(ws),
        )
        val scope = (projection as BodyExecutionProjection.Scope).scope as BlockShellScope.Directory
        assertEquals(ws.resolve("b").normalize(), scope.target)
    }
}
