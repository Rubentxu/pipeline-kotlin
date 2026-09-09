package dev.rubentxu.pipeline.v2.sdk.runtime

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.durable.BranchSpec
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.JoinPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.sdk.CompatibilityLevel
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.sdk.JenkinsSurface
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.Step
import dev.rubentxu.pipeline.v2.sdk.StepContext
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import java.nio.file.Files
import java.time.Instant
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
/**
 * SDK-level sh step.
 *
 * LF-0308: this used to delegate to [ProcessExecutor] (a direct PB wrapper that
 * buffered whole pipes via `readText()` — the memory blow-up the runtime
 * invariant forbids). Now it routes through [ProcessDurableTaskRuntime] with a
 * [TaskSpec.ExecTask] carrying the verbatim argv. The runtime owns the script
 * lifecycle, chunked stdout/stderr drains, process-tree termination, and the
 * durable result file. Output is streamed to the sink and a single
 * [EchoOutputCaptured] event is emitted per step (legacy readText() merged
 * stdout + stderr — preserved here).
 */
suspend fun sh(context: StepContext, argv: List<String>, sink: EventSink, stepIndex: Int): Int {
    require(argv.isNotEmpty()) { "sh argv must not be empty" }
    val controlDir = Files.createTempDirectory("sdk-sh-")
    val runtime = ProcessDurableTaskRuntime(
        controlDir,
        object : Clock {
            override fun now(): Instant = Instant.now()
        },
    )
    val request = TaskExecutionRequest(
        task = TaskSpec.ExecTask(argv = argv),
        runId = RunId(context.runId),
        opId = "sdk-sh-${UUID.randomUUID()}",
        timeoutMs = 60_000L,
        env = emptyMap(),
    )
    val stdoutBuilder = StringBuilder()
    val stderrBuilder = StringBuilder()
    val outputSink = ExecutionOutputSink { chunk ->
        when (chunk.stream) {
            TaskStream.STDOUT -> stdoutBuilder.append(String(chunk.data, Charsets.UTF_8))
            TaskStream.STDERR -> stderrBuilder.append(String(chunk.data, Charsets.UTF_8))
        }
    }
    val result = runtime.execute(request, outputSink)
    val output = stdoutBuilder.toString() + stderrBuilder.toString()
    sink.append(
        EchoOutputCaptured(
            eventId = UUID.randomUUID().toString(),
            runId = context.runId,
            sequence = 0L,
            occurredAt = Instant.now(),
            stepIndex = stepIndex,
            content = output,
        ),
    )
    if (result.exitCode != 0) {
        val message = when {
            result.timedOut -> "sh timed out after 60000ms"
            stderrBuilder.isNotEmpty() ->
                "sh exited with code ${result.exitCode}: ${stderrBuilder.toString().take(256)}"
            else -> "sh exited with code ${result.exitCode}"
        }
        sink.append(
            StepFailed(
                eventId = UUID.randomUUID().toString(),
                runId = context.runId,
                sequence = 0L,
                occurredAt = Instant.now(),
                stepIndex = stepIndex,
                stepName = "sh",
                stepType = "sh",
                failureKind = FailureKind.SCRIPT,
                message = message,
            ),
        )
    }
    return result.exitCode
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
