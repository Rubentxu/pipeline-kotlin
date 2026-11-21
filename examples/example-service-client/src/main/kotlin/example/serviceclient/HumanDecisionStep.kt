package example.serviceclient

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.runtime.input.INPUT_DECISIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.runtime.input.InputDecisions
import dev.rubentxu.pipeline.v2.runtime.input.InputRequest

/**
 * A step of an OUTSIDE plugin that asks a person.
 *
 * ## What this proves
 *
 * That a program which resolves NOTHING but the published SDK can name the
 * human-decision capability and declare it in the contract of a step: the
 * capability key, the port and the request it carries all come from the
 * published bundle, and a bundle that did not carry them would not compile
 * this file at all.
 *
 * ## What it does NOT do
 *
 * It does not run a pipeline and it does not answer a question. A plugin that
 * runs inside the runtime receives the port from the engine; this example is
 * OUTSIDE, so what it can show is that the contract is reachable and
 * nameable, which is the half that was missing before the port was published.
 *
 * ## The shape of the declaration
 *
 * The contract declares [INPUT_DECISIONS_CAPABILITY] among the capabilities
 * the step requires, and the handler reads the port out of the capability
 * table with the very key it declared. A step that declares a capability the
 * runtime does not supply is refused before its handler runs, so declaring it
 * is a promise the engine keeps.
 */
object HumanDecisionStep {

    /** The key this step is registered under. */
    val KEY: PluginStepId = PluginStepId("example.humanDecision")

    /** The capability this step declares, as the runtime sees it. */
    val declaredCapability: String = INPUT_DECISIONS_CAPABILITY.key

    /**
     * The contract of the step: the capability it declares and the codecs of
     * its payload.
     */
    val contract: StepContract<String, String> = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "humanDecision",
            // The equivalent in Jenkins is its `input` step: both ask a person and continue
            // only on the affirmative answer.
            jenkinsSurface = "input",
            // The divergence, with its motive: this step asks through the port of the
            // published SDK, and the submitter it carries is attribution and not
            // authorization.
            jenkinsDivergence = "it asks through the port of the published SDK, and the submitter it carries is attribution and not authorization",
            configRef = "",
        ),
        inputCodec = StringCodec,
        outputCodec = StringCodec,
        requiredCapabilities = setOf(INPUT_DECISIONS_CAPABILITY),
    )

    /**
     * The handler of the step: it asks through the port the runtime injected.
     */
    val handler: StepHandler<String, String> = StepHandler { message, context ->
        val decisions: InputDecisions = context.capabilities.get(INPUT_DECISIONS_CAPABILITY, InputDecisions::class)
        decisions.awaitDecision(
            request = InputRequest(message = message, ok = "Proceed", submitter = null, id = null),
            waitMillis = null,
        ).toString()
    }

    /** A codec that transports a string, and nothing else. */
    private object StringCodec : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)

        override fun decode(encoded: EncodedStepValue): String = encoded.value
    }
}
