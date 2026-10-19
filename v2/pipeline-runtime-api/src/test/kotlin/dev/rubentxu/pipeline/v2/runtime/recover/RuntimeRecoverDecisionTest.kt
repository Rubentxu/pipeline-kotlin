package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.domain.AttemptId
import dev.rubentxu.pipeline.v2.runtime.inspect.IntrospectionFailure
import dev.rubentxu.pipeline.v2.runtime.inspect.JournalPosition
import dev.rubentxu.pipeline.v2.runtime.inspect.LeaseHolder
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test


/**
 * Recover is the one verb that answers "what happened to that run?" after the fact, so every branch
 * of this decider is a claim about a run the operator cannot see any more. Two of them are dangerous
 * in opposite directions:
 *
 *  - `Reattach` re-attaches to a run it believes is still live. Being wrong about that means the
 *    operator believes a dead run is progressing.
 *  - `ReuseTerminal` serves a journalled terminal receipt instead of re-reading the substrate.
 *    Being wrong about that means the operator is shown a result for work that did not happen.
 *
 * Both are decided here, from two snapshots, with no clock and no process. The decider is TOTAL:
 * every (observation, journal) pair yields exactly one [RecoveryChoice], and the last one is a
 * refusal rather than a guess.
 *
 * The precedence is the design. Reading the rules one at a time gives a different table, because
 * several of them match the same input and only the ORDER says which one was meant. So each test
 * below is named for the branch it pins, and the branches are the source's own P-numbering rather
 * than a re-derivation.
 */
class RuntimeRecoverDecisionTest {

    private fun decide(observation: RuntimeObservation, journal: RuntimeRecoverDecision.JournalProof) =
        RuntimeRecoverDecision.decideRecovery(observation, journal)

    // --- fixtures ---------------------------------------------------------------

    private fun running(
        attempt: Int = 1,
        lease: LeaseHolder? = LeaseHolder("worker-a", fencingToken = 100L, alive = true),
        fencingToken: Long = 100L,
    ) = RuntimeObservation.Running(
        attempt = AttemptId(attempt),
        leaseHolder = lease,
        fencingToken = fencingToken,
        journalPosition = JournalPosition(operations = 0, latestOpId = null, latestTerminalAtMs = null),
        outputTails = emptyList(),
    )

    private fun terminal(attempt: Int = 1, atMs: Long = 5_000L) =
        RuntimeObservation.Terminal(AttemptId(attempt), TerminalObservation.Succeeded(atMs), atMs)

    private fun proof(
        terminalRow: RuntimeRecoverDecision.TerminalRow? = null,
        cursor: RuntimeRecoverDecision.ReplayCursor? = null,
        operations: List<RuntimeRecoverDecision.OperationSnapshot> = emptyList(),
        schemaVersion: String = RuntimeRecoverDecision.CURRENT_JOURNAL_SCHEMA_VERSION,
    ) = RuntimeRecoverDecision.JournalProof(terminalRow, cursor, operations, schemaVersion)

    private fun op(
        opId: String,
        outcome: RuntimeRecoverDecision.OperationOutcome? = RuntimeRecoverDecision.OperationOutcome.Succeeded,
    ) = RuntimeRecoverDecision.OperationSnapshot(opId, attempt = 1, outcome = outcome, replayPolicy = "MEMOIZED", effects = listOf("READ_ONLY"))

    private fun row(outcome: String, atMs: Long = 9_000L) = RuntimeRecoverDecision.TerminalRow(outcome, atMs)

    // --- P0: the substrate could not be inspected -------------------------------

    @Test
    fun `P0 an unobservable substrate fails closed with its own reason`() {
        val choice = decide(
            RuntimeObservation.Unobservable(IntrospectionFailure.NoJournal),
            proof(terminalRow = row("succeeded")),
        )

        // Even with a terminal row in hand. The row cannot be read on its own — it is a snapshot the
        // caller could not fetch — so trusting it here would be recovering a run nobody could see.
        val failed = choice as RecoveryChoice.FailClosed
        assertEquals(RecoverRefusal.StorageError("NoJournal"), failed.cause)
    }

    @Test
    fun `P0 renders every introspection reason, and a storage error keeps its cause`() {
        val rendered = listOf(
            IntrospectionFailure.NoControlRoot,
            IntrospectionFailure.NoEventStore,
            IntrospectionFailure.NoOutputPlane,
            IntrospectionFailure.NoJournal,
            IntrospectionFailure.StorageError("disk gone"),
        ).map { reason ->
            (decide(RuntimeObservation.Unobservable(reason), proof()) as RecoveryChoice.FailClosed).cause
        }

        assertEquals(
            listOf(
                RecoverRefusal.StorageError("NoControlRoot"),
                RecoverRefusal.StorageError("NoEventStore"),
                RecoverRefusal.StorageError("NoOutputPlane"),
                RecoverRefusal.StorageError("NoJournal"),
                RecoverRefusal.StorageError("StorageError(disk gone)"),
            ),
            rendered,
            "a refusal the operator cannot read is a refusal the operator cannot act on",
        )
    }

    // --- P1: schema version ----------------------------------------------------

    @Test
    fun `P1 a journal written by another schema version is refused, not guessed at`() {
        val choice = decide(terminal(), proof(schemaVersion = "2", terminalRow = row("succeeded")))

        val failed = choice as RecoveryChoice.FailClosed
        assertEquals(RecoverRefusal.JournalIncompatible("2"), failed.cause)
    }

    @Test
    fun `P1 outranks the cancelled rule`() {
        // Ordering stated, not assumed: a mismatched schema with a CANCELLED row still refuses.
        // Reading this rule after the terminal ones would report the run as already recovered from
        // rows this version cannot interpret.
        val choice = decide(terminal(), proof(schemaVersion = "99", terminalRow = row("cancelled")))

        assertTrue(choice is RecoveryChoice.FailClosed, "got $choice")
    }

    // --- P2: a cancelled run is terminal ----------------------------------------

    @Test
    fun `P2 a cancelled terminal row is already recovered, whoever observed what`() {
        val choice = decide(running(), proof(terminalRow = row("cancelled")))

        val recovered = choice as RecoveryChoice.AlreadyRecovered
        assertEquals(9_000L, recovered.terminalAtMs, "the timestamp is the journal's, not the caller's")
    }

    @Test
    fun `P2 the cancelled rule reports attempt one whatever the observed attempt was`() {
        // The source hard-codes `AttemptId(1)` here, while P3 reports the OBSERVED attempt for the
        // same `AlreadyRecovered` case. Those two rows disagree, and this pins the disagreement
        // rather than hiding it: a fifth attempt that was cancelled is reported as attempt one.
        //
        // It is pinned, not endorsed. The authority that would settle which is right is the design
        // Markdown this repository deleted, so the honest state is "observed, unverified". If this
        // value is changed, the diff IS the decision, and the roadmap entry should move with it.
        val fifth = running(attempt = 5)

        assertEquals(AttemptId(1), (decide(fifth, proof(terminalRow = row("cancelled"))) as RecoveryChoice.AlreadyRecovered).attempt)
    }

    @Test
    fun `P3 a terminal observation with a terminal row is reconciled`() {
        val choice = decide(terminal(attempt = 4, atMs = 1_234L), proof(terminalRow = row("succeeded", atMs = 9_999L)))

        val recovered = choice as RecoveryChoice.AlreadyRecovered
        assertEquals(AttemptId(4), recovered.attempt, "here the observed attempt IS reported")
        assertEquals(9_999L, recovered.terminalAtMs, "the JOURNAL row is the terminal authority")
    }

    // --- P4: known run, nothing to recover from ---------------------------------

    @Test
    fun `P4 a live-but-empty substrate with an empty journal cannot be served`() {
        val empty = RuntimeObservation.LiveButEmpty(AttemptId(1), "no events yet")

        val failed = decide(empty, proof()) as RecoveryChoice.FailClosed
        assertEquals(RecoverRefusal.SubstrateUnavailable("no events yet"), failed.cause)
    }

    @Test
    fun `P4 LiveButEmpty with journalled operations is not this row`() {
        // The contrast that makes P4 mean something: the same observation with a non-empty journal
        // falls through to the refusal at the END, whose cause names a different fact.
        val empty = RuntimeObservation.LiveButEmpty(AttemptId(1), "no events yet")

        val failed = decide(empty, proof(operations = listOf(op("op-1")))) as RecoveryChoice.FailClosed
        assertEquals(
            RecoverRefusal.SubstrateUnavailable("no terminal row, no terminal observation, and no RUNNING operation to reattach to"),
            failed.cause,
        )
    }

    // --- P5: a released lease with a reconciled journal -------------------------

    @Test
    fun `P5 a running run whose lease was released and whose journal is settled reuses the journal`() {
        val choice = decide(running(lease = null), proof(terminalRow = row("failed")))

        val reuse = choice as RecoveryChoice.ReuseTerminal
        assertEquals(TerminalObservation.Failed("failed", 9_000L), reuse.receipt.terminal)
    }

    @Test
    fun `P5 an operation still RUNNING in the journal is not a settled journal`() {
        // The lease was released but the journal says an operation is in flight. Serving the terminal
        // here would abandon that operation, so this must NOT be a reuse.
        val inFlight = op("op-2", RuntimeRecoverDecision.OperationOutcome.Running)

        val choice = decide(running(lease = null), proof(terminalRow = row("failed"), operations = listOf(inFlight)))

        val failed = choice as RecoveryChoice.FailClosed
        assertEquals(
            RecoverRefusal.SubstrateUnavailable(
                "no terminal row, no terminal observation, and no RUNNING operation to reattach to",
            ),
            failed.cause,
            "a settled lease is not a settled journal; serving the terminal here abandons op-2",
        )
    }

    // --- P6: the observation is the only terminal there is ----------------------

    @Test
    fun `P6 a terminal observation with no journal row materialises from the observation`() {
        val choice = decide(terminal(attempt = 3, atMs = 7_777L), proof())

        val reuse = choice as RecoveryChoice.ReuseTerminal
        assertEquals(TerminalObservation.Succeeded(7_777L), reuse.receipt.terminal)
    }

    // --- P7: a settled journal row plus a cursor -------------------------------

    @Test
    fun `P7 a settled row with a replay cursor is reused`() {
        val cursor = RuntimeRecoverDecision.ReplayCursor("run-1", lastOpId = "op-9", stageIndex = 2, savedAtMs = 8_000L)

        // The row's OWN outcome is what reaches the operator, translated by the closed map. Reading
        // the terminal from the OBSERVATION instead would report "running" for a settled run.
        val expected = mapOf(
            "succeeded" to TerminalObservation.Succeeded(9_000L),
            "failed" to TerminalObservation.Failed("failed", 9_000L),
            "unstable" to TerminalObservation.Unstable(9_000L),
        )

        for ((outcome, terminalState) in expected) {
            val reuse = decide(running(), proof(terminalRow = row(outcome), cursor = cursor))
                as RecoveryChoice.ReuseTerminal

            assertEquals(terminalState, reuse.receipt.terminal, "journal outcome \"$outcome\"")
        }
    }

    @Test
    fun `P7 the same row without a cursor is not this row`() {
        // The cursor is the half of the proof that says the row was actually reached, not inferred.
        val choice = decide(running(), proof(terminalRow = row("succeeded"), cursor = null))

        assertTrue(choice !is RecoveryChoice.ReuseTerminal, "got $choice")
    }

    // --- P8: reattach to a live owner -------------------------------------------

    @Test
    fun `P8 a live lease with an operation in flight reattaches`() {
        val inFlight = op("op-2", RuntimeRecoverDecision.OperationOutcome.Running)

        val choice = decide(running(attempt = 2), proof(operations = listOf(inFlight)))

        val reattach = choice as RecoveryChoice.Reattach
        assertEquals(AttemptId(2), reattach.attempt)
        assertEquals(100L + 30_000L, reattach.deadlineMs, "the deadline is the lease token offset, and the adapter finalises it")
    }

    @Test
    fun `P8 an unknown liveness is treated as live`() {
        // `alive == null` is not `false`: nobody proved the owner is gone, so re-attaching is the
        // recoverable choice. Refusing instead would strand a run whose owner stopped heartbeating.
        val unknown = LeaseHolder("worker-a", fencingToken = 100L, alive = null)

        val choice = decide(
            running(lease = unknown),
            proof(operations = listOf(op("op-2", RuntimeRecoverDecision.OperationOutcome.Running))),
        )

        assertTrue(choice is RecoveryChoice.Reattach, "got $choice")
    }

    // --- P9: a dead owner -------------------------------------------------------

    @Test
    fun `P9 a lease whose owner is known dead reattaches without a RUNNING operation`() {
        val dead = LeaseHolder("worker-a", fencingToken = 42L, alive = false)

        val reattach = decide(running(lease = dead), proof()) as RecoveryChoice.Reattach

        assertEquals(42L + 30_000L, reattach.deadlineMs)
    }

    // --- P10: nothing matched ---------------------------------------------------

    @Test
    fun `P10 an input that matches nothing fails closed rather than guessing`() {
        val choice = decide(running(), proof())

        val failed = choice as RecoveryChoice.FailClosed
        assertEquals(
            RecoverRefusal.SubstrateUnavailable(
                "no terminal row, no terminal observation, and no RUNNING operation to reattach to",
            ),
            failed.cause,
        )
    }

    @Test
    fun `P10 the refusal is total, not a fallthrough for the rows that were forgotten`() {
        // Two more pairs that a rule added later could plausibly claim. Both must still land on the
        // final refusal, which is the only place a new rule may NOT be inserted without a test.
        val deadLeaseNoWork = running(lease = LeaseHolder("w", 1L, alive = false))
        val liveLeaseNoWork = running(lease = LeaseHolder("w", 1L, alive = true))

        assertTrue(decide(deadLeaseNoWork, proof()) is RecoveryChoice.Reattach)
        assertTrue(decide(liveLeaseNoWork, proof()) is RecoveryChoice.FailClosed)
    }

    // --- the receipt's per-operation map ----------------------------------------

    @Test
    fun `every operation outcome maps to exactly one replay decision`() {
        val operations = listOf(
            op("succeeded", RuntimeRecoverDecision.OperationOutcome.Succeeded),
            op("unstable", RuntimeRecoverDecision.OperationOutcome.Unstable),
            op("failed", RuntimeRecoverDecision.OperationOutcome.Failed),
            op("aborted", RuntimeRecoverDecision.OperationOutcome.Aborted),
            op("cancelled", RuntimeRecoverDecision.OperationOutcome.Cancelled),
            op("running", RuntimeRecoverDecision.OperationOutcome.Running),
            op("weird", RuntimeRecoverDecision.OperationOutcome.Other("TIMED_OUT")),
            op("absent", null),
        )

        val receipt = TerminalReceipt(
            TerminalObservation.Succeeded(1L),
            listOf(
                PerOperationDecision("succeeded", "SKIP"),
                PerOperationDecision("unstable", "SKIP"),
                PerOperationDecision("failed", "RERUN"),
                PerOperationDecision("aborted", "ABORT"),
                PerOperationDecision("cancelled", "ABORT"),
                PerOperationDecision("running", "RERUN"),
                PerOperationDecision("weird", "RERUN"),
                PerOperationDecision("absent", "RERUN"),
            ),
        )

        // A terminal observation with no journal row (P6) is the shortest path to a receipt, so this
        // test measures the MAP and not the rule that happened to reach it. The P5 route would not:
        // one of these operations is RUNNING, which makes the journal unsettled and sends P5 onward.
        val reuse = decide(
            terminal(attempt = 3),
            proof(operations = operations),
        ) as RecoveryChoice.ReuseTerminal

        // Written as the expected list so an operation ADDED above without a decision here fails,
        // rather than appearing silently in the receipt.
        assertEquals(receipt.perOperationDecisions, reuse.receipt.perOperationDecisions)
    }

    @Test
    fun `an unrecognised journal outcome is carried, not flattened`() {
        val reuse = decide(running(lease = null), proof(terminalRow = row("TIMED_OUT"))) as RecoveryChoice.ReuseTerminal

        assertEquals(
            TerminalObservation.Other("TIMED_OUT", 9_000L),
            reuse.receipt.terminal,
            "an outcome this version does not know must reach the operator as itself",
        )
    }
}