package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import java.security.MessageDigest

/** Runtime dependencies required to dispatch one canonical isUnix node. */
data class CanonicalIsUnixDispatchContext(
    val runId: String,
    val stepIndex: Int,
    val eventSink: EventSink,
)

/** Dispatches canonical `core.isUnix` nodes — returns true on Linux/macOS/Darwin. */
class CanonicalIsUnixNodeDispatcher {
    fun dispatch(command: CanonicalCoreStepCommand.IsUnix, context: CanonicalIsUnixDispatchContext): StepOutcome {
        val osName = System.getProperty("os.name", "")
        val isUnix = osName.lowercase().let {
            it.contains("linux") || it.contains("mac") || it.contains("darwin") || it.contains("freebsd")
        }

        val sha256 = sha256(osName)
        context.eventSink.append(
            dev.rubentxu.pipeline.v2.events.UnixDetected(
                eventId = java.util.UUID.randomUUID().toString(),
                runId = context.runId,
                sequence = 0L,
                occurredAt = java.time.Instant.now(),
                isUnix = isUnix,
                osName = osName,
                sha256 = sha256,
            ),
        )
        return StepOutcome.Success
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
