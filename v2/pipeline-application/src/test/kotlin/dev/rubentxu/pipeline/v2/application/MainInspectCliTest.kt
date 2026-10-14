package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.InspectParseResult.Parsed
import dev.rubentxu.pipeline.v2.application.InspectParseResult.Rejected
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R4 / WU-R4.1 — `inspect` skeleton contract.
 *
 * The full HF1 (in-process `MainInspectCli.main` with a real durable store) and HF2
 * (`InspectCliInstallDistCanaryTest` against the JAR) are filed as R4 / WU-R4.1.x
 * follow-ups; this file pins the HF0 layer — the closed ADT and the parser — so a
 * regression in either is caught without paying for the heavier fixture.
 */
class MainInspectCliTest {

    // ------------------------------------------------------------------ HF0 closed ADT pins

    @Test
    fun `InspectField ADT has exactly the five R4 cases and no others`() {
        val all = listOf(
            MainInspectCli.InspectField.Stage,
            MainInspectCli.InspectField.Step,
            MainInspectCli.InspectField.Outcome,
            MainInspectCli.InspectField.Message,
            MainInspectCli.InspectField.ConsoleTail,
        )
        assertEquals(5, all.size, "InspectField must remain a five-case closed ADT")
        // No duplicates by identity
        assertEquals(all.size, all.toSet().size, "InspectField cases must be distinct objects")
    }

    @Test
    fun `InspectField parse round-trips on the documented CLI tokens`() {
        listOf(
            "STAGE" to MainInspectCli.InspectField.Stage,
            "STEP" to MainInspectCli.InspectField.Step,
            "OUTCOME" to MainInspectCli.InspectField.Outcome,
            "MESSAGE" to MainInspectCli.InspectField.Message,
            "CONSOLETAIL" to MainInspectCli.InspectField.ConsoleTail,
        ).forEach { (token, expected) ->
            assertEquals(expected, MainInspectCli.InspectField.parse(token), "token $token")
        }
        // Unknown token is refused, not silently mapped to a default.
        assertEquals(null, MainInspectCli.InspectField.parse("bogus"))
        assertEquals(null, MainInspectCli.InspectField.parse(""))
    }

    @Test
    fun `InspectField Stage and Step project the matching fields of an InspectionRecord`() {
        val rec = InspectionRecord(
            stage = "build",
            stepOrdinal = 7L,
            outcome = "failure",
            message = "compile failed",
        )
        assertEquals("build", MainInspectCli.InspectField.Stage.extract(rec))
        assertEquals("7", MainInspectCli.InspectField.Step.extract(rec))
        assertEquals("failure", MainInspectCli.InspectField.Outcome.extract(rec))
        assertEquals("compile failed", MainInspectCli.InspectField.Message.extract(rec))
        assertEquals("", MainInspectCli.InspectField.ConsoleTail.extract(rec))
    }

    // ------------------------------------------------------------------ HF1 parser rows

    @Test
    fun `parser accepts the default empty query and emits all-default columns`() {
        val r = MainInspectCli.parseInspect(arrayOf())
        assertTrue(r is InspectParseResult.Parsed, "empty options must be a parsed query, not rejected")
        val q = (r as Parsed).value
        assertEquals(false, q.failed)
        assertEquals(null, q.kind)
        assertEquals(null, q.stage)
        assertEquals(null, q.step)
        assertEquals(0, q.context)
        assertEquals(0, q.logTail)
        assertEquals(null, q.fields)
        assertEquals(MainInspectCli.InspectFormat.TEXT, q.format)
    }

    @Test
    fun `parser turns --failed --kind K --stage S --step N --context C --log-tail L into typed fields`() {
        val r = MainInspectCli.parseInspect(
            arrayOf(
                "--failed",
                "--kind", "StepFailed",
                "--stage", "build",
                "--step", "5",
                "--context", "3",
                "--log-tail", "1024",
                "--fields", "STAGE,STEP,OUTCOME,MESSAGE,CONSOLETAIL",
                "--format", "json",
            ),
        )
        assertTrue(r is InspectParseResult.Parsed)
        val q = (r as Parsed).value
        assertEquals(true, q.failed)
        assertEquals("StepFailed", q.kind)
        assertEquals("build", q.stage)
        assertEquals(5L, q.step)
        assertEquals(3, q.context)
        assertEquals(1024, q.logTail)
        assertEquals(MainInspectCli.InspectFormat.JSON, q.format)
        assertNotNull(q.fields)
        assertEquals(5, q.fields!!.size)
    }

    @Test
    fun `parser rejects unknown --fields tokens with a typed message rather than a silent default`() {
        val r = MainInspectCli.parseInspect(arrayOf("--fields", "STAGE,BOGUS"))
        assertTrue(r is InspectParseResult.Rejected, "unknown field MUST be rejected")
        assertTrue(
            (r as Rejected).error.contains("unknown token 'BOGUS'"),
            "rejection MUST name the offending token, got '${r.error}'",
        )
    }

    @Test
    fun `parser refuses negative --context and non-Long --step values`() {
        assertTrue(MainInspectCli.parseInspect(arrayOf("--context", "-1")) is InspectParseResult.Rejected)
        assertTrue(MainInspectCli.parseInspect(arrayOf("--step", "abc")) is InspectParseResult.Rejected)
        assertTrue(
            MainInspectCli.parseInspect(arrayOf("--format", "yaml")) is InspectParseResult.Rejected,
            "an unknown --format must be rejected, not silently defaulted",
        )
    }

    @Test
    fun `parser refuses an unknown --option name explicitly`() {
        val r = MainInspectCli.parseInspect(arrayOf("--bogus", "value"))
        assertTrue(r is InspectParseResult.Rejected)
        assertTrue(
            (r as Rejected).error.contains("unknown option"),
            "rejection MUST name it as an option, got '${r.error}'",
        )
    }
}