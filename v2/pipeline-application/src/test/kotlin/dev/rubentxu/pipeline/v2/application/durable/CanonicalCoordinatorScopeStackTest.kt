package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * UAT-LFC1-008-SCOPE-STACK: Coordinator scope tracking for catchError/warnError.
 *
 * Tests the scope-stack semantics:
 * - Scope is pushed on CatchErrorEntered and popped on CatchErrorTriggered(emitted=true)
 * - Failure inside UNSTABLE scope is downgraded to Unstable (pipeline continues)
 * - Failure inside FAILURE scope propagates as Failure (pipeline aborts)
 * - Scope stack must be empty at stage boundary (illegalStateException if not)
 */
@Timeout(10)
class CanonicalCoordinatorScopeStackTest {

    private fun makeCoordinator(): CanonicalDurableRunCoordinator {
        val clock = SystemClock()
        return CanonicalDurableRunCoordinator(
            CanonicalNodeDispatcher(),
            InMemoryOperationJournal(clock),
            InMemoryReplayCursorStore(clock),
            clock,
            DefaultEffectReplayPolicy(),
            InMemoryEventStore(),
        )
    }

    /**
     * Helper to build a pipeline with a single echo step.
     */
    private fun echoPipeline(): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("test"),
        source = SourceDescriptor("test", Digest("test")),
        pluginLockDigest = Digest("test"),
        stages = listOf(
            StageNode(
                id = StageId("test"),
                name = "test",
                body = StageBody.Steps(
                    listOf(
                        OpaqueStepNode(
                            id = StepId("test/echo"),
                            pluginStepId = PluginStepId("core.echo"),
                            payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"hello"}"""),
                        ),
                    ),
                ),
            ),
        ),
    )

    /**
     * Helper to build a pipeline with CatchErrorEntered + Shell(fail) + CatchErrorTriggered(emitted=true).
     * Simulates: catchError(buildResult="UNSTABLE") { sh("exit 1") }
     * Expected: Failure inside UNSTABLE scope → coordinator downgrades to Unstable → pipeline continues
     */
    private fun catchErrorUnstablePipeline(): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("catch-error-test"),
        source = SourceDescriptor("test", Digest("test")),
        pluginLockDigest = Digest("test"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(
                    listOf(
                        // [0] CatchErrorEntered — scope entry
                        OpaqueStepNode(
                            id = StepId("build/catch-enter-0"),
                            pluginStepId = PluginStepId("core.emit.event"),
                            payload = VersionedStepPayload(
                                "dsl-v1",
                                """{"kind":"CatchErrorEntered","buildResult":"UNSTABLE","stageResult":"UNSTABLE","enteredAt":"${System.currentTimeMillis()}"}""",
                            ),
                        ),
                        // [1] Shell step that fails
                        OpaqueStepNode(
                            id = StepId("build/sh-0"),
                            pluginStepId = PluginStepId("core.sh"),
                            payload = VersionedStepPayload("dsl-v1", """{"kind":"sh","command":"exit 1","isScriptBlock":false,"returnStdout":false}"""),
                        ),
                        // [2] CatchErrorTriggered(emitted=true) — scope exit
                        OpaqueStepNode(
                            id = StepId("build/catch-trigger-0"),
                            pluginStepId = PluginStepId("core.emit.event"),
                            payload = VersionedStepPayload(
                                "dsl-v1",
                                """{"kind":"CatchErrorTriggered","buildResult":"UNSTABLE","stageResult":"UNSTABLE","emitted":"true"}""",
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    /**
     * Helper: catchError(buildResult="FAILURE") { sh("exit 1") }
     * Expected: Failure inside FAILURE scope propagates as Failure
     */
    private fun catchErrorFailurePipeline(): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("catch-error-failure"),
        source = SourceDescriptor("test", Digest("test")),
        pluginLockDigest = Digest("test"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(
                    listOf(
                        OpaqueStepNode(
                            id = StepId("build/catch-enter-0"),
                            pluginStepId = PluginStepId("core.emit.event"),
                            payload = VersionedStepPayload(
                                "dsl-v1",
                                """{"kind":"CatchErrorEntered","buildResult":"FAILURE","stageResult":"FAILURE","enteredAt":"${System.currentTimeMillis()}"}""",
                            ),
                        ),
                        OpaqueStepNode(
                            id = StepId("build/sh-0"),
                            pluginStepId = PluginStepId("core.sh"),
                            payload = VersionedStepPayload("dsl-v1", """{"kind":"sh","command":"exit 1","isScriptBlock":false,"returnStdout":false}"""),
                        ),
                        OpaqueStepNode(
                            id = StepId("build/catch-trigger-0"),
                            pluginStepId = PluginStepId("core.emit.event"),
                            payload = VersionedStepPayload(
                                "dsl-v1",
                                """{"kind":"CatchErrorTriggered","buildResult":"FAILURE","stageResult":"FAILURE","emitted":"true"}""",
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `happy path echo pipeline succeeds`() = runBlocking {
        val coordinator = makeCoordinator()
        val outcome = coordinator.run(echoPipeline(), RunId("happy-run"))
        assertEquals(RunOutcome.Success, outcome)
    }

    @Test
    fun `downgrade Failure to Unstable within UNSTABLE catchError scope`() = runBlocking {
        val coordinator = makeCoordinator()
        val outcome = coordinator.run(catchErrorUnstablePipeline(), RunId("unstable-scope-run"))
        // Failure inside UNSTABLE scope → coordinator downgrades to Unstable
        assertEquals(RunOutcome.Unstable, outcome)
    }

    @Test
    fun `propagate Failure within FAILURE catchError scope`() = runBlocking {
        val coordinator = makeCoordinator()
        val outcome = coordinator.run(catchErrorFailurePipeline(), RunId("failure-scope-run"))
        // Failure inside FAILURE scope → coordinator propagates Failure
        assertTrue(outcome is RunOutcome.Failure, "Expected RunOutcome.Failure but got $outcome")
    }

    @Test
    fun `scope leak across stages throws IllegalStateException`() = runBlocking {
        // Pipeline where scope is NOT properly popped before stage ends
        val pipelineWithLeak = CompiledPipeline(
            id = DefinitionId("scope-leak"),
            source = SourceDescriptor("test", Digest("test")),
            pluginLockDigest = Digest("test"),
            stages = listOf(
                StageNode(
                    id = StageId("build"),
                    name = "build",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("build/catch-enter-0"),
                                pluginStepId = PluginStepId("core.emit.event"),
                                payload = VersionedStepPayload(
                                    "dsl-v1",
                                    """{"kind":"CatchErrorEntered","buildResult":"UNSTABLE","enteredAt":"${System.currentTimeMillis()}"}""",
                                ),
                            ),
                            // Missing CatchErrorTriggered to pop the scope
                            OpaqueStepNode(
                                id = StepId("build/echo"),
                                pluginStepId = PluginStepId("core.echo"),
                                payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"hello"}"""),
                            ),
                        ),
                    ),
                ),
                StageNode(
                    id = StageId("deploy"),
                    name = "deploy",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("deploy/echo"),
                                pluginStepId = PluginStepId("core.echo"),
                                payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"deploy"}"""),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val coordinator = makeCoordinator()
        try {
            coordinator.run(pipelineWithLeak, RunId("leak-run"))
            fail("Expected IllegalStateException for scope leak")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Scope stack leaked") == true,
                "Error message should mention scope leak: ${e.message}")
        }
    }

    @Test
    fun `pop on empty stack throws IllegalStateException`() = runBlocking {
        // Pipeline that pops scope without pushing first
        val pipelineWithUnderflow = CompiledPipeline(
            id = DefinitionId("scope-underflow"),
            source = SourceDescriptor("test", Digest("test")),
            pluginLockDigest = Digest("test"),
            stages = listOf(
                StageNode(
                    id = StageId("build"),
                    name = "build",
                    body = StageBody.Steps(
                        listOf(
                            // Try to pop without pushing first
                            OpaqueStepNode(
                                id = StepId("build/catch-trigger-0"),
                                pluginStepId = PluginStepId("core.emit.event"),
                                payload = VersionedStepPayload(
                                    "dsl-v1",
                                    """{"kind":"CatchErrorTriggered","buildResult":"UNSTABLE","stageResult":"UNSTABLE","emitted":"true"}""",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val coordinator = makeCoordinator()
        try {
            coordinator.run(pipelineWithUnderflow, RunId("underflow-run"))
            fail("Expected IllegalStateException for scope underflow")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("underflow") == true,
                "Error message should mention underflow: ${e.message}")
        }
    }
}
