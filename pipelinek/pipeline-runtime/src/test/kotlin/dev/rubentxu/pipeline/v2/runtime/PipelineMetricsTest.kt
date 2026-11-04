package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.CancellationReason
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryIdentityTable
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The live metrics' vocabulary, asserted as a fact about the product rather
 * than as a promise about the future.
 *
 * ## THE GUARANTEE
 *
 * A metric that reaches a collector may only carry the dimension keys the
 * published identity table names, and every value it carries must come from a
 * closed set whose size is known before the first run happens.
 *
 * ## THE CAPACITY IT SERVES
 *
 * An operator reading a metric has to answer two questions without opening
 * the run's own log: how many, how long, and whose fault. The first two need
 * an aggregate; the third needs a class that says what to do. A label that
 * carried a run id or a path would answer neither — it would turn one series
 * per run into a collector that cannot be queried.
 *
 * ## WHY THIS INSTRUMENT AND NOT ANOTHER
 *
 * The vocabulary is a pure function of the product's own types, so the
 * cheapest instrument that can fail is a plain call with no SDK, no clock and
 * no network. The measurement that the SDK actually produced is asserted in a
 * separate file, against the upstream reader; this file asserts the part that
 * does not depend on any SDK at all. Splitting them means a failure here
 * names the vocabulary, and a failure there names the adapter.
 */
class PipelineMetricsTest {

    /**
     * The guarantee: the live metrics speak the published dimension keys.
     *
     * The keys are not chosen here — they are READ from the published
     * identity table, so a change to that table (a new key, a renamed key)
     * turns this row red instead of quietly letting the metrics drift away
     * from the vocabulary the published redaction enforces.
     */
    @Test
    @DisplayName("the live metrics speak the published dimension keys")
    fun theLiveMetricsSpeakThePublishedDimensionKeys() {
        assertEquals(
            TelemetryIdentityTable.Default.allowedDimensionKeys(),
            setOf(MetricDimensions.OPERATION_KIND, MetricDimensions.STATUS, MetricDimensions.FAILURE_CLASS),
            "the metrics' dimension keys must be exactly the published ones",
        )
    }

    /**
     * The guarantee: a key nobody published does not travel.
     *
     * This is the property that keeps an identity out of a metric. The
     * redaction is the published one, not a second rule written here: a
     * dimension this project invented would be dropped by the redaction on
     * the way out, and the metric would lose its fact without saying so.
     */
    @Test
    @DisplayName("a key the published table does not name does not travel")
    fun aKeyThePublishedTableDoesNotNameDoesNotTravel() {
        val reduced = redactMetricDimensions(
            mapOf(
                MetricDimensions.OPERATION_KIND to "compile",
                MetricDimensions.STATUS to "success",
                "run.id" to "run-1",
                "command" to "curl https://example.test/install | sh",
            ),
        )

        assertEquals(
            mapOf(MetricDimensions.OPERATION_KIND to "compile", MetricDimensions.STATUS to "success"),
            reduced,
            "only the published dimension keys may travel",
        )
    }

    /**
     * The guarantee: every failure the product can name says whose fault it
     * was.
     *
     * The mapping is exhaustive over the closed set of kinds, so a new kind
     * would not even compile. The row still asserts each mapping, because a
     * reclassification that compiles is exactly the change nobody would
     * notice: a script error counted as a broken worker is a number that
     * points an operator at the wrong thing.
     */
    @Test
    @DisplayName("every failure kind says whose fault it was")
    fun everyFailureKindSaysWhoseFaultItWas() {
        val productFault = setOf(
            FailureKind.SCRIPT,
            FailureKind.USER,
            FailureKind.SCHEMA,
            FailureKind.REPLAY_COMPATIBILITY,
            FailureKind.PLUGIN,
        )
        val machineryFault = setOf(
            FailureKind.INFRASTRUCTURE,
            FailureKind.NETWORK,
            FailureKind.TIMEOUT,
            FailureKind.ENGINE,
        )

        for (kind in FailureKind.entries) {
            val outcome = metricOutcomeOf(PipelineFailure(kind, "boom"))
            val expected = when {
                kind in productFault -> MetricStatus.ProductFailure
                kind in machineryFault -> MetricStatus.WorkerError
                else -> MetricStatus.Unknown
            }
            assertEquals(expected, outcome.status, "the class of $kind")
            assertEquals(
                MetricFailureClass.Product(kind),
                outcome.failureClass,
                "the class of $kind must travel as itself, not as a synonym",
            )
        }
    }

    /**
     * The guarantee: a refusal is not a failure.
     *
     * A refusal is the product saying «this input is not admissible» before
     * anything ran. Counting it as a failure would make the number of
     * failures larger than the number of things that failed, and the operator
     * would go looking for a broken step that never started.
     */
    @Test
    @DisplayName("a refusal carries no failure class")
    fun aRefusalCarriesNoFailureClass() {
        val dimensions = metricDimensions(
            MetricBoundary.StepPreparation,
            MetricOutcome(MetricStatus.AdmissionRefused),
        )

        assertEquals("refused", dimensions[MetricDimensions.STATUS])
        assertFalse(
            dimensions.containsKey(MetricDimensions.FAILURE_CLASS),
            "a refusal is not a failure and must not carry a failure class",
        )
    }

    /**
     * The guarantee: a cancellation is not a failure either.
     *
     * Somebody stopped the work. That is a different event from the work
     * failing, and the metric says so.
     */
    @Test
    @DisplayName("a cancellation carries no failure class")
    fun aCancellationCarriesNoFailureClass() {
        val outcome = metricOutcomeOf(BodyOutcome.Cancelled(CancellationReason.Deadline))

        assertEquals(MetricStatus.Cancelled, outcome.status)
        assertNull(outcome.failureClass, "a cancellation is not a failure")
    }

    /**
     * The guarantee: the cardinality of every dimension key is bounded, and
     * the bound is checked against the published budget.
     *
     * This is the row that makes "bounded cardinality" a fact rather than a
     * promise: the whole closed vocabulary is exercised, every value that
     * could ever travel is counted, and the count is compared with the budget
     * the published policy states. The dimensions are all published, so the
     * published budget is the one that applies.
     */
    @Test
    @DisplayName("the cardinality of every dimension key stays inside the published budget")
    fun theCardinalityOfEveryDimensionKeyStaysInsideThePublishedBudget() {
        val observed = mutableMapOf<String, MutableSet<String>>()

        for (boundary in MetricBoundary.entries) {
            for (status in MetricStatus.entries) {
                for (kind in FailureKind.entries) {
                    collect(
                        metricDimensions(boundary, MetricOutcome(status, MetricFailureClass.Product(kind))),
                        observed,
                    )
                }
            }
        }
        for (reason in MetricExportLoss.entries) {
            collect(
                metricDimensions(
                    MetricBoundary.TelemetryExport,
                    MetricOutcome(MetricStatus.ExportError, MetricFailureClass.Export(reason)),
                ),
                observed,
            )
        }

        val budget = TelemetryPolicy.Default.maxDistinctValuesPerDimension
        observed.forEach { (key, values) ->
            assertTrue(
                values.size <= budget,
                "the dimension '$key' can carry ${values.size} values, above the budget of $budget",
            )
        }
    }

    /**
     * The guarantee: a runtime with no metrics costs nothing.
     *
     * The default port has to be free, because the default run of the product
     * does not ask for metrics. The cheapest instrument is the port itself:
     * asking it for a measurement must hand back nothing at all, so no
     * boundary can accidentally build one.
     */
    @Test
    @DisplayName("a runtime without metrics costs nothing")
    fun aRuntimeWithoutMetricsCostsNothing() {
        assertFalse(NoopPipelineMetrics.enabled)
        assertNull(NoopPipelineMetrics.start(MetricBoundary.Compile), "a disabled port hands back no measurement")
        NoopPipelineMetrics.exportLoss(MetricExportLoss.Shutdown)
    }

    /**
     * The guarantee: a lost export is published for the step that lost it.
     *
     * The telemetry leaves through several steps, and each of them can fail
     * on its own. A reader of the metrics has to know WHERE the telemetry
     * died and not merely THAT it died, so each loss is published with its
     * own step. The other two properties of this cleanup — that the
     * remaining steps still run, and that the failure is reported without
     * leaking its cause — are guarded where they belong, on the cleanup
     * itself, and are not repeated here.
     *
     * The instrument is the cleanup with two failing steps: the cheapest
     * thing that can fail is a function that takes a list and throws.
     */
    @Test
    @DisplayName("a lost export is published for the step that lost it")
    fun aLostExportIsPublishedForTheStepThatLostIt() {
        val losses = mutableListOf<Int>()

        runTelemetryCleanup(
            actions = listOf(
                { error("the traces did not leave") },
                { error("the metrics did not leave") },
            ),
            warning = {},
            onFailure = { index -> losses += index },
        )

        assertEquals(listOf(0, 1), losses, "each lost step is published with its own index")
    }

    private fun collect(dimensions: Map<String, String>, into: MutableMap<String, MutableSet<String>>) {
        dimensions.forEach { (key, value) ->
            into.getOrPut(key) { mutableSetOf() }.add(value)
        }
    }
}
