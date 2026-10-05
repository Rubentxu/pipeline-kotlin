package example.uppercase

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.registry.PLUGIN_EVENT_EMISSION_CAPABILITY
import dev.rubentxu.pipeline.v2.events.registry.PluginEventEmission

/**
 * P3-D / S6.4 — the plugin's SECOND Step, and the reason there are two.
 *
 * `uppercase` demands nothing from the host: it is the reference proof that an external Step runs
 * with zero capabilities and zero core changes, and the certification suite asserts exactly that.
 * The first attempt at P3-D added the event emission to `uppercase` itself, and that suite caught
 * it — a standing assertion that the reference plugin is capability-free, broken by a well-meaning
 * change. The assertion was right and the change was wrong: a reference that has quietly acquired
 * a dependency is no longer the reference.
 *
 * So the observation is a separate Step that asks for ONE thing and gets it. This plugin therefore
 * demonstrates both shapes at once, which is more useful than either alone:
 *
 *   - `uppercase`     — asks for nothing, runs anyway
 *   - `uppercaseObserved` — asks for exactly one public seam, gets only that seam
 *
 * It is a `warnError`-shaped idea in miniature: do the work, and also record your own observation of
 * it. That is what a plugin actually wants from an open Event Plane.
 */
object UppercaseObservedStepDefinition : StepDefinition<UppercaseInput, UppercaseOutput> {
    val KEY = PluginStepId("example.uppercase.observed")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "uppercase-observed",
            configRef = "",
            pluginId = "example.uppercase",
            pluginVersion = "0.1.0",
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        // Reuses the codecs the other Step already published rather than declaring its own: the
        // wire shape of "text in, text out" is the same fact, and two codecs for it would be a
        // second way to encode the same value.
        inputCodec = UppercaseCodec,
        outputCodec = UppercaseOutputCodec,
        // Fail-closed by construction: a run that composed no event registry never exposes this
        // seam, so admission refuses the Step before its handler runs. There is no "declared but
        // silently inert" mode — which is the whole point of declaring instead of reaching.
        requiredCapabilities = setOf(PLUGIN_EVENT_EMISSION_CAPABILITY),
    )

    override val handler = StepHandler<UppercaseInput, UppercaseOutput> { input, context ->
        val output = UppercaseOutput(value = input.text.uppercase())

        // The handler sees the seam and nothing else: no EventStore, no JSON log, no SQLite, no
        // sequence assigner, no clock, no runId. `get` returns the typed capability and the store
        // assigns the sequence, so this Step cannot invent its own position in the stream.
        //
        // The outcome is deliberately NOT inspected. An unregistered kind and a codec refusal are
        // both named results the runtime surfaces; turning either into a Step failure would be
        // wrong, because the uppercase work already succeeded and an observation is not the work.
        val emission = context.capabilities.get<PluginEventEmission>(PLUGIN_EVENT_EMISSION_CAPABILITY)
        emission.emit(
            "example.uppercase.applied",
            UppercaseApplied(inputLength = input.text.length, outputLength = output.value.length),
        )

        output
    }
}