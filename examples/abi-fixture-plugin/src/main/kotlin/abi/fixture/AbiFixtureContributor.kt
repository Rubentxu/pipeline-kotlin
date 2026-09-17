package abi.fixture

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
import dev.rubentxu.pipeline.v2.dsl.StageScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ABI FIXTURE PLUGIN (LFC-2E3-P / P1) — a plugin frozen in time.
 *
 * ## What this models
 *
 * A plugin JAR built against an EARLIER SDK shape and never rebuilt. Concretely it implements
 * ONLY the original [StepDefinitionContributor] surface:
 *
 * ```kotlin
 * interface StepDefinitionContributor {
 *     val id: String
 *     fun definitions(): Iterable<StepDefinition<*, *>>
 * }
 * ```
 *
 * It deliberately does NOT implement `StepCapabilityContributor`, declares NO capabilities, and
 * takes no dependency on any post-freeze SDK addition. That is exactly the shape of every plugin
 * JAR that existed before the capability SPI was introduced.
 *
 * ## What it proves
 *
 * A host built from a NEWER SDK must still:
 *
 * ```text
 * load this JAR  ->  discover its contributor  ->  register its Step
 *                ->  admit it at prepare-time  ->  EXECUTE its handler
 * ```
 *
 * If an existing SPI ever gains, loses, renames or re-types a member — with or without a default
 * value — this fixture breaks at runtime with `AbstractMethodError` while every source-level test
 * keeps passing. That asymmetry is the whole reason the fixture is a frozen binary.
 *
 * The Step is intentionally trivial (`abi.fixture.echo`): the point is that it RUNS, not what it
 * computes.
 */
class AbiFixtureContributor : StepDefinitionContributor {
    override val id: String = COORDINATE

    override fun definitions(): Iterable<StepDefinition<*, *>> = listOf(AbiFixtureEchoStepDefinition)

    companion object {
        const val COORDINATE: String = "abi.fixture"
        const val PLUGIN_VERSION: String = "1.0.0"

        /** The StepKey this fixture contributes. Asserted by the host-side ABI test. */
        const val STEP_KEY: String = "abi.fixture.echo"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// A trivial typed Step: echo one string through the canonical registry path.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class AbiFixtureInput(val text: String)

@Serializable
data class AbiFixtureOutput(val text: String, val length: Int)

object AbiFixtureCodec : StepCodec<AbiFixtureInput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: AbiFixtureInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(AbiFixtureInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): AbiFixtureInput =
        json.decodeFromString(AbiFixtureInput.serializer(), encoded.value)
}

object AbiFixtureOutputCodec : StepCodec<AbiFixtureOutput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: AbiFixtureOutput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(AbiFixtureOutput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): AbiFixtureOutput =
        json.decodeFromString(AbiFixtureOutput.serializer(), encoded.value)
}

object AbiFixtureEchoStepDefinition : StepDefinition<AbiFixtureInput, AbiFixtureOutput> {
    val KEY: PluginStepId = PluginStepId(AbiFixtureContributor.STEP_KEY)

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "abiFixtureEcho",
            configRef = "",
            pluginId = AbiFixtureContributor.COORDINATE,
            pluginVersion = AbiFixtureContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = AbiFixtureCodec,
        outputCodec = AbiFixtureOutputCodec,
        // No capabilities: this fixture must be admissible with the canonical bridge alone, so
        // its execution depends on nothing that could be re-wired later.
        requiredCapabilities = emptySet(),
    )

    override val handler = StepHandler<AbiFixtureInput, AbiFixtureOutput> { input, _ ->
        AbiFixtureOutput(text = input.text, length = input.text.length)
    }
}

fun StageScope.abiFixtureEcho(text: String) = registryStep(
    stepKey = AbiFixtureEchoStepDefinition.KEY,
    encodedInput = AbiFixtureCodec.encode(AbiFixtureInput(text)),
)
