package com.pipeline.v2.events

import com.pipeline.v2.scripting.CacheKey
import com.pipeline.v2.scripting.ScriptingDiagnostic
import com.pipeline.v2.scripting.ScriptDiagnosticSeverity
import java.time.Instant

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
                sb.append(encodeCacheKey(event.cacheKey))
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

    private fun encodeCacheKey(ck: CacheKey): String {
        return "{\"value\":\"" + ck.value + "\",\"version\":\"" + ck.version + "\"}"
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

    /**
     * Splits a JSON array content into individual event strings.
     * Handles nested objects and strings correctly.
     */
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
                ch == '{' && !inString -> { depth++; current.append(ch) }
                ch == '}' && !inString -> { depth--; current.append(ch) }
                ch == ',' && depth == 0 && !inString -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        // Discard the trailing ']' that closes the JSON array.
        val trailing = current.toString().trimEnd()
        if (trailing.isNotEmpty() && trailing != "]") result.add(current.toString())
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
                val cacheKey = parseCacheKey(s) ?: return null
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

    /**
     * Extracts a string field value from JSON by finding the field name
     * and reading until the closing quote (handling escapes).
     */
    private fun stringField(json: String, name: String): String? {
        val nameStart = json.indexOf("\"$name\"") ?: return null
        val colonPos = json.indexOf(':', nameStart) ?: return null
        // Find the opening quote after the colon
        var i = colonPos + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') return null
        // i is now at the opening quote of the value
        var stringEnd = i + 1
        var inString = true
        var escape = false
        while (stringEnd < json.length && inString) {
            when {
                escape -> { escape = false; stringEnd++ }
                json[stringEnd] == '\\' && inString -> { escape = true; stringEnd++ }
                json[stringEnd] == '"' -> { inString = false }
                else -> stringEnd++
            }
        }
        // stringEnd is now at the closing quote (or end of string)
        // We want the content between quotes: from i+1 to stringEnd-1
        return if (stringEnd > i + 1) json.substring(i + 1, stringEnd) else ""
    }

    /**
     * Extracts the cacheKey object value from the event JSON.
     * Returns a CacheKey or null if parsing fails.
     */
    private fun parseCacheKey(json: String): CacheKey? {
        val keyStart = json.indexOf("\"cacheKey\"") ?: return null
        val bracePos = json.indexOf('{', keyStart) ?: return null
        // Extract the cacheKey object by finding matching braces
        var depth = 0
        var i = bracePos
        while (i < json.length) {
            when (json[i]) {
                '{' -> { depth++; i++ }
                '}' -> { depth--; if (depth == 0) break; i++ }
                '"' -> {
                    // Skip over a quoted string
                    i++
                    while (i < json.length) {
                        when {
                            json[i] == '\\' -> i += 2
                            json[i] == '"' -> { i++; break }
                            else -> i++
                        }
                    }
                }
                else -> i++
            }
        }
        if (depth != 0) return null
        val cacheKeyJson = json.substring(bracePos, i + 1)
        val value = stringField(cacheKeyJson, "value") ?: ""
        val version = stringField(cacheKeyJson, "version") ?: ""
        return CacheKey(value, version)
    }

    private fun longField(json: String, name: String): Long? {
        val nameStart = json.indexOf("\"$name\"") ?: return null
        val colonPos = json.indexOf(':', nameStart) ?: return null
        var i = colonPos + 1
        while (i < json.length && json[i].isWhitespace()) i++
        var numEnd = i
        while (numEnd < json.length && (json[numEnd].isDigit() || json[numEnd] == '-')) numEnd++
        return if (numEnd > i) json.substring(i, numEnd).toLongOrNull() else null
    }

    private fun decodeDiagnostics(json: String): List<ScriptingDiagnostic> {
        val arrStart = json.indexOf("\"diagnostics\"") ?: return emptyList()
        val bracketPos = json.indexOf('[', arrStart) ?: return emptyList()
        var i = bracketPos + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] == ']') return emptyList()

        // Parse the diagnostics array manually
        val results = mutableListOf<ScriptingDiagnostic>()
        var depth = 0
        var inString = false
        var escape = false
        val current = StringBuilder()
        i = bracketPos + 1

        while (i < json.length) {
            val ch = json[i]
            when {
                escape -> { current.append(ch); escape = false; i++ }
                ch == '\\' && inString -> { current.append(ch); escape = true; i++ }
                ch == '"' -> { current.append(ch); inString = !inString; i++ }
                ch == '{' && !inString -> { depth++; current.append(ch); i++ }
                ch == '}' && !inString -> {
                    depth--
                    current.append(ch)
                    if (depth == 0) {
                        val diagStr = current.toString().trim()
                        if (diagStr.isNotEmpty()) {
                            parseDiagnostic(diagStr)?.let { results.add(it) }
                        }
                        current.clear()
                    }
                    i++
                }
                ch == ',' && depth == 0 && !inString -> {
                    // end of current diagnostic
                    i++
                }
                else -> { if (depth > 0) current.append(ch); i++ }
            }
        }
        return results
    }

    private fun parseDiagnostic(s: String): ScriptingDiagnostic? {
        val severityStr = stringField(s, "severity")
        val message = stringField(s, "message") ?: ""
        val line = stringField(s, "line")?.toIntOrNull() ?: 0
        val column = stringField(s, "column")?.toIntOrNull() ?: 0
        val path = stringField(s, "path") ?: ""
        val severity = severityStr?.let {
            try { ScriptDiagnosticSeverity.valueOf(it) } catch (_: Exception) { ScriptDiagnosticSeverity.INFO }
        } ?: ScriptDiagnosticSeverity.INFO
        return ScriptingDiagnostic(severity, message, line, column, path)
    }
}
