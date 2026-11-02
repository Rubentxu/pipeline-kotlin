package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryPolicy
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.HistogramPointData
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What the metrics adapter actually publishes, read back through the upstream
 * in-memory reader.
 *
 * ## THE GUARANTEE
 *
 * A measured boundary that finished is published exactly once, carrying the
 * published dimensions and nothing else; a boundary that was skipped publishes
 * nothing at all; and an export that could not leave is counted on its own
 * instrument rather than hidden inside a boundary that finished.
 *
 * ## THE CAPACITY IT SERVES
 *
 * The reader of a metric has to trust that a number means what it says. A
 * count that doubled because a measurement was closed twice, or a duration
 * published for work that never happened, would not be a slow metric — it
 * would be a wrong one, and the collector would be the place where the wrong
 * answer became permanent.
 *
 * ## WHY THIS INSTRUMENT AND NOT ANOTHER
 *
 * The reader is the upstream one, the same reader the library's own tests
 * use. A reader written inside this project would be a second understanding
 * of how the library aggregates, and a metric that this project's reader
 * accepts while a real collector rejects is a metric that was never checked.
 * The clock is injected, so the duration is asserted on an exact number
 * instead of on a range.
 */
class SdkPipelineMetricsTest {

    private lateinit var reader: InMemoryMetricReader
    private lateinit var provider: SdkMeterProvider
    private lateinit var clock: FakeClock
    private lateinit var metrics: SdkPipelineMetrics

    @BeforeEach
    fun setUp() {
        reader = InMemoryMetricReader.create()
        provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
        clock = FakeClock()
        metrics = SdkPipelineMetrics(provider.meterBuilder("pipelinek-test").build(), clock)
    }

    @AfterEach
    fun tearDown() {
        provider.close()
    }

    /**
     * The guarantee: a boundary that finished is published once, with the
     * published dimensions and nothing else.
     *
     * The two instruments travel together on purpose: a reader who asks "how
     * slow is the compile" and a reader who asks "how many compiles failed"
     * must be reading the same slice of the same series.
     */
    @Test
    @DisplayName("a finished boundary is published once")
    fun aFinishedBoundaryIsPublishedOnce() {
        metrics.start(MetricBoundary.Compile)?.end(MetricOutcome(MetricStatus.Success))

        val duration = metric(SdkPipelineMetrics.BOUNDARY_DURATION)
        val total = metric(SdkPipelineMetrics.BOUNDARY_TOTAL)

        assertEquals(1, duration.data.points.size, "the duration is published once")
        assertEquals(1, total.data.points.size, "the count is published once")
        assertEquals(1L, (total.data.points.single() as LongPointData).value, "the count says one")

        val expected = mapOf(
            MetricDimensions.OPERATION_KIND to "compile",
            MetricDimensions.STATUS to "success",
        )
        assertEquals(expected, (total.data.points.single() as LongPointData).attributes.asStringMap())
        assertEquals(expected, (duration.data.points.single() as HistogramPointData).attributes.asStringMap())
    }

    /**
     * The guarantee: a boundary that was skipped publishes nothing.
     *
     * The skipped work did not happen, so a duration and a count for it would
     * be a claim about an effect that never occurred.
     */
    @Test
    @DisplayName("a discarded boundary publishes nothing")
    fun aDiscardedBoundaryPublishesNothing() {
        metrics.start(MetricBoundary.StepDispatch)?.discard()

        assertTrue(reader.collectAllMetrics().isEmpty(), "a discarded boundary must publish nothing")
    }

    /**
     * The guarantee: a measurement closed twice publishes once.
     *
     * This is the shape a reader of the code would not think about, because
     * the caller is expected to end a measurement once — but the engine has
     * several endings (a result, an exception, a skip) and only one of them
     * must count. A second ending would publish a second count, and a count
     * that doubled is a number no reader can act on.
     */
    @Test
    @DisplayName("a boundary closed twice is published once")
    fun aBoundaryClosedTwiceIsPublishedOnce() {
        val handle = metrics.start(MetricBoundary.RetryAttempt)
        handle?.end(MetricOutcome(MetricStatus.Success))
        handle?.end(MetricOutcome(MetricStatus.Unknown))

        val total = metric(SdkPipelineMetrics.BOUNDARY_TOTAL)
        assertEquals(1, total.data.points.size, "a closed measurement publishes once")
        assertEquals(1L, (total.data.points.single() as LongPointData).value)
    }

    /**
     * The guarantee: a boundary that was skipped stays skipped, even if
     * something closes it afterwards.
     *
     * The other order of the same two endings. It matters because the two
     * endings are not symmetric: the skip says the work did NOT happen, so
     * a later close must not resurrect it and publish a duration and a count
     * for an effect that never occurred.
     */
    @Test
    @DisplayName("a boundary discarded and then closed publishes nothing")
    fun aBoundaryDiscardedAndThenClosedPublishesNothing() {
        val handle = metrics.start(MetricBoundary.StepDispatch)
        handle?.discard()
        handle?.end(MetricOutcome(MetricStatus.Success))

        assertTrue(
            reader.collectAllMetrics().isEmpty(),
            "a skipped boundary must stay skipped",
        )
    }

    /**
     * The guarantee: the duration is the elapsed time, in milliseconds.
     *
     * The clock is injected, so this is an exact number rather than a range.
     * The unit is part of the assertion: a duration published without its
     * unit is a number the reader has to guess about.
     */
    @Test
    @DisplayName("the duration is the elapsed time in milliseconds")
    fun theDurationIsTheElapsedTimeInMilliseconds() {
        clock.now = 0L
        val handle = metrics.start(MetricBoundary.Recovery)
        clock.now = 12_500_000L
        handle?.end(MetricOutcome(MetricStatus.Success))

        val duration = metric(SdkPipelineMetrics.BOUNDARY_DURATION)
        assertEquals("ms", duration.unit, "the unit has to be published, not guessed")
        val point = duration.data.points.single() as HistogramPointData
        assertEquals(12.5, point.sum, 0.000_001, "twelve and a half milliseconds")
    }

    /**
     * The guarantee: a refusal is published as a refusal, with no failure
     * class attached.
     */
    @Test
    @DisplayName("a refusal is published as a refusal")
    fun aRefusalIsPublishedAsARefusal() {
        metrics.start(MetricBoundary.StepPreparation)?.end(MetricOutcome(MetricStatus.AdmissionRefused))

        val attributes = (metric(SdkPipelineMetrics.BOUNDARY_TOTAL).data.points.single() as LongPointData)
            .attributes.asStringMap()
        assertEquals("refused", attributes[MetricDimensions.STATUS])
        assertEquals("step.preparation", attributes[MetricDimensions.OPERATION_KIND])
        assertFalse(
            attributes.containsKey(MetricDimensions.FAILURE_CLASS),
            "a refusal must not carry a failure class",
        )
    }

    /**
     * The guarantee: an export that could not leave is counted on its own
     * instrument, and it does not pretend to be a boundary.
     *
     * A loss has no duration — the telemetry died on the way out, not inside
     * a measured boundary — so it cannot ride on the histogram of a boundary
     * that finished. It is counted where a reader will look for it.
     */
    @Test
    @DisplayName("a lost export is counted on its own instrument")
    fun aLostExportIsCountedOnItsOwnInstrument() {
        metrics.exportLoss(MetricExportLoss.TracerFlush)

        val loss = metric(SdkPipelineMetrics.EXPORT_LOSS_TOTAL)
        assertEquals(1, loss.data.points.size)
        val point = loss.data.points.single() as LongPointData
        assertEquals(1L, point.value)
        assertEquals(
            mapOf(
                MetricDimensions.OPERATION_KIND to "telemetry.export",
                MetricDimensions.STATUS to "export_error",
                MetricDimensions.FAILURE_CLASS to "export.tracer_flush",
            ),
            point.attributes.asStringMap(),
        )
        assertTrue(
            reader.collectAllMetrics().none { it.name == SdkPipelineMetrics.BOUNDARY_TOTAL },
            "a loss is not a boundary that finished",
        )
    }

    /**
     * The guarantee: the cardinality of every published dimension key stays
     * inside the published budget, measured on what the collector receives.
     *
     * The whole closed vocabulary is exercised through the adapter, so the
     * count is the number of distinct values a key can ever carry — not the
     * number of values one run happened to produce.
     */
    @Test
    @DisplayName("the published cardinality stays inside the published budget")
    fun thePublishedCardinalityStaysInsideThePublishedBudget() {
        for (boundary in MetricBoundary.entries) {
            for (status in MetricStatus.entries) {
                for (kind in FailureKind.entries) {
                    metrics.start(boundary)?.end(MetricOutcome(status, MetricFailureClass.Product(kind)))
                }
            }
        }
        for (reason in MetricExportLoss.entries) {
            metrics.exportLoss(reason)
        }

        val observed = mutableMapOf<String, MutableSet<String>>()
        reader.collectAllMetrics().forEach { metric ->
            metric.data.points.forEach { point ->
                point.attributes.asStringMap().forEach { (key, value) ->
                    observed.getOrPut(key) { mutableSetOf() }.add(value)
                }
            }
        }

        val budget = TelemetryPolicy.Default.maxDistinctValuesPerDimension
        observed.forEach { (key, values) ->
            assertTrue(
                values.size <= budget,
                "the dimension '$key' can carry ${values.size} values, above the budget of $budget",
            )
        }
    }

    private fun metric(name: String): MetricData =
        reader.collectAllMetrics().firstOrNull { it.name == name }
            ?: error("the metric '$name' was not published")

    /**
     * The library's attributes as the plain string map the product speaks.
     *
     * The library keys its attributes by object and the product keys them by
     * name, so the comparison the rows above make needs the name. Reading
     * the name off the key is what a collector does when it labels a series,
     * so the assertion is made on the same thing the collector sees.
     */
    private fun Attributes.asStringMap(): Map<String, String> =
        asMap().entries.associate { (key, value) -> key.key to value.toString() }

    /**
     * A clock that only moves when it is told to.
     *
     * The adapter reads it twice — once when the measurement opens and once
     * when it ends — so a duration is a difference between two readings and
     * can be asserted exactly.
     */
    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }
}
