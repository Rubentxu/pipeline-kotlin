package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.StepContext
import java.nio.file.Path
import java.security.MessageDigest

/** Runtime dependencies required to dispatch one canonical pwd node. */
data class CanonicalPwdDispatchContext(
    val runId: String,
    val stepIndex: Int,
    val eventSink: EventSink,
    val workspaceRoot: Path,
)

/** Dispatches canonical `core.pwd` nodes — returns workspace path as an absolute string. */
class CanonicalPwdNodeDispatcher {
    fun dispatch(command: CanonicalCoreStepCommand.Pwd, context: CanonicalPwdDispatchContext): StepOutcome {
        val path = if (command.tmp) {
            // tmp=true: create a temp subdirectory and return its path
            val tmpDir = context.workspaceRoot.resolve("tmp-pwd-${System.currentTimeMillis()}")
            tmpDir.toFile().mkdirs()
            tmpDir.toAbsolutePath().toString()
        } else {
            context.workspaceRoot.toAbsolutePath().toString()
        }

        val sha256 = sha256(path)
        context.eventSink.append(
            dev.rubentxu.pipeline.v2.events.PwdResolved(
                eventId = java.util.UUID.randomUUID().toString(),
                runId = context.runId,
                sequence = 0L,
                occurredAt = java.time.Instant.now(),
                path = path,
                workspaceRoot = context.workspaceRoot.toAbsolutePath().toString(),
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
