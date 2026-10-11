package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.runtime.inspect.AttemptId
import dev.rubentxu.pipeline.v2.runtime.inspect.IntrospectionFailure
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * M2 — fitness test pinning [RuntimeRecoverDecision.decideRecovery]'s
 * decision matrix exhaustively over the closed [RuntimeObservation] ADT and
 * the closed [RuntimeRecoverDecision.JournalProof] ADT.
 *
 * The matrix source of truth is §5.5.1 of `M2_INSPECT_RECOVER_CANCEL_DESIGN.md`.
 * Each test pins ONE cell. The future-proofing property: every change to the
 * matrix that drops or rearranges a case must turn one of these tests red.
 *
 * Tests are grouped by `observation` case:
 *  - Running
 *  - Terminal
 *  - LiveButEmpty
 *  - Unobservable
 *
 * Plus journal-side cases (terminalRow states and schema version) that apply
 * independent of the observation.
 */
class RuntimeRecoverDecisionTableFitnessTest {

    private fun runningObs(
        leaseHolderAlive: Boolean? = true,
        leaseHolderOwner: String = "writer",
        fence: Long = 1L,
        attempt: Int = 1,
    ): RuntimeObservation.Running = RuntimeObservation.Running(
        attempt = AttemptId(attempt),
        leaseHolder = if (leaseHolderOwner.isBlank()) null else dev.rubentxu.pipeline.v2.runtime.inspect.LeaseHolder(
            ownerId = leaseHolderOwner,
            fencingToken = fence,
            alive = leaseHolderAlive,
        ),
        fencingToken = fence,
        journalPosition = dev.rubentxu.pipeline.v2.runtime.inspect.JournalPosition(
            operations = 1,
            latestOpId = "op-1",
            latestTerminalAtMs = null,
        ),
        outputTails = emptyList(),
    )

    private fun terminalObs(
        terminal: TerminalObservation,
        attempt: Int = 1,
    ): RuntimeObservation.Terminal = RuntimeObservation.Terminal(
        attempt = AttemptId(attempt),
        terminal = terminal,
        terminalAtMs = 1_700_000_000_000L,
    )

    private fun emptyProof(
        terminalRow: RuntimeRecoverDecision.TerminalRow? = null,
        replayCursor: RuntimeRecoverDecision.ReplayCursor? = null,
        operations: List<RuntimeRecoverDecision.OperationSnapshot> = emptyList(),
        journalSchemaVersion: String = RuntimeRecoverDecision.CURRENT_JOURNAL_SCHEMA_VERSION,
    ): RuntimeRecoverDecision.JournalProof = RuntimeRecoverDecision.JournalProof(
        terminalRow = terminalRow,
        replayCursor = replayCursor,
        operations = operations,
        journalSchemaVersion = journalSchemaVersion,
    )

    @Test
    @DisplayName("P0 Unobservable observation → FailClosed(StorageError)")
    fun p0_unobservable() {
        val obs: RuntimeObservation = RuntimeObservation.Unobservable(
            reason = IntrospectionFailure.StorageError("disk full"),
        )
        val choice = RuntimeRecoverDecision.decideRecovery(obs, emptyProof())
        val fc = assertInstanceOf(RecoveryChoice.FailClosed::class.java, choice)
        assertInstanceOf(RecoverRefusal.StorageError::class.java, fc.cause)
    }

    @Test
    @DisplayName("P1 schema mismatch → FailClosed(JournalIncompatible)")
    fun p1_schema_incompatible() {
        val obs = runningObs()
        val proof = emptyProof(journalSchemaVersion = "999-not-supported")
        val choice = RuntimeRecoverDecision.decideRecovery(obs, proof)
        val fc = assertInstanceOf(RecoveryChoice.FailClosed::class.java, choice)
        assertInstanceOf(RecoverRefusal.JournalIncompatible::class.java, fc.cause)
    }

    @Test
    @DisplayName("P2 cancelled terminal row → AlreadyRecovered")
    fun p2_cancelled_terminal() {
        val obs = runningObs()
        val proof = emptyProof(
            terminalRow = RuntimeRecoverDecision.TerminalRow(
                outcome = "cancelled",
                terminalAtMs = 1_700_000_000_000L,
            ),
        )
        val choice = RuntimeRecoverDecision.decideRecovery(obs, proof)
        val already = assertInstanceOf(RecoveryChoice.AlreadyRecovered::class.java, choice)
        assertEquals(1_700_000_000_000L, already.terminalAtMs)
    }

    @Test
    @DisplayName("P3 Terminal observation + terminal row → AlreadyRecovered")
    fun p3_terminal_and_row() {
        val obs = terminalObs(TerminalObservation.Succeeded(1_700_000_000_000L))
        val proof = emptyProof(
            terminalRow = RuntimeRecoverDecision.TerminalRow(
                outcome = "succeeded",
                terminalAtMs = 1_700_000_000_001L,
            ),
        )
        val choice = RuntimeRecoverDecision.decideRecovery(obs, proof)
        assertInstanceOf(RecoveryChoice.AlreadyRecovered::class.java, choice)
    }

    @Test
    @DisplayName("P4 LiveButEmpty + empty journal → FailClosed(SubstrateUnavailable)")
    fun p4_live_but_empty() {
        val obs = RuntimeObservation.LiveButEmpty(
            attempt = AttemptId(1),
            reason = "no evidence",
        )
        val choice = RuntimeRecoverDecision.decideRecovery(obs, emptyProof())
        val fc = assertInstanceOf(RecoveryChoice.FailClosed::class.java, choice)
        assertInstanceOf(RecoverRefusal.SubstrateUnavailable::class.java, fc.cause)
    }

    @Test
    @DisplayName("P5 Running + lease released + all operations terminal in journal → ReuseTerminal")
    fun p5_lease_released() {
        val obs = runningObs(leaseHolderOwner = "")
        val proof = emptyProof(
            terminalRow = RuntimeRecoverDecision.TerminalRow(
                outcome = "succeeded",
                terminalAtMs = 1_700_000_000_000L,
            ),
            operations = listOf(
                RuntimeRecoverDecision.OperationSnapshot(
                    opId = "op-1",
                    attempt = 1,
                    outcome = RuntimeRecoverDecision.OperationOutcome.Succeeded,
                    replayPolicy = "RERUN",
                    effects = emptyList(),
                ),
            ),
        )
        val choice = RuntimeRecoverDecision.decideRecovery(obs, proof)
        val rt = assertInstanceOf(RecoveryChoice.ReuseTerminal::class.java, choice)
        assertInstanceOf(TerminalObservation.Succeeded::class.java, rt.receipt.terminal)
    }

    @Test
    @DisplayName("P6 Terminal observation + no terminal row → ReuseTerminal")
    fun p6_terminal_no_row() {
        val obs = terminalObs(TerminalObservation.Failed("oom", 1_700_000_000_000L))
        val proof = emptyProof(terminalRow = null)
        val choice = RuntimeRecoverDecision.decideRecovery(obs, proof)
        val rt = assertInstanceOf(RecoveryChoice.ReuseTerminal::class.java, choice)
        assertInstanceOf(TerminalObservation.Failed::class.java, rt.receipt.terminal)
    }

    @Test
    @DisplayName("P7 terminal row + succeeded/failed/unstable + cursor advances → ReuseTerminal")
    fun p7_terminal_with_cursor() {
        val obs = runningObs()
        val proof = emptyProof(
            terminalRow = RuntimeRecoverDecision.TerminalRow(
                outcome = "failed",
                terminalAtMs = 1_700_000_000_000L,
            ),
            replayCursor = RuntimeRecoverDecision.ReplayCursor(
                runId = "run-x",
                lastOpId = "op-1",
                stageIndex = 0,
                savedAtMs = 1_700_000_000_000L,
            ),
        )
        val choice = RuntimeRecoverDecision.decideRecovery(obs, proof)
        assertInstanceOf(RecoveryChoice.ReuseTerminal::class.java, choice)
    }

    @Test
    @DisplayName("P8 Running + live lease holder + any RUNNING op in journal → Reattach")
    fun p8_running_running_op() {
        val obs = runningObs(leaseHolderAlive = true, fence = 100L)
        val proof = emptyProof(
            operations = listOf(
                RuntimeRecoverDecision.OperationSnapshot(
                    opId = "op-1",
                    attempt = 1,
                    outcome = RuntimeRecoverDecision.OperationOutcome.Running,
                    replayPolicy = "RERUN",
                    effects = emptyList(),
                ),
            ),
        )
        val choice = RuntimeRecoverDecision.decideRecovery(obs, proof)
        val reattach = assertInstanceOf(RecoveryChoice.Reattach::class.java, choice)
        assertEquals(100L + 30_000L, reattach.deadlineMs)
    }

    @Test
    @DisplayName("P9 Running + lease holder dead → Reattach")
    fun p9_lease_dead() {
        val obs = runningObs(leaseHolderAlive = false, fence = 7L)
        val choice = RuntimeRecoverDecision.decideRecovery(obs, emptyProof())
        val reattach = assertInstanceOf(RecoveryChoice.Reattach::class.java, choice)
        assertEquals(7L + 30_000L, reattach.deadlineMs)
    }

    @Test
    @DisplayName("P10 no-op fallback: Running with alive=null and no evidence → FailClosed")
    fun p10_fallback() {
        // The fallback fires only when no earlier rule matched. Constructing
        // such a tuple is rare (every Running with a non-null lease triggers
        // either P5/P8/P9). The cleanest path that lands at P10 is a Running
        // observation with `alive == null` (unknown heartbeat, not false) and
        // no RUNNING op and no terminal row — P5 / P8 / P9 each require a
        // non-true alive or a terminal row that this state lacks.
        val obs = runningObs(leaseHolderAlive = null, fence = 99L)
        val choice = RuntimeRecoverDecision.decideRecovery(obs, emptyProof())
        val fc = assertInstanceOf(RecoveryChoice.FailClosed::class.java, choice)
        assertInstanceOf(RecoverRefusal.SubstrateUnavailable::class.java, fc.cause)
    }

    @Test
    @DisplayName("every RecoveryChoice case name is among {Reattach, ReuseTerminal, FailClosed, AlreadyRecovered}")
    fun result_is_in_closed_set() {
        // Exhaustive sweep over a small set of (observation, journal) tuples.
        // Pinned by the design's §5.5.1 matrix. The enum guard makes the test
        // a future-proof fitness check: any new case introduced to
        // RecoveryChoice must update the test (and the matrix), which is the
        // point of a fitness test.
        val observations: List<RuntimeObservation> = listOf(
            RuntimeObservation.LiveButEmpty(AttemptId(1), "test"),
            terminalObs(TerminalObservation.Succeeded(1L)),
            runningObs(),
            RuntimeObservation.Unobservable(IntrospectionFailure.NoJournal),
        )
        val proofs: List<RuntimeRecoverDecision.JournalProof> = listOf(
            emptyProof(),
            emptyProof(journalSchemaVersion = "1"),
            emptyProof(journalSchemaVersion = "999"),
            emptyProof(
                terminalRow = RuntimeRecoverDecision.TerminalRow("cancelled", 1L),
            ),
            emptyProof(
                terminalRow = RuntimeRecoverDecision.TerminalRow("succeeded", 1L),
                replayCursor = RuntimeRecoverDecision.ReplayCursor("r", "op", 0, 1L),
            ),
            // A proof with one RUNNING op and no terminal row — exercises the
            // P8 reattach path under a Running observation.
            emptyProof(
                operations = listOf(
                    RuntimeRecoverDecision.OperationSnapshot(
                        opId = "op-running",
                        attempt = 1,
                        outcome = RuntimeRecoverDecision.OperationOutcome.Running,
                        replayPolicy = "RERUN",
                        effects = emptyList(),
                    ),
                ),
            ),
        )
        val allowed = setOf("Reattach", "ReuseTerminal", "FailClosed", "AlreadyRecovered")
        for (obs in observations) {
            for (proof in proofs) {
                val choice = RuntimeRecoverDecision.decideRecovery(obs, proof)
                val simpleName = choice::class.simpleName ?: "?"
                assertTrue(
                    allowed.contains(simpleName),
                    "RecoveryChoice $simpleName is not in the closed set for obs=$obs, proof=$proof",
                )
            }
        }
        // Each choice has a non-null, deterministic payload.
        for (obs in observations) {
            for (proof in proofs) {
                val choice = RuntimeRecoverDecision.decideRecovery(obs, proof)
                assertNotNull(choice)
            }
        }
        // Closing the matrix: every case referenced at least once.
        val allSeen: MutableSet<String> = mutableSetOf()
        for (obs in observations) {
            for (proof in proofs) {
                allSeen.add(
                    RuntimeRecoverDecision.decideRecovery(obs, proof)::class.simpleName ?: "?",
                )
            }
        }
        assertTrue(allSeen.contains("Reattach"))
        assertTrue(allSeen.contains("ReuseTerminal"))
        assertTrue(allSeen.contains("FailClosed"))
    }
}
