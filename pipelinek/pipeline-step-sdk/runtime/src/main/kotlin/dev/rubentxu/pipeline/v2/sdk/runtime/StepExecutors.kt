package dev.rubentxu.pipeline.v2.sdk.runtime

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.sdk.StepContext
import java.time.Instant
import java.util.UUID

/**
 * Step executors for echo, error and sleep. Called by the PipelineRun orchestrator at runtime.
 *
 * S6/H: these functions used to carry `@Step` / `@JenkinsSurface` annotations, read by a KSP
 * processor that emitted a `GeneratedStepDescriptors.kt` file. That file had no consumer and,
 * for every Step it processed, hard-coded `requiredCapabilities = emptyList()` — claiming that
 * `echo`, which declares `EVENT_SINK_CAPABILITY` in its real contract, requires no capability at
 * all. The metadata these functions declare lives in `StepDefinition.contract`, which is the
 * authority the runtime actually resolves from; the annotations are gone with the processor.
 */

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

fun sleep(context: StepContext, seconds: Long, sink: EventSink, stepIndex: Int) {
    Thread.sleep(seconds * 1000L)
}
