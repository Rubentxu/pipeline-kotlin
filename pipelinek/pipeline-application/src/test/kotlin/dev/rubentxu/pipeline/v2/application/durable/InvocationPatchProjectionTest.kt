package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.ContextOverlay
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.step.ExecutionContextPatch
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The projection of a body invocation's patch, exercised as the pure function it is.
 *
 * The claim under test is the one that was FALSE before: a patch says "run this body under
 * these conditions", and the body only runs under them if the projection reaches the surface
 * the body actually lives in. For a `sh` body that surface is the environment of the process,
 * so a patch that only reaches the context is a patch that does not happen.
 */
class InvocationPatchProjectionTest {

    @TempDir
    lateinit var tempDir: Path

    private fun options(env: Map<String, SecretHandle> = emptyMap()) = ShOptions(
        workspaceRoot = tempDir,
        captureStdout = false,
        timeoutMs = null,
        env = env,
    )

    @Test
    fun `an environment patch reaches the environment of the process the body is launched with`() {
        val projected = projectInvocationPatch(
            ExecutionContext.EMPTY,
            options(),
            ExecutionContextPatch.Environment(mapOf("PIPELINEK_SAGA_OPERATION_KEY" to "saga-v1|forward")),
        )

        // The body is a process: it reads its conditions from the process environment, and
        // the process environment is the options. A value that only reached the context would
        // be a value the body never sees.
        val applied = assertInstanceOf<InvocationPatchProjection.Applied>(projected)
        assertEquals(
            "saga-v1|forward",
            applied.shOptions.env["PIPELINEK_SAGA_OPERATION_KEY"]?.materialize(),
        )
    }

    @Test
    fun `an environment patch also reaches the context`() {
        val projected = projectInvocationPatch(
            ExecutionContext.EMPTY,
            options(),
            ExecutionContextPatch.Environment(mapOf("K" to "V")),
        )

        // The context is the OTHER surface, and the scope the body runs under. A projection
        // that reached only the options would leave the context lying about the world.
        val applied = assertInstanceOf<InvocationPatchProjection.Applied>(projected)
        val overlay = assertInstanceOf<ContextOverlay.Environment>(applied.context.overlays.single())
        assertEquals(mapOf("K" to "V"), overlay.values.values)
    }

    @Test
    fun `the patch wins over the options it is applied to`() {
        val projected = projectInvocationPatch(
            ExecutionContext.EMPTY,
            options(mapOf("K" to SecretHandle.plain("old"))),
            ExecutionContextPatch.Environment(mapOf("K" to "new")),
        )

        // The patch is the MORE SPECIFIC scope, so it wins over the scope it is applied to —
        // the same rule the `withEnv` scope follows.
        val applied = assertInstanceOf<InvocationPatchProjection.Applied>(projected)
        assertEquals("new", applied.shOptions.env["K"]?.materialize())
    }

    @Test
    fun `a directory patch sets the working directory of the body`() {
        val target = tempDir.resolve("inside")
        val projected = projectInvocationPatch(
            ExecutionContext.EMPTY,
            options(),
            ExecutionContextPatch.Directory(target.toString()),
        )

        val applied = assertInstanceOf<InvocationPatchProjection.Applied>(projected)
        assertEquals(target, applied.shOptions.workingDirectory)
    }

    @Test
    fun `a directory patch that escapes the workspace is refused`() {
        val projected = projectInvocationPatch(
            ExecutionContext.EMPTY,
            options(),
            ExecutionContextPatch.Directory("/etc"),
        )

        // Refused, not dropped: a body that believes it is somewhere else is worse than a
        // body that never ran, and the sibling that shares a prefix is the one an attacker
        // picks — `/ws/base-evil` begins with `/ws/base` as a string.
        val refused = assertInstanceOf<InvocationPatchProjection.Refused>(projected)
        assertEquals(true, refused.reason.contains("escapes the workspace"))
    }

    @Test
    fun `no patch changes nothing`() {
        val original = options(mapOf("K" to SecretHandle.plain("v")))
        val projected = projectInvocationPatch(ExecutionContext.EMPTY, original, ExecutionContextPatch.None)

        val applied = assertInstanceOf<InvocationPatchProjection.Applied>(projected)
        assertEquals(ExecutionContext.EMPTY, applied.context)
        assertEquals(original.env, applied.shOptions.env)
        assertNull(applied.shOptions.workingDirectory)
    }

    @Test
    fun `a credential lease is the preamble's business, not this seam's`() {
        val original = options()
        val projected = projectInvocationPatch(
            ExecutionContext.EMPTY,
            original,
            ExecutionContextPatch.CredentialLease("lease-1"),
        )

        // The seam is honest about what it does not do: the lease preamble owns the
        // acquisition and the release, and a second acquisition here would be a second
        // authority over the same secret.
        val applied = assertInstanceOf<InvocationPatchProjection.Applied>(projected)
        assertEquals(ExecutionContext.EMPTY, applied.context)
        assertEquals(original.env, applied.shOptions.env)
    }
}
