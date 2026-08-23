package com.pipeline.v2.application

import com.pipeline.v2.dsl.PipelineSpec
import com.pipeline.v2.dsl.StageSpec
import com.pipeline.v2.dsl.StepSpec
import com.pipeline.v2.events.CompilationFinished
import com.pipeline.v2.events.DomainEvent
import com.pipeline.v2.events.EventSink
import com.pipeline.v2.events.EventStore
import com.pipeline.v2.events.InMemoryEventStore
import com.pipeline.v2.events.RunFinished
import com.pipeline.v2.events.RunStarted
import com.pipeline.v2.events.StageFinished
import com.pipeline.v2.events.StageStarted
import com.pipeline.v2.events.StepFinished
import com.pipeline.v2.events.StepStarted
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
    val dslJar = ScriptDefinition.dslApiJar()
    val dslClasspath = if (dslJar != null) listOf(dslJar) else emptyList()
    val definition = ScriptDefinition.file(scriptPath, classpath = dslClasspath)
    val result = host.compile(definition)

    if (result.isSuccess) {
        val scriptInstance = result.value
        // Kotlin .kts scripts expose their return value via get$$result()
        val pipelineSpec = scriptInstance?.let { inst ->
            try {
                val resultMethod = inst.javaClass.getMethod("get\$\$result")
                @Suppress("UNCHECKED_CAST")
                resultMethod.invoke(inst) as? PipelineSpec
            } catch (_: Exception) {
                null
            }
        }
        if (pipelineSpec != null) {
            walkPipelineSpec(pipelineSpec, runId, eventSink)
        }
    }

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
 * Walks a [PipelineSpec] and emits Stage/Step lifecycle events.
 * Steps are recorded but not executed (record-only sh).
 */
private fun walkPipelineSpec(
    spec: PipelineSpec,
    runId: String,
    eventSink: EventSink,
) {
    for ((stageIndex, stage) in spec.stages.withIndex()) {
        val stageStartedId = UUID.randomUUID().toString()
        val stageStartedAt = Instant.now()
        eventSink.append(
            StageStarted(
                eventId = stageStartedId,
                runId = runId,
                sequence = 0L,
                occurredAt = stageStartedAt,
                stageIndex = stageIndex,
                stageName = stage.name,
            )
        )

        for ((stepIndex, step) in stage.steps.withIndex()) {
            val stepStartedId = UUID.randomUUID().toString()
            val stepStartedAt = Instant.now()
            val stepName = step.name
            val stepType = step.type
            eventSink.append(
                StepStarted(
                    eventId = stepStartedId,
                    runId = runId,
                    sequence = 0L,
                    occurredAt = stepStartedAt,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    stepName = stepName,
                    stepType = stepType,
                )
            )

            val stepFinishedId = UUID.randomUUID().toString()
            val stepFinishedAt = Instant.now()
            eventSink.append(
                StepFinished(
                    eventId = stepFinishedId,
                    runId = runId,
                    sequence = 0L,
                    occurredAt = stepFinishedAt,
                    stageIndex = stageIndex,
                    stepIndex = stepIndex,
                    stepName = stepName,
                    stepType = stepType,
                )
            )
        }

        val stageFinishedId = UUID.randomUUID().toString()
        val stageFinishedAt = Instant.now()
        eventSink.append(
            StageFinished(
                eventId = stageFinishedId,
                runId = runId,
                sequence = 0L,
                occurredAt = stageFinishedAt,
                stageIndex = stageIndex,
                stageName = stage.name,
                outcome = "success",
            )
        )
    }
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
