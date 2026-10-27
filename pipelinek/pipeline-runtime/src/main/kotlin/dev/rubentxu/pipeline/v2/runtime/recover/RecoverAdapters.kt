package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.RunOutcomeReducer
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.durable.LeaseAcquisition
import dev.rubentxu.pipeline.v2.events.durable.LeaseLiveness
import dev.rubentxu.pipeline.v2.events.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursor
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.RunLeasePort
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputPin
import dev.rubentxu.pipeline.v2.output.OutputPinPort
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.OutputRecoveryPort
import dev.rubentxu.pipeline.v2.domain.AttemptId
import dev.rubentxu.pipeline.v2.runtime.control.CancelReceipt
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * How long a re-attach is offered for, measured from the moment the recover
 * answers.
 *
 * It is a WINDOW, not an instant: the caller may come back until then.
 */
private const val REATTACH_WINDOW_MS = 30_000L

/**
 * What the substrate says about a run — or the refusal that stops us from
 * knowing.
 *
 * The distinction is the point. "The run has no cursor" and "the cursor could
 * not be read" are opposites, and the recover decides the run's fate on the
 * difference. Collapsing them into one nullable cursor turned a broken
 * substrate into a confident verdict.
 */
private sealed interface RecoverEvidence {

    /** The facts, all of them read. */
    data class Ready(
        val observation: RuntimeObservation,
        val proof: RuntimeRecoverDecision.JournalProof,
        val journalOperations: List<DurableOperation>,
        val cursor: ReplayCursor?,
    ) : RecoverEvidence

    /** The substrate could not be read, and says why in a closed vocabulary. */
    data class Refused(val reason: RecoverRefusal) : RecoverEvidence
}

/**
 * The narrow port the recover adapter uses to ask "what does this run look
 * like right now?". It exists so the recover adapter does not depend on the
 * concrete `RuntimeIntrospectionPortStoreAdapter` type and so the test can
 * substitute a stub introspect without instantiating the storage stack.
 * Production wiring is `::inspect` against the live
 * `RuntimeIntrospectionPortStoreAdapter`; tests may pass a hand-rolled
 * lambda.
 * Declared BEFORE the class so the class can use it as a constructor parameter
 * type without a self-import dance.
*/
fun interface RuntimeRecoverPortIntrospect {
    fun inspect(runId: String): RuntimeIntrospectionResult
}

/**
 * Production [RuntimeRecoverPort] adapter backed by the existing journal,
 * cursor, lease, and output-recovery authorities.
 * ## What this adapter is and is not
 * The adapter is a thin composition: it does NOT introduce a new lease, a new
 * fencing scheme, a new journal schema, a new output store, or a new scheduler.
 * Every authority it composes is named below.
 * The adapter consults the existing [RuntimeIntrospectionPort] to get the typed
 * observation, fetches the journal's terminal row + cursor via the existing
 * `OperationJournal.listForRun` + `ReplayCursorStore.load`, hands the proof to
 * the pure [RuntimeRecoverDecision.decideRecovery] decider, and dispatches the
 * returned [RecoveryChoice] into one of the four [RecoverOutcome] cases.
 * ## No-rerun invariant (audit D.4)
 * The adapter NEVER re-executes a step's `execute()`. It materialises the
 * terminal row by writing it through the existing `OperationJournal.append`
 * (which is the journal's own observation, not a side-effecting re-run) and
 * reconciles the output store via the existing `OutputRecoveryPort.recover` +
 * `OutputFrameIndex.recoverUnframedBytes` pair. The unit test
 * `RuntimeRecoverPortStoreAdapterTest` pins the invariant.
*/
class RuntimeRecoverPortStoreAdapter(
    private val journal: OperationJournal,
    private val cursors: ReplayCursorStore,
    private val lease: RunLeasePort,
    private val outputRecovery: OutputRecoveryPort,
    private val frames: OutputFrameIndex,
    private val introspect: RuntimeRecoverPortIntrospect,
    private val clock: Clock,
    /**
     * Optional pin port consulted by [recover] before returning
     * `RecoveredTerminal`. When `null` (default), no pin check runs and
     * The recover adapter behaves exactly as it did in
     * The auditD.4 PARTIAL verdict flagged that the recover port is
     * Silent on pins; closes this with a consult that returns
     * [RecoverRefusal.PinnedBytesOutsideRecoveredRegion] when an active
     * pin covers bytes the run owns.
*/
    private val pinPort: OutputPinPort? = null,
    private val syntheticObserverId: String = "m2-observer",
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : RuntimeRecoverPort {

    // recover composes four contract surfaces — lease boundary, introspection,
    // journal proof, and cursor replay — in a strict order; each stage's failure
    // mode short-circuits to FailClosed with a typed refusal. Refactoring in this
    // cycle would require re-deriving the same ordering inside extracted helpers
    // without gaining isolation. The complexity and length are intrinsic to the
    // four-stage contract.
    override fun recover(runId: String, options: RecoverOptions): RecoverOutcome {
        val owner: RunOwnerId = RunOwnerId.of(syntheticObserverId)
            ?: error("invalid syntheticObserverId $syntheticObserverId")

        // A dry run computes the decision and writes NOTHING — and the lease
        // is not an exception to that. Taking it IS a write: it locks the run
        // and stamps a durable ownership record. A dry run that held the lease
        // would also make a live writer look busy for as long as it lasted.
        if (options.dryRun) {
            return decideWithoutWriting(runId)
        }

        // 1. TAKE the run's lease. The lock behind it is the only authority on
        //    "is a live process publishing this run".
        //
        //    It used to CONSULT the lease record and decide with the pure
        //    decider, and that was the same defect section 2.20 of the ROADMAP
        //    fixed in the cancel adapter: a record left behind by a killed
        //    writer still says its owner is alive, so recovering a DEAD run
        //    concluded the run was busy and refused. The whole point of
        //    recovering a run is that nobody is working on it.
        //
        //    The lease is held for the whole call and given back at the end,
        //    so a recover that refused and a recover that committed both leave
        //    the run closable.
        val acquisition = try {
            lease.acquire(LeaseRequest(runId = runId, ownerId = owner))
        } catch (e: Exception) {
            return RecoverOutcome.FailClosed(
                RecoverRefusal.StorageError(e.shortDiagnostic()),
            )
        }
        when (acquisition) {
            is LeaseAcquisition.AlreadyOwned -> {
                return RecoverOutcome.FailClosed(
                    RecoverRefusal.LeaseHeldByAnother(
                        "run $runId is held by ${acquisition.heldBy.value}",
                    ),
                )
            }

            is LeaseAcquisition.Unverifiable -> {
                return RecoverOutcome.FailClosed(
                    RecoverRefusal.StorageError(acquisition.reason),
                )
            }

            else -> { /* Acquired / Reentered / TakenOver — the run is ours now. */ }
        }

        try {
        // 2. Read the substrate and build the journal proof. Both are reads,
        //    and each has exactly two answers: the facts, or a typed refusal.
        val evidence = evidenceFor(runId)
        if (evidence is RecoverEvidence.Refused) {
            return RecoverOutcome.FailClosed(evidence.reason)
        }
        val ready = evidence as RecoverEvidence.Ready
        val observation: RuntimeObservation = ready.observation

        // 3. Decide.
        val decision = RuntimeRecoverDecision.decideRecovery(observation, ready.proof)

        // 4. Dispatch.
        return applyDecision(runId, decision, ready, writesToSubstrate = true)
        } finally {
            lease.release(owner)
        }
    }

    /**
     * The dry run: the decision the recovery would take, without any of its
     * writes.
     *
     * The dry run asks "what would happen?", so it must leave nothing behind
     * AND it must answer what the recovery answers.
     *
     * ## The live question comes first
     * The recovery's first act is to take the lease, and taking it is what
     * proves that no live process is publishing the run. A dry run cannot take
     * it — that is a write — so it ASKS the same question of the kernel
     * through [RunLeasePort.liveness], which holds the lock for a moment and
     * gives it straight back.
     *
     * The answer is the recovery's answer: a run a live process is publishing
     * is refused with [RecoverRefusal.LeaseHeldByAnother], and a question the
     * kernel could not answer is refused as a storage error. A dry run that
     * answered "reattach" for a run another process is working on would send
     * the operator to a plan the recovery would refuse to execute.
     *
     * ## The lease the dry run reads
     * The recovery reads a lease that names ITSELF, alive, because it took it.
     * The dry run reads the same view through [LeaseAsAcquired], which
     * presents the record the acquisition WOULD have left — so both decide on
     * the same facts, and neither has to guess what the other would have seen.
     */
    private fun decideWithoutWriting(runId: String): RecoverOutcome {
        when (val liveness = lease.liveness(runId)) {
            is LeaseLiveness.Held -> return RecoverOutcome.FailClosed(
                RecoverRefusal.LeaseHeldByAnother(
                    "run $runId is held by ${liveness.heldBy.value}",
                ),
            )

            is LeaseLiveness.Unverifiable -> return RecoverOutcome.FailClosed(
                RecoverRefusal.StorageError(liveness.reason),
            )

            LeaseLiveness.Free -> { /* Nobody is publishing this run. */
            }
        }

        val evidence = evidenceFor(runId)
        if (evidence is RecoverEvidence.Refused) {
            return RecoverOutcome.FailClosed(evidence.reason)
        }
        val ready = evidence as RecoverEvidence.Ready
        val decision = RuntimeRecoverDecision.decideRecovery(ready.observation, ready.proof)
        return applyDecision(runId, decision, ready, writesToSubstrate = false)
    }

    /**
     * What the substrate says about a run — or the refusal that stops us from
     * knowing.
     *
     * Every read below has exactly two answers: the facts, or a typed
     * refusal. Nothing here decides anything: the decision belongs to the
     * published decider, and it happens on the other side of this boundary.
     */
    private fun evidenceFor(runId: String): RecoverEvidence {
        val observationResult = try {
            introspect.inspect(runId)
        } catch (e: Exception) {
            return RecoverEvidence.Refused(RecoverRefusal.StorageError(e.shortDiagnostic()))
        }
        val observation: RuntimeObservation = when (observationResult) {
            is RuntimeIntrospectionResult.Observation -> observationResult.observation
            is RuntimeIntrospectionResult.Refused -> {
                return RecoverEvidence.Refused(
                    RecoverRefusal.SubstrateUnavailable(observationResult.refusal.toString()),
                )
            }
        }

        val journalOps: List<DurableOperation> = try {
            journal.listForRun(runId)
        } catch (e: Exception) {
            return RecoverEvidence.Refused(RecoverRefusal.StorageError(e.shortDiagnostic()))
        }

        // THE RUN'S TERMINAL OUTCOME, not an operation's.
        //
        // It used to be the FIRST operation row whose status is terminal, and
        // that is a fact about an OPERATION. The published decider asks for
        // "the journal's terminal row" and takes it as the run's outcome, so a
        // first-terminal-row answer lets a HALF-FINISHED run look finished:
        // its first step succeeded, and the decider would close the run as
        // successful while another step was still running.
        //
        // The run's own answer is the verdict the DOMAIN computes over ALL of
        // the run's work, and only once all of that work finished. While an
        // operation is still running, the run has no terminal outcome, and the
        // proof says so instead of inventing one.
        val terminalRow: RuntimeRecoverDecision.TerminalRow? = runTerminalRowOf(runId, journalOps)

        // A cursor that cannot be READ is a storage failure. Only a cursor
        // that does not exist is an absent cursor, and the two decide the
        // run's fate in opposite ways: an absent cursor means the run never
        // got there, and a cursor that could not be read means nobody can say
        // what it did. Reading the failure as "there is no cursor" turned a
        // broken substrate into a confident verdict about the run.
        val cursor = try {
            cursors.load(runId)
        } catch (e: Exception) {
            return RecoverEvidence.Refused(RecoverRefusal.StorageError(e.shortDiagnostic()))
        }

        val proof = RuntimeRecoverDecision.JournalProof(
            terminalRow = terminalRow,
            replayCursor = cursor?.let {
                RuntimeRecoverDecision.ReplayCursor(
                    runId = it.runId,
                    lastOpId = it.lastOpId ?: "",
                    stageIndex = it.stageIndex,
                    savedAtMs = it.savedAt,
                )
            },
            operations = journalOps.map { op ->
                RuntimeRecoverDecision.OperationSnapshot(
                    opId = op.id,
                    attempt = op.attempt,
                    outcome = outcomeOf(op.status),
                    replayPolicy = op.replayPolicy.name,
                    effects = emptyList(),
                )
            },
        )
        return RecoverEvidence.Ready(
            observation = observation,
            proof = proof,
            journalOperations = journalOps,
            cursor = cursor,
        )
    }

    /**
     * The outcome of a decision.
     *
     * The dry run and the recovery differ in exactly ONE thing: whether the
     * decision is applied to the substrate. Everything else — the decision
     * itself, the deadline, the pins that block a recover — is the same code
     * for both, so the two cannot drift apart.
     */
    private fun applyDecision(
        runId: String,
        decision: RecoveryChoice,
        ready: RecoverEvidence.Ready,
        writesToSubstrate: Boolean,
    ): RecoverOutcome = when (decision) {
        is RecoveryChoice.AlreadyRecovered -> {
            // Also consult the pin port in the AlreadyRecovered
            // path. A run that was already recovered on a previous call
            // and now has a new pin must STILL fail closed; otherwise
            // re-attaching under a recovered-terminal contract would
            // silently release bytes the consumer holds.
            val refusal = pinRefusal(runId)
            if (refusal != null) {
                RecoverOutcome.FailClosed(refusal)
            } else {
                RecoverOutcome.AlreadyRecovered(
                    attempt = decision.attempt,
                    terminalAtMs = decision.terminalAtMs,
                )
            }
        }
        is RecoveryChoice.Reattach -> RecoverOutcome.ReattachPending(
            attempt = decision.attempt,
            deadlineMs = reattachDeadlineMs(),
        )
        is RecoveryChoice.FailClosed -> RecoverOutcome.FailClosed(decision.cause)
        is RecoveryChoice.ReuseTerminal -> {
            // A pin blocks the recover exactly as it blocks the recovery that
            // would follow it, so the dry run reads it too.
            val refusal = pinRefusal(runId)
            when {
                refusal != null -> RecoverOutcome.FailClosed(refusal)
                writesToSubstrate -> dispatchReuseTerminal(runId, decision.receipt, ready)
                else -> RecoverOutcome.RecoveredTerminal(
                    attempt = AttemptId(1),
                    terminal = decision.receipt.terminal,
                    terminalAtMs = terminalAtMsOf(decision.receipt.terminal),
                    report = RecoverReport.Empty,
                )
            }
        }
    }

    /**
     * The refusal a pin on this run's streams creates, or `null` when nothing
     * is pinned.
     */
    private fun pinRefusal(runId: String): RecoverRefusal? {
        val port = pinPort ?: return null
        val streams = try {
            frames.streamsOfRun(runId)
        } catch (e: Exception) {
            return RecoverRefusal.StorageError(e.shortDiagnostic())
        }
        for (stream in streams) {
            val pins = try {
                port.pinsOf(stream)
            } catch (e: Exception) {
                return RecoverRefusal.StorageError(e.shortDiagnostic())
            }
            if (pins.isNotEmpty()) {
                return RecoverRefusal.PinnedBytesOutsideRecoveredRegion(
                    stream = stream,
                    range = pins.first().range,
                    pins = pins,
                )
            }
        }
        return null
    }

    /**
     * Apply a terminal receipt to the substrate: the journal row, the cursor
     * and the output plane. This is the only part of a recover that WRITES.
     */
    private fun dispatchReuseTerminal(
        runId: String,
        receipt: TerminalReceipt,
        ready: RecoverEvidence.Ready,
    ): RecoverOutcome {
        val journalOps = ready.journalOperations

        // a) Append one terminal journal row (idempotent via the
        //    journal's ON CONFLICT path).
        val now = clock.now().toEpochMilli()
        val outcomeName = outcomeNameOf(receipt.terminal)
        val opId = "recovered-$outcomeName-$runId"
        val input = OperationInput(
            stepId = "recover",
            params = mapOf(
                "kind" to JsonPrimitive("recovered-terminal"),
                "runId" to JsonPrimitive(runId),
                "outcome" to JsonPrimitive(outcomeName),
                "terminalAtMs" to JsonPrimitive(now),
            ),
            runId = runId,
            attempt = 1,
        )
        val fingerprintHex = Sha256.ofText("$opId|${json.encodeToString(input)}")
        val recoveredStatus = recoveredStatusOf(receipt.terminal)
        val recoveredOp = RerunOperation(
            id = opId,
            fingerprint = Fingerprint(fingerprintHex),
            input = input,
            output = null,
            status = recoveredStatus,
            attempt = 1,
        )
        val rowsBefore = journalOps.size
        try {
            journal.append(recoveredOp)
        } catch (e: Exception) {
            return RecoverOutcome.FailClosed(
                RecoverRefusal.StorageError(e.shortDiagnostic()),
            )
        }
        val rowsAfter = try {
            journal.listForRun(runId).size
        } catch (e: Exception) {
            // The count is read BACK from the journal, so a storage that cannot
            // be read cannot be counted. Reporting "0 rows committed" for an
            // append that DID commit one is the same lie the cursor used to
            // tell, and it is a lie the operator never sees: the report is not
            // printed. A refusal names what failed.
            return RecoverOutcome.FailClosed(
                RecoverRefusal.StorageError(
                    "the journal could not be read back: ${e.shortDiagnostic()}",
                ),
            )
        }
        val journalRowsCommitted = maxOf(0, rowsAfter - rowsBefore)

        // b) Advance the cursor (idempotent CAS).
        //
        // A cursor that cannot be ADVANCED is a storage failure, exactly as a
        // cursor that cannot be READ is one: both are the substrate refusing
        // to say where the run is, and the report must not answer for it. The
        // refusal names the cursor, so the operator is told WHICH write failed
        // instead of reading a report that says everything went well.
        //
        // The refusal is safe to retry: the append above is idempotent, so a
        // second attempt commits nothing twice.
        try {
            cursors.advance(runId, opId, stageIndex = 0)
        } catch (e: Exception) {
            return RecoverOutcome.FailClosed(
                RecoverRefusal.StorageError(
                    "the replay cursor could not be advanced: ${e.shortDiagnostic()}",
                ),
            )
        }

        // c) Reconcile output store (existing, idempotent).
        val recoveryReport = try {
            outputRecovery.recover()
        } catch (e: Exception) {
            return RecoverOutcome.FailClosed(
                RecoverRefusal.StorageError(e.shortDiagnostic()),
            )
        }
        val framesAppended = try {
            frames.recoverUnframedBytes().size
        } catch (e: Exception) {
            return RecoverOutcome.FailClosed(
                RecoverRefusal.StorageError(e.shortDiagnostic()),
            )
        }

        val report = RecoverReport(
            journalRowsCommitted = journalRowsCommitted,
            framesAppended = framesAppended,
            streamsReconciled = recoveryReport.streamsReconciled,
            // The cursor advanced: the advance above returned without failing.
            // This said whether a cursor EXISTED before the recovery, which is
            // a different fact — and the wrong one twice over: a cursor that
            // existed and could not be moved was reported as advanced, and a
            // cursor that did not exist and was created was reported as not
            // advanced.
            cursorAdvanced = true,
            replayDecisions = receipt.perOperationDecisions.map { it.replayDecision },
        )

        return RecoverOutcome.RecoveredTerminal(
            attempt = AttemptId(1),
            terminal = receipt.terminal,
            terminalAtMs = terminalAtMsOf(receipt.terminal),
            report = report,
        )
    }

    /**
     * The instant a re-attach is due.
     *
     * A fencing token is an authority counter, not an instant: it says HOW
     * RECENT a holder is, not WHEN anything happened. Adding thirty seconds
     * to one produced an instant in 1970, and the operator was told to come
     * back before the epoch. The only owner of a clock is this adapter, so
     * the only place a real instant can come from is here.
     */
    private fun reattachDeadlineMs(): Long = clock.now().toEpochMilli() + REATTACH_WINDOW_MS

    /**
     * The run's own terminal row: the outcome the JOURNAL observes for the
     * WHOLE run, or `null` when the run has no terminal outcome yet.
     *
     * `null` is the answer in two cases, and both are facts about the run:
     *  - the run has no journaled operation at all;
     *  - the run still has work in flight. A terminal OPERATION row is not a
     *    terminal RUN, and the run's outcome does not exist until all of its
     *    work finished.
     *
     * A CANCELLED run is the third case, and it is terminal even when a step
     * was in flight when the cancel happened. The cancel is the run's terminal
     * fact — it says the run was closed on purpose — and the step it
     * interrupted may or may not have committed its effect, which is exactly
     * why such a run is neither re-attached nor re-run. Without this, a
     * cancelled run looked like a crashed one: its journal had no terminal
     * row, so the recover offered a re-attach, and resuming it could repeat an
     * effect the interrupted step already had.
     *
     * When the run does have an outcome, it is the one the DOMAIN computes.
     * [RunOutcomeReducer] is the single authority that folds a run's step
     * verdicts into a run verdict, and it says that success requires EVERY
     * step to have succeeded. So a run whose first operation failed is a
     * FAILED run even when a later operation succeeded — otherwise the recover
     * would close a failed run as a successful one, which is the one thing a
     * durable replay may never do.
     *
     * The vocabulary is the one the PUBLISHED DECIDER reads — "succeeded",
     * "failed", "unstable", "aborted" — and not the event lane's "success" /
     * "failure". The decider is the contract this row is handed to, and a word
     * it does not know becomes an untyped `Other`.
     */
    private fun runTerminalRowOf(
        runId: String,
        ops: List<DurableOperation>,
    ): RuntimeRecoverDecision.TerminalRow? {
        val cancelReceipt = ops.firstOrNull { CancelReceipt.isReceiptOf(it.id, runId) }
        if (cancelReceipt != null) {
            return RuntimeRecoverDecision.TerminalRow(
                outcome = "cancelled",
                terminalAtMs = endedAtOf(cancelReceipt),
            )
        }

        if (ops.isEmpty()) return null
        if (ops.any { !it.status.isTerminal }) return null

        // The orchestrator's explicit abort is READ, not derived: the reducer
        // never derives an abort, and the journal is where that explicit abort
        // is recorded.
        val aborted = ops.filter { it.status == OperationStatus.ABORTED }

        // The reducer is the single authority for the rest of the verdict. It
        // says that the FIRST failure wins, that an unstable step wins over a
        // success, and that success requires every step to have succeeded. An
        // aborted operation contributes no step verdict — the reducer only
        // asks whether a step failed or ended unstable — so it is not part of
        // the reduction.
        //
        // The abort outranks an unstable step (a cancelled run never reached a
        // normal terminal state) and it never outranks a failure (the failure
        // is what ended the run, and it came first).
        val verdict = RunOutcomeReducer.reduce(
            ops.filterNot { it.status == OperationStatus.ABORTED }.map { stepOutcomeOf(it) },
        )
        val outcome = when (verdict) {
            is RunOutcome.Failure -> "failed"
            RunOutcome.Success -> if (aborted.isEmpty()) "succeeded" else "aborted"
            RunOutcome.Unstable -> if (aborted.isEmpty()) "unstable" else "aborted"
            RunOutcome.Aborted -> "aborted"
        }
        // The moment the run's verdict became final: the operation that carries
        // it, and for a successful run the operation that ended last.
        val decisive = when (outcome) {
            "failed" -> ops.first { it.status.isFailureOfTheRun() }
            "aborted" -> aborted.first()
            "unstable" -> ops.first { it.status == OperationStatus.UNSTABLE }
            else -> ops.maxBy { endedAtOf(it) }
        }
        return RuntimeRecoverDecision.TerminalRow(
            outcome = outcome,
            terminalAtMs = endedAtOf(decisive),
        )
    }

    /**
     * The step verdict a journaled operation carries.
     *
     * The projection is one-way on purpose: the runtime writes a
     * [StepOutcome] and the journal stores it as an [OperationStatus], so the
     * status is the durable form and this reads it back. The journal does not
     * carry the failure's kind — only its status — so the kind is the honest
     * "unknown" and the message names the operation that failed.
     */
    private fun stepOutcomeOf(op: DurableOperation): StepOutcome = when (op.status) {
        OperationStatus.SUCCEEDED -> StepOutcome.Success
        OperationStatus.UNSTABLE -> StepOutcome.Unstable
        OperationStatus.FAILED -> StepOutcome.Failure(
            PipelineFailure(FailureKind.UNKNOWN, "operation ${op.id} ended as FAILED"),
        )
        OperationStatus.FAILED_TIMEOUT -> StepOutcome.Failure(
            PipelineFailure(FailureKind.TIMEOUT, "operation ${op.id} was killed by its deadline"),
        )
        OperationStatus.DIVERGENT -> StepOutcome.Failure(
            PipelineFailure(
                FailureKind.REPLAY_COMPATIBILITY,
                "operation ${op.id} diverged from its journaled fingerprint",
            ),
        )
        OperationStatus.LOST -> StepOutcome.Failure(
            PipelineFailure(FailureKind.UNKNOWN, "operation ${op.id} was lost: its outcome is unknown"),
        )
        // Unreachable in the reduction: an aborted operation is not part of
        // it, and a run with work in flight has no terminal outcome at all
        // (the caller returns null before reaching here).
        OperationStatus.ABORTED, OperationStatus.PENDING, OperationStatus.RUNNING -> StepOutcome.Success
    }

    /**
     * The statuses that are a FAILURE of the run's work.
     *
     * LOST belongs here, and it is the important one: a lost operation's
     * outcome is UNKNOWN, and unknown never means success.
     */
    private fun OperationStatus.isFailureOfTheRun(): Boolean =
        this == OperationStatus.FAILED ||
            this == OperationStatus.FAILED_TIMEOUT ||
            this == OperationStatus.DIVERGENT ||
            this == OperationStatus.LOST

    /**
     * When an operation reached its terminal state, in epoch millis.
     *
     * A journal that cannot say is a journal that says zero: the run's
     * terminal outcome is not worth failing a recover over, and the decider
     * does not depend on the timestamp to choose.
     */
    private fun endedAtOf(op: DurableOperation): Long = try {
        journal.getEndedAt(op.id, op.attempt) ?: 0L
    } catch (_: Exception) {
        0L
    }

    private fun outcomeOf(status: OperationStatus): RuntimeRecoverDecision.OperationOutcome =
        when (status) {
            OperationStatus.SUCCEEDED -> RuntimeRecoverDecision.OperationOutcome.Succeeded
            OperationStatus.FAILED,
            OperationStatus.FAILED_TIMEOUT,
            OperationStatus.DIVERGENT -> RuntimeRecoverDecision.OperationOutcome.Failed
            OperationStatus.UNSTABLE -> RuntimeRecoverDecision.OperationOutcome.Unstable
            OperationStatus.ABORTED -> RuntimeRecoverDecision.OperationOutcome.Aborted
            OperationStatus.LOST -> RuntimeRecoverDecision.OperationOutcome.Other("lost")
            OperationStatus.RUNNING -> RuntimeRecoverDecision.OperationOutcome.Running
            OperationStatus.PENDING -> RuntimeRecoverDecision.OperationOutcome.Other("pending")
        }

    private fun outcomeNameOf(terminal: TerminalObservation): String =
        when (terminal) {
            is TerminalObservation.Succeeded -> "succeeded"
            is TerminalObservation.Failed -> terminal.failureKind
            is TerminalObservation.Unstable -> "unstable"
            is TerminalObservation.Aborted -> "aborted"
            is TerminalObservation.Cancelled -> "cancelled"
            is TerminalObservation.Other -> terminal.rawOutcome
        }

    private fun terminalAtMsOf(terminal: TerminalObservation): Long = when (terminal) {
        is TerminalObservation.Succeeded -> terminal.terminalAtMs
        is TerminalObservation.Failed -> terminal.terminalAtMs
        is TerminalObservation.Unstable -> terminal.terminalAtMs
        is TerminalObservation.Aborted -> terminal.terminalAtMs
        is TerminalObservation.Cancelled -> terminal.terminalAtMs
        is TerminalObservation.Other -> terminal.terminalAtMs
    }

    private fun recoveredStatusOf(terminal: TerminalObservation): OperationStatus =
        when (terminal) {
            is TerminalObservation.Succeeded -> OperationStatus.SUCCEEDED
            is TerminalObservation.Failed -> OperationStatus.FAILED
            is TerminalObservation.Unstable -> OperationStatus.UNSTABLE
            is TerminalObservation.Aborted -> OperationStatus.ABORTED
            is TerminalObservation.Cancelled -> OperationStatus.ABORTED
            is TerminalObservation.Other -> OperationStatus.FAILED
        }

    private fun Throwable.shortDiagnostic(): String {
        val cls = this::class.simpleName ?: this.javaClass.name
        val msg = message?.take(120)?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }
}
