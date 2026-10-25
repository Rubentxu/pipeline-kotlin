package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.runtime.explain.Diagnosis
import dev.rubentxu.pipeline.v2.runtime.explain.Fact
import dev.rubentxu.pipeline.v2.runtime.explain.Inference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The projection extracts facts from the run's own events and the report
 * renders them. The lies these tests refuse: a failure that is not the first
 * one presented as the cause, an empty store read as a diagnosis, a report
 * that leaks the run's payloads, and a query that asks for two whole-run
 * reports at once.
 */
class RunExplainTest {

    private var seq = 0L

    private fun start(scriptPath: String = "/workspace/secret/script.kts", at: Long) =
        RunStarted("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), scriptPath)

    private fun finished(outcome: String, at: Long) =
        RunFinished("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), outcome, emptyList())

    private fun stepStart(at: Long, step: Int = 0, name: String = "deploy") =
        StepStarted("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), 0, step, name, "sh")

    private fun stepFinish(at: Long, step: Int = 0) =
        StepFinished("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), 0, step, "deploy", "sh", "success")

    private fun stepFail(at: Long, step: Int = 0, name: String = "deploy", message: String = "exit 1") =
        StepFailed("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), 0, step, name, "sh", FailureKind.SCRIPT, message)

    @Test
    fun `a store with no events is a typed refusal, never a success-shaped diagnosis`() {
        assertEquals(Diagnosis.Refused("unknown-run"), RunExplainProjection.project(emptyList()))
    }

    @Test
    fun `the first failure by evidence order is the one explained`() {
        val events = listOf(
            start(at = 0),
            stepStart(at = 10, step = 0, name = "first-breaks"),
            stepFail(at = 20, step = 0, name = "first-breaks"),
            stepStart(at = 30, step = 1, name = "second-breaks"),
            stepFail(at = 40, step = 1, name = "second-breaks"),
            finished("failure", at = 50),
        )

        val diagnosis = RunExplainProjection.project(events) as Diagnosis.Explained

        assertEquals("ended-failure", diagnosis.status)
        val broke = diagnosis.findings.filterIsInstance<Fact.StepBroke>().single()
        assertEquals("first-breaks", broke.stepName)
        // The first failure is the second event of the run; its evidence names it.
        assertTrue(broke.evidence.sequence < diagnosis.findings.filterIsInstance<Fact.RunEnded>().single().evidence.sequence)
    }

    @Test
    fun `a step that never closed is an inference on an open run`() {
        val events = listOf(
            start(at = 0),
            stepStart(at = 10),
            finished("failure", at = 30),
        )

        val diagnosis = RunExplainProjection.project(events) as Diagnosis.Explained

        assertEquals("ended-failure", diagnosis.status)
        val dangling = diagnosis.findings.filterIsInstance<Inference.StepNeverClosed>()
        assertEquals(listOf("deploy"), dangling.map { it.stepName })
    }

    @Test
    fun `a closed step is not reported dangling`() {
        val events = listOf(
            start(at = 0),
            stepStart(at = 10),
            stepFinish(at = 20),
            finished("success", at = 30),
        )

        val diagnosis = RunExplainProjection.project(events) as Diagnosis.Explained

        assertEquals("ended-success", diagnosis.status)
        assertTrue(diagnosis.findings.none { it is Inference })
    }

    @Test
    fun `the report renders status, evidence and lanes without the run's payloads`() {
        val events = listOf(
            start(scriptPath = "/workspace/secret/script.kts", at = 0),
            stepStart(at = 10, name = "deploy"),
            stepFail(at = 20, name = "deploy", message = "exit 1"),
            finished("failure", at = 30),
        )
        val diagnosis = RunExplainProjection.project(events)

        val rendered = (RunExplainReport.textLines("run-1", diagnosis) +
            RunExplainReport.jsonLines("run-1", diagnosis)).joinToString("\n")

        // The script path is the run's payload; the step name is the
        // diagnosis's actionable subject, so one is absent and the other is not.
        assertTrue(!rendered.contains("secret"))
        assertTrue(rendered.contains("step=deploy"))
        assertTrue(rendered.contains("status=ended-failure"))
        assertTrue(rendered.contains("completeness event-journal=read"))
        assertTrue(rendered.contains("\"schema\":\"runtime.explain.v1\""))
    }

    @Test
    fun `a terminal that matches several open steps closes none and says so`() {
        val events = listOf(
            start(at = 0),
            stageEvent(at = 10, stage = 0),
            stageStep(at = 15, stage = 0, step = 1),
            stageEvent(at = 20, stage = 1),
            stageStep(at = 25, stage = 1, step = 1),
            // No stage index: two open steps share ordinal 1, so this
            // terminal names neither of them and both stay open.
            StepFailed(
                "e${seq++}", "run-1", seq++, Instant.ofEpochMilli(50),
                null, 1, "deploy", "sh", FailureKind.SCRIPT, "exit 1",
            ),
            finished("failure", at = 60),
        )

        val diagnosis = RunExplainProjection.project(events) as Diagnosis.Explained

        val ambiguous = diagnosis.findings.filterIsInstance<Inference.AttributionAmbiguous>()
        assertEquals(1, ambiguous.size)
        assertEquals(1, ambiguous.single().count)
        assertTrue(diagnosis.findings.none { it is Inference.StepNeverClosed })
    }

    @Test
    fun `a terminal without stage index still closes a unique candidate`() {
        val events = listOf(
            start(at = 0),
            stageEvent(at = 10, stage = 0),
            stageStep(at = 15, stage = 0, step = 1),
            StepFailed(
                "e${seq++}", "run-1", seq++, Instant.ofEpochMilli(40),
                null, 1, "deploy", "sh", FailureKind.SCRIPT, "exit 1",
            ),
            finished("failure", at = 50),
        )

        val diagnosis = RunExplainProjection.project(events) as Diagnosis.Explained

        // The ordinal named exactly one open step, so it closed; only the
        // two observed facts remain, with no inference at all.
        assertTrue(diagnosis.findings.none { it is Inference })
    }

    private fun stageEvent(at: Long, stage: Int) =
        dev.rubentxu.pipeline.v2.events.StageStarted("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), stage, "build")

    private fun stageStep(at: Long, stage: Int, step: Int) =
        StepStarted("e${seq++}", "run-1", seq++, Instant.ofEpochMilli(at), stage, step, "deploy", "sh")

    @Test
    fun `control characters in a failure message render as valid JSON escapes`() {
        val events = listOf(
            start(at = 0),
            stepStart(at = 10),
            stepFail(at = 20, message = "line one\tline two\rfinished\u0001"),
            finished("failure", at = 30),
        )
        val diagnosis = RunExplainProjection.project(events)

        val line = RunExplainReport.jsonLines("run-1", diagnosis)
            .first { it.contains("message") }

        assertTrue(line.contains("line one\\tline two\\rfinished\\u0001"))
        assertTrue(!line.contains("\t"))
        assertTrue(!line.contains("\r"))
    }

    @Test
    fun `the JSON report is line-delimited and every line is a JSON object`() {
        val events = listOf(
            start(at = 0),
            stepStart(at = 10, name = "deploy"),
            stepFail(at = 20, name = "deploy", message = "exit 1"),
            finished("failure", at = 30),
        )
        val diagnosis = RunExplainProjection.project(events) as Diagnosis.Explained

        val lines = RunExplainReport.jsonLines("run-1", diagnosis)

        // Every line parses ON ITS OWN. A consumer tails this stream and
        // never has to know that a document was split across lines.
        val parsed = lines.map { line -> Json.parseToJsonElement(line) }
        assertTrue(parsed.all { it is JsonObject })
        val header = parsed.first().jsonObject
        assertEquals("runtime.explain.v1", header["schema"]?.jsonPrimitive?.content)
        assertEquals("run-1", header["runId"]?.jsonPrimitive?.content)
        assertEquals("ended-failure", header["status"]?.jsonPrimitive?.content)
        // One line per finding, each saying whether it is a fact or an
        // inference, and carrying the evidence that grounds it.
        val findingLines = parsed.drop(1).map { it.jsonObject }
        assertEquals(diagnosis.findings.size, findingLines.size)
        assertTrue(findingLines.all { "kind" in it && "code" in it && "evidence" in it })
        assertTrue(findingLines.any { it["kind"]?.jsonPrimitive?.content == "fact" })
    }

    @Test
    fun `a run id carrying quotes and control characters does not break the JSON`() {
        val events = listOf(start(at = 0), stepStart(at = 10), stepFail(at = 20), finished("failure", at = 30))
        val diagnosis = RunExplainProjection.project(events)

        val lines = RunExplainReport.jsonLines("run \"one\"\n\ttwo", diagnosis)

        // A parser that throws IS the assertion.
        lines.forEach { line -> Json.parseToJsonElement(line) }
        val header = Json.parseToJsonElement(lines.first()).jsonObject
        assertEquals("run \"one\"\n\ttwo", header["runId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an outcome word carrying quotes and control characters does not break the JSON`() {
        val events = listOf(start(at = 0), stepStart(at = 10), stepFail(at = 20), finished("suc\"cess\t1", at = 30))
        val diagnosis = RunExplainProjection.project(events)

        val lines = RunExplainReport.jsonLines("run-1", diagnosis)

        lines.forEach { line -> Json.parseToJsonElement(line) }
        val ended = lines.map { Json.parseToJsonElement(it).jsonObject }
            .mapNotNull { it["evidence"]?.jsonObject }
            .first { "outcome" in it }
        assertEquals("suc\"cess\t1", ended["outcome"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the explain mode parses alone and refuses to share a query with the other reports`() {
        val parsed = MainInspectCli.parseInspect(arrayOf("--explain", "--db", "/tmp/x.db"))
        assertTrue(parsed is InspectParseResult.Parsed)
        assertEquals(true, (parsed as InspectParseResult.Parsed).value.explain)

        assertTrue(MainInspectCli.parseInspect(arrayOf("--state", "--explain")) is InspectParseResult.Rejected)
        assertTrue(MainInspectCli.parseInspect(arrayOf("--profile", "--explain")) is InspectParseResult.Rejected)
    }
}
