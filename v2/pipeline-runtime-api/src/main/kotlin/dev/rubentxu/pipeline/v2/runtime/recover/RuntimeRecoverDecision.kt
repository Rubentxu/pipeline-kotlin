package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.runtime.inspect.AttemptId
import dev.rubentxu.pipeline.v2.runtime.inspect.IntrospectionFailure
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation

/**
 * M2 — public pure decider the recover port composes.
 *
 * Returns a [RecoveryChoice] that the recover port interprets into a
 * [RecoverOutcome]. The decider is PURE: same inputs always yield the same
 * outputs; no I/O, no clock, no process id.
 *
 * The shape mirrors `PK-SPEC-02-RUNTIME-OBSERVATION.md` §"Modelo funcional".
 * `Reattach` / `ReuseTerminal` / `FailClosed` are the three cases the spec names;
 * this design adds the `AlreadyRecovered` case to make the idempotency check
 * observable from outside the implementation.
 *
 * The decider is consulted with a `JournalProof` snapshot — the journal rows for
 * the run + the cursor state — so it does NOT call the journal itself. The
 * caller is responsible for fetching this from
 * `OperationJournal.listForRun` + `ReplayCursorStore.load` and passing it in.
 *
 * @see M2_INSPECT_RECOVER_CANCEL_DESIGN.md §5.5.
 */
object RuntimeRecoverDecision {

    /**
     * Decide what to do with `observation` given the durable `journal` proof.
     *
     * The function is PURE and TOTAL: every (observation, journal) tuple maps to
     * exactly one [RecoveryChoice]. The contract test
     * `RuntimeRecoverDecisionTableFitnessTest` (§7.3) pins every cell of the
     * matrix.
     *
     * @param observation The run-level view from
     *   [dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPort.inspect].
     * @param journal     A `JournalProof` snapshot — the journal rows for the run
     *   + the cursor state. The decider does NOT call the journal; it reads from
     *   the proof the caller passes in.
     * @return A [RecoveryChoice] that the recover port interprets into a
     *   [RecoverOutcome].
     */
    fun decideRecovery(
        observation: RuntimeObservation,
        journal: JournalProof,
    ): RecoveryChoice = decide(observation, journal)

    /**
     * A snapshot of the journal state the decider needs.
     *
     * The caller is responsible for fetching this from
     * `OperationJournal.listForRun` + `ReplayCursorStore.load` and passing it in;
     * the decider does not call the journal itself.
     */
    data class JournalProof(
        val terminalRow: TerminalRow?,
        val replayCursor: ReplayCursor?,
        val operations: List<OperationSnapshot>,
        /**
         * The journal schema version. Used to pin the `JournalIncompatible`
         * recovery case. The M2 first cut ships the same version PK has shipped
         * since M3-R1 (`1`).
         */
        val journalSchemaVersion: String = CURRENT_JOURNAL_SCHEMA_VERSION,
    )

    /**
     * The terminal journal row, if any. `null` when the run is not terminal.
     */
    data class TerminalRow(
        val outcome: String,
        val terminalAtMs: Long,
    )

    /** Snapshot of one journaled operation; mirrors `DurableOperation`. */
    data class OperationSnapshot(
        val opId: String,
        val attempt: Int,
        val outcome: OperationOutcome?,
        val replayPolicy: String,
        val effects: List<String>,
    )

    /** Closed vocabulary of operation outcomes the decider recognises. */
    sealed interface OperationOutcome {
        data object Succeeded : OperationOutcome
        data object Failed : OperationOutcome
        data object Unstable : OperationOutcome
        data object Aborted : OperationOutcome
        data object Cancelled : OperationOutcome
        data object Running : OperationOutcome
        /** Any outcome not in the closed set. */
        data class Other(val raw: String) : OperationOutcome
    }

    /** Snapshot of the replay cursor; mirrors `ReplayCursorStore.load`. */
    data class ReplayCursor(
        val runId: String,
        val lastOpId: String,
        val stageIndex: Int,
        val savedAtMs: Long,
    )

    /**
     * The current journal schema version this PK can read.
     *
     * Bumping this constant is a contract-affecting change: a journal row whose
     * schema version is greater than this is rejected as
     * [RecoverRefusal.JournalIncompatible]. The M2 first cut ships the same
     * version PK has shipped since M3-R1 (`1`).
     */
    const val CURRENT_JOURNAL_SCHEMA_VERSION: String = "1"

    private fun decide(
        observation: RuntimeObservation,
        journal: JournalProof,
    ): RecoveryChoice {
        // P0: if the observation is itself Unobservable, the substrate cannot be
        // served. Mirror it as FailClosed(StorageError) so the port returns
        // RecoverOutcome.FailClosed.
        if (observation is RuntimeObservation.Unobservable) {
            return RecoveryChoice.FailClosed(
                RecoverRefusal.StorageError(renderIntrospectionFailure(observation.reason)),
            )
        }

        // P1: schema version mismatch (cross-version PK reading older journal is
        // unsupported).
        if (journal.journalSchemaVersion != CURRENT_JOURNAL_SCHEMA_VERSION) {
            return RecoveryChoice.FailClosed(
                RecoverRefusal.JournalIncompatible(journal.journalSchemaVersion),
            )
        }

        // P2: cancelled-run is terminal; there is nothing to recover.
        val terminalOutcome = journal.terminalRow?.outcome?.lowercase()
        if (terminalOutcome == "cancelled") {
            return RecoveryChoice.AlreadyRecovered(
                attempt = AttemptId(1),
                terminalAtMs = journal.terminalRow!!.terminalAtMs,
            )
        }

        // P3: terminal observation AND terminal row → the run is fully reconciled.
        if (observation is RuntimeObservation.Terminal && journal.terminalRow != null) {
            return RecoveryChoice.AlreadyRecovered(
                attempt = observation.attempt,
                terminalAtMs = journal.terminalRow.terminalAtMs,
            )
        }

        // P4: LiveButEmpty with an empty journal → substrate observed but yields
        // nothing recoverable.
        if (observation is RuntimeObservation.LiveButEmpty && journal.operations.isEmpty()) {
            return RecoveryChoice.FailClosed(
                RecoverRefusal.SubstrateUnavailable(observation.reason),
            )
        }

        // P5: Running with lease released AND operations all terminal in the
        // journal → reuse the journal's terminal observation. The lease was
        // released cleanly.
        if (observation is RuntimeObservation.Running && observation.leaseHolder == null) {
            val anyRunning = journal.operations.any { it.outcome is OperationOutcome.Running }
            if (!anyRunning && journal.terminalRow != null) {
                return RecoveryChoice.ReuseTerminal(
                    receipt = TerminalReceipt(
                        terminal = terminalOf(journal.terminalRow),
                        perOperationDecisions = buildDecisions(journal.operations),
                    ),
                )
            }
        }

        // P6: Terminal observation but journal terminalRow is null → the
        // observation is the source of truth; materialise from it.
        if (observation is RuntimeObservation.Terminal && journal.terminalRow == null) {
            return RecoveryChoice.ReuseTerminal(
                receipt = TerminalReceipt(
                    terminal = observation.terminal,
                    perOperationDecisions = buildDecisions(journal.operations),
                ),
            )
        }

        // P7: terminal row exists in journal, succeeded/failed/unstable, AND the
        // cursor advances → reuse the journal's terminal.
        if (journal.terminalRow != null &&
            (terminalOutcome == "succeeded" ||
                terminalOutcome == "failed" ||
                terminalOutcome == "unstable") &&
            journal.replayCursor != null
        ) {
            return RecoveryChoice.ReuseTerminal(
                receipt = TerminalReceipt(
                    terminal = terminalOf(journal.terminalRow),
                    perOperationDecisions = buildDecisions(journal.operations),
                ),
            )
        }

        // P8: Running with a live lease holder AND at least one RUNNING op in the
        // journal → reattach. The deadline is a 30 s offset that the recover
        // adapter may finalise at the call site.
        if (observation is RuntimeObservation.Running &&
            observation.leaseHolder != null &&
            observation.leaseHolder.alive != false
        ) {
            val anyRunning = journal.operations.any { it.outcome is OperationOutcome.Running }
            if (anyRunning) {
                return RecoveryChoice.Reattach(
                    attempt = observation.attempt,
                    deadlineMs = observation.leaseHolder.fencingToken + 30_000L,
                )
            }
        }

        // P9: Running with the lease holder marked dead (`alive == false`) → the
        // owner is gone but no terminal yet; treat as reattach with the standard
        // 30 s deadline. The decider is data-only and cannot call `clock.now()`,
        // so the deadline is the run-attempt-relative `fencingToken + 30_000L`
        // placeholder that the recover adapter finalises at the call site.
        if (observation is RuntimeObservation.Running && observation.leaseHolder?.alive == false) {
            return RecoveryChoice.Reattach(
                attempt = observation.attempt,
                deadlineMs = observation.leaseHolder.fencingToken + 30_000L,
            )
        }

        // P10: nothing else matches → fail closed.
        return RecoveryChoice.FailClosed(
            RecoverRefusal.SubstrateUnavailable(
                "no terminal row, no terminal observation, and no RUNNING operation to reattach to",
            ),
        )
    }

    private fun terminalOf(row: TerminalRow): TerminalObservation =
        when (row.outcome.lowercase()) {
            "success", "succeeded" -> TerminalObservation.Succeeded(row.terminalAtMs)
            "failed" -> TerminalObservation.Failed("failed", row.terminalAtMs)
            "unstable" -> TerminalObservation.Unstable(row.terminalAtMs)
            "aborted" -> TerminalObservation.Aborted(row.terminalAtMs)
            "cancelled" -> TerminalObservation.Cancelled(row.terminalAtMs)
            else -> TerminalObservation.Other(row.outcome, row.terminalAtMs)
        }

    private fun buildDecisions(
        operations: List<OperationSnapshot>,
    ): List<PerOperationDecision> = operations.map { op ->
        PerOperationDecision(
            opId = op.opId,
            replayDecision = when (op.outcome) {
                is OperationOutcome.Succeeded -> "SKIP"
                is OperationOutcome.Unstable -> "SKIP"
                is OperationOutcome.Failed -> "RERUN"
                is OperationOutcome.Aborted -> "ABORT"
                is OperationOutcome.Cancelled -> "ABORT"
                is OperationOutcome.Running -> "RERUN"
                is OperationOutcome.Other -> "RERUN"
                null -> "RERUN"
            },
        )
    }

    private fun renderIntrospectionFailure(reason: IntrospectionFailure): String =
        when (reason) {
            is IntrospectionFailure.NoControlRoot -> "NoControlRoot"
            is IntrospectionFailure.NoEventStore -> "NoEventStore"
            is IntrospectionFailure.NoOutputPlane -> "NoOutputPlane"
            is IntrospectionFailure.NoJournal -> "NoJournal"
            is IntrospectionFailure.StorageError -> "StorageError(${reason.cause})"
        }
}

/**
 * M2 — closed ADT returned by [RuntimeRecoverDecision.decideRecovery].
 *
 * Mirrors `PK-SPEC-02-RUNTIME-OBSERVATION.md` lines 29-33 plus an
 * `AlreadyRecovered` idempotency case.
 *
 * @see M2_INSPECT_RECOVER_CANCEL_DESIGN.md §5.5.
 */
sealed interface RecoveryChoice {

    /**
     * The substrate is still reattachable and no terminal has been observed.
     * The recover port returns [RecoverOutcome.ReattachPending] with the
     * deadline.
     */
    data class Reattach(
        val attempt: AttemptId,
        val deadlineMs: Long,
    ) : RecoveryChoice

    /**
     * The substrate was inspected and the terminal is the journal's own
     * observation; the recover port materialises the terminal and returns
     * [RecoverOutcome.RecoveredTerminal]. This is the ONLY case that does NOT
     * re-execute external effects (audit D.4).
     */
    data class ReuseTerminal(
        val receipt: TerminalReceipt,
    ) : RecoveryChoice

    /**
     * The recovery cannot proceed. The recover port returns
     * [RecoverOutcome.FailClosed] with the closed reason.
     */
    data class FailClosed(
        val cause: RecoverRefusal,
    ) : RecoveryChoice

    /**
     * The run was already recovered by a prior call. The recover port returns
     * [RecoverOutcome.AlreadyRecovered] without touching the substrate.
     */
    data class AlreadyRecovered(
        val attempt: AttemptId,
        val terminalAtMs: Long,
    ) : RecoveryChoice
}

/**
 * The typed terminal receipt the recover port materialises.
 *
 * Mirrors [TerminalObservation] but carries the original `ReplayDecision` per
 * operation so the audit trail is observable.
 */
data class TerminalReceipt(
    val terminal: TerminalObservation,
    val perOperationDecisions: List<PerOperationDecision>,
)

/** Per-operation decision the decider made. */
data class PerOperationDecision(
    val opId: String,
    val replayDecision: String,
)
