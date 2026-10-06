package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.CancellationReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * E4b.4 — the three projections, and the typed abort cause.
 *
 * `FArchE4b4WaitUntilTerminalAuthorityTest` holds that no caller re-decides or re-reads the
 * terminal. This holds what the ADT actually says, which is the half a source scan cannot see:
 * that the three columns agree *by construction* rather than by five call sites happening to
 * agree today.
 *
 * Every assertion here is a value, not a round-trip. A `when` over [WaitUntilCompletion] is
 * written without an `else` in the KDoc-facing surface, so a fourth terminal breaks compilation
 * here rather than becoming a token only the wire knows about.
 */
class WaitUntilCompletionProjectionTest {

    /** Every case, built once, so a table cannot quietly omit one. */
    private val all: List<WaitUntilCompletion> = listOf(
        WaitUntilCompletion.Satisfied,
        WaitUntilCompletion.DeadlineExceeded(attempt = 3, ceilingMs = 1_000L),
        WaitUntilCompletion.Aborted(WaitUntilAbortCause.DurableRowAlreadyAborted),
        WaitUntilCompletion.Aborted(
            WaitUntilAbortCause.BodyCancelled(CancellationReason.ScopeShutDown),
        ),
    )

    @Test
    fun `satisfied is the only terminal that reports success`() {
        val successes = all.filter { it.toStepOutcome() == StepOutcome.Success }

        assertEquals(
            listOf<WaitUntilCompletion>(WaitUntilCompletion.Satisfied),
            successes,
            "Only a satisfied predicate may produce StepOutcome.Success. A terminal that " +
                "reported success without the predicate holding would conclude a run that " +
                "never finished.",
        )
    }

    @Test
    fun `the durable status, the step outcome and the wire token never disagree`() {
        data class Row(val status: OperationStatus, val outcome: StepOutcome, val wire: String)

        val rows = all.map { Row(it.durableStatus, it.toStepOutcome(), it.wireOutcome) }

        // One table, one law: which token may go with which status, and which
        // StepOutcome shape may go with which status. Both are recorded as the
        // *shape* — "Success" / "Failure" — rather than as instances, so the
        // comparison below is between two strings and a mismatch names a column
        // instead of printing an object identity.
        val allowed = mapOf(
            OperationStatus.SUCCEEDED to ("Success" to "completed"),
            OperationStatus.FAILED_TIMEOUT to ("Failure" to "deadline-exceeded"),
            OperationStatus.ABORTED to ("Failure" to "aborted"),
        )

        rows.forEach { row ->
            val expected = allowed[row.status]
            assertTrue(
                expected != null,
                "Terminal status ${row.status} is not one of the three the ADT declares. A " +
                    "fourth terminal needs a case here and a schema decision, not a new token.",
            )
            val (outcomeShape, wire) = expected!!
            assertEquals(wire, row.wire, "Wire token disagrees with the durable status.")
            val actualShape = if (row.outcome is StepOutcome.Failure) "Failure" else "Success"
            assertEquals(outcomeShape, actualShape, "StepOutcome shape disagrees with the status.")
        }
    }

    @Test
    fun `the wire vocabulary is exactly the three historical tokens`() {
        assertEquals(
            setOf("completed", "deadline-exceeded", "aborted"),
            WaitUntilCompletionWireOutcomes,
            "S8 freezes event schemas against these three tokens. A fourth means a new terminal " +
                "reached the wire without a schema decision; a missing one means a terminal lost " +
                "its projection.",
        )
    }

    @Test
    fun `an abort cause never leaks into the status or the token`() {
        val fromBody = WaitUntilCompletion.Aborted(
            WaitUntilAbortCause.BodyCancelled(CancellationReason.Deadline),
        )
        val fromRow = WaitUntilCompletion.Aborted(WaitUntilAbortCause.DurableRowAlreadyAborted)

        assertEquals(fromBody.durableStatus, fromRow.durableStatus)
        assertEquals(fromBody.wireOutcome, fromRow.wireOutcome)
        assertEquals(
            FailureKind.ENGINE,
            (fromBody.toStepOutcome() as StepOutcome.Failure).failure.kind,
            "Both arrivals are an abort, so both must carry the same failure kind. A cause that " +
                "changed the kind would make the two arrival paths disagree about the terminal.",
        )
    }

    @Test
    fun `the reconciler abort no longer repeats itself in its own message`() {
        val outcome = WaitUntilCompletion.Aborted(
            WaitUntilAbortCause.DurableRowAlreadyAborted,
        ).toStepOutcome() as StepOutcome.Failure

        assertEquals(
            "waitUntil aborted",
            outcome.failure.message,
            "Before E4b.4 this read `waitUntil aborted: waitUntil aborted`: the reconciler's only " +
                "construction site passed the literal as its reason and the message added the same " +
                "words as a prefix. The cause is now a value, so there is nothing to concatenate.",
        )
    }

    @Test
    fun `a body cancellation keeps the wording it always had`() {
        val outcome = WaitUntilCompletion.Aborted(
            WaitUntilAbortCause.BodyCancelled(CancellationReason.ParentCancelled),
        ).toStepOutcome() as StepOutcome.Failure

        assertEquals(
            "waitUntil cancelled: ParentCancelled",
            outcome.failure.message,
            "The fresh path interpolated the same CancellationReason enum into the same template, " +
                "so this string is byte-identical to the pre-ADT behaviour. If it moved, the " +
                "change was not part of E4b.4 and should be argued on its own.",
        )
    }

    @Test
    fun `a deadline quotes the attempt and the ceiling it actually reached`() {
        val outcome = WaitUntilCompletion.DeadlineExceeded(attempt = 7, ceilingMs = 2_500L)
            .toStepOutcome() as StepOutcome.Failure

        assertEquals(
            "waitUntil deadline exceeded at poll 7 (2500ms backoff ceiling)",
            outcome.failure.message,
        )
        assertEquals(
            FailureKind.TIMEOUT,
            outcome.failure.kind,
            "A deadline is a timeout. Deriving the kind from the case is the point: the kind is no " +
                "longer a second thing the call site has to agree with.",
        )
    }
}
