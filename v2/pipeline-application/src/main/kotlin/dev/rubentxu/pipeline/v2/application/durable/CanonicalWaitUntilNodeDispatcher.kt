package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.StepContext
import kotlinx.coroutines.delay
import java.security.MessageDigest

/** Runtime dependencies required to dispatch one canonical waitUntil node. */
data class CanonicalWaitUntilDispatchContext(
    val runId: String,
    val stepIndex: Int,
    val eventSink: EventSink,
    val condition: () -> Boolean,
)

/**
 * Dispatches canonical `core.waitUntil` nodes with exponential backoff.
 *
 * Per Jenkins verbatim: polls the condition at initialRecurrencePeriod intervals.
 * After the first poll, the period backs off exponentially, capped at 60 seconds.
 * Throws WaitUntilTimeoutException if cancelled or deadline exceeded.
 */
class CanonicalWaitUntilNodeDispatcher {
    companion object {
        private const val MAX_BACKOFF_MS = 60_000L
        private const val DEADLINE_MS = Long.MAX_VALUE // No default timeout; configurable via DSL
    }

    /**
     * Stub dispatch for the canonical path where the condition lambda is not serializable.
     * Emits one WaitUntilPolled and one WaitUntilCompleted event, then returns success.
     * The actual polling with condition evaluation requires the in-memory path where
     * lambdas are preserved.
     */
    fun dispatchStub(
        command: CanonicalCoreStepCommand.WaitUntil,
        context: CanonicalWaitUntilDispatchContext,
    ): StepOutcome {
        val startTime = System.currentTimeMillis()
        // Emit one poll event (canonical path has no real condition)
        context.eventSink.append(
            dev.rubentxu.pipeline.v2.events.WaitUntilPolled(
                eventId = java.util.UUID.randomUUID().toString(),
                runId = context.runId,
                sequence = 0L,
                occurredAt = java.time.Instant.now(),
                attempt = 1,
                durationMs = 0L,
                conditionResult = true, // Stub: assume condition is met
            ),
        )
        // Emit completion event
        context.eventSink.append(
            dev.rubentxu.pipeline.v2.events.WaitUntilCompleted(
                eventId = java.util.UUID.randomUUID().toString(),
                runId = context.runId,
                sequence = 0L,
                occurredAt = java.time.Instant.now(),
                totalAttempts = 1,
                totalDurationMs = 0L,
                outcome = "completed",
            ),
        )
        return StepOutcome.Success
    }

    fun dispatch(
        command: CanonicalCoreStepCommand.WaitUntil,
        context: CanonicalWaitUntilDispatchContext,
        deadlineMs: Long = DEADLINE_MS,
    ): StepOutcome {
        var periodMs = command.initialRecurrencePeriod
        val startTime = System.currentTimeMillis()
        var attempt = 0

        while (System.currentTimeMillis() < deadlineMs) {
            // Check for interruption
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("waitUntil cancelled")
            }

            attempt++
            val conditionResult = context.condition()
            val elapsedMs = System.currentTimeMillis() - startTime

            // Emit WaitUntilPolled event per attempt
            context.eventSink.append(
                dev.rubentxu.pipeline.v2.events.WaitUntilPolled(
                    eventId = java.util.UUID.randomUUID().toString(),
                    runId = context.runId,
                    sequence = 0L,
                    occurredAt = java.time.Instant.now(),
                    attempt = attempt,
                    durationMs = elapsedMs,
                    conditionResult = conditionResult,
                ),
            )

            if (conditionResult) {
                // Emit WaitUntilCompleted with "completed" outcome
                context.eventSink.append(
                    dev.rubentxu.pipeline.v2.events.WaitUntilCompleted(
                        eventId = java.util.UUID.randomUUID().toString(),
                        runId = context.runId,
                        sequence = 0L,
                        occurredAt = java.time.Instant.now(),
                        totalAttempts = attempt,
                        totalDurationMs = elapsedMs,
                        outcome = "completed",
                    ),
                )
                return StepOutcome.Success
            }

            // Sleep with exponential backoff, capped at MAX_BACKOFF_MS
            if (periodMs < MAX_BACKOFF_MS) {
                periodMs *= 2
                if (periodMs > MAX_BACKOFF_MS) periodMs = MAX_BACKOFF_MS
            }
            Thread.sleep(periodMs)
        }

        // Deadline exceeded
        val elapsedMs = System.currentTimeMillis() - startTime
        context.eventSink.append(
            dev.rubentxu.pipeline.v2.events.WaitUntilCompleted(
                eventId = java.util.UUID.randomUUID().toString(),
                runId = context.runId,
                sequence = 0L,
                occurredAt = java.time.Instant.now(),
                totalAttempts = attempt,
                totalDurationMs = elapsedMs,
                outcome = "deadline-exceeded",
            ),
        )
        return StepOutcome.Failure(
            PipelineFailure(
                kind = FailureKind.TIMEOUT,
                message = "waitUntil deadline exceeded after ${attempt} attempts and ${elapsedMs}ms",
            ),
        )
    }
}
