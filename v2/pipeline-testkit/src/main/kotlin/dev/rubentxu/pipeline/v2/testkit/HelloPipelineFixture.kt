package dev.rubentxu.pipeline.v2.testkit

import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.PipelineDefinition
import dev.rubentxu.pipeline.v2.domain.StepDescriptor

/**
 * Pairing of a V2 [PipelineDefinition] with the [StepDescriptor] seeds that
 * compose it. Used by [HelloPipelineFixture] for the UAT-M0-001 baseline.
 */
data class HelloPipeline(
    val definition: PipelineDefinition,
    val steps: List<StepDescriptor>,
)

/**
 * Value-only fixture for UAT-M0-001. NO config resolution, NO I/O, NO clock.
 * Deterministic: equal across re-builds.
 */
object HelloPipelineFixture {
    val echoStep: StepDescriptor =
        StepDescriptor(id = "hello-echo", type = "echo", configRef = "hello.echo.config")
    val sleepStep: StepDescriptor =
        StepDescriptor(id = "hello-sleep", type = "sleep", configRef = "hello.sleep.config")

    fun build(): HelloPipeline =
        HelloPipeline(
            definition = PipelineDefinition(
                id = DefinitionId("hello"),
                name = "hello",
                version = "0.0.0",
            ),
            steps = listOf(echoStep, sleepStep),
        )
}
