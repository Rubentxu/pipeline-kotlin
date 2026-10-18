package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * SEMANTIC CONSTITUTION §8 — a projection must not render a status its event does not carry.
 *
 * Measured 2026-10-18 against distribution 0.62.0, one failing `sh` step printed:
 *
 * ```text
 * [PipelineK] ERROR: step 's1/sh-0' failed [SCRIPT]: shell exited with code 7
 * [PipelineK] [step: s1] s1/sh-0 ok
 * ```
 *
 * The second line is the defect. `StepExecutionBoundary` emits `StepFinished` from a
 * `finally` — it closes the step's span on success, failure and exception alike — and the
 * event has no outcome field. `ok` was the projection's own verdict, and it contradicted the
 * `ERROR:` line printed immediately before it.
 *
 * Why the rows are ordered the way they are: the claim that `StepFinished` renders no verdict
 * comes FIRST, and the guard that this projection is capable of rendering a verdict at all
 * comes SECOND. Reversing them makes a mutation die on the guard and report "the row cannot
 * see what it says it sees" instead of naming the missing verdict. See the assertion-order
 * lesson recorded for H1.3c.
 */
class ConsoleStepFinishedVerdictTest {

    /** Every word a reader would read as a verdict. Closed set: this is the whole vocabulary. */
    private val VERDICTS = listOf("ok", "success", "succeeded", "failed", "failure", "error")

    private val at = Instant.parse("2026-10-18T00:00:00Z")

    private fun finished() = StepFinished(
        eventId = "evt-3",
        runId = "run-1",
        sequence = 2L,
        occurredAt = at,
        stageIndex = 0,
        stepIndex = 1,
        // A name that contains no verdict word, so a hit is the projection's and not the input's.
        stepName = "probe",
        stepType = "core.sh",
    )

    @Test
    fun `StepFinished renders no verdict because the event carries none`() {
        val line = ConsoleFamilyLines.structure(finished(), HumanConsoleRenderer.Scope())
        assertTrue(
            line.text.contains("probe"),
            "precondition: the row must be looking at a real Step line, got: '${line.text}'",
        )
        val claimed = VERDICTS.filter { line.text.lowercase().contains(it) }
        assertTrue(
            claimed.isEmpty(),
            "StepFinished carries no outcome, yet its console line claims ${claimed.joinToString(", ")}: " +
                "'${line.text}'. The event is emitted from a `finally`, so it closes the span on " +
                "failure too — a verdict here is the projection inventing one.",
        )
    }

    @Test
    fun `every event that carries an outcome has its line show it`() {
        // The anti-vacuity guard, below the claim it protects: without it, "renders no verdict"
        // is also true of a projection that renders nothing at all, and the row above would pass
        // on a broken render.
        //
        // One table, not three rows, because the property is one property. It was three rows
        // long for a moment and the extra two were unpinned: a mutation that stripped `outcome`
        // from all three projections killed exactly ONE row. A defect class with three instances
        // and three rows still ships two of them uncaught; the table is what makes the class
        // closed rather than sampled.
        val scope = HumanConsoleRenderer.Scope()
        val carrying: List<Pair<String, (DomainEvent) -> String>> = listOf(
            "StageFinished" to { event ->
                ConsoleFamilyLines.structure(
                    event as StageFinished, scope,
                ).text
            },
            "ParallelBranchFinished" to { event ->
                ConsoleFamilyLines.structure(
                    event as ParallelBranchFinished, scope,
                ).text
            },
            "RetryAttemptFinished" to { event ->
                ConsoleFamilyLines.controlNote(event as RetryAttemptFinished).text
            },
        )
        val events: List<Pair<String, DomainEvent>> = listOf(
            "StageFinished" to StageFinished(
                eventId = "evt-a", runId = "run-1", sequence = 1L, occurredAt = at,
                stageIndex = 0, stageName = "build", outcome = "failure",
            ),
            "ParallelBranchFinished" to ParallelBranchFinished(
                eventId = "evt-b", runId = "run-1", sequence = 2L, occurredAt = at,
                branchIndex = 0, branchName = "left", parentStageIndex = 0, outcome = "failure",
            ),
            "RetryAttemptFinished" to RetryAttemptFinished(
                eventId = "evt-c", runId = "run-1", sequence = 3L, occurredAt = at,
                attemptNumber = 1, maxAttempts = 3, stepName = "probe",
                stepType = "core.sh", stageIndex = 0, stepIndex = 1, outcome = "failure",
            ),
        )
        val silent = events.map { (kind, event) ->
            kind to carrying.first { (k, _) -> k == kind }.second(event)
        }.filterNot { (_, text) -> text.contains("failure") }.map { (kind, text) -> "$kind -> '$text'" }
        assertTrue(
            silent.isEmpty(),
            "these events carry `outcome = failure` and their console line drops it: " +
                "${silent.joinToString("; ")}. A projection that discards a carried verdict is the " +
                "same defect as one that invents an absent one, in the opposite direction.",
        )
    }

    /**
     * HF1 — crosses [HumanConsoleRenderer.render], the productive authority the operator's `run`
     * and `observe` output goes through. The two rows above are HF0 contract rows on the
     * projection itself; this one proves the sequence a failing step actually produces does not
     * contradict itself.
     */
    @Test
    fun `a failing step's rendered console never reports it as ok`() {
        val events = listOf(
            StepStarted("evt-1", "run-1", 0L, at, stageIndex = 0, stepIndex = 1, stepName = "probe", stepType = "core.sh"),
            StepFailed(
                eventId = "evt-2",
                runId = "run-1",
                sequence = 1L,
                occurredAt = at,
                stepIndex = 1,
                stepName = "probe",
                stepType = "core.sh",
                failureKind = FailureKind.SCRIPT,
                message = "shell exited with code 7",
            ),
            finished(),
        )
        val console = HumanConsoleRenderer.render(events, ObservationView.CONSOLE)
        assertTrue(
            console.contains("shell exited with code 7"),
            "precondition: the failure line must be present, got:\n$console",
        )
        // The exact shape measured: the error, then a line claiming the same step succeeded.
        assertFalse(
            console.contains("probe ok"),
            "the console reported a failed step as ok:\n$console",
        )
        // And the closing line must say the span ended, which is all StepFinished knows.
        assertTrue(
            console.contains("probe finished"),
            "the closing line must report the span ended:\n$console",
        )
    }
}