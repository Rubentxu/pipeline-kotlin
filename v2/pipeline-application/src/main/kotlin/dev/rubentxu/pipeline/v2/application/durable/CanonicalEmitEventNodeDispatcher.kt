package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import java.time.Instant
import java.util.UUID

/**
 * LFC1-007 whitelist of known [DomainEvent] kinds for [core.emit.event].
 *
 * ADR-0054 §D6: event payload contracts are part of the observable runtime surface.
 * The whitelist enforces fail-closed semantics: unknown kinds return SCHEMA failure
 * and emit NO event.
 */
private val ALLOWED_EMIT_EVENT_KINDS = setOf(
    "CatchErrorTriggered",
    "StageMarkedUnstable",
    "FileWritten",
)

/**
 * Dispatches canonical [CanonicalCoreStepCommand.EmitEvent] nodes.
 *
 * Validates `kind` against the [ALLOWED_EMIT_EVENT_KINDS] whitelist.
 * Unknown `kind` returns [StepOutcome.Failure] with [FailureKind.SCHEMA] and emits NO event.
 */
class CanonicalEmitEventNodeDispatcher {

    suspend fun dispatch(command: CanonicalCoreStepCommand.EmitEvent, ctx: CanonicalEmitEventDispatchContext): StepOutcome {
        val allowedKind = command.kind in ALLOWED_EMIT_EVENT_KINDS
        if (!allowedKind) {
            return StepOutcome.Failure(
                PipelineFailure(
                    FailureKind.SCHEMA,
                    "core.emit.event kind='${command.kind}' is not in the canonical whitelist " +
                        "(${ALLOWED_EMIT_EVENT_KINDS.joinToString(" / ")})",
                ),
            )
        }

        val event: dev.rubentxu.pipeline.v2.events.DomainEvent = when (command.kind) {
            "CatchErrorTriggered" -> dev.rubentxu.pipeline.v2.events.CatchErrorTriggered(
                eventId = UUID.randomUUID().toString(),
                runId = ctx.runId,
                sequence = 0L,
                occurredAt = Instant.now(),
                stageName = command.payload["stageName"] ?: ctx.stageName,
                buildResult = command.payload["buildResult"],
                stageResult = command.payload["stageResult"]
                    ?: error("CatchErrorTriggered requires 'stageResult' in payload"),
                message = command.payload["message"],
            )
            "StageMarkedUnstable" -> dev.rubentxu.pipeline.v2.events.StageMarkedUnstable(
                eventId = UUID.randomUUID().toString(),
                runId = ctx.runId,
                sequence = 0L,
                occurredAt = Instant.now(),
                stageName = command.payload["stageName"] ?: ctx.stageName,
                message = command.payload["message"]
                    ?: error("StageMarkedUnstable requires 'message' in payload"),
            )
            "FileWritten" -> dev.rubentxu.pipeline.v2.events.FileWritten(
                eventId = UUID.randomUUID().toString(),
                runId = ctx.runId,
                sequence = 0L,
                occurredAt = Instant.now(),
                path = java.nio.file.Paths.get(
                    command.payload["path"]
                        ?: error("FileWritten requires 'path' in payload"),
                ),
                sha256 = command.payload["sha256"]
                    ?: error("FileWritten requires 'sha256' in payload"),
                size = command.payload["size"]?.toLongOrNull()
                    ?: error("FileWritten requires 'size' in payload"),
                atomicallyMoved = command.payload["atomicallyMoved"]?.toBooleanStrictOrNull() ?: false,
            )
            else -> return StepOutcome.Failure(
                PipelineFailure(
                    FailureKind.SCHEMA,
                    "core.emit.event kind='${command.kind}' is not in the canonical whitelist",
                ),
            )
        }

        ctx.eventSink.append(event)
        return StepOutcome.Success
    }
}
