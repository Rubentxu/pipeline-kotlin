package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.step.AttemptSegment
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.ExecutionContextPatch
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The seam a Saga handler reaches its own body's children through.
 *
 * The claim under test is that the selected child travels the canonical spine with the identity
 * it WOULD have had if the engine had dispatched it itself, and that a name the body does not
 * hold is answered inside the handler's own outcome without reaching the spine at all.
 */
class SagaSlotDispatcherTest {

    @TempDir
    lateinit var tempDir: Path

    private val runId = RunId("run-1")

    private fun child(index: Int, kind: String, slot: String? = null): StepNode =
        OpaqueStepNode(
            id = StepId("child-$index"),
            pluginStepId = PluginStepId(kind),
            payload = VersionedStepPayload("1", "{}"),
            slot = slot?.let { PluginStepId(it) },
        )

    private fun options() = ShOptions(
        workspaceRoot = tempDir,
        captureStdout = false,
        timeoutMs = null,
        env = emptyMap(),
    )

    private fun request(
        bodyPath: List<BlockSegment> = listOf(BlockSegment(0, PluginStepId("saga"))),
        executionContext: ExecutionContext = ExecutionContext.EMPTY,
    ) = SagaSlotRequest(
        runId = runId,
        stageName = "build",
        stageIndex = 2,
        stepIndex = 4,
        bodyPath = bodyPath,
        stageShOptions = options(),
        executionContext = executionContext,
    )

    private fun failureOf(outcome: BodyOutcome): dev.rubentxu.pipeline.v2.domain.PipelineFailure {
        val completed = assertInstanceOf<BodyOutcome.Completed>(outcome)
        return assertInstanceOf<StepOutcome.Failure>(completed.outcome).failure
    }

    @Test
    fun `the selected child travels the seam with the identity it would have had`() {
        val dispatcher = RecordingDispatcher()
        val forward = child(0, "sh")

        val outcome = runBlocking {
            SagaSlotDispatcher(listOf(forward), "saga", dispatcher)
                .invoker(request())
                .invoke(PluginStepId("sh"), BodyInvocationContext())
        }

        assertEquals(1, dispatcher.callCount)
        assertEquals(forward, dispatcher.child)
        assertEquals(runId, dispatcher.runId)
        assertEquals("build", dispatcher.stageName)
        assertEquals(2, dispatcher.stageIndex)
        assertEquals(4, dispatcher.stepIndex)
        assertEquals(
            listOf(BlockSegment(0, PluginStepId("saga")), BlockSegment(0, PluginStepId("sh"))),
            dispatcher.bodyPath,
        )
        assertInstanceOf<BodyOutcome.Completed>(outcome)
    }

    @Test
    fun `the attempt the handler carries is part of the child's path`() {
        val dispatcher = RecordingDispatcher()

        runBlocking {
            SagaSlotDispatcher(listOf(child(0, "sh")), "saga", dispatcher)
                .invoker(request())
                .invoke(
                    PluginStepId("sh"),
                    BodyInvocationContext(
                        attempt = AttemptSegment(index = 3, key = PluginStepId("retry-attempt")),
                    ),
                )
        }

        // The attempt travels between the parent's path and the child's own segment, exactly
        // where the canonical body loop puts it. A child that landed anywhere else would own
        // journal rows the rest of the run never reads.
        assertEquals(
            listOf(
                BlockSegment(0, PluginStepId("saga")),
                BlockSegment(3, PluginStepId("retry-attempt")),
                BlockSegment(0, PluginStepId("sh")),
            ),
            dispatcher.bodyPath,
        )
    }

    @Test
    fun `a name the body does not hold never reaches the spine`() {
        val dispatcher = RecordingDispatcher()

        val outcome = runBlocking {
            SagaSlotDispatcher(listOf(child(0, "sh")), "saga", dispatcher)
                .invoker(request())
                .invoke(PluginStepId("absent"), BodyInvocationContext())
        }

        assertEquals(0, dispatcher.callCount)
        assertEquals(FailureKind.ENGINE, failureOf(outcome).kind)
        assertTrue(
            failureOf(outcome).message.contains("absent"),
            "the failure must name the slot it could not resolve",
        )
    }

    @Test
    fun `a name two children share never reaches the spine`() {
        val dispatcher = RecordingDispatcher()

        val outcome = runBlocking {
            SagaSlotDispatcher(
                listOf(child(0, "sh"), child(1, "sh")),
                "saga",
                dispatcher,
            )
                .invoker(request())
                .invoke(PluginStepId("sh"), BodyInvocationContext())
        }

        // Two children under one name do not identify either of them, so the answer is a
        // refusal and not a guess.
        assertEquals(0, dispatcher.callCount)
        assertEquals(FailureKind.ENGINE, failureOf(outcome).kind)
    }

    @Test
    fun `a named child is reached by the name the plugin gave it`() {
        val dispatcher = RecordingDispatcher()
        val named = child(0, "sh", slot = "forward")

        runBlocking {
            SagaSlotDispatcher(listOf(named), "saga", dispatcher)
                .invoker(request())
                .invoke(PluginStepId("forward"), BodyInvocationContext())
        }

        assertEquals(1, dispatcher.callCount)
        assertEquals(named, dispatcher.child)
    }

    @Test
    fun `a child reached by the name the plugin gave it keeps its own segment`() {
        val dispatcher = RecordingDispatcher()
        val unnamed = child(0, "sh")
        val named = child(1, "sh", slot = "forward")

        runBlocking {
            SagaSlotDispatcher(listOf(unnamed, named), "saga", dispatcher)
                .invoker(request())
                .invoke(PluginStepId("forward"), BodyInvocationContext())
        }

        // The segment carries the child's POSITION in the body and the child's OWN kind — not
        // the name it is called by. A child that landed under the slot's name would own journal
        // rows no other child of that body could ever claim.
        assertEquals(
            listOf(BlockSegment(0, PluginStepId("saga")), BlockSegment(1, PluginStepId("sh"))),
            dispatcher.bodyPath,
        )
    }

    @Test
    fun `the patch the handler carries reaches the child it launches`() {
        val dispatcher = RecordingDispatcher()

        runBlocking {
            SagaSlotDispatcher(listOf(child(0, "sh")), "saga", dispatcher)
                .invoker(request())
                .invoke(
                    PluginStepId("sh"),
                    BodyInvocationContext(
                        patch = ExecutionContextPatch.Environment(mapOf("OPERATION_KEY" to "v1|forward")),
                    ),
                )
        }

        // The body is a process: it reads its conditions from the process environment, and the
        // process environment is the options the child is launched with.
        assertEquals(
            "v1|forward",
            dispatcher.childShOptions?.env?.get("OPERATION_KEY")?.materialize(),
        )
    }

    @Test
    fun `a child that fails travels its failure back to the handler`() {
        val dispatcher = RecordingDispatcher(
            StepOutcome.Failure(
                dev.rubentxu.pipeline.v2.domain.PipelineFailure(FailureKind.SCRIPT, "the child refused"),
            ),
        )

        val outcome = runBlocking {
            SagaSlotDispatcher(listOf(child(0, "sh")), "saga", dispatcher)
                .invoker(request())
                .invoke(PluginStepId("sh"), BodyInvocationContext())
        }

        assertEquals("the child refused", failureOf(outcome).message)
    }

    private class RecordingDispatcher(
        private val result: StepOutcome = StepOutcome.Success,
    ) : BodyChildDispatcher {
        var callCount = 0
        var child: StepNode? = null
        var runId: RunId? = null
        var stageName: String? = null
        var stageIndex: Int? = null
        var stepIndex: Int? = null
        var childShOptions: ShOptions? = null
        var bodyPath: List<BlockSegment> = emptyList()
        var executionContext: ExecutionContext? = null

        override suspend fun dispatchChild(
            child: StepNode,
            runId: RunId,
            stageName: String,
            stageIndex: Int,
            stepIndex: Int,
            childShOptions: ShOptions,
            bodyPath: List<BlockSegment>,
            executionContext: ExecutionContext,
        ): StepOutcome {
            callCount += 1
            this.child = child
            this.runId = runId
            this.stageName = stageName
            this.stageIndex = stageIndex
            this.stepIndex = stepIndex
            this.childShOptions = childShOptions
            this.bodyPath = bodyPath
            this.executionContext = executionContext
            return result
        }
    }
}
