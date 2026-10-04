package dev.rubentxu.pipeline.v2.sdk.runtime

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.sdk.CompatibilityLevel
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.sdk.JenkinsSurface
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.Step
import dev.rubentxu.pipeline.v2.sdk.StepContext
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
