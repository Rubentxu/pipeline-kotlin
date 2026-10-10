package dev.rubentxu.pipeline.v2.runtime.inspect

import dev.rubentxu.pipeline.v2.events.durable.FileBackedRunExecutionLeaseStore
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.RunExecutionLease
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailPort
import dev.rubentxu.pipeline.v2.output.OutputTailState

/**
 * M2 — production [RuntimeIntrospectionPort] adapter backed by the existing
 * M1 read-side ports and the existing PK internal stores.
 *
 * ## What this adapter is and is not
 *
 * The adapter is a thin composition: it does not introduce a new lease, a new
 * journal, or a new event/output store. Every authority it composes is named in
 * the design's composition table (M2 design §6). Wiring it in production is
 * the application-layer's job; this adapter takes the read-side ports and the
 * internal stores as constructor arguments so the tests can wire them up
 * against `@TempDir` SQLite + segment stores.
 *
 * ## Read-only by construction
 *
 * The adapter MUST NOT mutate durable state. The only operations it calls are
 * SELECTs (the journal's [OperationJournal.listForRun] /
 * [dev.rubentxu.pipeline.v2.events.durable.OperationJournal.getEndedAt]), tail-
 * state queries ([OutputTailPort.tailState]), the read-page
 * ([EventRecordReadPort.readRecords]) used only for "is there any row?" checks,
 * the durable cursor ([ReplayCursorStore.load]), and the pure lease decider
 * ([RunExecutionLease.acquire]) with a synthetic [dev.rubentxu.pipeline.v2.events.durable.LeaseRequest].
 * The `IntrospectionDoesNotMutateStateTest` contract test pins this.
 *
 * @see M2_INSPECT_RECOVER_CANCEL_DESIGN.md §3 and §6.
 */
class RuntimeIntrospectionPortStoreAdapter(
    private val eventReads: EventRecordReadPort,
    private val tails: OutputTailPort,
    private val frames: OutputFrameIndex,
    private val journal: OperationJournal,
    private val cursors: ReplayCursorStore,
    private val lease: FileBackedRunExecutionLeaseStore,
) : RuntimeIntrospectionPort {

    override fun inspect(runId: String): RuntimeIntrospectionResult {
        return try {
            // M1-A composition: a run is "known" if the lease has ever recorded a
            // record for it OR the event plane has an envelope for it. Unknown
            // (Empty-refusal) is the distinguished case where neither holds.
            val knownViaLease = lease.isKnown(runId)
            val observedLease = lease.observe(runId)
            val runKnown = knownViaLease || observedLease != null

            if (!runKnown) {
                // Distinguish empty-but-known from unknown. If the EventReadPort
                // also can't find any envelope and the journal is empty, the run
                // is genuinely unknown. Otherwise we serve LiveButEmpty.
                val journalOps = try {
                    journal.listForRun(runId)
                } catch (e: Exception) {
                    return RuntimeIntrospectionResult.Refused(
                        IntrospectionRefusal.StorageError(e.shortDiagnostic()),
                    )
                }
                if (journalOps.isEmpty()) {
                    val evRead = eventReads.readRecords(
                        runId = runId,
                        after = null,
                        query = dev.rubentxu.pipeline.v2.events.identity.EventQuery.All,
                        limit = 1,
                    )
                    if (evRead is dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult.Refused) {
                        return RuntimeIntrospectionResult.Refused(
                            IntrospectionRefusal.UnknownRun(runId),
                        )
                    }
                    return RuntimeIntrospectionResult.Observation(
                        RuntimeObservation.LiveButEmpty(
                            attempt = AttemptId(1),
                            reason = "no lease record, no journal rows, no event envelopes",
                        ),
                    )
                }
            }

            // From here on, the run is known.
            val journalOps: List<dev.rubentxu.pipeline.v2.domain.durable.DurableOperation> = try {
                journal.listForRun(runId)
            } catch (e: Exception) {
                return RuntimeIntrospectionResult.Refused(
                    IntrospectionRefusal.StorageError(e.shortDiagnostic()),
                )
            }
            val cursor = try {
                cursors.load(runId)
            } catch (e: Exception) {
                return RuntimeIntrospectionResult.Refused(
                    IntrospectionRefusal.StorageError(e.shortDiagnostic()),
                )
            }

            // Terminal row (any row whose status is terminal). Used both for the
            // Terminal observation and for the journalPosition.latestTerminalAtMs.
            val terminalOp = journalOps.firstOrNull {
                it.status.isTerminal
            }

            if (terminalOp != null) {
                val terminalAtMs: Long = try {
                    journal.getEndedAt(terminalOp.id, terminalOp.attempt)
                        ?: terminalAtMsOf(terminalOp)
                } catch (e: Exception) {
                    return RuntimeIntrospectionResult.Refused(
                        IntrospectionRefusal.StorageError(e.shortDiagnostic()),
                    )
                }
                return RuntimeIntrospectionResult.Observation(
                    RuntimeObservation.Terminal(
                        attempt = AttemptId(terminalOp.attempt),
                        terminal = terminalObservationOf(terminalOp, terminalAtMs),
                        terminalAtMs = terminalAtMs,
                    ),
                )
            }

            // No terminal row → Running (with lease authority) OR LiveButEmpty.
            val runExistsAnywhere = journalOps.isNotEmpty() ||
                observedLease != null ||
                evStoreHasRun(runId)
            if (!runExistsAnywhere) {
                return RuntimeIntrospectionResult.Observation(
                    RuntimeObservation.LiveButEmpty(
                        attempt = AttemptId(1),
                        reason = "no journal rows, no lease record, no event envelopes",
                    ),
                )
            }

            // Lease authority. Consult the pure decider with a synthetic request —
            // we do NOT mutate the lease, we just ask what it WOULD do.
            val syntheticRequest = dev.rubentxu.pipeline.v2.events.durable.LeaseRequest(
                runId = runId,
                ownerId = SYNTHETIC_OWNER,
            )
            val decision = RunExecutionLease.acquire(observedLease, syntheticRequest)
            when (decision) {
                is dev.rubentxu.pipeline.v2.events.durable.LeaseAcquisition.AlreadyOwned -> {
                    return RuntimeIntrospectionResult.Refused(
                        IntrospectionRefusal.LeaseHeldByAnother(
                            heldBy = decision.heldBy.value,
                            fencingToken = decision.fencingToken.value,
                        ),
                    )
                }
                is dev.rubentxu.pipeline.v2.events.durable.LeaseAcquisition.Unverifiable -> {
                    return RuntimeIntrospectionResult.Refused(
                        IntrospectionRefusal.StorageError(decision.reason),
                    )
                }
                else -> {
                    // Acquired / Reentered / TakenOver → we conceptually observe
                    // the run as running under `observedLease`. fall through.
                }
            }

            val leaseHolder = observedLease?.ownerId?.let { ownerId ->
                LeaseHolder(
                    ownerId = ownerId.value,
                    fencingToken = observedLease.fencingToken.value,
                    alive = observedLease.ownerAlive,
                )
            }

            val outputTails: List<OutputTailView> = try {
                frames.streamsOfRun(runId).map { streamId ->
                    val tail: OutputTailState? = tails.tailState(streamId)
                    OutputTailView(
                        stream = streamId,
                        state = when (tail) {
                            is OutputTailState.Open -> TailState.Open(tail.committedEnd)
                            is OutputTailState.Sealed -> TailState.Sealed(tail.finalEnd)
                            null -> TailState.Open(0L)
                        },
                        lastOrdinal = frames.lastOrdinal(runId),
                    )
                }
            } catch (e: Exception) {
                return RuntimeIntrospectionResult.Refused(
                    IntrospectionRefusal.StorageError(e.shortDiagnostic()),
                )
            }

            RuntimeIntrospectionResult.Observation(
                RuntimeObservation.Running(
                    attempt = AttemptId(1),
                    leaseHolder = leaseHolder,
                    fencingToken = observedLease?.fencingToken?.value ?: 0L,
                    journalPosition = JournalPosition(
                        operations = journalOps.size,
                        latestOpId = journalOps.lastOrNull()?.id,
                        latestTerminalAtMs = null,
                    ),
                    outputTails = outputTails,
                ),
            )
        } catch (e: Exception) {
            RuntimeIntrospectionResult.Refused(
                IntrospectionRefusal.StorageError(e.shortDiagnostic()),
            )
        }
    }

    /**
     * Check whether the event plane has an envelope for [runId]. Uses the
     * existing cursor (`tailSequence`) so we do not need a second store API.
     * Returns `false` for an unknown runId.
     */
    private fun evStoreHasRun(runId: String): Boolean {
        // The M1-A `runExists` callback is what the EventRecordReadPort adapter
        // wraps for that port's own runExists/tailSequence. The introspection
        // adapter consumes the EventRecordReadPort with `limit = 1` to detect
        // existence: a refused-on-Unknown is the unknown answer; a Page (even
        // empty) is the known answer.
        val page = eventReads.readRecords(
            runId = runId,
            after = null,
            query = dev.rubentxu.pipeline.v2.events.identity.EventQuery.All,
            limit = 1,
        )
        return page !is dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult.Refused
    }

    private fun terminalObservationOf(
        op: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation,
        terminalAtMs: Long,
    ): TerminalObservation {
        val outcome = op.status.name.lowercase()
        return when (op.status) {
            dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED ->
                TerminalObservation.Succeeded(terminalAtMs)
            dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.FAILED,
            dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.FAILED_TIMEOUT,
            dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.DIVERGENT ->
                TerminalObservation.Failed(outcome, terminalAtMs)
            dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.UNSTABLE ->
                TerminalObservation.Unstable(terminalAtMs)
            dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.ABORTED ->
                TerminalObservation.Aborted(terminalAtMs)
            dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.LOST ->
                TerminalObservation.Other("lost", terminalAtMs)
            dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.PENDING,
            dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.RUNNING ->
                TerminalObservation.Other(outcome, terminalAtMs)
        }
    }

    private fun terminalAtMsOf(op: dev.rubentxu.pipeline.v2.domain.durable.DurableOperation): Long = 0L

    private fun Throwable.shortDiagnostic(): String {
        val cls = this::class.simpleName ?: this.javaClass.name
        val msg = message?.take(120)?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }

    private companion object {
        // A synthetic owner id used when consulting the pure lease decider for
        // an observation-only path. The decider does not persist anything about
        // this id; the call is read-only and the decider's `Acquire` verdict is
        // only observed for the typed `LeaseHeldByAnother` case.
        val SYNTHETIC_OWNER: dev.rubentxu.pipeline.v2.events.durable.RunOwnerId =
            dev.rubentxu.pipeline.v2.events.durable.RunOwnerId.of("inspect-m2-observer")!!
    }
}

/**
 * M2 — the [OutputFrameIndex] extension used by the introspection adapter to
 * list the declared streams of a run.
 *
 * The interface already publishes [OutputFrameIndex.streamsOfRun]; this helper
 * keeps the adapter symmetric (every other input it passes is via the
 * constructor) and lets the test substitute an in-memory implementation
 * without subclassing `OutputFrameIndex` directly.
 */
fun OutputFrameIndex.streamsOfRunOrEmpty(runId: String): List<OutputStreamId> = try {
    streamsOfRun(runId)
} catch (_: Exception) {
    emptyList()
}
