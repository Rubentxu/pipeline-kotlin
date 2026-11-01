package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyRejection
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolver
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompiledPipelineValidatorTest {

    @Test
    fun `rejects duplicate step ids among stage siblings`() {
        val duplicate = opaqueStep("same")
        val pipeline = pipeline(duplicate, opaqueStep("same"))

        val failure = assertThrows(IllegalArgumentException::class.java) {
            CompiledPipelineValidator.validate(pipeline)
        }

        assertTrue(failure.message.orEmpty().contains("Duplicate step id 'same'"))
    }

    @Test
    fun `admits plugin block through the supplied run policy resolver`() {
        val pluginKey = PluginStepId("plugin.scope")
        val resolver = BodyPolicyResolver { key ->
            if (key == pluginKey) {
                BodyPolicyResolution.Resolved(key, BodyExecutionPolicy.Sequential)
            } else {
                BodyPolicyResolution.Rejected(key, BodyPolicyRejection.UnknownStep(key))
            }
        }
        val block = BlockStepNode(
            id = StepId("plugin-block"),
            pluginStepId = pluginKey,
            payload = payload(),
            body = listOf(opaqueStep("child")),
        )

        assertDoesNotThrow { CompiledPipelineValidator.validate(pipeline(block), resolver) }
    }

    private fun pipeline(vararg steps: StepNode) = CompiledPipeline(
        id = DefinitionId("definition"),
        source = SourceDescriptor("pipeline.kts", Digest("digest")),
        stages = listOf(
            StageNode(StageId("stage"), "stage", body = StageBody.Steps(steps.toList())),
        ),
        pluginLockDigest = Digest("lock"),
    )

    private fun opaqueStep(id: String) = OpaqueStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId("core.sh"),
        payload = payload(),
    )

    private fun payload() = VersionedStepPayload("dsl-v1", "{}")
}
