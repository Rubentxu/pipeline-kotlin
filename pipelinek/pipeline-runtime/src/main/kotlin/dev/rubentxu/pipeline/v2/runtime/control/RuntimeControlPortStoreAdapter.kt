package dev.rubentxu.pipeline.v2.runtime.control

import dev.rubentxu.pipeline.v2.domain.AttemptId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.durable.LeaseAcquisition
import dev.rubentxu.pipeline.v2.events.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.RunLeasePort
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.OutputRecoveryPort
import dev.rubentxu.pipeline.v2.output.store.SealOutcome
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * The production [RuntimeControlPort]: it closes a run that nobody is working
 * on any more, and it refuses to close one that is.
 *
 * ## What this adapter is
 * A composition, and nothing else. It introduces no lease, no journal schema,
 * no event vocabulary and no scheduler of its own. Every authority it touches
 * is one the product already had:
 *
 *  - the **lease** decides who may write, and the OS lock behind it is the only
 *    authority on whether a live process is publishing the run;
 *  - the **journal** records that a cancellation happened;
 *  - the **event lane** holds the run's terminal fact, and the cancel writes
 *    the same bookend the run's own writer would have written;
 *  - the **output plane** holds the run's bytes, and the cancel seals the
 *    streams the dead writer left open.
 *
 * ## Why the cancel takes the lease
 * A cancel that only *consulted* the lease could not write the run's terminal
 * fact safely, because the terminal fact has to be exclusive: two bookends for
 * one run is the ambiguity this whole port exists to remove. The lease store's
 * acquisition is what makes the write exclusive, because the kernel grants the
 * lock only when no other process holds it. A live owner is therefore never
 * crossed — the acquisition refuses and the cancel refuses with it — and a
 * dead owner is superseded, which is exactly the case an operator needs: the
 * run whose writer was killed and whose lease record still claims the owner is
 * alive.
 *
 * That last sentence is why the previous version of this adapter could not
 * work. It read the lease *record*, and a record is not a liveness fact: a
 * process that was killed leaves its record behind saying the owner is alive.
 * The cancel therefore refused exactly the runs it was written for. The lock
 * is the fact; the record is the audit trail.
 *
 * ## What the run's terminal fact is
 * The **event lane's** `RunFinished` bookend, not a journal row. A journal row
 * is an operation's state, and an operation reaches a terminal state long
 * before the run does: a run whose first step succeeded and whose second step
 * is still running already has a terminal row. Reading that row as "the run
 * finished" refuses the cancel of a half-finished run, and — worse — leaves
 * the run with two answers about whether it is over. The bookend is the run's
 * own terminal fact, and the cancel writes it with the same vocabulary the
 * writer uses.
 *
 * ## What the cancel does NOT do
 * It does not decide whether an in-flight operation had an effect. A step that
 * was running when its writer died may or may not have committed its side
 * effect, and the cancel has no way to know, so it leaves that operation's
 * journal row exactly as it found it. A later resume still sees the
 * "effect unknown" state, which is the honest answer, and which is why the
 * cancel never marks an operation ABORTED.
 *
 * @param journal the operation journal. The cancel's own receipt is written
 *   here; the run's operations are not touched.
 * @param lease the run's lease store. The cancel takes the lease for the
 *   duration of the write and releases it afterwards. It is the PORT, not the
 *   file-backed implementation: the adapter composes authorities, and a test
 *   has to be able to answer them without a disk.
 * @param events the event lane's write side.
 * @param eventReader the event lane's read side, used to find the run's
 *   terminal bookend.
 * @param frames the output plane's index, used to find the streams to seal.
 * @param outputRecovery the output plane's recovery pass, which a seal
 *   requires to have run first.
 * @param sealStream the output plane's seal.
 * @param clock the time source. The cancel records when it closed the run.
 * @param ownerId the identity this process writes under. It becomes the run's
 *   owner for as long as the cancel holds the lease.
 */
class RuntimeControlPortStoreAdapter(
    private val journal: OperationJournal,
    private val lease: RunLeasePort,
    private val events: EventSink,
    private val eventReader: EventRecordReadPort,
    private val frames: OutputFrameIndex,
    private val outputRecovery: OutputRecoveryPort,
    private val sealStream: (OutputStreamId) -> SealOutcome,
    private val clock: Clock,
    private val ownerId: RunOwnerId,
) : RuntimeControlPort {

    /** The receipt's own encoding. The journal's encoding is the journal's business. */
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun cancel(runId: String, reason: CancelReason): CancelOutcome {
        // 1. Does this instance know the run at all? Asked BEFORE the
        //    acquisition, because the acquisition would otherwise create a
        //    lease record for a run that never existed, and a cancel may not
        //    invent a run.
        val terminal = readTerminal(runId)
        if (terminal is TerminalRead.Unreadable) {
            return CancelOutcome.Refused(CancelRefusal.StorageError(terminal.cause))
        }
        val journalRows = try {
            journal.listForRun(runId)
        } catch (failure: Exception) {
            return CancelOutcome.Refused(
                CancelRefusal.JournalUnavailable(failure.shortDiagnostic()),
            )
        }
        val runKnown = lease.isKnown(runId) || journalRows.isNotEmpty() || terminal is TerminalRead.Read
        if (!runKnown) {
            return CancelOutcome.Refused(CancelRefusal.UnknownRun(runId))
        }

        // 2. Take the run's lease. This is the exclusivity step, and the lock
        //    behind it is the only authority on "is a live process publishing
        //    this run".
        val acquisition = try {
            lease.acquire(LeaseRequest(runId, ownerId))
        } catch (failure: Exception) {
            return CancelOutcome.Refused(
                CancelRefusal.JournalUnavailable(failure.shortDiagnostic()),
            )
        }
        val leaseVerdict = when (acquisition) {
            is LeaseAcquisition.AlreadyOwned -> RunCancellationDecision.LeaseVerdict.LiveOwner(
                ownerId = acquisition.heldBy.value,
                fencingToken = acquisition.fencingToken.value,
            )

            is LeaseAcquisition.Unverifiable -> return CancelOutcome.Refused(
                CancelRefusal.JournalUnavailable(acquisition.reason),
            )

            else -> RunCancellationDecision.LeaseVerdict.Held
        }

        try {
            // 3. The rule, and then the write. The rule is pure; the stores
            //    only supply its facts and carry out its verdict.
            val decision = RunCancellationDecision.decide(
                RunCancellationDecision.Facts(
                    runKnown = true,
                    lease = leaseVerdict,
                    terminal = (terminal as? TerminalRead.Read)?.finished?.let { finished ->
                        RunCancellationDecision.Terminal(
                            outcome = finished.outcome,
                            terminalAtMs = finished.occurredAt.toEpochMilli(),
                        )
                    },
                    cancelRecorded = isCancelRecorded(runId),
                ),
            )
            return when (decision) {
                RunCancellationDecision.Decision.Proceed -> closeRun(runId, reason)

                is RunCancellationDecision.Decision.AlreadyCancelled -> CancelOutcome.AlreadyCancelled(
                    attempt = AttemptId(1),
                    terminalAtMs = decision.terminalAtMs,
                )

                is RunCancellationDecision.Decision.Refused -> refuse(decision.reason, runId)
            }
        } finally {
            // The run is closed either way; the lease goes back so that a
            // later cancel, a resume or a fresh run can take it again.
            lease.release(ownerId)
        }
    }

    /**
     * Writes the terminal facts of a cancellation, in the order that keeps the
     * run honest if one of the writes fails.
     *
     * ## The order is the contract
     *
     * [CancelOutcome.Cancelled] says what it means: this call closed the run,
     * the cancellation was recorded, and the output streams were sealed. That
     * claim is only true if the run's terminal fact is written LAST. So the
     * order is: the receipt, then the seal, then the bookend.
     *
     * The order used to be the receipt, the bookend, then the seal, and it
     * left a state nothing could finish: a run whose terminal fact existed
     * (so it was ABORTED) and whose streams were never sealed (so its bytes
     * never ended). The next cancel found a recorded cancellation and a
     * terminal run, said "already cancelled", and sealed nothing — so the
     * output of an aborted run stayed open forever, and a reader following it
     * waited forever. The cancel that had failed had also answered "refused",
     * which contradicted the run's own bookend.
     *
     * With the terminal fact last, a failure in the middle leaves the run
     * UNCLOSED, with the receipt of the intent already in the journal. That
     * is the state the next cancel finishes: it sees a recorded cancellation
     * and no terminal fact, seals what is left to seal (the seal is
     * idempotent), writes the bookend once, and answers [CancelOutcome.Cancelled].
     * The first cancel's refusal is then true as well — nothing terminal
     * happened — instead of contradicting the run it refused to close.
     *
     * ## Why the receipt comes first
     * Because it is the intent, and the intent is what makes a retry a repair
     * instead of a second terminal state: the receipt is keyed by the run, so
     * writing it again updates that one row instead of adding another.
     *
     * ## Why the seal comes before the bookend and not after
     * Because the seal is the statement "no more bytes will be written to this
     * stream", and the cancel holds the run's lease while it says it: nobody
     * else can be publishing this run, so the statement is true at the moment
     * it is made — and it stays true whether or not the bookend lands.
     */
    private fun closeRun(runId: String, reason: CancelReason): CancelOutcome {
        val now = clock.now()

        // 1. The receipt: the intent. It makes the retry a repair instead of
        //    a second terminal state, because the row is keyed by the run.
        try {
            journal.append(cancelReceipt(runId, reason, now))
        } catch (failure: Exception) {
            return CancelOutcome.Refused(
                CancelRefusal.JournalUnavailable(failure.shortDiagnostic()),
            )
        }

        // 2. The seal: the run's bytes are complete. The seal needs the store
        //    to have reconciled its segments first.
        val streamsSealed = try {
            outputRecovery.recover()
            var sealed = 0
            for (stream in frames.streamsOfRun(runId)) {
                when (val outcome = sealStream(stream)) {
                    is SealOutcome.Sealed -> sealed += 1
                    is SealOutcome.AlreadySealed -> sealed += 1
                    is SealOutcome.NeverOpened -> Unit
                    is SealOutcome.Failure -> return CancelOutcome.Refused(
                        CancelRefusal.StorageError(outcome.cause.shortDiagnostic()),
                    )
                }
            }
            sealed
        } catch (failure: Exception) {
            return CancelOutcome.Refused(
                CancelRefusal.StorageError(failure.shortDiagnostic()),
            )
        }

        // 3. The terminal fact: the run is closed. It goes last, so that a
        //    run is never closed and unsealed at the same time.
        try {
            events.append(
                RunFinished(
                    eventId = UUID.randomUUID().toString(),
                    runId = runId,
                    // Zero asks the store for the next per-run sequence; it is
                    // not a value, it is a request.
                    sequence = 0L,
                    occurredAt = now,
                    outcome = RunOutcome.Aborted.wireToken,
                    diagnostics = listOf(cancelDiagnostic(reason)),
                ),
            )
        } catch (failure: Exception) {
            return CancelOutcome.Refused(
                CancelRefusal.StorageError(failure.shortDiagnostic()),
            )
        }

        return CancelOutcome.Cancelled(
            attempt = AttemptId(1),
            terminalAtMs = now.toEpochMilli(),
            streamsSealed = streamsSealed,
        )
    }

    /**
     * The cancel's own receipt: the record that a cancellation of this run
     * happened, and the reason for it.
     *
     * Its status is SUCCEEDED because the control operation succeeded. The run
     * is what was aborted, not the cancellation, and a receipt that claimed to
     * have failed would show up in the product's failure report as a failed
     * operation of the run.
     */
    private fun cancelReceipt(
        runId: String,
        reason: CancelReason,
        at: java.time.Instant,
    ): RerunOperation {
        val opId = receiptOpId(runId)
        val input = OperationInput(
            stepId = "cancel",
            params = mapOf(
                "kind" to JsonPrimitive("cancel"),
                "runId" to JsonPrimitive(runId),
                "reason" to JsonPrimitive(reasonText(reason)),
                "at" to JsonPrimitive(at.toEpochMilli().toString()),
            ),
            runId = runId,
            attempt = 1,
        )
        return RerunOperation(
            id = opId,
            fingerprint = Fingerprint(Sha256.ofText("$opId|${json.encodeToString(input)}")),
            input = input,
            output = null,
            status = OperationStatus.SUCCEEDED,
            attempt = 1,
        )
    }

    /** The diagnostic the terminal bookend carries, so a reader sees why. */
    private fun cancelDiagnostic(reason: CancelReason): ScriptingDiagnostic = ScriptingDiagnostic(
        severity = ScriptDiagnosticSeverity.INFO,
        message = "cancelled by operator: ${reasonText(reason)}",
        line = 0,
        column = 0,
        path = CANCEL_DIAGNOSTIC_PATH,
    )

    /** Whether this run already carries a cancellation receipt. */
    private fun isCancelRecorded(runId: String): Boolean = try {
        journal.get(receiptOpId(runId), 1) != null
    } catch (_: Exception) {
        false
    }

    /**
     * The run's terminal bookend, read from the event lane.
     *
     * The lane serves the run's events in sequence order, and the bookend is
     * the last one, so the read pages to the end of the run and keeps the last
     * `RunFinished` it sees.
     */
    private fun readTerminal(runId: String): TerminalRead {
        var cursor: EventCursor? = null
        var finished: RunFinished? = null
        while (true) {
            val result = eventReader.readRecords(runId, cursor, EventQuery.All, EVENT_PAGE_SIZE)
            when (result) {
                is EventRecordReadResult.Refused -> return when (result.refusal) {
                    // The lane does not know the run. That is a fact about the
                    // run, not an error: the lease or the journal may still
                    // know it.
                    is EventRecordReadRefusal.UnknownRun -> TerminalRead.NoEvents
                    else -> TerminalRead.Unreadable(result.refusal.toString())
                }

                is EventRecordReadResult.Page -> {
                    finished = result.slice.decoded.filterIsInstance<RunFinished>().lastOrNull() ?: finished
                    if (!result.slice.hasMore) {
                        return TerminalRead.Read(finished)
                    }
                    cursor = result.slice.nextCursor
                }
            }
        }
    }

    private fun refuse(
        reason: RunCancellationDecision.Refusal,
        runId: String,
    ): CancelOutcome = CancelOutcome.Refused(
        when (reason) {
            RunCancellationDecision.Refusal.UnknownRun -> CancelRefusal.UnknownRun(runId)

            is RunCancellationDecision.Refusal.LiveOwnerHeld -> CancelRefusal.LeaseHeldByAnother(
                ownerId = reason.ownerId,
                fencingToken = reason.fencingToken,
            )

            is RunCancellationDecision.Refusal.AlreadyTerminal -> CancelRefusal.RunTerminal(reason.outcome)
        },
    )

    private fun reasonText(reason: CancelReason): String = when (reason) {
        CancelReason.UserRequested -> "user-requested"
        is CancelReason.Annotated -> reason.text
    }

    private fun Throwable.shortDiagnostic(): String {
        val cls = this::class.simpleName ?: this.javaClass.name
        val msg = message?.take(120)?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }

    /** What the event lane knows about the run's terminal state. */
    private sealed interface TerminalRead {

        /** The lane served the run. [finished] is its last bookend, if any. */
        data class Read(val finished: RunFinished?) : TerminalRead

        /** The lane does not know this run. */
        data object NoEvents : TerminalRead

        /** The lane could not be read. */
        data class Unreadable(val cause: String) : TerminalRead
    }

    companion object {

        /**
         * The operation id of a run's cancellation receipt.
         *
         * It is NOT an operation id of the pipeline: the pipeline's ids are
         * `{runId}-s{stage}-{step}`, and this one names a control action on the
         * run as a whole. The prefix keeps it from ever colliding with an
         * operation id, and keeps the receipt out of the readers that look for
         * operations by shape.
         *
         * The shape itself lives in [CancelReceipt], because the recover reads
         * it too.
         */
        fun receiptOpId(runId: String): String = CancelReceipt.opIdOf(runId)

        /** The `path` the cancel's diagnostic carries: the producer's name. */
        const val CANCEL_DIAGNOSTIC_PATH: String = "pipelinek:runs cancel"

        /** How many events one page of the terminal read carries. */
        private const val EVENT_PAGE_SIZE: Int = 512
    }
}
