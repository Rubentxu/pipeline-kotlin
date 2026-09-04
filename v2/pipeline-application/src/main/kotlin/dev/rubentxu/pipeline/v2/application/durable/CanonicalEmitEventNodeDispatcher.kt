package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import java.time.Instant
import java.util.UUID

/**
 * LFC1-008 whitelist of known [DomainEvent] kinds for [core.emit.event].
 *
 * ADR-0054 §D6: event payload contracts are part of the observable runtime surface.
 * The whitelist enforces fail-closed semantics: unknown kinds return SCHEMA failure
 * and emit NO event.
 *
 * LFC1-008 D1: CatchErrorEntered is added as a 4th entry — it is a scope-only
 * signal (coordinator handles the push) and emits NO DomainEvent.
 */
private val ALLOWED_EMIT_EVENT_KINDS = setOf(
    "CatchErrorEntered",
    "CatchErrorTriggered",
    "StageMarkedUnstable",
    "FileWritten",
)

/**
 * Dispatches canonical [CanonicalCoreStepCommand.EmitEvent] nodes.
 *
 * Validates `kind` against the [ALLOWED_EMIT_EVENT_KINDS] whitelist.
 * Unknown `kind` returns [StepOutcome.Failure] with [FailureKind.SCHEMA] and emits NO event.
 *
 * Special handling:
 * - [CatchErrorEntered] is a scope-entry marker — validates kind, returns [StepOutcome.Success],
 *   emits NO event (coordinator pushes the scope frame).
 * - [StageMarkedUnstable] returns [StepOutcome.Unstable] to signal the coordinator to
 *   propagate the unstable outcome up to run level.
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

        // CatchErrorEntered: scope-only signal, no event emitted
        if (command.kind == "CatchErrorEntered") {
            // Coordinator validates buildResult presence and pushes scope frame
            return StepOutcome.Success
        }

        // StageMarkedUnstable: returns Unstable to signal run-level unstable outcome
        if (command.kind == "StageMarkedUnstable") {
            ctx.eventSink.append(
                dev.rubentxu.pipeline.v2.events.StageMarkedUnstable(
                    eventId = UUID.randomUUID().toString(),
                    runId = ctx.runId,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    stageName = command.payload["stageName"] ?: ctx.stageName,
                    message = command.payload["message"]
                        ?: error("StageMarkedUnstable requires 'message' in payload"),
                ),
            )
            return StepOutcome.Unstable
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
