package com.pipeline.v2.application

import com.pipeline.v2.events.DomainEvent
import com.pipeline.v2.scripting.ScriptingDiagnostic

/**
 * Deterministic snapshot normalizer for compatibility corpus diff.
 *
 * Strips non-structural fields (eventId, runId, occurredAt; diagnostic stack traces)
 * and projects to a stable shape that hash-diffs equal across runs.
 */
object CorpusNormalizer {

    data class FixtureSnapshot(
        val events: List<Map<String, Any?>>,
        val diagnostics: List<Map<String, Any?>>,
        val descriptorProjection: Map<String, Any?>,
    )

    fun normalize(events: List<DomainEvent>, diags: List<ScriptingDiagnostic>): FixtureSnapshot {
        return FixtureSnapshot(
            events = events.map { normalizeEvent(it) },
            diagnostics = diags.map { normalizeDiagnostic(it) },
            descriptorProjection = emptyMap(),
        )
    }

    fun normalizeEvent(event: DomainEvent): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        map["kind"] = event::class.simpleName
        // Strip eventId, runId, occurredAt (non-deterministic)
        when (event) {
            is com.pipeline.v2.events.RunStarted -> {
                map["runId"] = null
                map["occurredAt"] = null
            }
            is com.pipeline.v2.events.RunFinished -> {
                map["runId"] = null
                map["occurredAt"] = null
            }
            is com.pipeline.v2.events.StageStarted -> {
                map["eventId"] = null
                map["runId"] = null
                map["occurredAt"] = null
                map["stageIndex"] = event.stageIndex
                map["stageName"] = event.stageName
            }
            is com.pipeline.v2.events.StageFinished -> {
                map["eventId"] = null
                map["runId"] = null
                map["occurredAt"] = null
                map["stageIndex"] = event.stageIndex
                map["stageName"] = event.stageName
            }
            is com.pipeline.v2.events.StepStarted -> {
                map["eventId"] = null
                map["runId"] = null
                map["occurredAt"] = null
                map["stepIndex"] = event.stepIndex
                map["stepType"] = event.stepType
            }
            is com.pipeline.v2.events.StepFinished -> {
                map["eventId"] = null
                map["runId"] = null
                map["occurredAt"] = null
                map["stepIndex"] = event.stepIndex
                map["stepType"] = event.stepType
            }
            is com.pipeline.v2.events.EchoOutputCaptured -> {
                map["eventId"] = null
                map["runId"] = null
                map["occurredAt"] = null
                map["content"] = event.content
            }
            is com.pipeline.v2.events.StepFailed -> {
                map["eventId"] = null
                map["runId"] = null
                map["occurredAt"] = null
                map["stepName"] = event.stepName
                map["stepType"] = event.stepType
                map["message"] = event.message
            }
            else -> {
                // For unknown event types, just use the class name
                map["kind"] = event::class.simpleName
            }
        }
        return map
    }

    fun normalizeDiagnostic(diag: ScriptingDiagnostic): Map<String, Any?> {
        return mapOf(
            "severity" to diag.severity?.name,
            "message" to diag.message,
            "path" to diag.path,
        )
    }
}
