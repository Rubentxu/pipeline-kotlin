package com.pipeline.v2.events

import com.pipeline.v2.scripting.CacheKey
import com.pipeline.v2.scripting.ScriptingDiagnostic
import com.pipeline.v2.scripting.ScriptDiagnosticSeverity
import java.time.Instant
import java.util.regex.Pattern

/**
 * JSON wire format encoder/decoder for domain events.
 * Wire shape: single-line JSON array, tagged `kind` field.
 */
object JsonEventLog {

    fun encode(events: List<DomainEvent>): String {
        val sb = StringBuilder("[")
        events.forEachIndexed { index, event ->
            if (index > 0) sb.append(",")
            sb.append(encodeEvent(event))
        }
        sb.append("]")
        return sb.toString()
    }

    private fun encodeEvent(event: DomainEvent): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"eventId\":")
        sb.append(jsonString(event.eventId))
        sb.append(",\"runId\":")
        sb.append(jsonString(event.runId))
        sb.append(",\"sequence\":")
        sb.append(event.sequence)
        sb.append(",\"kind\":")
        sb.append(jsonString(event.kind))
        sb.append(",\"occurredAt\":")
        sb.append(jsonString(event.occurredAt.toString()))
        when (event) {
            is RunStarted -> {
                sb.append(",\"scriptPath\":")
                sb.append(jsonString(event.scriptPath))
            }
            is CompilationStarted -> { /* no extra fields */ }
            is CompilationFinished -> {
                sb.append(",\"cacheKey\":")
                sb.append("{\"value\":\"").append(event.cacheKey.value).append("\",\"version\":\"").append(event.cacheKey.version).append("\"}")
                sb.append(",\"diagnostics\":")
                sb.append(encodeDiagnostics(event.diagnostics))
            }
            is RunFinished -> {
                sb.append(",\"outcome\":")
                sb.append(jsonString(event.outcome))
                sb.append(",\"diagnostics\":")
                sb.append(encodeDiagnostics(event.diagnostics))
            }
        }
        sb.append("}")
        return sb.toString()
    }

    private fun encodeDiagnostics(diagnostics: List<ScriptingDiagnostic>): String {
        val sb = StringBuilder("[")
        diagnostics.forEachIndexed { index, diag ->
            if (index > 0) sb.append(",")
            sb.append("{")
            sb.append("\"severity\":")
            sb.append(jsonString(diag.severity.name))
            sb.append(",\"message\":")
            sb.append(jsonString(diag.message))
            sb.append(",\"line\":")
            sb.append(diag.line)
            sb.append(",\"column\":")
            sb.append(diag.column)
            sb.append(",\"path\":")
            sb.append(jsonString(diag.path))
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return "\"${sb}\""
    }

    fun decode(payload: String): List<DomainEvent> {
        if (payload.isBlank() || payload == "[]") return emptyList()
        val events = mutableListOf<DomainEvent>()
        val eventStrings = splitArray(payload.substring(1, payload.length - 1))
        for (eventStr in eventStrings) {
            val trimmed = eventStr.trim()
            if (trimmed.isEmpty()) continue
            val event = decodeEvent(trimmed) ?: continue
            events.add(event)
        }
        return events
    }

    private fun splitArray(s: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escape = false
        val current = StringBuilder()
        for (ch in s) {
            when {
                escape -> {
                    current.append(ch)
                    escape = false
                }
                ch == '\\' && inString -> {
                    current.append(ch)
                    escape = true
                }
                ch == '"' -> {
                    current.append(ch)
                    inString = !inString
                }
                ch == '{' && !inString -> depth++
                ch == '}' && !inString -> depth--
                ch == ',' && depth == 0 && !inString -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    private fun decodeEvent(s: String): DomainEvent? {
        val eventId = stringField(s, "eventId") ?: return null
        val runId = stringField(s, "runId") ?: return null
        val sequence = longField(s, "sequence") ?: return null
        val kind = stringField(s, "kind") ?: return null
        val occurredAtStr = stringField(s, "occurredAt") ?: return null
        val occurredAt = try { Instant.parse(occurredAtStr) } catch (_: Exception) { Instant.now() }

        return when (kind) {
            "RunStarted" -> RunStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
                scriptPath = stringField(s, "scriptPath") ?: "",
            )
            "CompilationStarted" -> CompilationStarted(
                eventId = eventId,
                runId = runId,
                sequence = sequence,
                occurredAt = occurredAt,
            )
            "CompilationFinished" -> {
                val cacheKeyJson = stringField(s, "cacheKey") ?: return null
                val value = stringField(cacheKeyJson, "value") ?: ""
                val version = stringField(cacheKeyJson, "version") ?: ""
                val cacheKey = CacheKey(value, version)
                val diagnostics = decodeDiagnostics(s)
                CompilationFinished(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    cacheKey = cacheKey,
                    diagnostics = diagnostics,
                )
            }
            "RunFinished" -> {
                val outcome = stringField(s, "outcome") ?: "unknown"
                val diagnostics = decodeDiagnostics(s)
                RunFinished(
                    eventId = eventId,
                    runId = runId,
                    sequence = sequence,
                    occurredAt = occurredAt,
                    outcome = outcome,
                    diagnostics = diagnostics,
                )
            }
            else -> null
        }
    }

    private fun stringField(json: String, name: String): String? {
        val pattern = Pattern.compile("\"$name\"\\s*:\\s*\"")
        val m = pattern.matcher(json)
        if (!m.find()) return null
        val start = m.end()
        var i = start
        var inString = true
        var escape = false
        while (i < json.length) {
            val ch = json[i]
            when {
                escape -> { escape = false; i++ }
                ch == '\\' && inString -> { escape = true; i++ }
                ch == '"' && inString -> return json.substring(start, i)
                ch == '"' -> { inString = false; i++ }
                else -> i++
            }
        }
        return json.substring(start, i)
    }

    private fun longField(json: String, name: String): Long? {
        val pattern = Pattern.compile("\"$name\"\\s*:\\s*(-?\\d+)")
        val m = pattern.matcher(json)
        return if (m.find()) m.group(1)?.toLongOrNull() else null
    }

    private fun decodeDiagnostics(json: String): List<ScriptingDiagnostic> {
        val diagnosticsPattern = Pattern.compile("\"diagnostics\"\\s*:\\s*\\[")
        val m = diagnosticsPattern.matcher(json)
        if (!m.find()) return emptyList()
        val start = m.end()
        var depth = 1
        var i = start
        while (i < json.length && depth > 0) {
            when (json[i]) {
                '[' -> depth++
                ']' -> depth--
            }
            i++
        }
        val arrContent = json.substring(start, i - 1)
        if (arrContent.isBlank()) return emptyList()
        val results = mutableListOf<ScriptingDiagnostic>()
        val items = splitArray(arrContent)
        for (item in items) {
            val trimmed = item.trim()
            if (trimmed.isEmpty()) continue
            val severityStr = stringField(trimmed, "severity")
            val message = stringField(trimmed, "message") ?: ""
            val line = stringField(trimmed, "line")?.toIntOrNull() ?: 0
            val column = stringField(trimmed, "column")?.toIntOrNull() ?: 0
            val path = stringField(trimmed, "path") ?: ""
            val severity = severityStr?.let {
                try { ScriptDiagnosticSeverity.valueOf(it) } catch (_: Exception) { ScriptDiagnosticSeverity.INFO }
            } ?: ScriptDiagnosticSeverity.INFO
            results.add(ScriptingDiagnostic(severity, message, line, column, path))
        }
        return results
    }
}
