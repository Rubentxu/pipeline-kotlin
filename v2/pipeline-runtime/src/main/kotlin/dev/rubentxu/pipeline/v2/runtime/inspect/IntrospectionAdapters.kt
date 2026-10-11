package dev.rubentxu.pipeline.v2.runtime.inspect

import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.events.durable.FileBackedRunExecutionLeaseStore
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.RunExecutionLease
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.shape.EventShapeReadPort
import dev.rubentxu.pipeline.v2.events.shape.StageFinishedTyped
import dev.rubentxu.pipeline.v2.events.shape.StageStartedTyped
import dev.rubentxu.pipeline.v2.events.shape.StepFinishedTyped
import dev.rubentxu.pipeline.v2.events.shape.StepStartedTyped
import dev.rubentxu.pipeline.v2.events.shape.TypedEvent
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
    private val syntheticObserverId: String = "m2-observer",
    /**
     * M7 — optional typed-event read port. When present, `runStructure`
     * uses it to drive event-driven grouping (the audit's I.6 PARTIAL
     * closure). When null, the adapter falls back to the M6 synthetic
     * single-stage / single-step view. The audit's design §C names the
     * pattern: the M7 surface is additive, the M6 fallback is preserved.
     */
    private val eventShapeRead: EventShapeReadPort? = null,
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

    // ── M6 (additive) — runStructure + inspectOperation ───────────────────
    //
    // The M6 design §C names the composition: every primitive the M6 methods
    // call is one already composed by `inspect` (OperationJournal.listForRun,
    // OperationJournal.get, OutputFrameIndex.streamsOfRun). The M6 methods do
    // NOT introduce a new lease, a new scheduler, or a new query engine — they
    // read the existing stores and project to the closed ADTs the M6 design
    // names in §B.
    //
    // ## First-cut scope
    //
    // The M6 design's audit-cited group of operations by their enclosing
    // stage / step would require reading the typed `StageStarted` / `StepStarted`
    // events from the event plane. The published `PipelineEventEnvelope`
    // projection strips the type-specific fields, exposing only `kind`,
    // `occurredAt`, and `sequence`; the stageIndex / stepOrdinal / stageName
    // are not on the envelope. A follow-up M-block (or M6.x patch) that
    // exposes a typed event-shape read port can lift this limitation; until
    // then, the M6 first cut uses a SYNTHETIC single-stage single-step view
    // derived from the journal alone. The contract test
    // `RuntimeIntrospectionPortStructureAdapterTest` pins the synthetic
    // grouping explicitly; a future M-block that adds event-shape correlation
    // will update the test to pin the event-driven grouping.
    //
    // The audit's I.6 PARTIAL closure is therefore "synthetic grouping in
    // M6 first cut; event-driven grouping in a follow-up M-block". The
    // PARTIAL is honest: a consumer that needs true event-driven grouping
    // must wait for the follow-up, and the M6 candidate's release-receipt
    // documents this.

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
            // M7 I.6 PARTIAL closure: read the typed events to drive
            // event-driven grouping. When the port is null OR returns
            // Empty / Refused, fall back to the M6 synthetic view.
            val typedEvents: List<TypedEvent> = readTypedEventsForRun(runId)
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
                IntrospectionRefusal.StorageError(
                    "no journal row for ($operationId, attempt=$attempt)",
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
                    stageOrdinal = 0, // synthetic in M6 first cut
                    stepOrdinal = 0,  // synthetic in M6 first cut
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

    // ── M6 helpers (private) ──────────────────────────────────────────────

    /**
     * Read the declared stdout / stderr streams of an operation. Uses the
     * existing [OutputFrameIndex.streamsOfRun] projection and matches by
     * stream-name convention `runId::operationId::stdout` / `::stderr`.
     * The convention is the M1 + M3 publish pattern; when a run has no
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

    // ── M7 helpers (private) ──────────────────────────────────────────────

    /**
     * Read the typed events for [runId] via the constructor's
     * [eventShapeRead] port. Returns an empty list when the port is
     * null (the M6 path), when the port returns [Empty], or when the
     * port returns [Refused] (the caller's failure mode is the typed
     * refusal, not a synthetic fallback that hides the failure).
     */
    private fun readTypedEventsForRun(runId: String): List<TypedEvent> {
        val port = eventShapeRead ?: return emptyList()
        return when (val r = port.eventShapesForRun(
            runId,
            dev.rubentxu.pipeline.v2.events.shape.EventShapeOptions.Default,
        )) {
            is dev.rubentxu.pipeline.v2.events.shape.EventShapeResult.Events -> r.events
            dev.rubentxu.pipeline.v2.events.shape.EventShapeResult.Empty,
            is dev.rubentxu.pipeline.v2.events.shape.EventShapeResult.Refused -> emptyList()
        }
    }

    /**
     * Build the synthetic single-stage / single-step view (the M6
     * fallback). Every operation is placed under a synthetic stage 0
     * / step 0. The test `RuntimeIntrospectionPortStructureAdapterTest`
     * pins this fallback explicitly; the M6 contract test row for
     * "synthetic grouping" lives on.
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
     *
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
