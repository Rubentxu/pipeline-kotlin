package example.uppercase

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.registry.PLUGIN_EVENT_EMISSION_CAPABILITY
import dev.rubentxu.pipeline.v2.events.registry.PluginEventEmission
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Example external plugin (LB-02 EP-3..EP-5).
 *
 * Depends ONLY on public SDK contracts (pipeline-domain) and the public DSL
 * (pipeline-scripting-api, for the extension function in UppercaseDsl.kt).
 * Contributes `uppercase` through [StepDefinitionContributor]; discovered by
 * the host runtime via ServiceLoader. Zero core/production changes.
 */

@Serializable
data class UppercaseInput(val text: String)

@Serializable
data class UppercaseOutput(val value: String)

object UppercaseCodec : StepCodec<UppercaseInput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: UppercaseInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(UppercaseInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): UppercaseInput =
        json.decodeFromString(UppercaseInput.serializer(), encoded.value)
}

object UppercaseOutputCodec : StepCodec<UppercaseOutput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: UppercaseOutput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(UppercaseOutput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): UppercaseOutput =
        json.decodeFromString(UppercaseOutput.serializer(), encoded.value)
}

object UppercaseStepDefinition : StepDefinition<UppercaseInput, UppercaseOutput> {
    val KEY = PluginStepId("example.uppercase")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "uppercase",
            configRef = "",
            pluginId = "example.uppercase",
            pluginVersion = "0.1.0",
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = UppercaseCodec,
        outputCodec = UppercaseOutputCodec,
        // P3-D / S6.4: the plugin asks for the emission seam by DECLARING it. Admission is
        // fail-closed — a run that composed no registry never exposes it and this Step is refused
        // before the handler runs — so there is no "declared but silently inert" mode.
        requiredCapabilities = setOf(PLUGIN_EVENT_EMISSION_CAPABILITY),
    )

    override val handler = StepHandler<UppercaseInput, UppercaseOutput> { input, context ->
        val output = UppercaseOutput(value = input.text.uppercase())

        // P3-D: the plugin emits its own event through the capability. It cannot reach the
        // EventStore, the JSON log, SQLite, the sequence assigner, the clock, or the runId —
        // `get` hands back exactly the seam, and the store assigns the sequence. The outcome is
        // NOT inspected: an unregistered kind or a codec refusal is a named result the runtime
        // surfaces, not an exception this handler should turn into a Step failure, because the
        // uppercase work itself already succeeded.
        val emission = context.capabilities.get<PluginEventEmission>(PLUGIN_EVENT_EMISSION_CAPABILITY)
        emission.emit(
            "example.uppercase.applied",
            UppercaseApplied(inputLength = input.text.length, outputLength = output.value.length),
        )

        output
    }
}

/**
 * ServiceLoader entry point (EP-4). Discovered generically by the runtime:
 * `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`.
 */
class UppercaseContributor : StepDefinitionContributor {
    override val id: String = "example.uppercase"

    override fun definitions(): Iterable<StepDefinition<*, *>> = listOf(UppercaseStepDefinition)
}
