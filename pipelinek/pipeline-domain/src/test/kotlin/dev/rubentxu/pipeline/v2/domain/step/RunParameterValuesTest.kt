package dev.rubentxu.pipeline.v2.domain.step

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The carrier of the effective values of a run's parameters.
 *
 * ## The guarantee under trial
 *
 * That the carrier is what the run's KDoc promises: an immutable, per-run snapshot of the
 * values the run was started with, that does not publish the values themselves and that
 * answers a name the run does not carry with nothing at all.
 *
 * ## Why the copy is the row that matters
 *
 * A carrier that held the caller's map would be a carrier the caller could change under the
 * run: two steps of the same run could read two different values for the same parameter, and
 * the second one would read a value the run never had. The copy is what makes the promise
 * "decided when the run starts" true, so the row below measures the copy and not the
 * constructor.
 *
 * ## What this instrument cannot see
 *
 * It does not run a step and does not read a declaration: it observes the CARRIER, which is
 * the only thing this file owns.
 */
class RunParameterValuesTest {

    @Test
    fun `the values are copied so the caller cannot change them under the run`() {
        val source = mutableMapOf("target" to "staging")
        val values = RunParameterValues.of(source)

        source["target"] = "prod"
        source["dryRun"] = "true"

        assertEquals(
            "staging",
            values.valueOf("target"),
            "the carrier answered a value the run was started with, not one the caller changed later",
        )
        assertNull(
            values.valueOf("dryRun"),
            "a value the caller added after the run started is not a value of that run",
        )
        assertEquals(1, values.size)
    }

    @Test
    fun `a name the run does not carry has no value`() {
        val values = RunParameterValues.of(mapOf("target" to "prod"))

        assertEquals("prod", values.valueOf("target"))
        assertNull(
            values.valueOf("dryRun"),
            "a name the run does not carry has no value: answering one would be inventing it",
        )
    }

    @Test
    fun `the printed shape does not carry the values`() {
        val values = RunParameterValues.of(mapOf("target" to "prod", "token" to "s3cret"))

        val printed = values.toString()

        assertFalse(
            printed.contains("prod"),
            "the printed shape published a value: $printed",
        )
        assertFalse(
            printed.contains("s3cret"),
            "the printed shape published a value: $printed",
        )
        assertEquals("RunParameterValues(2 values)", printed)
    }

    @Test
    fun `a run that declares no parameter carries none`() {
        val values = RunParameterValues.of(emptyMap())

        assertTrue(values.isEmpty)
        assertEquals(0, values.size)
        assertNull(values.valueOf("target"))
        assertSame(
            RunParameterValues.EMPTY,
            RunParameterValues.of(emptyMap()),
            "an empty carrier is the SAME empty carrier, so a run without parameters carries one",
        )
    }

    @Test
    fun `two carriers of the same values are equal and interchangeable`() {
        val first = RunParameterValues.of(mapOf("target" to "prod"))
        val second = RunParameterValues.of(mapOf("target" to "prod"))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `two carriers of different values are not interchangeable`() {
        val first = RunParameterValues.of(mapOf("target" to "prod"))
        val second = RunParameterValues.of(mapOf("target" to "staging"))

        assertNotEquals(
            first,
            second,
            "two carriers of different values are two different carriers",
        )
    }

    @Test
    fun `a carrier that carries values is not empty`() {
        val values = RunParameterValues.of(mapOf("target" to "prod"))

        assertFalse(
            values.isEmpty,
            "a carrier that carries a value is not empty, whatever it carries",
        )
        assertEquals(1, values.size)
        assertEquals(
            mapOf("target" to "prod"),
            values.values,
            "the carrier carries exactly the values the run was given",
        )
    }

    @Test
    fun `the capability of the parameters is named the way the world reads it`() {
        assertEquals(
            "runtime.run-parameters",
            RUN_PARAMETERS_CAPABILITY.key,
            "the name of a capability is the contract that crosses the manifest",
        )
    }
}
