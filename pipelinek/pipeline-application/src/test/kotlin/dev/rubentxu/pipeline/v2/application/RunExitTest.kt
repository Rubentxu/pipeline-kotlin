package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: a run's exit is decided by ONE table, and that table answers with the CASE of
 * the [CliExit] taxonomy, never with a number written at the call site.
 *
 * The capacity this serves: a caller that branches on a process exit code can tell a run that
 * failed from a request that was not understood, and it can tell them the same way whether the
 * run had a durable journal or not. The two execution paths used to carry their own copy of the
 * decision, and a copy is free to disagree with the other one: the durable path printed the
 * failure and then its cause, and the volatile path answered with a number of its own. A suite
 * that ran only one of them could not see that there was a choice to make.
 *
 * ## Why this instrument
 *
 * The rows below CALL the decision and read the case and the code it answers with. That call is
 * the observation: a decision that answered with a number instead of a case, or that picked the
 * wrong case for an outcome, is caught here — and it is caught in a millisecond, without a
 * process, a journal or a store.
 *
 * The rows also read what the decision PRINTED, because the report is part of the same decision:
 * the same outcome has to be said the same way in both worlds, and the words the operator reads
 * are a published contract that the end-to-end scenarios pin.
 *
 * ## What this does not freeze
 *
 * WHICH function owns the decision. Nothing in a unit test can see that: the invariant is a
 * property of the source, and the instrument for it is review plus the scenarios that observe
 * both worlds. These rows ask WHAT the decision is, not who calls it.
 *
 * These rows are pure: no filesystem, no clock, no process, no network. The only side effect is
 * what the CLI prints, and that is captured and read.
 */
class RunExitTest {

    @Test
    fun `a successful run answers with the success case`() {
        val (exit, report) = capturing { runExit(RunOutcome.Success, null) }

        assertEquals("Success", exit.name, "a run that finished well is the success case")
        assertEquals(0, exit.code, "the success case carries the published code")
        assertTrue(
            report.contains("Pipeline finished with SUCCESS"),
            "the operator is told the run succeeded: $report",
        )
    }

    @Test
    fun `an unstable run answers zero because it finished`() {
        val (exit, report) = capturing { runExit(RunOutcome.Unstable, null) }

        assertEquals("Success", exit.name, "an unstable run is not a failure, so it is not the failure case")
        assertEquals(0, exit.code, "the run finished, so the code is the success one")
        assertTrue(
            report.contains("Pipeline finished with UNSTABLE"),
            "the operator reads the unstable verdict: $report",
        )
        assertTrue(
            !report.contains("FAILURE"),
            "an unstable run is never reported as a failure: $report",
        )
    }

    @Test
    fun `a failed run answers with the pipeline-failed case`() {
        val failure = PipelineFailure(kind = FailureKind.USER, message = "the step refused")

        val (exit, report) = capturing { runExit(RunOutcome.Failure(failure), null) }

        assertEquals("PipelineFailed", exit.name, "a run that produced a failure is the failure case")
        assertEquals(1, exit.code, "the failure case carries the published code")
        assertTrue(
            report.contains("Pipeline finished with FAILURE"),
            "the operator is told the run failed: $report",
        )
        assertTrue(
            report.contains("cause [USER]: the step refused"),
            "the operator is told WHY it failed, and of which kind: $report",
        )
    }

    @Test
    fun `an aborted run answers with the pipeline-failed case`() {
        val (exit, report) = capturing { runExit(RunOutcome.Aborted, null) }

        assertEquals("PipelineFailed", exit.name, "a run that never reached a terminal outcome did not succeed")
        assertEquals(1, exit.code, "the failure case carries the published code")
        assertTrue(
            report.contains("cause [ABORTED]"),
            "the operator is told the run was cancelled rather than failed: $report",
        )
    }

    @Test
    fun `a run with no typed outcome that finished successfully answers zero`() {
        val (exit, report) = capturing {
            runExit(null, runFinished(RunOutcome.SUCCESS_TOKEN))
        }

        assertEquals(0, exit.code, "the run finished successfully, so the code is the success one")
        assertTrue(
            report.contains("Pipeline finished with SUCCESS"),
            "the operator is told the run succeeded: $report",
        )
    }

    @Test
    fun `a run with no typed outcome that did not finish successfully answers one`() {
        val (exit, report) = capturing {
            runExit(null, runFinished(RunOutcome.FAILURE_TOKEN))
        }

        assertEquals(1, exit.code, "the run did not finish successfully, so the code is the failure one")
        assertTrue(
            report.contains("Pipeline finished with FAILURE"),
            "the operator is told the run failed: $report",
        )
    }

    @Test
    fun `a run with no typed outcome and no event at all answers one`() {
        val (exit, report) = capturing { runExit(null, null) }

        assertEquals(1, exit.code, "a run that said nothing succeeded did not succeed")
        assertTrue(
            report.contains("Pipeline finished with FAILURE"),
            "the operator is told the run failed: $report",
        )
    }

    @Test
    fun `every answer is a code the taxonomy declares`() {
        val outcomes = listOf(
            RunOutcome.Success,
            RunOutcome.Unstable,
            RunOutcome.Failure(PipelineFailure(kind = FailureKind.SCRIPT, message = "the shell failed")),
            RunOutcome.Aborted,
        )

        val answers = outcomes.map { outcome -> capturing { runExit(outcome, null) }.first }

        answers.forEach { exit ->
            val declared = CliExit.of(exit.code)
            assertNotNull(declared, "the code ${exit.code} is one this taxonomy declares")
            assertEquals(
                declared?.name,
                exit.name,
                "the case that answered is the case that owns the code",
            )
        }
    }

    /**
     * The last event of a run that produced no typed outcome.
     *
     * The instant is a fixed constant, not a reading of the clock: the decision under test reads
     * the outcome word and nothing else, and a wall-clock reading would make the row depend on
     * time it does not care about.
     */
    private fun runFinished(outcome: String): DomainEvent = RunFinished(
        eventId = "evt-1",
        runId = "run-1",
        sequence = 1L,
        occurredAt = Instant.EPOCH,
        outcome = outcome,
        diagnostics = emptyList(),
    )

    /**
     * Run [block] with the error stream captured, and give back what it decided and what it said.
     *
     * The decision is read on stderr because that is where the outcome is summarised, and a row
     * that asserts a decision has to be able to read what the decision said out loud without
     * letting it into the report of the run.
     */
    private fun <T> capturing(block: () -> T): Pair<T, String> {
        val err = ByteArrayOutputStream()
        val originalErr = System.err
        try {
            System.setErr(PrintStream(err, true, Charsets.UTF_8.name()))
            val value = block()
            return value to err.toString()
        } finally {
            System.setErr(originalErr)
        }
    }
}
