package com.pipeline.v2.application

import com.pipeline.v2.events.CompilationFinished
import com.pipeline.v2.events.EventSink
import com.pipeline.v2.events.EventStore
import com.pipeline.v2.events.InMemoryEventStore
import com.pipeline.v2.events.RunFinished
import com.pipeline.v2.events.RunStarted
import com.pipeline.v2.scripting.Kotlin24ScriptingHost
import com.pipeline.v2.scripting.ScriptDefinition
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Executes a pipeline script, emitting a complete event timeline.
 */
fun execute(scriptPath: Path, store: EventStore): List<com.pipeline.v2.events.DomainEvent> {
    val runId = UUID.randomUUID().toString()
    val eventSink = store as? EventSink ?: InMemoryEventStore()

    val runStartedId = UUID.randomUUID().toString()
    val runStartedAt = Instant.now()
    eventSink.append(
        RunStarted(
            eventId = runStartedId,
            runId = runId,
            sequence = 1L,
            occurredAt = runStartedAt,
            scriptPath = scriptPath.toString(),
        )
    )

    val host = Kotlin24ScriptingHost(eventSink, runId)
    val definition = ScriptDefinition.file(scriptPath)
    val result = host.compile(definition)

    val outcome = if (result.isSuccess) "success" else "failure"
    val runFinishedId = UUID.randomUUID().toString()
    val runFinishedAt = Instant.now()
    eventSink.append(
        RunFinished(
            eventId = runFinishedId,
            runId = runId,
            sequence = 3L,
            occurredAt = runFinishedAt,
            outcome = outcome,
            diagnostics = result.diagnostics,
        )
    )

    return eventSink.eventsFor(runId).toList()
}
