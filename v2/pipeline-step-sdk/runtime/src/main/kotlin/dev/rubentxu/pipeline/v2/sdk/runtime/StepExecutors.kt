package dev.rubentxu.pipeline.v2.sdk.runtime

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.durable.BranchSpec
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.JoinPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame
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
        eventId = java.util.UUID.randomUUID().toString(),
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
        eventId = java.util.UUID.randomUUID().toString(),
        runId = context.runId,
        sequence = 0L,
        occurredAt = java.time.Instant.now(),
        stepIndex = stepIndex,
        content = result.stdout,
    ))
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
        eventId = java.util.UUID.randomUUID().toString(),
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
 * Executor for [ParallelFrame] that validates branch inputs and returns successful results.
 *
 * This is a no-op executor for M3-R4.2: it validates that the parallel frame has at least
 * one branch and that each branch has at least one step, then returns successful results.
 *
 * Concurrent branch execution using structured concurrency ([coroutineScope]/[async]/[awaitAll])
 * is planned for M3-R4.3 (per design.md C-PAR-003).
 *
 * @param clock The clock for timestamps.
 */
class ParallelFrameExecutor(
    private val clock: Clock,
) {
    init {
        // Validate preconditions: parallel frame must have at least one branch
        // (actual validation happens in execute() using require)
    }

    /**
     * Executes the given [ParallelFrame] by validating inputs and returning successful results.
     *
     * This is a no-op implementation for M3-R4.2. Concurrent execution via coroutines
     * is deferred to M3-R4.3.
     *
     * @param frame The parallel frame to execute.
     * @param context The step context (unused in no-op implementation).
     * @return A list of successful [BranchResult] for each branch.
     * @throws IllegalArgumentException if the frame has no branches or any branch has no steps.
     */
    fun execute(
        frame: ParallelFrame,
        context: StepContext,
    ): List<BranchResult> {
        // Validate: parallel frame must have at least one branch
        require(frame.branches.isNotEmpty()) {
            "ParallelFrame must have at least one branch"
        }

        // Validate: each branch must have at least one step
        frame.branches.forEachIndexed { index, branch ->
            require(branch.steps.isNotEmpty()) {
                "Branch '${branch.name}' at index $index must have at least one step"
            }
        }

        // Return successful results for each branch
        // Concurrent execution via coroutines is deferred to M3-R4.3
        return frame.branches.mapIndexed { branchIndex, branch ->
            BranchResult(
                branchIndex = branchIndex,
                outcome = "success",
                stageIndex = branchIndex, // Placeholder; actual stage index computed in PipelineRun
            )
        }
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
