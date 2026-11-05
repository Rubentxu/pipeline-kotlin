package dev.rubentxu.pipeline.v2.runtime.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The cardinality cap decides how many **distinct** values one dimension key
 * may carry across a whole export. The decisions pinned here are the ones an
 * export that holds every signal and an export that streams them have to
 * agree on: the values that fill the budget exactly, the value that does not
 * fit, the value that appears twice, and the bound that says how much the
 * cap is allowed to remember.
 */
class TelemetryDimensionCapTest {

    private val policy = TelemetryPolicy.Default

    private fun counter(value: String) =
        TelemetrySignal.Counter("pipelinek.run_outcome_total", 1, mapOf("status" to value))

    private fun statusOf(signal: TelemetrySignal): String? =
        (signal as TelemetrySignal.Counter).dimensions["status"]

    @Test
    fun `a key whose values exactly fill the budget keeps every one of them`() {
        val tight = policy.copy(maxDistinctValuesPerDimension = 3)
        val trace = TelemetryTrace(
            runId = "run-1",
            traceId = TelemetryTrace.traceIdOf("run-1"),
            signals = (1..3).map { index -> counter("status-$index") },
        )

        val redacted = redactAndLimit(trace, tight)

        // Three values and a budget of three: the key does not exceed the
        // budget, so nothing collapses. The marker announces a surplus, and
        // there is no surplus here.
        assertEquals(
            listOf("status-1", "status-2", "status-3"),
            redacted.signals.map { statusOf(it) },
        )
    }

    @Test
    fun `the value that fills the budget collapses when one more value comes`() {
        val tight = policy.copy(maxDistinctValuesPerDimension = 3)
        val trace = TelemetryTrace(
            runId = "run-1",
            traceId = TelemetryTrace.traceIdOf("run-1"),
            signals = (1..4).map { index -> counter("status-$index") },
        )

        val redacted = redactAndLimit(trace, tight)

        // The budget counts the marker among the values it admits: three
        // distinct values travel, one of which is the marker. The value that
        // filled the budget is the first one that does not fit, and the
        // value that came after it never had a place.
        assertEquals(
            listOf("status-1", "status-2", DIMENSION_OVERFLOW, DIMENSION_OVERFLOW),
            redacted.signals.map { statusOf(it) },
        )
    }

    @Test
    fun `a value that appears more than once is one value of the budget`() {
        val tight = policy.copy(maxDistinctValuesPerDimension = 2)
        val trace = TelemetryTrace(
            runId = "run-1",
            traceId = TelemetryTrace.traceIdOf("run-1"),
            signals = listOf(counter("status-1"), counter("status-1"), counter("status-2")),
        )

        val redacted = redactAndLimit(trace, tight)

        // The budget counts DISTINCT values: a value that repeats is one
        // value, and the key that carries two of them has not exceeded the
        // budget. A budget that counted appearances would collapse a value
        // that never exceeded anything.
        assertEquals(
            listOf("status-1", "status-1", "status-2"),
            redacted.signals.map { statusOf(it) },
        )
    }

    @Test
    fun `the cap holds a bounded number of values whatever the export carries`() {
        val tight = policy.copy(maxDistinctValuesPerDimension = 4)
        val cap = TelemetryDimensionCap.plan(tight)

        (1..1_000).forEach { index -> cap.observe(counter("status-$index")) }

        // The bound is the reason the cap can be learned from an export of
        // any size: what it holds is a function of the policy, and the
        // thousandth value did not make it hold one more.
        assertEquals(
            4,
            cap.retainedValues,
            "el tope guarda los primeros valores de cada clave y nada mas: el valor que no cabe " +
                "no se guarda, se marca",
        )
        assertEquals("status-1", statusOf(cap.apply(counter("status-1"))))
        assertEquals("status-3", statusOf(cap.apply(counter("status-3"))))
        assertEquals(DIMENSION_OVERFLOW, statusOf(cap.apply(counter("status-4"))))
        assertEquals(DIMENSION_OVERFLOW, statusOf(cap.apply(counter("status-1000"))))
    }

    @Test
    fun `a key the policy does not allow is not learned`() {
        val cap = TelemetryDimensionCap.plan(policy)

        cap.observe(TelemetrySignal.Counter("pipelinek.run_outcome_total", 1, mapOf("script.path" to "/secret")))

        // The key could not travel, so it cannot spend the budget of the
        // export either: what the cap holds is a function of the policy.
        assertEquals(0, cap.retainedValues)
    }

    @Test
    fun `with no slot for any value the dimensions do not travel`() {
        val cap = TelemetryDimensionCap.plan(policy.copy(maxDistinctValuesPerDimension = 0))

        cap.observe(counter("status-1"))

        // No slot for any value, and none for the overflow marker either:
        // the keys do not travel at all. The signals still travel — dropping
        // them would hide that they existed.
        assertTrue((cap.apply(counter("status-1")) as TelemetrySignal.Counter).dimensions.isEmpty())
    }
}
