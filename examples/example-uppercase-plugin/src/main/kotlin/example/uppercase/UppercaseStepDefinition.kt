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
        requiredCapabilities = emptySet(),
    )

    override val handler = StepHandler<UppercaseInput, UppercaseOutput> { input, _ ->
        UppercaseOutput(value = input.text.uppercase())
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
