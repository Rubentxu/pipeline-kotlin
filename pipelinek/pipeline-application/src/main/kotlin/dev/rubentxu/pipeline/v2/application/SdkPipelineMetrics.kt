package dev.rubentxu.pipeline.v2.application

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter

/**
 * The metrics that the OpenTelemetry SDK records.
 *
 * ## WHY THE ADAPTER IS THIN
 *
 * The vocabulary lives in the product and the redaction rule lives in the
 * published module. This adapter only does the three things the library
 * requires and nothing else: it holds the instruments, it turns the product's
 * dimensions into the library's attributes, and it counts. It never decides
 * what a dimension means, and it never invents a value — a decision made here
 * would be a second vocabulary.
 *
 * ## WHY THE CLOCK IS INJECTED
 *
 * A duration is the one number this adapter computes, and a duration computed
 * from a clock nobody can control is a number nobody can assert on. The clock
 * is a parameter, so a caller that wants to check the arithmetic can hand in
 * one that only moves when it is told to.
 */
internal class SdkPipelineMetrics(
    meter: Meter,
    private val nanos: () -> Long = System::nanoTime,
) : PipelineMetrics {
    override val enabled: Boolean = true

    /**
     * How long each measured boundary took.
     *
     * The unit is declared so a consumer does not have to guess it, and the
     * duration is recorded together with the same dimensions the counter
     * carries: a duration without its class is a number nobody can act on,
     * because "400 ms" says nothing about whether the 400 ms was a success.
     */
    private val boundaryDuration = meter.histogramBuilder(BOUNDARY_DURATION)
        .setUnit("ms")
        .build()

    /** How many times each measured boundary ended, and how it ended. */
    private val boundaryTotal = meter.counterBuilder(BOUNDARY_TOTAL).build()

    /** How many exports were lost, and which step of the way out lost them. */
    private val exportLossTotal = meter.counterBuilder(EXPORT_LOSS_TOTAL).build()

    override fun start(boundary: MetricBoundary): MetricHandle? {
        val startedAt = nanos()
        val elapsed = { (nanos() - startedAt).coerceAtLeast(0L) }
        return MetricHandle(SdkMetricMeasurement(boundary, elapsed, ::record))
    }

    override fun exportLoss(reason: MetricExportLoss) {
        val dimensions = metricDimensions(
            MetricBoundary.TelemetryExport,
            MetricOutcome(MetricStatus.ExportError, MetricFailureClass.Export(reason)),
        )
        exportLossTotal.add(1L, attributesOf(dimensions))
    }

    /**
     * Counts one finished boundary.
     *
     * The duration and the count travel together and carry the same
     * dimensions, so a reader who asks "how slow is the compile" and a reader
     * who asks "how many compiles failed" are reading the same slice of the
     * same series rather than two series that may disagree.
     */
    internal fun record(boundary: MetricBoundary, outcome: MetricOutcome, elapsedNanos: Long) {
        val attributes = attributesOf(metricDimensions(boundary, outcome))
        boundaryDuration.record(elapsedNanos / NANOS_PER_MILLI, attributes)
        boundaryTotal.add(1L, attributes)
    }

    companion object {
        /** The histogram of how long each measured boundary took. */
        const val BOUNDARY_DURATION: String = "pipelinek.boundary_duration_ms"

        /** The counter of how each measured boundary ended. */
        const val BOUNDARY_TOTAL: String = "pipelinek.boundary_total"

        /** The counter of the exports that did not leave the process. */
        const val EXPORT_LOSS_TOTAL: String = "pipelinek.export_loss_total"

        private const val NANOS_PER_MILLI: Double = 1_000_000.0
    }
}

/**
 * One measurement in flight inside the SDK adapter.
 *
 * It holds the clock reading it started from and nothing else: the outcome is
 * known only at the end, and the boundary it describes was decided at the
 * start.
 */
private class SdkMetricMeasurement(
    private val boundary: MetricBoundary,
    private val elapsedNanos: () -> Long,
    private val record: (MetricBoundary, MetricOutcome, Long) -> Unit,
) : MetricMeasurement {
    override fun end(outcome: MetricOutcome) {
        record(boundary, outcome, elapsedNanos())
    }

    override fun discard() {
        // Deliberately empty: the work this measurement described did NOT
        // happen, so counting it would publish a duration and a count for an
        // effect that was skipped.
    }
}

/**
 * The product's dimensions as the library's attributes.
 *
 * The keys were already reduced by the published redaction rule before they
 * arrived here, so this function only changes the SHAPE and never the
 * content: it cannot add a key and it cannot add a value.
 */
internal fun attributesOf(dimensions: Map<String, String>): Attributes {
    val builder = Attributes.builder()
    dimensions.forEach { (key, value) -> builder.put(AttributeKey.stringKey(key), value) }
    return builder.build()
}
