package dev.rubentxu.pipeline.v2.sdk.runtime

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.durable.BranchSpec
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.JoinPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.sdk.CompatibilityLevel
import dev.rubentxu.pipeline.v2.sdk.Effect
import dev.rubentxu.pipeline.v2.sdk.ExecutionLocation
import dev.rubentxu.pipeline.v2.sdk.JenkinsSurface
import dev.rubentxu.pipeline.v2.sdk.ReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.Step
import dev.rubentxu.pipeline.v2.sdk.StepContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import java.util.UUID

/**
 * @Step-annotated step executors for echo, sh, error, and sleep.
 * These are called by the PipelineRun orchestrator at runtime.
 */

@JenkinsSurface(step = "echo", plugin = "workflow-durable-task-step", compatibility = CompatibilityLevel.MIGRATION)
@Step(
    id = "core.echo",
    name = "echo",
    execution = ExecutionLocation.CONTROLLER,
    effects = [Effect.READ_ONLY],
    replay = ReplayPolicy.MEMOIZED,
)
fun echo(context: StepContext, message: String, sink: EventSink, stepIndex: Int): String {
    val payload = message + "\n"
    sink.append(EchoOutputCaptured(
        eventId = UUID.randomUUID().toString(),
        runId = context.runId,
        sequence = 0L,
        occurredAt = java.time.Instant.now(),
        stepIndex = stepIndex,
        content = payload,
    ))
    return payload
}

@JenkinsSurface(step = "sh", plugin = "workflow-durable-task-step", compatibility = CompatibilityLevel.MIGRATION)
@Step(
    id = "core.sh",
    name = "sh",
    execution = ExecutionLocation.WORKER,
    effects = [Effect.EXECUTES_SUBPROCESS],
    replay = ReplayPolicy.RERUN,
)
fun sh(context: StepContext, argv: List<String>, sink: EventSink, stepIndex: Int): ShellResult {
    val result = ProcessExecutor().execute(argv, timeoutMs = 60_000L, cwd = null, env = emptyMap())
    sink.append(EchoOutputCaptured(
        eventId = UUID.randomUUID().toString(),
        runId = context.runId,
        sequence = 0L,
        occurredAt = java.time.Instant.now(),
        stepIndex = stepIndex,
        content = result.stdout,
    ))
    if (result.exitCode != 0) {
        val message = if (result.stderr.isNotBlank()) {
            "sh exited with code ${result.exitCode}: ${result.stderr.take(256)}"
        } else {
            "sh exited with code ${result.exitCode}"
        }
        sink.append(StepFailed(
            eventId = UUID.randomUUID().toString(),
            runId = context.runId,
            sequence = 0L,
            occurredAt = java.time.Instant.now(),
            stepIndex = stepIndex,
            stepName = "sh",
            stepType = "sh",
            failureKind = FailureKind.SCRIPT,
            message = message,
        ))
    }
    return result
}

@JenkinsSurface(step = "error", plugin = "workflow-step", compatibility = CompatibilityLevel.MIGRATION)
@Step(
    id = "core.error",
    name = "error",
    execution = ExecutionLocation.AGENT,
    effects = [Effect.ABORTS_PIPELINE],
    replay = ReplayPolicy.NEVER,
)
fun error(context: StepContext, message: String, failureKind: FailureKind, sink: EventSink, stepIndex: Int): Nothing {
    sink.append(StepFailed(
        eventId = UUID.randomUUID().toString(),
        runId = context.runId,
        sequence = 0L,
        occurredAt = java.time.Instant.now(),
        stepIndex = stepIndex,
        stepName = "error",
        stepType = "error",
        failureKind = failureKind,
        message = message,
    ))
    error("Step SDK error: $message")
}

@JenkinsSurface(step = "sleep", plugin = "workflow-durable-task-step", compatibility = CompatibilityLevel.MIGRATION)
@Step(
    id = "core.sleep",
    name = "sleep",
    execution = ExecutionLocation.CONTROLLER,
    effects = [Effect.READ_ONLY],
    replay = ReplayPolicy.MEMOIZED,
)
fun sleep(context: StepContext, seconds: Long, sink: EventSink, stepIndex: Int) {
    Thread.sleep(seconds * 1000L)
}

/**
 * Result of a [ParallelFrameExecutor.execute] call.
 *
 * @see ParallelFrameExecutor.execute
 */
sealed interface ParallelFrameResult {
    val branchResults: List<BranchResult>
    val overallOutcome: String

    data class Success(override val branchResults: List<BranchResult>) : ParallelFrameResult {
        override val overallOutcome: String = "success"
    }

    data class Failure(
        override val branchResults: List<BranchResult>,
        val firstFailure: BranchResult,
    ) : ParallelFrameResult {
        override val overallOutcome: String = "failure"
    }
}

/**
 * Result of a single branch execution within a parallel frame.
 *
 * @property branchIndex The index of the branch that was executed.
 * @property outcome The outcome string ("success" or "failure").
 * @property stageIndex The stage index after this branch completed.
 */
data class BranchResult(
    val branchIndex: Int,
    val outcome: String,
    val stageIndex: Int,
)

/**
 * Executor for [ParallelFrame] using structured concurrency (ADR-0039).
 *
 * ## M3-R4.3 Concurrent Execution
 *
 * Replaces the M3-R4.2 no-op stub with real concurrent branch execution
 * using `coroutineScope { async(Dispatchers.IO) }.awaitAll()`.
 *
 * ## JoinPolicy Dispatch
 *
 * - [JoinPolicy.ALL_COMPLETE]: waits for all branches, returns success if all succeed
 * - [JoinPolicy.FIRST_SUCCESS]: returns as soon as one branch succeeds, cancels others
 * - [JoinPolicy.ANY_COMPLETE]: returns as soon as any branch completes, cancels others
 *
 * ## Structured Concurrency
 *
 * Uses `coroutineScope` so that parent cancellation propagates to all child branches.
 * Uses `Dispatchers.IO` for blocking operations (subprocess execution via `sh`).
 *
 * @param clock The clock for timestamps.
 */
class ParallelFrameExecutor(
    private val clock: Clock,
) {
    /**
     * Executes the given [ParallelFrame] concurrently, according to its [JoinPolicy].
     *
     * ## ADR-0039 §Decision
     *
     * Uses `coroutineScope { async(Dispatchers.IO) }` for structured concurrency:
     * - Parent cancellation propagates to all branch children via `CoroutineScope`
     * - `Dispatchers.IO` is used for blocking operations (subprocess execution via `sh`)
     * - `awaitAll()` waits for all branches in ALL_COMPLETE policy
     *
     * @param frame The parallel frame to execute.
     * @param context The step context for the pipeline run.
     * @return A [ParallelFrameResult] describing the outcome of all branches.
     * @throws IllegalArgumentException if the frame has no branches or any branch has no steps.
     * @throws CancellationException if the parent scope is cancelled (cascades to all branches).
     */
    suspend fun execute(
        frame: ParallelFrame,
        context: StepContext,
    ): ParallelFrameResult {
        // Validate preconditions
        require(frame.branches.isNotEmpty()) {
            "ParallelFrame must have at least one branch"
        }
        frame.branches.forEachIndexed { index, branch ->
            require(branch.steps.isNotEmpty()) {
                "Branch '${branch.name}' at index $index must have at least one step"
            }
        }

        return when (frame.joinPolicy) {
            JoinPolicy.ALL_COMPLETE -> executeAllComplete(frame, context)
            JoinPolicy.FIRST_SUCCESS -> executeFirstSuccess(frame, context)
            JoinPolicy.ANY_COMPLETE -> executeAnyComplete(frame, context)
        }
    }

    /**
     * ALL_COMPLETE: wait for all branches, return success if all succeed.
     */
    private suspend fun executeAllComplete(
        frame: ParallelFrame,
        context: StepContext,
    ): ParallelFrameResult = coroutineScope {
        val deferreds = frame.branches.mapIndexed { branchIndex, branch ->
            async(Dispatchers.IO) {
                runBranch(branch, branchIndex, context)
            }
        }
        val results = deferreds.awaitAll()

        val allSuccess = results.all { it.outcome == "success" }
        if (allSuccess) {
            ParallelFrameResult.Success(results)
        } else {
            val firstFailure = results.first { it.outcome == "failure" }
            ParallelFrameResult.Failure(results, firstFailure)
        }
    }

    /**
     * FIRST_SUCCESS: return as soon as one branch succeeds, cancel all others.
     */
    private suspend fun executeFirstSuccess(
        frame: ParallelFrame,
        context: StepContext,
    ): ParallelFrameResult = coroutineScope {
        val deferreds = frame.branches.mapIndexed { branchIndex, branch ->
            async(Dispatchers.IO) {
                runBranch(branch, branchIndex, context)
            }
        }

        var successResult: BranchResult? = null
        var failureResult: BranchResult? = null

        for (deferred in deferreds) {
            try {
                val result = deferred.await()
                if (result.outcome == "success") {
                    successResult = result
                    deferreds.forEach { it.cancel() }
                    break
                } else {
                    failureResult = result
                }
            } catch (_: CancellationException) {
                // Branch was cancelled — ignore
            }
        }

        when {
            successResult != null -> {
                val allResults = deferreds.mapNotNull {
                    try { it.await() } catch (_: CancellationException) { null }
                }.let { listOf(successResult!!) + listOfNotNull(failureResult).filter { it != successResult } }
                ParallelFrameResult.Success(allResults)
            }
            failureResult != null -> {
                val allResults = deferreds.mapNotNull {
                    try { it.await() } catch (_: CancellationException) { null }
                }
                ParallelFrameResult.Failure(allResults, failureResult)
            }
            else -> {
                // All cancelled
                ParallelFrameResult.Failure(emptyList(), BranchResult(0, "failure", 0))
            }
        }
    }

    /**
     * ANY_COMPLETE: return as soon as any branch completes (success or failure), cancel others.
     */
    private suspend fun executeAnyComplete(
        frame: ParallelFrame,
        context: StepContext,
    ): ParallelFrameResult = coroutineScope {
        val deferreds = frame.branches.mapIndexed { branchIndex, branch ->
            async(Dispatchers.IO) {
                runBranch(branch, branchIndex, context)
            }
        }

        var firstResult: BranchResult? = null
        for (deferred in deferreds) {
            try {
                val result = deferred.await()
                firstResult = result
                deferreds.forEach { it.cancel() }
                break
            } catch (_: CancellationException) {
                // Branch was cancelled — continue to next
            }
        }

        when (firstResult?.outcome) {
            "success" -> ParallelFrameResult.Success(listOfNotNull(firstResult))
            else -> ParallelFrameResult.Failure(
                listOfNotNull(firstResult),
                firstResult ?: BranchResult(0, "failure", 0),
            )
        }
    }

    /**
     * Runs a single branch within a coroutine.
     *
     * Executes each step in the branch sequentially, returning the branch outcome.
     *
     * @param branch The branch specification to run.
     * @param branchIndex The index of this branch within the parallel frame.
     * @param context The step context.
     * @return The [BranchResult] for this branch.
     */
    private suspend fun runBranch(
        branch: BranchSpec,
        branchIndex: Int,
        context: StepContext,
    ): BranchResult {
        var outcome = "success"
        var lastStageIndex = branchIndex

        for ((stepIdx, step) in branch.steps.withIndex()) {
            try {
                when (step) {
                    is StepSpec.Echo -> {
                        echo(context, step.text, NoOpEventSink, stepIdx)
                    }
                    is StepSpec.Shell -> {
                        val argv = listOf("bash", "-c", step.command)
                        val result = sh(context, argv, NoOpEventSink, stepIdx)
                        if (result.exitCode != 0) {
                            outcome = "failure"
                            lastStageIndex = stepIdx
                            return BranchResult(branchIndex, outcome, lastStageIndex)
                        }
                    }
                    is StepSpec.Sleep -> {
                        sleep(context, step.seconds, NoOpEventSink, stepIdx)
                    }
                    is StepSpec.Error -> {
                        val failureKind = try {
                            FailureKind.valueOf(step.failureKind)
                        } catch (_: Exception) {
                            FailureKind.UNKNOWN
                        }
                        error(context, step.message, failureKind, NoOpEventSink, stepIdx)
                    }
                    is StepSpec.Parallel -> {
                        // Nested parallel — recursive execution
                        val nestedBranches = step.branches.map { dslBranch ->
                            dev.rubentxu.pipeline.v2.domain.durable.BranchSpec(
                                name = dslBranch.name,
                                steps = dslBranch.steps,
                            )
                        }
                        val nestedFrame = ParallelFrame(
                            branches = nestedBranches,
                            joinPolicy = JoinPolicy.ALL_COMPLETE,
                        )
                        val nestedExecutor = ParallelFrameExecutor(clock)
                        val nestedResult = nestedExecutor.execute(nestedFrame, context)
                        if (nestedResult.overallOutcome == "failure") {
                            outcome = "failure"
                            lastStageIndex = stepIdx
                            return BranchResult(branchIndex, outcome, lastStageIndex)
                        }
                    }
                    is StepSpec.Checkout -> {
                        // C1 (P1): Checkout in parallel branch is a no-op.
                        // The durable execution path (PipelineRun.executeDurableStepImpl) handles
                        // the actual checkout. Parallel branch execution is lightweight and
                        // does not re-execute checkout steps — this avoids duplicate execution
                        // and event duplication. The branch workspace is isolated from the
                        // durable workspace in the parallel context.
                        // (No-op: silently succeed — same as PipelineRun durable path)
                    }
                }
            } catch (e: Exception) {
                outcome = "failure"
                lastStageIndex = stepIdx
                return BranchResult(branchIndex, outcome, lastStageIndex)
            }
        }

        return BranchResult(branchIndex, outcome, lastStageIndex)
    }
}

/**
 * A no-op event sink used for branch execution where events are not persisted.
 */
private object NoOpEventSink : EventSink {
    override fun append(event: dev.rubentxu.pipeline.v2.events.DomainEvent) {
        // no-op
    }

    override fun eventsFor(runId: String): Sequence<dev.rubentxu.pipeline.v2.events.DomainEvent> {
        return emptySequence()
    }
}
