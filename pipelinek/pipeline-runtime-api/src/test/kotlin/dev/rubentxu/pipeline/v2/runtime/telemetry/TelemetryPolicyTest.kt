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
}
