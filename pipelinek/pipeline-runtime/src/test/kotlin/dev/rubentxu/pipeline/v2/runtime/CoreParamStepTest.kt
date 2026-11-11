package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.RUN_PARAMETERS_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.RunParameterValues
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.reflect.KClass

/**
 * The step that reads the effective value of a parameter the run declares.
 *
 * ## The guarantee under trial
 *
 * That the step answers with the value the RUN carries, and that a name the run does not
 * carry is a FAILURE that names the parameter — never a value nobody delivered.
 *
 * ## Why the absence is a failure and not an empty string
 *
 * Because the two are different facts, and the difference is the whole point of the seam. A
 * parameter that declares no default and was not delivered never starts the run, so the only
 * way to reach "the run does not carry this name" is to ask for a name the pipeline does not
 * declare. Answering an empty string would let a pipeline that reads a misspelled parameter
 * carry on with a value nobody chose, and the author would never learn about it.
 *
 * ## What this instrument cannot see
 *
 * It does not run a pipeline, does not read a declaration and does not touch the journal: it
 * observes the STEP, with the capability access faked. Whether the values really travel from
 * the client to the run is the question of the integration lane, and the question of whether
 * the bridge exposes them is the question of the capability access rows.
 */
class CoreParamStepTest {

    @Test
    fun `the step answers with the value the run carries`() {
        val output = execute(
            input = ParamInput(name = "target"),
            parameters = RunParameterValues.of(mapOf("target" to "prod")),
        )

        val resolved = assertInstanceOf(
            ParamOutput.Resolved::class.java,
            output,
            "the step must answer with the value the run carries",
        )
        assertEquals("target", resolved.name)
        assertEquals("prod", resolved.value)
        assertEquals(StepOutcome.Success, resolved.outcome)
    }

    @Test
    fun `a name the run does not carry fails the step by name`() {
        val output = execute(
            input = ParamInput(name = "dryRun"),
            parameters = RunParameterValues.of(mapOf("target" to "prod")),
        )

        val notDeclared = assertInstanceOf(
            ParamOutput.NotDeclared::class.java,
            output,
            "a name the run does not carry must not be answered with a value",
        )
        assertEquals("dryRun", notDeclared.name)
        assertEquals(
            StepOutcome.Failure(
                PipelineFailure(FailureKind.USER, "the run declares no parameter named 'dryRun'"),
            ),
            notDeclared.outcome,
            "the failure names the parameter, so the author knows which one to declare",
        )
    }

    @Test
    fun `a run that carries no parameter fails the read instead of answering an empty value`() {
        val output = execute(
            input = ParamInput(name = "target"),
            parameters = RunParameterValues.EMPTY,
        )

        assertInstanceOf(
            ParamOutput.NotDeclared::class.java,
            output,
            "a run that declares no parameter answers no value at all",
        )
    }

    @Test
    fun `the contract declares the capability of the run parameters`() {
        assertEquals(
            setOf(RUN_PARAMETERS_CAPABILITY),
            CoreParamStep.definition.contract.requiredCapabilities,
            "a step that reads the parameters and declares no capability would be admitted " +
                "into a run that carries none",
        )
    }

    @Test
    fun `the resolved shape makes the round trip without losing the value`() {
        val codec = CoreParamStep.definition.contract.outputCodec
        val resolved = ParamOutput.Resolved(name = "target", value = "prod")

        val decoded = codec.decode(codec.encode(resolved))

        assertEquals(resolved, decoded)
    }

    @Test
    fun `the refusal shape makes the round trip without losing the name`() {
        val codec = CoreParamStep.definition.contract.outputCodec
        val notDeclared = ParamOutput.NotDeclared(name = "dryRun")

        val decoded = codec.decode(codec.encode(notDeclared))

        assertEquals(notDeclared, decoded)
    }

    @Test
    fun `an output of another kind is rejected instead of being interpreted`() {
        assertThrows<IllegalArgumentException> {
            CoreParamStep.definition.contract.outputCodec.decode(
                EncodedStepValue("""{"kind":"artifactQuery","outcome":"OK"}"""),
            )
        }
    }

    @Test
    fun `an input of another kind is rejected instead of being interpreted`() {
        assertThrows<IllegalArgumentException> {
            CoreParamStep.definition.contract.inputCodec.decode(
                EncodedStepValue("""{"kind":"artifactQuery","name":"target"}"""),
            )
        }
    }

    @Test
    fun `the input of the step carries the name it reads`() {
        val decoded = CoreParamStep.definition.contract.inputCodec.decode(
            EncodedStepValue("""{"kind":"param","name":"target"}"""),
        )

        assertEquals(ParamInput(name = "target"), decoded)
    }

    @Test
    fun `the input of the step encodes the name it reads`() {
        val codec = CoreParamStep.definition.contract.inputCodec

        val decoded = codec.decode(codec.encode(ParamInput(name = "target")))

        assertEquals(ParamInput(name = "target"), decoded)
    }

    @Test
    fun `an input with no name is refused instead of being read`() {
        assertThrows<IllegalArgumentException> {
            ParamInput(name = "  ")
        }
    }

    @Test
    fun `the key of the step is the one the DSL and the registry name`() {
        assertEquals(PluginStepId("core.param"), CoreParamStep.KEY)
        assertEquals(
            CoreParamStep.KEY,
            CoreParamStep.definition.contract.key,
            "the contract is reached by the key the step publishes, and not by another one",
        )
    }

    /**
     * Runs the handler of the step against a context that carries [parameters].
     */
    private fun execute(input: ParamInput, parameters: RunParameterValues): ParamOutput =
        runBlocking {
            CoreParamStep.definition.handler.execute(
                input,
                StepHandlerContext(
                    runId = RunId("run-1"),
                    stepIndex = 0,
                    capabilities = CarryingAccess(parameters),
                ),
            )
        }

    /**
     * The capability access of a run that carries [parameters].
     *
     * It is the seam the production bridge implements, faked to the one fact this file
     * measures: the carrier that reaches the handler is the run's own.
     */
    private class CarryingAccess(
        private val parameters: RunParameterValues,
    ) : StepCapabilityAccess {

        override fun available(): Set<StepCapability> = setOf(RUN_PARAMETERS_CAPABILITY)

        override fun <T : Any> get(key: StepCapability, type: KClass<T>): T {
            assertTrue(
                key == RUN_PARAMETERS_CAPABILITY,
                "the step asked for a capability it does not declare: $key",
            )
            assertTrue(
                type.isInstance(parameters),
                "the capability $key is not of the declared type: expected ${type.simpleName}, " +
                    "found ${parameters::class.simpleName}",
            )
            return type.java.cast(parameters)
        }
    }
}
