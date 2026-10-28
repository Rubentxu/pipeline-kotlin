package dev.rubentxu.pipeline.v2.runtime.inspect

import dev.rubentxu.pipeline.v2.domain.AttemptId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.RunExecutionLease
import dev.rubentxu.pipeline.v2.events.durable.RunLeaseObservationPort
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import dev.rubentxu.pipeline.v2.events.shape.EventShapeReadPort
import dev.rubentxu.pipeline.v2.events.shape.StageStartedTyped
import dev.rubentxu.pipeline.v2.events.shape.StepStartedTyped
import dev.rubentxu.pipeline.v2.events.shape.TypedEvent
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailPort
import dev.rubentxu.pipeline.v2.output.OutputTailState

/**
 * Production [RuntimeIntrospectionPort] adapter backed by the existing
 * Read-side ports and the existing internal stores
 * ## What this adapter is and is not
 * The adapter is a thin composition: it does not introduce a new lease, a new
 * journal, or a new event/output store. Every authority it composes is named in
 * the "Read-only by construction" list below. Wiring it in production is
 * the application-layer's job; this adapter takes the read-side ports and the
 * internal stores as constructor arguments, so a test can wire them up against
 * in-memory answers instead of a directory of lock files.
 * ## What the run's terminal fact is
 * The **event lane's** `RunFinished` bookend. It used to be the journal's
 * first row whose status is terminal, which is a fact about an OPERATION: an
 * operation reaches a terminal state long before the run does, so a run whose
 * first step succeeded and whose second step is still running was reported as
 * Terminal. The bookend is the run's own terminal fact, it is what the writer
 * writes and what the cancel writes, and reading it is what makes this
 * adapter answer about the RUN.
 * ## Read-only by construction
 * The adapter MUST NOT mutate durable state. The only operations it calls are
 * SELECTs (the journal's [OperationJournal.listForRun] /
 * [dev.rubentxu.pipeline.v2.events.durable.OperationJournal.getEndedAt]), tail-
 * state queries ([OutputTailPort.tailState]), the read-page
 * ([EventRecordReadPort.readRecords]), the durable cursor
 * ([ReplayCursorStore.load]), the lease's read-only observation
 * ([RunLeaseObservationPort]) and the pure lease decider
 * ([RunExecutionLease.acquire]) with a synthetic [dev.rubentxu.pipeline.v2.events.durable.LeaseRequest].
 * The `RuntimeIntrospectionPortStoreAdapterTest` unit test pins this: its
 * stores answer from memory and count the writes they were asked for.
*/
class RuntimeIntrospectionPortStoreAdapter(
    private val eventReads: EventRecordReadPort,
    private val tails: OutputTailPort,
    private val frames: OutputFrameIndex,
    private val journal: OperationJournal,
    private val cursors: ReplayCursorStore,
    private val lease: RunLeaseObservationPort,
    private val syntheticObserverId: String = "m2-observer",
    /**
     * Optional typed-event read port. When present, `runStructure`
     * uses it to drive event-driven grouping. When null, the adapter falls
     * back to the synthetic single-stage / single-step view. The pattern
     * this signature states: the surface is additive, the fallback is
     * preserved.
*/
    private val eventShapeRead: EventShapeReadPort? = null,
) : RuntimeIntrospectionPort {

    // The inspect body composes (lease-or-event-plane) knownness, tail
    // Openness, and frame-index declaration; the orchestration order is the
    // contract. Splitting helpers would re-derive the sequencing invariants inside
    // each one. The body's length is intentional.
    @Suppress("LongMethod")
    override fun inspect(runId: String): RuntimeIntrospectionResult {
        return try {
            // A run is "known" if the lease has ever recorded a
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
                        // Through the shared mapping, so a StorageError is reported as a
                        // storage error. This branch used to re-label every refusal as
                        // UnknownRun, which is the fail-CLOSED direction and therefore
                        // safer than its counterpart — but it is still a wrong answer to
                        // a question the caller asked, and it made the same read mean two
                        // different things within one file.
                        return RuntimeIntrospectionResult.Refused(
                            asIntrospectionRefusal(evRead.refusal, runId),
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

            // ## The run's terminal fact is the event lane's bookend
            //
            // It used to be the journal's first row whose status is terminal.
            // That is a fact about an OPERATION, and an operation reaches a
            // terminal state long before the run does: a run whose first step
            // succeeded and whose second step is still running already has a
            // terminal row. Reading that row as "the run finished" reported a
            // half-finished run as Terminal — the exact defect the control
            // adapter fixed, left standing here, and left unfixed precisely
            // because nothing consumed this adapter.
            //
            // The run's own terminal fact is the event lane's `RunFinished`
            // bookend: the same bookend the writer writes and the same one the
            // cancel writes. Reading it means the adapter answers about the
            // RUN, and a run with no bookend is a run that is not over, even
            // if some of its operations are.
            val runTerminal = when (val read = readRunTerminal(runId)) {
                is RunTerminalRead.Unreadable -> return RuntimeIntrospectionResult.Refused(
                    IntrospectionRefusal.StorageError(read.cause),
                )

                // The lane does not know the run, so it cannot have a bookend
                // for it. That is an answer about the lane, not about the
                // run's terminal state, and it is NOT a terminal fact.
                RunTerminalRead.NoEvents -> null

                is RunTerminalRead.Read -> read.finished
            }

            if (runTerminal != null) {
                val terminalAtMs: Long = runTerminal.occurredAt.toEpochMilli()
                return RuntimeIntrospectionResult.Observation(
                    RuntimeObservation.Terminal(
                        // The bookend does not carry an attempt ordinal: a run
                        // is one invocation, and the ordinal of an OPERATION is
                        // not the ordinal of the run.
                        attempt = AttemptId(1),
                        terminal = terminalObservationOf(runTerminal),
                        terminalAtMs = terminalAtMs,
                    ),
                )
            }

            // No terminal row → Running (with lease authority) OR LiveButEmpty.
            // Reaching this line means the run is known via a DURABLE lease record
            // that nothing currently holds (`lease.isKnown` is row-presence, not the
            // live lock table), or via a non-empty journal. The lease alone cannot
            // tell a live run from a released one, so the event store is consulted
            // here for the one fact it uniquely carries: did the run ever emit an
            // envelope. That is a genuine second input, not a redundant authority —
            // it is the ONLY input that separates "running" from "released and empty".
            // What it must not be is a Boolean. The previous expression collapsed
            // every refusal into `false`, so a transient store failure surfaced as a
            // confidently-worded positive claim about a run that exists. `Presence`
            // keeps the answer and the failure apart, and the failure is answered by
            // refusing rather than by guessing.
            val eventPresence = evStorePresenceOf(runId)
            val runExistsAnywhere = journalOps.isNotEmpty() ||
                observedLease != null ||
                when (eventPresence) {
                    EventStorePresence.Present -> true
                    EventStorePresence.Absent -> false
                    is EventStorePresence.Unreadable -> return RuntimeIntrospectionResult.Refused(eventPresence.refusal)
                }
            if (!runExistsAnywhere) {
                return RuntimeIntrospectionResult.Observation(
                    RuntimeObservation.LiveButEmpty(
                        attempt = AttemptId(1),
                        reason = "no journal rows, no live lease, no event envelopes",
                    ),
                )
            }

            // Lease authority. Consult the pure decider with a synthetic request —
            // we do NOT mutate the lease, we just ask what it WOULD do.
            val syntheticOwner: dev.rubentxu.pipeline.v2.events.durable.RunOwnerId =
                dev.rubentxu.pipeline.v2.events.durable.RunOwnerId.of(syntheticObserverId)
                    ?: error("invalid syntheticObserverId $syntheticObserverId")
            val syntheticRequest = dev.rubentxu.pipeline.v2.events.durable.LeaseRequest(
                runId = runId,
                ownerId = syntheticOwner,
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

            // No inner try/catch here. The one this block used to carry built the
            // IDENTICAL refusal the enclosing `catch` builds — same constructor, same
            // `shortDiagnostic()` — so it was a second copy of one mapping, not a
            // second policy. Letting the exception reach the outer handler is
            // behaviour-preserving and keeps exactly one place that decides what a
            // failure in this function becomes.
            val outputTails = outputTailsOf(runId)

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
     * The Output Plane's view of [runId]'s streams, in declaration order.
     * Pure shape, no policy: every stream's tail state is projected, and a stream the
     * tail port has never heard of is reported as an open tail at offset 0 rather
     * than dropped — the frame index declared it, so it is a fact about the run even
     * when the writer has not opened it yet.
     * Exceptions are deliberately NOT handled here. The caller decides what a failure
     * becomes, and there is exactly one such decision in this class's [inspect].
*/
    private fun outputTailsOf(runId: String): List<OutputTailView> =
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

    /**
     * Whether the event plane can answer "does [runId] have an envelope", and
     * whether it answered at all.
     * Three states because the port has three. A `Page` — even an empty one — is
     * the store saying "I looked, here is the answer", which is [Present]: the
     * run exists in this store's world. [Absent] is the store naming the run as
     * unknown. [Unreadable] is the store failing for some other reason, and it
     * carries the already-mapped refusal so the caller never has to translate
     * the event-store vocabulary itself.
     * A `Boolean` return could not carry all three, and the arm that got dropped
     * was the dangerous one: folding `StorageError` into "no envelopes" reports a
     * transient failure as a confident fact about a run that does exist.
*/
    private fun evStorePresenceOf(runId: String): EventStorePresence =
        when (
            val r = eventReads.readRecords(
                runId = runId,
                after = null,
                query = dev.rubentxu.pipeline.v2.events.identity.EventQuery.All,
                limit = 1,
            )
        ) {
            is dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult.Page ->
                EventStorePresence.Present
            is dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult.Refused ->
                when (val mapped = asIntrospectionRefusal(r.refusal, runId)) {
                    // Only the store naming the run unknown is an answer about the run.
                    is IntrospectionRefusal.UnknownRun -> EventStorePresence.Absent
                    // Everything else is the store failing to answer, and it stays a
                    // failure all the way out rather than becoming a claim about the run.
                    else -> EventStorePresence.Unreadable(mapped)
                }
        }

    /**
     * The one translation from the event store's refusal vocabulary to this
     * port's. Every site that reads events through [EventRecordReadPort] maps
     * through here, so `StorageError` cannot be re-labelled as `UnknownRun` in
     * one place and preserved in another.
*/
    private fun asIntrospectionRefusal(
        refusal: dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal,
        runId: String,
    ): IntrospectionRefusal = when (refusal) {
        is dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal.UnknownRun ->
            IntrospectionRefusal.UnknownRun(runId)
        is dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal.StorageError ->
            IntrospectionRefusal.StorageError(refusal.cause)
        is dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal.CursorBeyondTail ->
            IntrospectionRefusal.StorageError(
                "cursor beyond tail (requested ${refusal.requestedSequence}, " +
                    "tail ${refusal.tailSequence}) for $runId",
            )
    }

    /**
     * The run's terminal fact, projected from the bookend.
     *
     * The vocabulary is the RUN's, not the operation's: the bookend spells the
     * outcome as one of [RunOutcome]'s tokens, and those are the four words a
     * consumer of the run's terminal fact can rely on. A token this adapter
     * does not know is surfaced verbatim as [TerminalObservation.Other] rather
     * than collapsed into a failure — a vocabulary that grew is not a failure.
     */
    private fun terminalObservationOf(finished: RunFinished): TerminalObservation {
        val terminalAtMs: Long = finished.occurredAt.toEpochMilli()
        return when (finished.outcome) {
            RunOutcome.SUCCESS_TOKEN -> TerminalObservation.Succeeded(terminalAtMs)
            RunOutcome.UNSTABLE_TOKEN -> TerminalObservation.Unstable(terminalAtMs)
            RunOutcome.FAILURE_TOKEN -> TerminalObservation.Failed(RunOutcome.FAILURE_TOKEN, terminalAtMs)
            RunOutcome.ABORTED_TOKEN -> TerminalObservation.Aborted(terminalAtMs)
            else -> TerminalObservation.Other(finished.outcome, terminalAtMs)
        }
    }

    /**
     * The run's terminal bookend, read from the event lane.
     *
     * The lane serves the run's events in sequence order and the bookend is
     * the last one, so the read pages to the end of the run and keeps the last
     * `RunFinished` it sees. Three answers, because the read has three: the
     * lane served the run (with or without a bookend), the lane does not know
     * the run, or the lane could not be read.
     *
     * The third one matters. A store that fails is not a run that has no
     * terminal fact: folding a storage failure into "the run is not over"
     * would answer a question about the run with a fact about the read.
     */
    private fun readRunTerminal(runId: String): RunTerminalRead {
        var cursor: EventCursor? = null
        var finished: RunFinished? = null
        while (true) {
            val result = eventReads.readRecords(runId, cursor, EventQuery.All, EVENT_PAGE_SIZE)
            when (result) {
                is EventRecordReadResult.Refused -> return when (result.refusal) {
                    // The lane does not know the run. That is a fact about the
                    // run, not an error: the journal or the lease may still
                    // know it.
                    is EventRecordReadRefusal.UnknownRun -> RunTerminalRead.NoEvents
                    else -> RunTerminalRead.Unreadable(result.refusal.toString())
                }

                is EventRecordReadResult.Page -> {
                    finished = result.slice.decoded.filterIsInstance<RunFinished>().lastOrNull() ?: finished
                    if (!result.slice.hasMore) {
                        return RunTerminalRead.Read(finished)
                    }
                    cursor = result.slice.nextCursor
                }
            }
        }
    }

    /** What the event lane knows about the run's terminal state. */
    private sealed interface RunTerminalRead {

        /** The lane served the run. [finished] is its last bookend, if any. */
        data class Read(val finished: RunFinished?) : RunTerminalRead

        /** The lane does not know this run. */
        data object NoEvents : RunTerminalRead

        /** The lane could not be read. */
        data class Unreadable(val cause: String) : RunTerminalRead
    }

    private fun Throwable.shortDiagnostic(): String {
        val cls = this::class.simpleName ?: this.javaClass.name
        val msg = message?.take(120)?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }

    // RunStructure + inspectOperation ───────────────────
    // The composition is inherited: every primitive the methods
    // call is one already composed by `inspect` (OperationJournal.listForRun,
    // OperationJournal.get, OutputFrameIndex.streamsOfRun). The methods do
    // NOT introduce a new lease, a new scheduler, or a new query engine — they
    // read the existing stores and project to closed ADTs.
    // ## First-cut scope
    // The design's audit-cited group of operations by their enclosing
    // stage / step would require reading the typed `StageStarted` / `StepStarted`
    // events from the event plane. The published `PipelineEventEnvelope`
    // projection strips the type-specific fields, exposing only `kind`,
    // `occurredAt`, and `sequence`; the stageIndex / stepOrdinal / stageName
    // Are not on the envelope. A follow-up (or.x patch) that
    // exposes a typed event-shape read port can lift this limitation; until
    // Then, the first cut uses a SYNTHETIC single-stage single-step view
    // derived from the journal alone. Nothing pins that synthetic grouping
    // today: the test that used to is gone, and the capability it would have
    // protected is announced and NOT delivered
    // (`runtime.structure.v1` — see `SdkCapabilities`). A future that adds
    // event-shape correlation has to bring the test with it.
    // The closure is therefore partial: "synthetic grouping in the first
    // cut; event-driven grouping in a follow-up". The partiality is
    // honest: a consumer that needs true event-driven grouping must wait
    // for the follow-up, and the candidate's release-receipt documents this.

    override fun runStructure(
        runId: String,
        options: StructureRunOptions,
    ): RunStructureResult {
        return try {
            // Page 1: read the journal rows (the operations of the run).
            val journalOps: List<DurableOperation> = try {
                journal.listForRun(runId)
            } catch (e: Exception) {
                return RunStructureResult.Refused(
                    IntrospectionRefusal.StorageError(e.shortDiagnostic()),
                )
            }
            if (journalOps.isEmpty()) {
                return RunStructureResult.Structure(
                    structure = RunStructure.Empty,
                    cursor = null,
                )
            }
            // Read the typed events to drive
            // event-driven grouping. An absent plane, an unconfigured plane
            // Or an event-free run falls back to the synthetic view
            // all three describe a stable property of the build. A plane
            // that FAILED, or that does not know a run the journal has rows
            // for, refuses instead: those are claims about the READ, and
            // rendering them as "this run has no event-driven shape" hands
            // the caller a structure the run does not have.
            val typedEvents: List<TypedEvent> =
                when (val availability = readTypedEventsForRun(runId)) {
                    is TypedEventAvailability.Available -> availability.events
                    is TypedEventAvailability.Unreadable ->
                        return RunStructureResult.Refused(availability.refusal)
                    TypedEventAvailability.Unavailable -> emptyList()
                }
            val startOpOrdinal: Int = options.cursor?.operationOrdinal ?: 0
            if (startOpOrdinal >= journalOps.size) {
                return RunStructureResult.Structure(
                    structure = RunStructure.Stages(emptyList()),
                    cursor = null,
                )
            }
            val effectiveOps = journalOps.drop(startOpOrdinal).take(options.pageSize)
            val stages: List<StageSummary> = if (typedEvents.isNotEmpty()) {
                groupEventDriven(
                    runId = runId,
                    operations = effectiveOps,
                    typedEvents = typedEvents,
                )
            } else {
                listOf(buildSyntheticStage(runId, effectiveOps))
            }
            // Apply includeTerminalStages filter: when false, drop stages
            // whose every operation is in a terminal status. Both the
            // event-driven and synthetic paths share this rule.
            val filtered = if (options.includeTerminalStages) stages else {
                stages.filter { stage ->
                    stage.steps.any { step ->
                        step.operations.any { !it.status.isTerminal }
                    }
                }
            }
            val lastOpOrdinalIncluded = startOpOrdinal + effectiveOps.size - 1
            val nextCursor: StructureCursor? = if (lastOpOrdinalIncluded < journalOps.size - 1) {
                StructureCursor(
                    stageOrdinal = 0,
                    stepOrdinal = 0,
                    operationOrdinal = lastOpOrdinalIncluded + 1,
                )
            } else null
            RunStructureResult.Structure(
                structure = RunStructure.Stages(filtered),
                cursor = nextCursor,
            )
        } catch (e: Exception) {
            RunStructureResult.Refused(
                IntrospectionRefusal.StorageError(e.shortDiagnostic()),
            )
        }
    }

    override fun inspectOperation(
        operationId: String,
        attempt: Int,
    ): OperationInspectionResult {
        if (attempt < 1) {
            return OperationInspectionResult.Refused(
                IntrospectionRefusal.StorageError("attempt must be >= 1, got $attempt"),
            )
        }
        return try {
            val op: DurableOperation = try {
                journal.get(operationId, attempt)
            } catch (e: Exception) {
                return OperationInspectionResult.Refused(
                    IntrospectionRefusal.StorageError(e.shortDiagnostic()),
                )
            } ?: return OperationInspectionResult.Refused(
                // Typed UnknownOperation refusal for
                // The "no row" case. The first-cut used a generic
                // StorageError with a cause string; the audit's
                // recommendation is a typed case so a consumer can
                // `when` on it without parsing strings.
                IntrospectionRefusal.UnknownOperation(
                    operationId = operationId,
                    attempt = attempt,
                ),
            )
            val runId = op.input.runId
            val startedAtMs: Long? = try {
                journal.getStartedAt(operationId, attempt)
            } catch (e: Exception) {
                return OperationInspectionResult.Refused(
                    IntrospectionRefusal.StorageError(e.shortDiagnostic()),
                )
            }
            val endedAtMs: Long? = try {
                journal.getEndedAt(operationId, attempt)
            } catch (e: Exception) {
                return OperationInspectionResult.Refused(
                    IntrospectionRefusal.StorageError(e.shortDiagnostic()),
                )
            }
            val deadlineMs: Long? = try {
                journal.getDeadlineMs(operationId, attempt)
            } catch (e: Exception) {
                return OperationInspectionResult.Refused(
                    IntrospectionRefusal.StorageError(e.shortDiagnostic()),
                )
            }
            val streams = streamsForOperation(runId, operationId)
            OperationInspectionResult.Inspection(
                OperationInspection(
                    operationId = operationId,
                    attempt = attempt,
                    runId = runId,
                    stageOrdinal = 0, // Synthetic in first cut
                    stepOrdinal = 0,  // Synthetic in first cut
                    status = op.status,
                    startedAtMs = startedAtMs,
                    endedAtMs = endedAtMs,
                    deadlineMs = deadlineMs,
                    fingerprint = op.fingerprint.hex,
                    streams = streams,
                ),
            )
        } catch (e: Exception) {
            OperationInspectionResult.Refused(
                IntrospectionRefusal.StorageError(e.shortDiagnostic()),
            )
        }
    }

    // ── helpers (private) ──────────────────────────────────────────────

    /**
     * Read the declared stdout / stderr streams of an operation. Uses the
     * existing [OutputFrameIndex.streamsOfRun] projection and matches by
     * stream-name convention `runId::operationId::stdout` / `::stderr`.
     * The convention is the + publish pattern; when a run has no
     * declared streams, both fields are null.
*/
    private fun streamsForOperation(runId: String, operationId: String): OperationOutputStreams {
        val streams = try {
            frames.streamsOfRun(runId)
        } catch (_: Exception) {
            emptyList<OutputStreamId>()
        }
        val stdoutSuffix = "::$operationId::stdout"
        val stderrSuffix = "::$operationId::stderr"
        val stdout = streams.firstOrNull { it.value.endsWith(stdoutSuffix) }
        val stderr = streams.firstOrNull { it.value.endsWith(stderrSuffix) }
        return OperationOutputStreams(stdoutStream = stdout, stderrStream = stderr)
    }

    private fun startedAtMsOf(op: DurableOperation): Long? = try {
        journal.getStartedAt(op.id, op.attempt)
    } catch (_: Exception) {
        null
    }

    private fun endedAtMsOf(op: DurableOperation): Long? = try {
        journal.getEndedAt(op.id, op.attempt)
    } catch (_: Exception) {
        null
    }

    private fun stateOf(ops: List<DurableOperation>): StepState {
        if (ops.isEmpty()) return StepState.Pending
        val anyFailed = ops.firstOrNull {
            it.status.name == "FAILED" || it.status.name == "FAILED_TIMEOUT" || it.status.name == "DIVERGENT"
        }
        if (anyFailed != null) return StepState.Failed(anyFailed.status.name)
        if (ops.any { it.status.name == "RUNNING" }) return StepState.Running
        if (ops.all { it.status.name == "SUCCEEDED" }) return StepState.Succeeded
        if (ops.any { it.status.name == "ABORTED" }) return StepState.Cancelled
        if (ops.any { it.status.name == "PENDING" }) return StepState.Pending
        return StepState.Pending
    }

    // ── helpers (private) ──────────────────────────────────────────────

    /**
     * What the event shape plane answered for [runId].
     * Three cases because the plane has three, and two of them are claims
     * about the RUN while the third is a claim about the READ — the same
     * distinction [EventStorePresence] draws one function above.
     * - [Available] — typed events are present; grouping can be event-driven.
     * - [Unavailable] — there is no event-driven grouping to have. Three
     *   distinct inputs collapse here because they are the same *state of
     * The build*, not three ways of failing: the port is absent (the
     *   path, a build with no event plane wired), the run is known but has
     *   no typed events yet, or the plane reports it is not configured
     *   ([dev.rubentxu.pipeline.v2.events.shape.EventShapeRefusal.NoEventStore]).
     * The synthetic view is the correct answer for all three
     * - [Unreadable] — the plane could not answer. This is NOT a statement
     *   that the run has no events, and rendering it as one is the defect
     *   this case exists to prevent.
*/
    private sealed interface TypedEventAvailability {
        data class Available(val events: List<TypedEvent>) : TypedEventAvailability

        data object Unavailable : TypedEventAvailability

        data class Unreadable(val refusal: IntrospectionRefusal) : TypedEventAvailability
    }

    /**
     * Read the typed events for [runId] via the constructor's
     * [eventShapeRead] port, keeping [TypedEventAvailability.Unavailable]
     * (a legitimate state) distinct from
     * [TypedEventAvailability.Unreadable] (the plane failed, or does not
     * know the run).
     * The two are not the same fact and must not share an answer. Pre-fix
     * this function returned `emptyList()` for a `Refused` as well, so a
     * transient substrate failure and a build with no event plane both
     * produced an identical synthetic structure — a confident claim about
     * The run's shape that no caller could falsify. The fallback keeps
     * its meaning for [TypedEventAvailability.Unavailable], which describes
     * a stable property of the build; only a failure stops degrading.
*/
    private fun readTypedEventsForRun(runId: String): TypedEventAvailability {
        val port = eventShapeRead ?: return TypedEventAvailability.Unavailable
        return when (val r = port.eventShapesForRun(
            runId,
            dev.rubentxu.pipeline.v2.events.shape.EventShapeOptions.Default,
        )) {
            is dev.rubentxu.pipeline.v2.events.shape.EventShapeResult.Events ->
                TypedEventAvailability.Available(r.events)
            dev.rubentxu.pipeline.v2.events.shape.EventShapeResult.Empty ->
                TypedEventAvailability.Unavailable
            is dev.rubentxu.pipeline.v2.events.shape.EventShapeResult.Refused ->
                when (val refusal = r.refusal) {
                    // Not a failure: the plane is not configured, which is the
                    // same state as an absent port. Degrading here is correct.
                    dev.rubentxu.pipeline.v2.events.shape.EventShapeRefusal.NoEventStore ->
                        TypedEventAvailability.Unavailable
                    // An answer about the RUN. Reaching this branch means the
                    // journal returned rows for a run the event plane does not
                    // know; that disagreement is worth surfacing rather than
                    // hiding behind a synthetic view.
                    is dev.rubentxu.pipeline.v2.events.shape.EventShapeRefusal.UnknownRun ->
                        TypedEventAvailability.Unreadable(
                            IntrospectionRefusal.UnknownRun(runId),
                        )
                    is dev.rubentxu.pipeline.v2.events.shape.EventShapeRefusal.StorageError ->
                        TypedEventAvailability.Unreadable(
                            IntrospectionRefusal.StorageError(refusal.cause),
                        )
                }
        }
    }

    /**
     * Build the synthetic single-stage / single-step view (the
     * fallback). Every operation is placed under a synthetic stage 0
     * / step 0. Nothing pins this fallback today: the method is not
     * composed by anything, so the capability that would carry it is
     * announced and NOT delivered (`runtime.structure.v1`).
*/
    private fun buildSyntheticStage(
        runId: String,
        operations: List<DurableOperation>,
    ): StageSummary {
        val stepStartedAtMs: Long = operations
            .mapNotNull { startedAtMsOf(it) }
            .minOrNull() ?: 0L
        val stepFinishedAtMs: Long? = operations
            .mapNotNull { endedAtMsOf(it) }
            .maxOrNull()
        val operationSummaries: List<OperationSummary> = operations.map { op ->
            OperationSummary(
                operationId = op.id,
                attempt = op.attempt,
                status = op.status,
                startedAtMs = startedAtMsOf(op),
                endedAtMs = endedAtMsOf(op),
                fingerprint = op.fingerprint.hex,
                streams = streamsForOperation(
                    runId = op.input.runId,
                    operationId = op.id,
                ),
            )
        }
        val stepSummary = StepSummary(
            stepOrdinal = 0,
            stepKey = "synthetic-step",
            startedAtMs = stepStartedAtMs,
            finishedAtMs = stepFinishedAtMs,
            state = stateOf(operations),
            operations = operationSummaries,
        )
        return StageSummary(
            stageOrdinal = 0,
            stageIndex = "synthetic-stage",
            startedAtMs = stepStartedAtMs,
            finishedAtMs = stepFinishedAtMs,
            steps = listOf(stepSummary),
        )
    }

    /**
     * Group operations by their enclosing stage and step using the
     * typed events. An operation's stage is the most recent
     * [StageStartedTyped] whose `occurredAtMs` is `<=` the operation's
     * `startedAtMs`; the operation's step is the most recent
     * [StepStartedTyped] for that stage. When no events match, the
     * operation falls back to a synthetic "ungrouped" stage 0 / step 0.
     * The grouping is in execution order: stages by `stageOrdinal`,
     * steps within a stage by `stepKey`, operations within a step by
     * their journal `startedAtMs`.
*/
    private fun groupEventDriven(
        runId: String,
        operations: List<DurableOperation>,
        typedEvents: List<TypedEvent>,
    ): List<StageSummary> {
        val stageStartEvents = typedEvents.filterIsInstance<StageStartedTyped>()
        val stepStartEvents = typedEvents.filterIsInstance<StepStartedTyped>()
        val groupKey: (DurableOperation) -> Triple<Int, String, String> = { op ->
            val opMs = startedAtMsOf(op)
            val stage = stageStartEvents
                .filter { opMs == null || it.occurredAtMs <= opMs }
                .maxByOrNull { it.occurredAtMs }
            val step = stepStartEvents
                .filter { stage == null || it.stageOrdinal == stage.stageOrdinal }
                .filter { opMs == null || it.occurredAtMs <= opMs }
                .maxByOrNull { it.occurredAtMs }
            val stageOrd = stage?.stageOrdinal ?: 0
            val stageName = stage?.stageName ?: "ungrouped-stage"
            val stepKey = step?.stepKey ?: "ungrouped-step"
            Triple(stageOrd, "$stageOrd::$stageName", "$stageOrd::$stepKey")
        }
        val groups: Map<Triple<Int, String, String>, List<DurableOperation>> =
            operations.groupBy(groupKey)
        return groups.entries
            .sortedBy { it.key.first }
            .map { (key, ops) ->
                val stageOrd = key.first
                val stageName = key.second.substringAfter("::")
                val stageStartedAtMs = ops
                    .mapNotNull { startedAtMsOf(it) }
                    .minOrNull() ?: 0L
                val stageFinishedAtMs = ops
                    .mapNotNull { endedAtMsOf(it) }
                    .maxOrNull()
                val stepGroupKey: (DurableOperation) -> String = { op ->
                    val opMs = startedAtMsOf(op)
                    val step = stepStartEvents
                        .filter { it.stageOrdinal == stageOrd }
                        .filter { opMs == null || it.occurredAtMs <= opMs }
                        .maxByOrNull { it.occurredAtMs }
                    step?.stepKey ?: "ungrouped-step"
                }
                val stepGroups: Map<String, List<DurableOperation>> = ops.groupBy(stepGroupKey)
                val stepSummaries: List<StepSummary> = stepGroups.entries
                    .sortedBy { it.key }
                    .map { (stepKey, stepOps) ->
                        val stepStartedAtMs: Long = stepOps
                            .mapNotNull { startedAtMsOf(it) }
                            .minOrNull() ?: 0L
                        val stepFinishedAtMs: Long? = stepOps
                            .mapNotNull { endedAtMsOf(it) }
                            .maxOrNull()
                        val operationSummaries: List<OperationSummary> = stepOps.map { op ->
                            OperationSummary(
                                operationId = op.id,
                                attempt = op.attempt,
                                status = op.status,
                                startedAtMs = startedAtMsOf(op),
                                endedAtMs = endedAtMsOf(op),
                                fingerprint = op.fingerprint.hex,
                                streams = streamsForOperation(
                                    runId = op.input.runId,
                                    operationId = op.id,
                                ),
                            )
                        }
                        StepSummary(
                            stepOrdinal = 0,
                            stepKey = stepKey,
                            startedAtMs = stepStartedAtMs,
                            finishedAtMs = stepFinishedAtMs,
                            state = stateOf(stepOps),
                            operations = operationSummaries,
                        )
                    }
                StageSummary(
                    stageOrdinal = stageOrd,
                    stageIndex = stageName,
                    startedAtMs = stageStartedAtMs,
                    finishedAtMs = stageFinishedAtMs,
                    steps = stepSummaries,
                )
            }
    }
}

/**
 * What the event plane answered when asked whether [runId] has an envelope.
 * Three states because [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort]
 * has three, and two of them are claims about the RUN rather than about the READ:
 * [Present] ("I looked; the run is here") and [Absent] ("I looked; the run is
 * unknown to me"). The third, [Unreadable], is a claim about neither — the store
 * failed — and it carries the refusal already translated into this port's
 * vocabulary so no caller re-derives it.
 * The distinction that matters: `Absent` is an answer a reader acts on, and
 * `Unreadable` is not. Collapsing the second into the first reports a transient
 * failure as a confident statement that a run does not exist.
*/
private sealed interface EventStorePresence {
    data object Present : EventStorePresence
    data object Absent : EventStorePresence
    data class Unreadable(val refusal: IntrospectionRefusal) : EventStorePresence
}

/**
 * How many events one page of the terminal read carries.
 *
 * The read pages to the END of the run, because the bookend is the last
 * event. A run with many events therefore costs more than one read, and this
 * is the size of each step of that walk.
 */
private const val EVENT_PAGE_SIZE: Int = 512
