package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The terminal an operator reads when a durable shell cannot be reconciled.
 *
 * **The guarantee.** A reconciliation that ends in LOST says WHICH fact made the outcome
 * unknowable. The failure code does not change — it is still `DURABLE_TASK_LOST`, still
 * fail-closed, still not retryable — but the message tells the reader what the control
 * directory actually held: a result nobody could read, a result that was not an exit code, or
 * no result at all.
 *
 * **Why the message and not the verdict.** The verdict is the reconciler's own decision and
 * lives in the SDK; this message is what crosses into the runtime and reaches the operator,
 * so it is the one that has to carry the fact. A verdict that names the fact and a message
 * that does not would leave the operator exactly where the verdict left it.
 *
 * **Why this instrument.** The composition of the terminal is a pure function of the operation
 * id and the fact, so it can be observed without a control directory, without a process and
 * without a clock — which is what a unit test is allowed to do.
 */
class LostTerminalTest {

    @Test
    fun `the terminal of a lost operation names the fact that made the outcome unknowable`() {
        val terminal = lostTerminal(
            "op-42",
            StepReconcilerL1.Classification.LostReason.ResultNotAnExitCode,
        )

        assertTrue(
            terminal.failure.message.contains("not an exit code"),
            "the message must name the fact that was observed: ${terminal.failure.message}",
        )
    }

    @Test
    fun `the three facts reach the operator as three different messages`() {
        val messages = StepReconcilerL1.Classification.LostReason.entries.associateWith { reason ->
            lostTerminal("op-42", reason).failure.message
        }

        assertEquals(
            messages.size,
            messages.values.toSet().size,
            "each fact must reach the operator as its own message: $messages",
        )
    }

    @Test
    fun `there are exactly three facts that leave the outcome unknown`() {
        val reasons = StepReconcilerL1.Classification.LostReason.entries

        assertEquals(3, reasons.size, "the outcome is unknown for three measured facts: $reasons")
        assertEquals(
            reasons.size,
            reasons.map { it.diagnostic }.toSet().size,
            "each fact must say something of its own: $reasons",
        )
    }

    @Test
    fun `the failure code of a lost operation does not change`() {
        val terminal = lostTerminal(
            "op-42",
            StepReconcilerL1.Classification.LostReason.NoResultPublished,
        )

        assertEquals("DURABLE_TASK_LOST", terminal.failure.code)
        assertEquals(FailureKind.INFRASTRUCTURE, terminal.failure.kind)
        assertEquals(FailureOrigin.RECONCILIATION, terminal.failure.origin)
        assertFalse(terminal.failure.retryable, "a lost operation is not retried: nobody knows its outcome")
        assertEquals("op-42", terminal.failure.operationId)
    }
}
