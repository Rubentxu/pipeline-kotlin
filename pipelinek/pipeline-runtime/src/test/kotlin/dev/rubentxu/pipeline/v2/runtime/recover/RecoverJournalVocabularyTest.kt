package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.runtime.recover.RuntimeRecoverDecision.OperationOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The words a recovered run is written under.
 *
 * **The guarantee.** Recovery re-uses a run's terminal fact instead of running it again, so
 * the word that lands in the journal is the only record a later reader has of what happened.
 * If a cancelled run is journalled as a success, or an unstable run as a failure, every later
 * reader believes something that never happened, and nothing else in the system contradicts
 * it.
 *
 * **Why the rule is asked directly.** The mapping is a pure function over two values, so it
 * can be asked without a journal, a cursor, a lease and an output store underneath it. That
 * matters because a rule that can only be reached by composing four collaborators is, in
 * practice, a rule nobody reaches.
 *
 * **The rows that carry the weight.**
 *
 * A terminal the runtime could not classify is journalled as a failure, never as a success:
 * unknown is not success. A cancelled run is journalled as aborted, because the durable
 * vocabulary has no cancelled and aborted is the state that says this run did not finish. An
 * unstable run is unstable and not failed, because the work ran and deserves attention, which
 * is a different fact from a failure.
 */
class RecoverJournalVocabularyTest {

    @Test
    fun `every operation status has exactly one meaning for the recover decider`() {
        assertEquals(OperationOutcome.Succeeded, operationOutcomeOf(OperationStatus.SUCCEEDED))
        assertEquals(OperationOutcome.Failed, operationOutcomeOf(OperationStatus.FAILED))
        // Killed by its deadline: it did not produce a result.
        assertEquals(OperationOutcome.Failed, operationOutcomeOf(OperationStatus.FAILED_TIMEOUT))
        // It did not produce the result it promised.
        assertEquals(OperationOutcome.Failed, operationOutcomeOf(OperationStatus.DIVERGENT))
        // The work ran and deserves attention. That is a different fact from a failure.
        assertEquals(OperationOutcome.Unstable, operationOutcomeOf(OperationStatus.UNSTABLE))
        assertEquals(OperationOutcome.Aborted, operationOutcomeOf(OperationStatus.ABORTED))
        assertEquals(OperationOutcome.Running, operationOutcomeOf(OperationStatus.RUNNING))
        assertEquals(OperationOutcome.Other("lost"), operationOutcomeOf(OperationStatus.LOST))
        assertEquals(OperationOutcome.Other("pending"), operationOutcomeOf(OperationStatus.PENDING))
    }

    @Test
    fun `a recovered terminal is journalled under a status that never claims a success it did not have`() {
        assertEquals(OperationStatus.SUCCEEDED, recoveredStatusOf(TerminalObservation.Succeeded(1L)))
        assertEquals(OperationStatus.FAILED, recoveredStatusOf(TerminalObservation.Failed("USER", 1L)))
        assertEquals(OperationStatus.UNSTABLE, recoveredStatusOf(TerminalObservation.Unstable(1L)))
        assertEquals(OperationStatus.ABORTED, recoveredStatusOf(TerminalObservation.Aborted(1L)))
        // The durable vocabulary has no cancelled, and aborted is what "did not finish" means.
        assertEquals(OperationStatus.ABORTED, recoveredStatusOf(TerminalObservation.Cancelled(1L)))
        // Unknown is not success.
        assertEquals(OperationStatus.FAILED, recoveredStatusOf(TerminalObservation.Other("weird", 1L)))
    }

    @Test
    fun `the name of a recovered terminal says what happened`() {
        assertEquals("succeeded", recoveredOutcomeNameOf(TerminalObservation.Succeeded(1L)))
        assertEquals("USER", recoveredOutcomeNameOf(TerminalObservation.Failed("USER", 1L)))
        assertEquals("unstable", recoveredOutcomeNameOf(TerminalObservation.Unstable(1L)))
        assertEquals("aborted", recoveredOutcomeNameOf(TerminalObservation.Aborted(1L)))
        // The operator must read "cancelled", not "failed": the two are different facts.
        assertEquals("cancelled", recoveredOutcomeNameOf(TerminalObservation.Cancelled(1L)))
        assertEquals("weird", recoveredOutcomeNameOf(TerminalObservation.Other("weird", 1L)))
    }

    @Test
    fun `the terminal time is the one the terminal carries`() {
        assertEquals(7L, terminalAtMsOf(TerminalObservation.Succeeded(7L)))
        assertEquals(8L, terminalAtMsOf(TerminalObservation.Failed("USER", 8L)))
        assertEquals(9L, terminalAtMsOf(TerminalObservation.Unstable(9L)))
        assertEquals(10L, terminalAtMsOf(TerminalObservation.Aborted(10L)))
        assertEquals(11L, terminalAtMsOf(TerminalObservation.Cancelled(11L)))
        assertEquals(12L, terminalAtMsOf(TerminalObservation.Other("weird", 12L)))
    }
}
