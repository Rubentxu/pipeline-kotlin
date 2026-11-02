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
 * Example external plugin
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
        // ZERO capabilities, on purpose, and that is the reference value this Step carries.
        // It is the strongest statement a plugin can make about not needing core changes: this
        // Step asks the host for nothing at all and still runs. first added the emission here
        // and the certification suite caught it — `contract completeness - no privileged
        // capabilities` is a standing assertion that the reference plugin demands nothing.
        // So the observation lives in a SECOND Step, `example.uppercase.observed`, which declares
        // exactly one capability and asks the host for it. One plugin, both shapes, and the
        // zero-capability proof survives untouched.
        requiredCapabilities = emptySet(),
    )

    override val handler = StepHandler<UppercaseInput, UppercaseOutput> { input, _ ->
        UppercaseOutput(value = input.text.uppercase())
    }
}

/**
 * ServiceLoader entry point. Discovered generically by the runtime
 * `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`.
*/
class UppercaseContributor : StepDefinitionContributor {
    override val id: String = "example.uppercase"

    /**
     * FIVE Steps, and each one is a different claim.
     *  - `uppercase` demands nothing at all and still runs;
     *  - `uppercaseObserved` demands exactly one host seam and gets only that seam;
     *  - `uppercaseMeasured` asks the RUNTIME to observe the work, through a different seam;
     *  - `uppercase.cased` demands a seam THIS plugin supplies, which closes the loop where the
     *    artifact hands the host a value and the host hands it back.
     * See [UppercaseObservedStepDefinition] and [UppercaseCasedStepDefinition] for why none of
     * the three could have been folded into another.
     *
     * The list here and the manifest's list are the SAME fact, and the host compares them: a
     * step the manifest declares and this contributor does not provide is refused at admission
     * ("declared Steps with no implementation"), and a step provided and not declared is refused
     * too. Adding a Step therefore means adding it in BOTH places, and the refusal is what makes
     * the two places impossible to forget one of.
*/
    override fun definitions(): Iterable<StepDefinition<*, *>> =
        listOf(
            UppercaseStepDefinition,
            UppercaseObservedStepDefinition,
            UppercaseMeasuredStepDefinition,
            UppercaseCasedStepDefinition,
            UppercaseAnnouncedCasedStepDefinition,
        )
}
