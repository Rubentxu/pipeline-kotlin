package example.serviceclient

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.runtime.durable.DURABLE_OPERATION_CAPABILITY
import dev.rubentxu.pipeline.v2.runtime.durable.DurableOperationFacts

/**
 * A step of an OUTSIDE plugin that reads the durable facts of its own operation.
 *
 * ## What this proves
 *
 * That a program which resolves NOTHING but the published SDK can name the
 * durable-facts capability, the identity of an operation and the port that
 * carries its facts: they all come from the published bundle, and a bundle
 * that did not carry them would not compile this file at all.
 *
 * ## What it does NOT do
 *
 * It does not run a pipeline and it does not read a journal. A step that runs
 * inside the runtime receives the port from the engine; this example is
 * OUTSIDE, so what it can show is that the contract is reachable and
 * nameable, which is the half that was missing before the facts were
 * published.
 *
 * ## The shape of the declaration
 *
 * The contract declares [DURABLE_OPERATION_CAPABILITY] among the capabilities
 * the step requires, and the handler reads the port out of the capability
 * table with the very key it declared. A step that declares a capability the
 * runtime does not supply is refused before its handler runs, so declaring it
 * is a promise the engine keeps.
 */
object DurableFactsStep {

    /** The key this step is registered under. */
    val KEY: PluginStepId = PluginStepId("example.durableFacts")

    /** The capability this step declares, as the runtime sees it. */
    val declaredCapability: String = DURABLE_OPERATION_CAPABILITY.key

    /**
     * The contract of the step: the capability it declares and the codecs of
     * its payload.
     */
    val contract: StepContract<String, String> = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "durableFacts",
            // The equivalent in Jenkins is its `catchError`/`retry` machinery over a
            // recorded attempt: both read what a previous attempt left behind.
            jenkinsSurface = "catchError",
            // The divergence, with its motive: this step reads the durable facts of its
            // OWN operation through the port of the published SDK, and the facts it
            // reads are the ones the engine bound to this invocation.
            jenkinsDivergence = "it reads the durable facts of its own operation through the port of the published SDK, and the identity it reads them under is the one the engine bound to this invocation",
            configRef = "",
        ),
        inputCodec = StringCodec,
        outputCodec = StringCodec,
        requiredCapabilities = setOf(DURABLE_OPERATION_CAPABILITY),
    )

    /**
     * The handler of the step: it reads the facts of its own operation through the
     * port the runtime injected.
     */
    val handler: StepHandler<String, String> = StepHandler { _, context ->
        val facts: DurableOperationFacts = context.capabilities.get(
            DURABLE_OPERATION_CAPABILITY,
            DurableOperationFacts::class,
        )
        "${facts.identity.opId}:${facts.recordedStatus()}"
    }

    /** A codec that transports a string, and nothing else. */
    private object StringCodec : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)

        override fun decode(encoded: EncodedStepValue): String = encoded.value
    }
}
