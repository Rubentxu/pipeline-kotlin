package dev.rubentxu.pipeline.v2.runtime.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The policy is the security boundary of telemetry export, and its failure
 * modes are all silent: an attribute that should not travel but does, a
 * value trimmed into a shape that still leaks, a dimension with unbounded
 * values hiding a per-run leak behind a generic name. Each row pins one
 * refusal.
 */
class TelemetryPolicyTest {

    private val policy = TelemetryPolicy.Default

    private fun span(vararg attributes: TelemetryAttribute) =
        TelemetrySignal.SpanStarted("pipelinek.step", "s1", null, 0L, attributes.toList())

    @Test
    fun `an attribute key outside the allowlist does not travel`() {
        val redacted = redactAndLimit(
            span(TelemetryAttribute.Text("script.path", "/workspace/secret/script.kts"), TelemetryAttribute.Text("run.id", "run-1")),
            policy,
        ) as TelemetrySignal.SpanStarted

        assertTrue(redacted.attributes.none { it.key == "script.path" })
        assertTrue(redacted.attributes.any { it.key == "run.id" })
    }

    @Test
    fun `an overlong value is shortened to the budget, never shipped whole`() {
        val long = "x".repeat(200)
        val redacted = redactAndLimit(span(TelemetryAttribute.Text("run.id", long)), policy) as TelemetrySignal.SpanStarted

        val value = (redacted.attributes.single() as TelemetryAttribute.Text).value
        assertEquals(policy.maxTextLength, value.length)
    }

    @Test
    fun `a value whose trim leaves nothing readable is dropped`() {
        val blanks = " \t".repeat(100)
        val redacted = redactAndLimit(span(TelemetryAttribute.Text("run.id", blanks)), policy) as TelemetrySignal.SpanStarted

        assertTrue(redacted.attributes.isEmpty())
    }

    @Test
    fun `attributes beyond the budget are dropped, not the signal`() {
        val many = (1..20).map { TelemetryAttribute.Count("step.index", it.toLong()) }
        val redacted = redactAndLimit(span(*many.toTypedArray()), policy) as TelemetrySignal.SpanStarted

        assertEquals(policy.maxAttributesPerSignal, redacted.attributes.size)
    }

    @Test
    fun `a dimension key outside the allowlist is dropped from counters`() {
        val signal = TelemetrySignal.Counter("pipelinek.run_outcome_total", 1, mapOf("status" to "failure", "run.id" to "run-42"))
        val redacted = redactAndLimit(signal, policy) as TelemetrySignal.Counter

        assertFalse(redacted.dimensions.containsKey("run.id"))
        assertEquals("failure", redacted.dimensions["status"])
    }

    @Test
    fun `the shipped policy admits only low-cardinality dimension keys`() {
        assertEquals(setOf("operation_kind", "status", "failure_class"), policy.allowedDimensionKeys)
        assertTrue(!policy.allowedAttributeKeys.contains("script.path"))
    }

    @Test
    fun `a dimension value longer than the budget is shortened`() {
        val long = "x".repeat(200)
        val signal = TelemetrySignal.Counter("pipelinek.run_outcome_total", 1, mapOf("status" to long))

        val redacted = redactAndLimit(signal, policy) as TelemetrySignal.Counter

        assertEquals(policy.maxTextLength, redacted.dimensions["status"]!!.length)
    }

    @Test
    fun `a dimension whose values exceed the budget collapses into the overflow marker`() {
        val tight = policy.copy(maxDistinctValuesPerDimension = 2)
        val trace = TelemetryTrace(
            runId = "run-1",
            traceId = TelemetryTrace.traceIdOf("run-1"),
            signals = (1..5).map { index ->
                TelemetrySignal.Counter("pipelinek.run_outcome_total", 1, mapOf("status" to "status-$index"))
            },
        )

        val redacted = redactAndLimit(trace, tight)

        val values = redacted.signals.map { (it as TelemetrySignal.Counter).dimensions["status"] }
        // The budget counts the marker among the values it admits: two
        // distinct values travel, one of which is the marker. The first
        // value is the one that was seen first, and the four that came
        // after it say so rather than pretending to be distinct.
        assertEquals(setOf("status-1", DIMENSION_OVERFLOW), values.toSet())
        assertEquals(4, values.count { it == DIMENSION_OVERFLOW })
        // The signals themselves still travel: a collapsed value is not a
        // missing signal.
        assertEquals(5, redacted.signals.size)
        assertTrue(redacted.signals.all { (it as TelemetrySignal.Counter).value == 1L })
    }

    @Test
    fun `the export keeps the run and the trace it describes`() {
        val trace = TelemetryTrace("run-1", TelemetryTrace.traceIdOf("run-1"), listOf(span()))

        val redacted = redactAndLimit(trace, policy)

        assertEquals("run-1", redacted.runId)
        assertEquals(trace.traceId, redacted.traceId)
        assertEquals(1, redacted.signals.size)
    }
}
