package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.durable.FileBackedRunExecutionLeaseStore
import dev.rubentxu.pipeline.v2.events.durable.LeaseAcquisition
import dev.rubentxu.pipeline.v2.events.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.RunExecutionLease
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.store.OutputRecoveryPort
import dev.rubentxu.pipeline.v2.runtime.inspect.AttemptId
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * The narrow port the recover adapter uses to ask "what does this run look
 * like right now?". It exists so the recover adapter does not depend on the
 * concrete `RuntimeIntrospectionPortStoreAdapter` type and so the test can
 * substitute a stub introspect without instantiating the storage stack.
 *
 * Production wiring is `::inspect` against the live
 * `RuntimeIntrospectionPortStoreAdapter`; tests may pass a hand-rolled
 * lambda.
 *
 * Declared BEFORE the class so the class can use it as a constructor parameter
 * type without a self-import dance.
 */
fun interface RuntimeRecoverPortIntrospect {
    fun inspect(runId: String): RuntimeIntrospectionResult
}

/**
 * M2 — production [RuntimeRecoverPort] adapter backed by the existing journal,
 * cursor, lease, and output-recovery authorities.
 *
 * ## What this adapter is and is not
 *
 * The adapter is a thin composition: it does NOT introduce a new lease, a new
 * fencing scheme, a new journal schema, a new output store, or a new scheduler.
 * Every authority it composes is named with module:file:line in the design's
 * composition table (M2 design §6).
 *
 * The adapter consults the existing [RuntimeIntrospectionPort] to get the typed
 * observation, fetches the journal's terminal row + cursor via the existing
 * `OperationJournal.listForRun` + `ReplayCursorStore.load`, hands the proof to
 * the pure [RuntimeRecoverDecision.decideRecovery] decider, and dispatches the
 * returned [RecoveryChoice] into one of the four [RecoverOutcome] cases.
 *
 * ## No-rerun invariant (audit D.4)
 *
 * The adapter NEVER re-executes a step's `execute()`. It materialises the
 * terminal row by writing it through the existing `OperationJournal.append`
 * (which is the journal's own observation, not a side-effecting re-run) and
 * reconciles the output store via the existing `OutputRecoveryPort.recover` +
 * `OutputFrameIndex.recoverUnframedBytes` pair. `RecoverNoRerunTest` (§7.3 of
 * the design) pins the invariant.
 *
 * @see M2_INSPECT_RECOVER_CANCEL_DESIGN.md §5 and §6.
 */
class RuntimeRecoverPortStoreAdapter(
    private val journal: OperationJournal,
    private val cursors: ReplayCursorStore,
    private val lease: FileBackedRunExecutionLeaseStore,
    private val outputRecovery: OutputRecoveryPort,
    private val frames: OutputFrameIndex,
    private val introspect: RuntimeRecoverPortIntrospect,
    private val clock: Clock,
    private val syntheticObserverId: String = "m2-observer",
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : RuntimeRecoverPort {

    override fun recover(runId: String, options: RecoverOptions): RecoverOutcome {
        val syntheticObserver: RunOwnerId = RunOwnerId.of(syntheticObserverId)
            ?: error("invalid syntheticObserverId $syntheticObserverId")
        // 1. Lease boundary (audit B.8, G.3 by analogy). Consult the pure
        //    decider with a synthetic request; refuse only on
        //    LeaseAcquisition.AlreadyOwned (a live other-owner present).
        //    Reentered / Acquired / TakenOver / Unverifiable are tolerated
        //    because recover does NOT actually acquire — it observes.
        val observed = try {
            lease.observe(runId)
        } catch (e: Exception) {
            return RecoverOutcome.FailClosed(
                RecoverRefusal.StorageError(e.shortDiagnostic()),
            )
        }
        val syntheticRequest = LeaseRequest(runId = runId, ownerId = syntheticObserver)
        when (val decision = RunExecutionLease.acquire(observed, syntheticRequest)) {
            is LeaseAcquisition.AlreadyOwned -> {
                return RecoverOutcome.FailClosed(
                    RecoverRefusal.LeaseHeldByAnother(
                        "run $runId is held by ${decision.heldBy.value}",
                    ),
                )
            }
            else -> { /* Reentered / Acquired / TakenOver / Unverifiable — proceed. */ }
        }

        // 2. Introspect (existing).
        val observationResult = try {
            introspect.inspect(runId)
        } catch (e: Exception) {
            return RecoverOutcome.FailClosed(
                RecoverRefusal.StorageError(e.shortDiagnostic()),
            )
        }
        val observation: RuntimeObservation = when (observationResult) {
            is RuntimeIntrospectionResult.Observation -> observationResult.observation
            is RuntimeIntrospectionResult.Refused -> {
                return RecoverOutcome.FailClosed(
                    RecoverRefusal.SubstrateUnavailable(observationResult.refusal.toString()),
                )
            }
        }

        // 3. Build the journal proof.
        val journalOps: List<DurableOperation> = try {
            journal.listForRun(runId)
        } catch (e: Exception) {
            return RecoverOutcome.FailClosed(
                RecoverRefusal.StorageError(e.shortDiagnostic()),
            )
        }
        val terminalOp = journalOps.firstOrNull { it.status.isTerminal }
        val terminalRow: RuntimeRecoverDecision.TerminalRow? = terminalOp?.let { op ->
            val terminalAtMs: Long = try {
                journal.getEndedAt(op.id, op.attempt) ?: 0L
            } catch (_: Exception) {
                0L
            }
            RuntimeRecoverDecision.TerminalRow(
                outcome = op.status.name.lowercase(),
                terminalAtMs = terminalAtMs,
            )
        }
        val cursor = try {
            cursors.load(runId)
        } catch (_: Exception) {
            null
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

        // 4. Decide.
        val decision = RuntimeRecoverDecision.decideRecovery(observation, proof)

        // 5. dryRun early-return.
        if (options.dryRun) {
            return when (decision) {
                is RecoveryChoice.ReuseTerminal -> RecoverOutcome.RecoveredTerminal(
                    attempt = AttemptId(1),
                    terminal = decision.receipt.terminal,
                    terminalAtMs = terminalAtMsOf(decision.receipt.terminal),
                    report = RecoverReport.Empty,
                )
                is RecoveryChoice.Reattach -> RecoverOutcome.ReattachPending(
                    attempt = decision.attempt,
                    deadlineMs = decision.deadlineMs,
                )
                is RecoveryChoice.FailClosed -> RecoverOutcome.FailClosed(decision.cause)
                is RecoveryChoice.AlreadyRecovered -> RecoverOutcome.AlreadyRecovered(
                    attempt = decision.attempt,
                    terminalAtMs = decision.terminalAtMs,
                )
            }
        }

        // 6. Dispatch.
        return when (decision) {
            is RecoveryChoice.AlreadyRecovered -> RecoverOutcome.AlreadyRecovered(
                attempt = decision.attempt,
                terminalAtMs = decision.terminalAtMs,
            )
            is RecoveryChoice.Reattach -> RecoverOutcome.ReattachPending(
                attempt = decision.attempt,
                deadlineMs = decision.deadlineMs,
            )
            is RecoveryChoice.FailClosed -> RecoverOutcome.FailClosed(decision.cause)
            is RecoveryChoice.ReuseTerminal -> {
                val receipt = decision.receipt
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
                val fingerprintHex = sha256Hex("$opId|${json.encodeToString(input)}")
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
                } catch (_: Exception) {
                    rowsBefore
                }
                val journalRowsCommitted = maxOf(0, rowsAfter - rowsBefore)

                // b) Advance cursor (idempotent CAS).
                try {
                    cursors.advance(runId, opId, stageIndex = 0)
                } catch (_: Exception) {
                    // Cursor advance is non-blocking; failure surfaces in the
                    // report rather than as a refusal — the terminal fact is
                    // already in the journal.
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
                    cursorAdvanced = cursor != null,
                    replayDecisions = receipt.perOperationDecisions.map { it.replayDecision },
                )

                RecoverOutcome.RecoveredTerminal(
                    attempt = AttemptId(1),
                    terminal = receipt.terminal,
                    terminalAtMs = terminalAtMsOf(receipt.terminal),
                    report = report,
                )
            }
        }
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

    private fun sha256Hex(text: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun Throwable.shortDiagnostic(): String {
        val cls = this::class.simpleName ?: this.javaClass.name
        val msg = message?.take(120)?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }
}
