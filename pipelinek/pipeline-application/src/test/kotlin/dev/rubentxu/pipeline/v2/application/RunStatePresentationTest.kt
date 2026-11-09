package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.AttemptId
import dev.rubentxu.pipeline.v2.runtime.inspect.IntrospectionFailure
import dev.rubentxu.pipeline.v2.runtime.inspect.JournalPosition
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The lines the command line prints when it answers about a run.
 *
 * ## The guarantee under trial
 *
 * A run's state is one line, and every refusal is one sentence that says what could
 * not be read and why. The vocabulary is the product's, and the sentences are pinned
 * here because they are what a reader of the output — a person or a program — parses:
 * a sentence that changes its wording without the meaning changing is a broken
 * promise to both.
 *
 * ## The capacity this serves
 *
 * The operator who asks a run how it is doing gets an answer they can act on, and the
 * program that reads the line finds the same shape it found yesterday. A refusal that
 * arrived as a success, or a state that arrived as a refusal, would be the one
 * answer either of them could not recover from.
 *
 * ## Why this instrument
 *
 * The rendering is a pure function of the answer: no store, no clock, no process. The
 * cheapest instrument that can fail is a call to the renderer and a reading of the
 * line it produced. The alternative — a verb that runs — would need a durable store
 * and a run in it, and it would say THAT the line changed, never WHICH of the
 * sentences moved.
 *
 * ## What this does not freeze
 *
 * Where the line goes and with which exit code it travels; that is the verb's wiring.
 */
class RunStatePresentationTest {

    @Test
    fun `a run that is going says so`() {
        assertEquals(
            "run-state running",
            InspectionPresentation.renderRunState(
                RuntimeObservation.Running(
                    attempt = AttemptId(1),
                    leaseHolder = null,
                    fencingToken = 7L,
                    journalPosition = JournalPosition(
                        operations = 0,
                        latestOpId = null,
                        latestTerminalAtMs = null,
                    ),
                    outputTails = emptyList(),
                ),
            ),
            "a run that is going says that it is going",
        )
    }

    @Test
    fun `a run that finished says how it finished and when`() {
        assertEquals(
            "run-state terminal outcome=succeeded at=1792575000000",
            InspectionPresentation.renderRunState(
                RuntimeObservation.Terminal(
                    attempt = AttemptId(1),
                    terminal = TerminalObservation.Succeeded(1_792_575_000_000L),
                    terminalAtMs = 1_792_575_000_000L,
                ),
            ),
            "the terminal line carries the outcome and the instant",
        )
    }

    @Test
    fun `a run whose substrate holds nothing is empty and not a refusal`() {
        assertEquals(
            "run-state empty",
            InspectionPresentation.renderRunState(
                RuntimeObservation.LiveButEmpty(attempt = AttemptId(1), reason = "no journal rows"),
            ),
            "an empty substrate is a fact about the substrate, not a refusal",
        )
    }

    @Test
    fun `a run that cannot be observed says so`() {
        assertEquals(
            "run-state unobservable",
            InspectionPresentation.renderRunState(
                RuntimeObservation.Unobservable(IntrospectionFailure.StorageError("the disk is gone")),
            ),
            "a substrate that could not be read says that it could not be read",
        )
    }

    @Test
    fun `every refusal is one sentence that names what could not be read`() {
        val rows: List<Pair<RunStateRefusal, String>> = listOf(
            RunStateRefusal.UnknownRun("run-7") to
                "this instance does not know run 'run-7'.",
            RunStateRefusal.NotConfigured(RunStateRefusal.Missing.CONTROL_ROOT) to
                "there is no control root to read the run's state from.",
            RunStateRefusal.NotConfigured(RunStateRefusal.Missing.EVENT_PLANE) to
                "the event plane is not configured for this instance.",
            RunStateRefusal.NotConfigured(RunStateRefusal.Missing.OUTPUT_PLANE) to
                "the output plane is not configured for this instance.",
            RunStateRefusal.StorageFailed("the disk is gone") to
                "the run's storage failed: the disk is gone",
            RunStateRefusal.HeldByAnother("owner-1", 7L) to
                "run run-7 is being published by 'owner-1' (fencing token 7).",
            RunStateRefusal.Inconsistent("the journal and the lease disagree") to
                "the run's lease state is inconsistent: the journal and the lease disagree",
            RunStateRefusal.UnknownOperation("op-1", 2) to
                "this instance does not know operation 'op-1' (attempt 2).",
        )

        rows.forEach { (refusal, sentence) ->
            assertEquals(
                sentence,
                InspectionPresentation.renderRunStateRefusal(refusal, "run-7"),
                "the sentence of $refusal moved",
            )
        }
    }
}
