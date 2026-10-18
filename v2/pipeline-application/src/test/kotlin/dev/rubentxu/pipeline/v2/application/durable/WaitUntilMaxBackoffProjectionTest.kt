package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CoreWaitUntilInput
import dev.rubentxu.pipeline.v2.application.CoreWaitUntilWireCodec
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.RetryPolicy
import dev.rubentxu.pipeline.v2.domain.step.WaitUntilShape
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The `maxBackoffMs` backoff ceiling must reach the durable polling engine as DECODED
 * INPUT, not as a structural declaration and not as a regex side-channel.
 *
 * ## The productive authority this crosses
 *
 * [BlockStepNode.projectBodyExecution] — the single projection the coordinator consults for
 * every body-bearing block, on every run path. Both waitUntil branches read their ceiling
 * from the projected [BlockShellScope.WaitUntilScope]:
 *
 * - durable branch, `BodyExecutionEngine.kt:455-483` -> `WaitUntilEngine(maxBackoffMs = scope.maxBackoffMs)`
 * - inline branch,  `BodyExecutionEngine.kt:486-489` -> `executeWaitUntilInline(scope.copy(maxBackoffMs = ...))`
 *
 * HF1 (in-process) against production code: no forked distribution, no mocked engine. The
 * property under test is a pure projection, so nothing durable is exercised and nothing may
 * be inferred about replay.
 *
 * ## Why these rows exist (measured 2026-10-18)
 *
 * `BlockShellScope.WaitUntilScope` declares `maxBackoffMs: Long = 60_000L`, but the
 * projection built it from [WaitUntilShape], which has no such field. So the durable branch
 * received `60_000L` unconditionally. The only production code that could change it was a
 * regex over `block.payload.encoded` at `StepDispatchEngine.kt:698`, whose result was
 * consumed exclusively by the INLINE branch — the branch that runs iff
 * `waitUntilControlJournal == null`, which `CompositionRoot.kt:191` never satisfies in
 * production. Two characterization fixtures wrote the key by hand to reach it.
 *
 * [WaitUntilShape]'s own KDoc already said where the value belongs: *"the shape is a
 * structural property of the Step KIND, not runtime data: the attempt budget and the
 * deadline still arrive as decoded typed input."* These rows hold it to that sentence.
 */
@Timeout(30)
class WaitUntilMaxBackoffProjectionTest {

    @Test
    fun `the projected ceiling is the one the payload declares, not the type default`() {
        val scope = projectedScope(payload = """{"kind":"waitUntilBlock","initialRecurrencePeriod":1,"quiet":false,"maxBackoffMs":25}""")

        assertEquals(
            25L,
            scope.maxBackoffMs,
            "The projection must read maxBackoffMs from the decoded payload. Returning 60000 here " +
                "is the defect: the durable branch then polls with a 60 s ceiling no run can lower.",
        )
    }

    @Test
    fun `a payload without the key keeps the declared default`() {
        val scope = projectedScope(payload = """{"kind":"waitUntilBlock","initialRecurrencePeriod":1,"quiet":false}""")

        assertEquals(
            DEFAULT_MAX_BACKOFF_MS,
            scope.maxBackoffMs,
            "Absence means 'use the declared default', so a pre-existing payload keeps today's ceiling.",
        )
    }

    @Test
    fun `a non-numeric ceiling is refused as typed input, not as an exception`() {
        val projection = project(
            payload = """{"kind":"waitUntilBlock","initialRecurrencePeriod":1,"quiet":false,"maxBackoffMs":"soon"}""",
        )

        val invalid = assertInstanceOf(
            BodyExecutionProjection.InvalidInput::class.java,
            projection,
            "Malformed decoded input must surface as the typed case the projector KDoc promises, " +
                "because the projector declares it returns InvalidInput and never throws for input shape.",
        )
        assertTrue(
            invalid.detail.contains("maxBackoffMs"),
            "The refusal must name the field it refused, got: ${invalid.detail}",
        )
    }

    @Test
    fun `the wire codec is the one that emits the key, so the projection has an authority to read`() {
        val encoded = CoreWaitUntilWireCodec.encode(CoreWaitUntilInput(maxBackoffMs = 25L)).value

        assertTrue(
            encoded.contains("\"maxBackoffMs\":25"),
            "CoreWaitUntilWireCodec must emit maxBackoffMs, otherwise the projection would read a key " +
                "that only tests write — exactly the residue this slice removes. Got: $encoded",
        )
    }

    @Test
    fun `the codec round-trips the ceiling, so emission and reading agree on one value`() {
        val decoded = CoreWaitUntilWireCodec.decode(
            CoreWaitUntilWireCodec.encode(
                CoreWaitUntilInput(initialRecurrencePeriod = 3L, maxBackoffMs = 25L),
            ),
        )

        assertEquals(25L, decoded.maxBackoffMs, "encode -> decode must preserve the ceiling")
        assertEquals(3L, decoded.initialRecurrencePeriod, "the existing field must survive the round trip")
    }

    private fun projectedScope(payload: String): BlockShellScope.WaitUntilScope {
        val projection = project(payload)
        val scope = assertInstanceOf(BodyExecutionProjection.Scope::class.java, projection).scope
        return assertInstanceOf(BlockShellScope.WaitUntilScope::class.java, scope)
    }

    private fun project(payload: String): BodyExecutionProjection =
        block(payload).projectBodyExecution(
            policy = BodyExecutionPolicy.Retrying(
                policy = RetryPolicy(),
                waitUntil = WaitUntilShape(initialRecurrencePeriodMs = 1L, quiet = false),
            ),
            options = shOptions,
        )

    private fun block(payload: String) = BlockStepNode(
        id = StepId("stage/block"),
        pluginStepId = PluginStepId("core.waitUntil"),
        payload = VersionedStepPayload("dsl-v1", payload),
        body = emptyList(),
    )

    private val shOptions: ShOptions by lazy {
        ShOptions(
            workspaceRoot = Path.of("."),
            captureStdout = false,
            timeoutMs = null,
            env = emptyMap(),
            sandbox = SandboxConfig.NONE,
        )
    }

    private companion object {
        const val DEFAULT_MAX_BACKOFF_MS = 60_000L
    }
}