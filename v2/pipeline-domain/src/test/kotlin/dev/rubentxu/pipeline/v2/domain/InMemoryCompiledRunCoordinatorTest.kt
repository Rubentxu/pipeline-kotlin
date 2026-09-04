package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InMemoryCompiledRunCoordinatorTest {
    private fun node(id: String) = OpaqueStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId("core.echo"),
        payload = VersionedStepPayload("dsl-v1", "{\"id\":\"$id\"}"),
    )

    private fun pipeline(vararg nodes: StepNode) = CompiledPipeline(
        id = DefinitionId("compiled-pipeline"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("main"),
                name = "main",
                body = StageBody.Steps(nodes.toList()),
            ),
        ),
    )

    @Test
    fun `executes a compiled pipeline through its nodes without a synthetic registry`() {
        val dispatched = mutableListOf<StepId>()
        val coordinator = InMemoryCompiledRunCoordinator(
            CompiledStepDispatcher { step, _ ->
                dispatched += step.id
                StepOutcome.Success
            },
        )

        val outcome = coordinator.run(CompiledRunRequest(pipeline(node("build"), node("test")), RunId("run-1")))

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(listOf(StepId("build"), StepId("test")), dispatched)
    }

    @Test
    fun `flattens canonical parallel units in plan order and reduces their typed outcomes`() {
        val dispatched = mutableListOf<StepId>()
        val failure = PipelineFailure(FailureKind.SCRIPT, "mac test failed")
        val coordinator = InMemoryCompiledRunCoordinator(
            CompiledStepDispatcher { step, _ ->
                dispatched += step.id
                when (step.id) {
                    StepId("linux-test") -> StepOutcome.Unstable
                    StepId("mac-test") -> StepOutcome.Failure(failure)
                    else -> StepOutcome.Success
                }
            },
        )
        val compiled = CompiledPipeline(
            id = DefinitionId("compiled-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(StageId("build"), "build", body = StageBody.Steps(listOf(node("compile")))),
                StageNode(
                    StageId("test"),
                    "test",
                    body = StageBody.Parallel(listOf(
                        StageNode(StageId("linux"), "linux", body = StageBody.Steps(listOf(node("linux-test")))),
                        StageNode(StageId("mac"), "mac", body = StageBody.Steps(listOf(node("mac-test")))),
                    )),
                ),
            ),
        )

        val outcome = coordinator.run(CompiledRunRequest(compiled, RunId("run-2")))

        assertEquals(RunOutcome.Failure(failure), outcome)
        assertEquals(listOf(StepId("compile"), StepId("linux-test"), StepId("mac-test")), dispatched)
    }
}
