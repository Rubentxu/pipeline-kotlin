package example.uppercase

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.runtime.telemetry.ObservationName
import dev.rubentxu.pipeline.v2.runtime.telemetry.ObservationStatus
import dev.rubentxu.pipeline.v2.runtime.telemetry.STEP_OBSERVABILITY_CAPABILITY
import dev.rubentxu.pipeline.v2.runtime.telemetry.StepObservability
import dev.rubentxu.pipeline.v2.runtime.telemetry.inSpan

/**
 * The plugin's THIRD Step, and the one that asks the RUNTIME to watch it.
 *
 * ## WHY A THIRD SHAPE
 * The plugin already showed two: `uppercase` asks for nothing, and `uppercaseObserved` asks
 * for the seam that lets it PUBLISH an event about its own work. Those are different owners of
 * an observation — the plugin writes the fact down itself, through the Event Plane, and the fact
 * belongs to the run's event stream.
 *
 * This third Step asks the opposite: it asks the RUNTIME to observe the work, through a seam
 * that says nothing about how the observation is stored or exported. The fact is a projection:
 * it belongs to whoever reads traces and metrics, and it is not part of what the run decided.
 * A plugin that wants both can ask for both — one at a time, one Step at a time.
 *
 * ## WHAT THE PLUGIN MAY SAY, AND WHAT IT MAY NOT
 * The seam accepts a NAME and a STATUS and nothing else. There is no attribute map, no free
 * string, no payload: a command, a path or a fragment of the output cannot be spelled into
 * this API even by mistake. The name below is a token of dotted lowercase words, and it says
 * what the work IS — never what the work CONTAINED.
 *
 * ## WHY THE WORK IS INSIDE THE OBSERVATION
 * The observation is opened around the work, so the thing that is measured is the thing the
 * Step did, and the scope ends when the work does. A plugin that opened the observation and
 * then forgot to close it would leave a measurement that never ends, which is a lie about
 * duration rather than a missing measurement.
 */
object UppercaseMeasuredStepDefinition : StepDefinition<UppercaseInput, UppercaseOutput> {

    val KEY = PluginStepId("example.uppercase.measured")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "uppercase-measured",
            configRef = "",
            pluginId = "example.uppercase",
            pluginVersion = "0.1.0",
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = UppercaseCodec,
        outputCodec = UppercaseOutputCodec,
        // One declared seam, and it is the runtime's own observation seam. Fail-closed by
        // construction: a runtime that composed no seam never exposes this capability, so
        // admission refuses the Step before its handler runs. There is no "declared but
        // silently inert" mode — which is the whole point of declaring instead of reaching.
        requiredCapabilities = setOf(STEP_OBSERVABILITY_CAPABILITY),
    )

    override val handler = StepHandler<UppercaseInput, UppercaseOutput> { input, context ->
        // The handler sees the seam and nothing else: no tracer, no exporter, no collector's
        // configuration. A plugin compiled against the published artifacts does not carry a
        // tracing library, and it never learns how the observation will be stored.
        val observability = context.capabilities.get(
            STEP_OBSERVABILITY_CAPABILITY,
            StepObservability::class,
        )

        val output = observability.inSpan(OBSERVATION) {
            UppercaseOutput(value = input.text.uppercase())
        }

        // The count is a second reading of the same fact: how often this work happened and how
        // it ended. It is recorded with the product's own vocabulary, so it lands in the same
        // slice of the same series as everything else the engine measures.
        observability.count(OBSERVATION, ObservationStatus.Succeeded)

        output
    }

    /**
     * The name of this plugin's own observation.
     *
     * A token, not a sentence: the name travels into a span name and into an instrument name,
     * and both are read by people who did not write the plugin. It says WHAT the work is and
     * nothing else — the input it was given is not in here, and cannot be.
     */
    private val OBSERVATION: ObservationName = ObservationName.of("uppercase.transform")
}
