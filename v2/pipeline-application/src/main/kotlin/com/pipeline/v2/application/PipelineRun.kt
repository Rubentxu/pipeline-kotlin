package com.pipeline.v2.application

import com.pipeline.v2.events.CompilationFinished
import com.pipeline.v2.events.DomainEvent
import com.pipeline.v2.events.EventSink
import com.pipeline.v2.events.EventStore
import com.pipeline.v2.events.InMemoryEventStore
import com.pipeline.v2.events.RunFinished
import com.pipeline.v2.events.RunStarted
import com.pipeline.v2.scripting.CacheKey
import com.pipeline.v2.scripting.Kotlin24ScriptingHost
import com.pipeline.v2.scripting.ScriptDefinition
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Executes a pipeline script, emitting a complete event timeline.
 */
fun execute(scriptPath: Path, store: EventStore): List<DomainEvent> {
    val scriptContent = scriptPath.toFile().readText()
    val runId = deriveRunId(scriptPath.toString(), scriptContent)
    val eventSink = store as? EventSink ?: InMemoryEventStore()

    val runStartedId = UUID.randomUUID().toString()
    val runStartedAt = Instant.now()
    eventSink.append(
        RunStarted(
            eventId = runStartedId,
            runId = runId,
            sequence = 0L,
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
            sequence = 0L,
            occurredAt = runFinishedAt,
            outcome = outcome,
            diagnostics = result.diagnostics,
        )
    )

    return eventSink.eventsFor(runId).toList()
}

/**
 * Derives a deterministic runId from the script path and content.
 * Two invocations of the same script produce the same runId.
 */
private fun deriveRunId(scriptPath: String, scriptContent: String): String {
    val input = "$scriptPath|$scriptContent"
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
    // Use first 36 chars (UUID-compatible length) from hex string
    return hash.joinToString("") { "%02x".format(it) }.take(36)
}
