package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.runtime.telemetry.NoopStepSpan
import dev.rubentxu.pipeline.v2.runtime.telemetry.ObservationName
import dev.rubentxu.pipeline.v2.runtime.telemetry.ObservationStatus
import dev.rubentxu.pipeline.v2.runtime.telemetry.StepObservability
import dev.rubentxu.pipeline.v2.runtime.telemetry.StepSpan
import io.opentelemetry.api.metrics.Meter

/**
 * The observability the tracing SDK records.
 *
 * ## WHY THE ADAPTER IS THIN
 *
 * The published seam says what a plugin may say; this file says how the
 * library is told. It never decides what a name means, and it never invents a
 * dimension: the dimensions come from the product's own vocabulary through
 * [metricDimensions], so a plugin's observation lands in the same slice of the
 * same series as everything else the engine measures.
 *
 * ## WHY THE PLUGIN'S NAME IS AN INSTRUMENT AND NOT A DIMENSION
 *
 * A dimension value is a label on every measurement of a kind, and the
 * published policy bounds how many distinct values one label may take. The
 * plugin's own name would be a new value on every plugin, so it belongs in
 * the instrument's NAME — where it creates one series instead of one label per
 * measurement — and not in the dimensions, which stay product-owned.
 *
 * ## WHY THE PARENT IS FIXED
 *
 * The seam is built per dispatch, already carrying the parent the step's own
 * execution hangs under. A plugin therefore cannot open a span that hangs from
 * nothing, and cannot open one that hangs from a run it invented: the parent
 * is the one the engine chose before the handler ran.
 *
 * ## WHY NOTHING HERE CAN BLOCK THE PLUGIN'S WORK
 *
 * Every call into the library is guarded. An observation that cannot be
 * recorded is an observation that is lost, not an observation that fails the
 * step: the pipeline's own result is a different thing from what the telemetry
 * managed to capture, and the second must never decide the first.
 */
internal class SdkStepObservability(
    private val telemetry: PipelineTelemetry,
    private val meter: Meter,
    private val parent: LiveSpanParent,
    private val nanos: () -> Long = System::nanoTime,
) : StepObservability {

    override val enabled: Boolean get() = telemetry.enabled

    override fun openSpan(name: ObservationName): StepSpan {
        val handle = LiveSpans.open(
            telemetry = telemetry,
            name = SPAN_PREFIX + name.value,
            parent = parent,
        ) ?: return NoopStepSpan
        return SdkStepSpan(handle, name, nanos())
    }

    override fun count(name: ObservationName, status: ObservationStatus) {
        runCatching {
            meter.counterBuilder(COUNTER_PREFIX + name.value + COUNTER_SUFFIX).build()
                .add(1L, attributesOf(dimensionsOf(status)))
        }
    }

    /**
     * The dimensions of one observation, taken from the product's vocabulary.
     *
     * The three keys are the published ones, so the published redaction rule
     * accepts them and nothing else could travel beside them.
     */
    private fun dimensionsOf(status: ObservationStatus): Map<String, String> = metricDimensions(
        MetricBoundary.PluginExecute,
        outcomeOf(status),
    )

    /**
     * The outcome of one observation, in the product's own classes.
     *
     * A failure inside the plugin is the plugin's fault; a dependency the
     * plugin could not reach is the machinery's. The two are different events
     * and a reader of the metric has to be able to tell them apart.
     */
    private fun outcomeOf(status: ObservationStatus): MetricOutcome = when (status) {
        ObservationStatus.Succeeded -> MetricOutcome(MetricStatus.Success)
        ObservationStatus.Failed -> MetricOutcome(
            MetricStatus.ProductFailure,
            MetricFailureClass.Product(FailureKind.PLUGIN),
        )
        ObservationStatus.Unavailable -> MetricOutcome(
            MetricStatus.WorkerError,
            MetricFailureClass.Product(FailureKind.INFRASTRUCTURE),
        )
    }

    /**
     * One observation in flight.
     *
     * The first ending wins and the second is a no-op, so a handler that ends
     * the observation on two paths cannot produce two outcomes.
     */
    private inner class SdkStepSpan(
        private val handle: LiveSpanHandle,
        private val name: ObservationName,
        private val startedAt: Long,
    ) : StepSpan {
        private var closed: Boolean = false

        override fun end(status: ObservationStatus) {
            if (closed) return
            closed = true
            LiveSpans.end(handle, liveStatusOf(status))
            val elapsed = (nanos() - startedAt).coerceAtLeast(0L)
            runCatching {
                meter.histogramBuilder(HISTOGRAM_PREFIX + name.value + DURATION_SUFFIX)
                    .setUnit(MILLISECOND_UNIT)
                    .build()
                    .record(elapsed / NANOS_PER_MILLI, attributesOf(dimensionsOf(status)))
            }
        }

        override fun discard() {
            if (closed) return
            closed = true
            LiveSpans.discard(handle)
        }
    }

    private companion object {
        /** The prefix of every span a plugin opens. */
        const val SPAN_PREFIX: String = "pipelinek.plugin."

        /** The prefix of every counter a plugin records. */
        const val COUNTER_PREFIX: String = "pipelinek.plugin."

        /** The prefix of every histogram a plugin records. */
        const val HISTOGRAM_PREFIX: String = "pipelinek.plugin."

        /**
         * The suffix of the counter.
         *
         * The counter and the histogram of ONE observation would otherwise
         * carry the same name, and a name that names two instruments is a name
         * a collector cannot tell apart: one of the two streams would be
         * dropped, or read as the other.
         */
        const val COUNTER_SUFFIX: String = ".total"

        /** The suffix of the duration. */
        const val DURATION_SUFFIX: String = ".duration_ms"

        /** The unit of a recorded duration. */
        const val MILLISECOND_UNIT: String = "ms"

        const val NANOS_PER_MILLI: Double = 1_000_000.0
    }
}

/**
 * The span status that matches an observation status.
 *
 * Both vocabularies say the same three things, so the mapping is total: an
 * observation that did not succeed did not succeed, and it is either the
 * plugin's fault or the machinery's.
 */
internal fun liveStatusOf(status: ObservationStatus): LiveSpanStatus = when (status) {
    ObservationStatus.Succeeded -> LiveSpanStatus.Completed
    ObservationStatus.Failed -> LiveSpanStatus.Failed
    ObservationStatus.Unavailable -> LiveSpanStatus.Failed
}
