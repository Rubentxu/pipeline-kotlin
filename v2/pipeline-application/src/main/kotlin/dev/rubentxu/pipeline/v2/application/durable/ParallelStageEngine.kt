package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.BranchTerminal
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.CompositeOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ParallelAggregateId
import dev.rubentxu.pipeline.v2.domain.durable.ParallelAggregateSnapshot
import dev.rubentxu.pipeline.v2.domain.durable.ParallelBranchChildSnapshot
import dev.rubentxu.pipeline.v2.domain.durable.ParallelDecision
import dev.rubentxu.pipeline.v2.domain.durable.ParallelReconciler
import dev.rubentxu.pipeline.v2.domain.durable.ParallelReconciliationInput
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyAggregateIdentity
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.async
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * TRAIN H4 / PR-020 — the parallel aggregate, out of the step-execution spine.
 *
 * A parallel stage is not a Step. It is a composite whose durable identity is an AGGREGATE row
 * reconciled against every branch child's row, and whose outcome is a fold over typed branch
 * outcomes. That is a different job from "take this Step and make it happen", so it gets its
 * own unit rather than living as a large private method of the Step dispatcher.
 *
 * The split is also the honest one for testing. [aggregateAction] is PURE — no journal, no
 * clock, no coroutine — so the whole parallel law (fail-closed on divergence, no duplicate row
 * on reuse, RUNNING written before any branch launches) is decidable without running a pipeline.
 *
 * One direction only: this engine dispatches branch steps through [StepDispatchEngine], which
 * knows nothing about parallel aggregates. There is no cycle and no second dispatch spine.
 */
internal class ParallelStageEngine(
    private val stepDispatch: StepDispatchEngine,
    private val journal: OperationJournal,
    private val eventSink: EventSink,
    private val clock: Clock,
    private val runLifecycle: RunLifecycleEngine,
    private val controlDirRoot: Path?,
    private val workspaceBase: Path?,
) {

    suspend fun runParallelStage(
        stage: StageNode,
        stageIndex: Int,
        stageShOptions: ShOptions,
        runId: RunId,
        executionContext: ExecutionContext,
    ): StepOutcome {
        val branches = (stage.body as? StageBody.Parallel)?.branches
            ?: throw EngineInvariantViolation("runParallelStage called for non-parallel stage '${stage.name}'")

        // INC-007 (+1 helper, canonical coordinator dispatchBody sibling).
        //
        // Dispatches parallel branch bodies with fresh per-child journal rows so
        // every branch child gets independent durable rows and a durable rerun
        // reuses completed branch work instead of duplicating it.
        // E-EM-11 Z2: the canonical stage law is StageStarted < stage execution <
        // StageFinished for EVERY admitted stage, including parallel bodies. The
        // parallel path previously forked before the linear-path StageStarted emitter,
        // an accidental implementation difference, not a different Stage semantic.
        // Exactly one StageStarted, same stage identity as the StageFinished emitted
        // by the caller's continuation handling.
        runLifecycle.stageStarted(runId, stageIndex, stage.name)

        // PAR-D D2: plan the parallel aggregate from durable facts BEFORE any branch
        // launches. The reconciler is pure; this class is the single writer.
        val aggregateId = ParallelAggregateId(runId = runId.value, stageIndex = stageIndex)
        val aggregateInput = OperationInput(
            stepId = BodyAggregateIdentity.ParallelStageAggregate.key.value,
            params = mapOf("control" to kotlinx.serialization.json.JsonPrimitive("aggregate")),
            runId = runId.value,
            attempt = 1,
        )
        val aggregateFingerprint = Fingerprint.compute(
            aggregateInput,
            BodyAggregateIdentity.ParallelStageAggregate.fingerprintKey(stageIndex, branches.map { it.name }),
            ReplayPolicy.MEMOIZED,
            1,
        )
        val aggregateRow = journal.get(parallelControlOpId(runId.value, stageIndex), 1)?.let {
            ParallelAggregateSnapshot(
                id = aggregateId,
                fingerprint = it.fingerprint,
                status = it.status,
                semanticOutcome = decodeBranchTerminal(it.output?.result),
            )
        }
        val childSnapshots = journal.listForRun(runId.value)
            .mapNotNull { op ->
                // Branch durable identity lives in the FIRST bodyPath segment
                // ("b{N}:branch"), never in an OpId -b{N} suffix (the canonical
                // parallel child key has no -b segment). Parse it deterministically.
                val branchIndex = Regex("-bp\\d+-b(\\d+):branch(-|$)").find(op.id)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                ParallelBranchChildSnapshot(
                    branchIndex = branchIndex,
                    childIndex = op.attempt,
                    status = op.status,
                )
            }
            .groupBy { it.branchIndex }
        val decision = ParallelReconciler.reconcile(
            ParallelReconciliationInput(
                aggregateId = aggregateId,
                currentFingerprint = aggregateFingerprint,
                aggregateRow = aggregateRow,
                branchCount = branches.size,
                childrenByBranch = childSnapshots,
            ),
        )

        val aggregateOpId = parallelControlOpId(runId.value, stageIndex)
        val action = aggregateAction(
            decision = decision,
            aggregateOpId = aggregateOpId,
            aggregateFingerprint = aggregateFingerprint,
            aggregateInput = aggregateInput,
            aggregateRowPresent = aggregateRow != null,
        )
        // Interpretation of the decision, not its making: the pure mapping above named the row to
        // persist and the stage outcome to report; this class performs the single journal write and
        // returns. The single-writer law is unchanged — exactly one writer, still this engine.
        val branchesToRun: List<Int> = when (action) {
            // Reuse arms report the durable outcome and write nothing: the row already exists.
            is AggregateAction.Settle -> return action.outcome

            is AggregateAction.Close -> {
                journal.append(action.row)
                return action.outcome
            }

            is AggregateAction.Launch -> {
                action.runningRow?.let { journal.append(it) }
                action.branches
            }
        }

        val branchOutcomes = launchBranches(branches, branchesToRun, runId, stageIndex, stageShOptions, executionContext)

        // PAR-D D2: fold the typed branch outcomes (exact semantic knowledge at fresh
        // execution time) and close the aggregate row with the lossless carrier.
        val outcomeByBranch = branchesToRun.mapIndexed { i, branchIdx ->
            val o = branchOutcomes[i]
            branchIdx to when (o) {
                is StepOutcome.Failure -> BranchTerminal.Failed(o.failure.message)
                is StepOutcome.Unstable -> BranchTerminal.Unstable
                else -> BranchTerminal.Succeeded
            }
        }.toMap()
        val fold = dev.rubentxu.pipeline.v2.domain.durable.foldAwaitAll(outcomeByBranch)
        val aggregateStatus = aggregateStatusOf(fold)
        journal.append(aggregateTerminalRow(aggregateOpId, aggregateFingerprint, aggregateInput, fold, aggregateStatus))

        // ALL_COMPLETE: first failure (deterministic: lowest branch index) is the aggregate.
        return branchOutcomes.firstOrNull { it is StepOutcome.Failure }
            ?: branchOutcomes.firstOrNull { it is StepOutcome.Unstable }
            ?: StepOutcome.Success
    }

    /**
     * PAR-D structured concurrency: the join runs inside a caller-bound supervisorScope; branch
     * outcomes are typed VALUES (contained), never exceptions used as control flow. The scope
     * exits only when every branch resolves, so no coroutine survives the parallel stage
     * lifecycle. A branch CancellationException stays an execution mechanism — it is converted
     * here, never into a durable terminal truth.
     *
     * The join is all-or-nothing by construction: every launched branch is awaited before this
     * returns, so a fast failure can never leave a slow sibling still writing journal rows.
     */
    private suspend fun launchBranches(
        branches: List<StageNode>,
        branchesToRun: List<Int>,
        runId: RunId,
        stageIndex: Int,
        stageShOptions: ShOptions,
        executionContext: ExecutionContext,
    ): List<StepOutcome> {
        val deferred: List<kotlinx.coroutines.Deferred<StepOutcome>> = kotlinx.coroutines.supervisorScope {
            branchesToRun.map { branchIndex ->
                val branch = branches[branchIndex]
                async(kotlinx.coroutines.Dispatchers.Default) {
                    eventSink.append(
                        dev.rubentxu.pipeline.v2.events.ParallelBranchStarted(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            branchIndex = branchIndex,
                            branchName = branch.name,
                            parentStageIndex = stageIndex,
                        ),
                    )
                    val branchOutcome = executeBranchSteps(
                        branch, runId, stageIndex, branchIndex, stageShOptions, executionContext,
                    )
                    val outcomeText = when (branchOutcome) {
                        is StepOutcome.Failure -> "failure"
                        is StepOutcome.Unstable -> "unstable"
                        else -> "success"
                    }
                    eventSink.append(
                        dev.rubentxu.pipeline.v2.events.ParallelBranchFinished(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            branchIndex = branchIndex,
                            branchName = branch.name,
                            parentStageIndex = stageIndex,
                            outcome = outcomeText,
                        ),
                    )
                    branchOutcome
                }
            }
        }
        return deferred.map { it.await() }
    }

    /** Deterministic control OpId for the parallel aggregate (PAR-D typed identity). */
    private fun parallelControlOpId(runIdValue: String, stageIndex: Int): String =
        OpId(runIdValue, stageIndex, -1, bodyPath = listOf(BlockSegment("0:parallel-control"))).format()

    /**
     * What an aggregate reconciliation decision requires, decided PURELY from the decision and
     * the durable facts. Closed, because "report a durable outcome", "close the aggregate and
     * report", and "keep it running and launch these branches" are three different obligations
     * and a caller that guessed between them would either duplicate a row or lose one.
     *
     * The row travels as a VALUE so this function never touches the journal. The engine stays
     * the single writer; it just no longer decides what to write.
     */
    private sealed interface AggregateAction {
        /**
         * The durable outcome is replayed. NO row is written: the row that carries this outcome
         * already exists, and rewriting it would be a second write of the same truth.
         */
        data class Settle(val outcome: StepOutcome) : AggregateAction

        /** Close the aggregate with [row], then report [outcome]. Exactly one write. */
        data class Close(val row: CompositeOperation, val outcome: StepOutcome) : AggregateAction

        /**
         * Keep the aggregate RUNNING and launch [branches]. [runningRow] is non-null only when
         * the aggregate row must be (re)written before launching; null means it is already
         * RUNNING and writing again would be a duplicate.
         */
        data class Launch(val runningRow: CompositeOperation?, val branches: List<Int>) : AggregateAction
    }

    /**
     * The PURE mapping from a reconciliation decision to the obligation it names. No journal, no
     * coroutine, no clock: given the same decision and durable facts it always returns the same
     * action, which is what makes the parallel law checkable without running a pipeline.
     */
    private fun aggregateAction(
        decision: ParallelDecision,
        aggregateOpId: String,
        aggregateFingerprint: Fingerprint,
        aggregateInput: OperationInput,
        aggregateRowPresent: Boolean,
    ): AggregateAction = when (decision) {
        // A divergent or ambiguous aggregate is fail-closed: the row records WHY, and the stage
        // reports the same reason. Both carry the decision's own text, never a paraphrase.
        is ParallelDecision.RejectDivergence -> AggregateAction.Close(
            aggregateTerminalRow(
                aggregateOpId, aggregateFingerprint, aggregateInput,
                BranchTerminal.Failed(decision.reason), OperationStatus.DIVERGENT,
            ),
            StepOutcome.Failure(
                PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE, decision.reason),
            ),
        )

        is ParallelDecision.RejectAmbiguousOutcome -> AggregateAction.Close(
            aggregateTerminalRow(
                aggregateOpId, aggregateFingerprint, aggregateInput,
                BranchTerminal.Failed(decision.reason), OperationStatus.ABORTED,
            ),
            StepOutcome.Failure(
                PipelineFailure(dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE, decision.reason),
            ),
        )

        // Reuse arms replay a durable truth and write nothing.
        is ParallelDecision.ReuseSuccess -> AggregateAction.Settle(StepOutcome.Success)
        is ParallelDecision.ReuseUnstable -> AggregateAction.Settle(StepOutcome.Unstable)
        is ParallelDecision.ReuseFailure -> AggregateAction.Settle(
            StepOutcome.Failure(
                // The exact semantic outcome is durable; surface it as a canonical failure.
                PipelineFailure(
                    dev.rubentxu.pipeline.v2.domain.FailureKind.USER,
                    "parallel aggregate previously failed",
                ),
            ),
        )

        is ParallelDecision.CloseFromChildren -> AggregateAction.Close(
            aggregateTerminalRow(
                aggregateOpId, aggregateFingerprint, aggregateInput,
                decision.outcome, aggregateStatusOf(decision.outcome),
            ),
            when (val outcome = decision.outcome) {
                is BranchTerminal.Succeeded -> StepOutcome.Success
                is BranchTerminal.Unstable -> StepOutcome.Unstable
                is BranchTerminal.Failed -> StepOutcome.Failure(
                    PipelineFailure(
                        dev.rubentxu.pipeline.v2.domain.FailureKind.USER,
                        outcome.message ?: "parallel branch failed",
                    ),
                )
            },
        )

        // Single writer: the aggregate must be RUNNING in the journal BEFORE any branch launches.
        is ParallelDecision.Start -> AggregateAction.Launch(
            compositeRunningRow(aggregateOpId, aggregateFingerprint, aggregateInput),
            decision.branches,
        )

        // Already RUNNING, or absent for a pre-PAR-D journal: write only when it is actually absent,
        // so a resume never duplicates a row that is already there.
        is ParallelDecision.ResumeBranches -> AggregateAction.Launch(
            if (aggregateRowPresent) null else compositeRunningRow(aggregateOpId, aggregateFingerprint, aggregateInput),
            decision.branches,
        )
    }

    /** The durable status that carries a branch terminal, in one place so the row and the
     *  reported outcome can never disagree about it. */
    private fun aggregateStatusOf(outcome: BranchTerminal): OperationStatus = when (outcome) {
        is BranchTerminal.Succeeded -> OperationStatus.SUCCEEDED
        is BranchTerminal.Unstable -> OperationStatus.ABORTED
        is BranchTerminal.Failed -> OperationStatus.FAILED
    }

    private fun compositeRunningRow(
        opId: String,
        fingerprint: Fingerprint,
        input: OperationInput,
    ): CompositeOperation = CompositeOperation(
        id = opId,
        fingerprint = fingerprint,
        input = input,
        output = null,
        status = OperationStatus.RUNNING,
        attempt = 1,
        subOperations = emptyList(),
    )

    /** Terminal aggregate row carrying the exact typed outcome (lossless carrier). */
    private fun aggregateTerminalRow(
        opId: String,
        fingerprint: Fingerprint,
        input: OperationInput,
        outcome: BranchTerminal,
        status: OperationStatus,
    ): CompositeOperation {
        val result = kotlinx.serialization.json.buildJsonObject {
            put("outcome", kotlinx.serialization.json.JsonPrimitive(outcome.asText))
            if (outcome is BranchTerminal.Failed && outcome.message != null) {
                put("message", kotlinx.serialization.json.JsonPrimitive(outcome.message))
            }
        }
        return CompositeOperation(
            id = opId,
            fingerprint = fingerprint,
            input = input,
            output = OperationOutput(result, durationMs = 0L, finishedAt = clock.now().toEpochMilli()),
            status = status,
            attempt = 1,
            subOperations = emptyList(),
        )
    }

    private fun decodeBranchTerminal(result: kotlinx.serialization.json.JsonElement?): BranchTerminal? {
        val obj = result as? kotlinx.serialization.json.JsonObject ?: return null
        val text = (obj["outcome"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        return when (text) {
            "success" -> BranchTerminal.Succeeded
            "unstable" -> BranchTerminal.Unstable
            "failure" -> BranchTerminal.Failed((obj["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
            else -> null
        }
    }

    /**
     * Dispatches one parallel branch's linear steps through the shared spine with
     * branch-indexed journal identity. A branch failure is contained: it becomes
     * the branch outcome, never a throw (the join waits for all branches).
     */
    private suspend fun executeBranchSteps(
        branch: StageNode,
        runId: RunId,
        stageIndex: Int,
        branchIndex: Int,
        stageShOptions: ShOptions,
        executionContext: ExecutionContext,
    ): StepOutcome {
        // CTX-P2 branch derivation: immutable value; today branchContext == parentContext.
        val branchContext = executionContext
        // SB-S-008 / WU-LPR-071: parallel branches get ISOLATED cwds. The workspace is
        // derived purely from (controlDirRoot, stageIndex, branchIndex) and carried in
        // the branch's immutable ShOptions copy — no coordinator state is mutated and
        // no branch can observe a sibling's working directory. With --workspace
        // (workspaceBase set) the shared project workspace wins, per WU-LPR-062.
        val branchShOptions = if (controlDirRoot != null && workspaceBase == null) {
            val resolver = WorkspaceResolver(controlDirRoot, workspaceBase)
            val branchWorkspace = resolver.ensureCreated(
                resolver.resolve("stage-$stageIndex-b$branchIndex", 0)
            )
            stageShOptions.copy(workspaceRoot = branchWorkspace)
        } else {
            stageShOptions
        }
        // P2 fitness: branches must observe an explicitly derived context, never coordinator state.
        val steps = (branch.body as? StageBody.Steps)?.steps
            ?: throw EngineInvariantViolation("Parallel branch '${branch.name}' has a non-linear body")
        var outcome: StepOutcome = StepOutcome.Success
        for (stepIndex in steps.indices) {
            val step = steps[stepIndex]
            // Branch durable identity: dispatch() derives the journal key from the
            // bodyPath it is handed, so the branch index is encoded as the FIRST
            // deterministic BlockSegment ("b{branchIndex}:branch"). Two branches'
            // children can never collide; a durable rerun reuses the same keys.
            val bodyPath = listOf(
                BlockSegment("b$branchIndex:branch"),
                BlockSegment(stepIndex, step.pluginStepId),
            )
            val dispatchedBranch = stepDispatch.dispatch(
                step,
                runId,
                branch.name,
                stageIndex,
                stepIndex,
                branchShOptions,
                bodyPath,
                branchContext,
            )
            // R14: a parallel branch only needs to KNOW the outcome, so it projects it here. The
            // carrier is not destroyed to make that possible — `Dispatched` still holds the encoded
            // value, and the branch aggregate is free to read it if it ever needs to.
            val stepOutcome = dispatchedBranch.result.outcome
            when (stepOutcome) {
                is StepOutcome.Failure, is StepOutcome.Unstable -> return stepOutcome
                else -> { /* continue */ }
            }
        }
        return outcome
    }
}
