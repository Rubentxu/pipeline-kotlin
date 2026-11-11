package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.MapRuntimeConfig
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageScope
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.runtime.CoreParamStep
import dev.rubentxu.pipeline.v2.runtime.ParamInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The DSL call that reads a parameter of the run.
 *
 * ## The guarantee under trial
 *
 * That the call lowers to the registry step that reads the parameters, with a payload the
 * step's OWN codec understands, and that it answers NOTHING to the author: the effective
 * value of a parameter is not knowable while the pipeline is being built, so a returned text
 * would be a value nobody delivered.
 *
 * ## Why the payload is read back through the step's codec
 *
 * Because the two ends of the wire live in different files and would otherwise drift: the
 * builder writes the JSON by hand and the step decodes it. Reading the builder's bytes back
 * through the step's own input codec is what makes this row the seam between them, so a
 * payload that drifts from the step's vocabulary fails here and not in a run.
 *
 * ## What this instrument cannot see
 *
 * It does not run a step and does not read a value: the value is decided by what the run is
 * given, which no builder can know. That the value really travels is the question of the
 * integration lane, and that the bridge exposes it is the question of the capability access
 * rows.
 */
class RunParameterReadDslTest {

    private val config = MapRuntimeConfig.empty()

    /** Builds one stage and returns the built pipeline. */
    private fun build(block: StageScope.() -> Unit): PipelineSpec =
        pipeline(config) {
            stages {
                stage("stage", block)
            }
        }

    private fun PipelineSpec.topLevelSteps(): List<StepSpec> = stages.single().steps

    @Test
    fun `the read lowers to the registry step of the parameters`() {
        val spec = build {
            param("target")
        }

        val step = spec.topLevelSteps().filterIsInstance<StepSpec.RegistryStepSpec>().single()
        assertEquals(PluginStepId("core.param"), step.stepKey)
    }

    @Test
    fun `the payload of the read is the one the step decodes`() {
        val spec = build {
            param("target")
        }

        val step = spec.topLevelSteps().filterIsInstance<StepSpec.RegistryStepSpec>().single()
        val decoded = CoreParamStep.definition.contract.inputCodec.decode(step.encodedInput)

        assertEquals(ParamInput(name = "target"), decoded)
    }

    @Test
    fun `a name that needs escaping travels escaped`() {
        val spec = build {
            param("the \"quoted\" name")
        }

        val step = spec.topLevelSteps().filterIsInstance<StepSpec.RegistryStepSpec>().single()
        val decoded = CoreParamStep.definition.contract.inputCodec.decode(step.encodedInput)

        assertEquals("the \"quoted\" name", decoded.name)
    }

    @Test
    fun `a read with no name is refused while the stage is being built`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            build {
                param("  ")
            }
        }

        assertTrue(
            failure.message.orEmpty().contains("name of the parameter"),
            "the refusal names what the call needs: ${failure.message}",
        )
    }

    @Test
    fun `the read adds a step of its own and nothing else`() {
        val spec = build {
            param("target")
        }

        assertEquals(
            1,
            spec.topLevelSteps().size,
            "the read is one step: ${spec.topLevelSteps()}",
        )
    }
}
