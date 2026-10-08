package dev.rubentxu.pipeline.v2.events.durable


import dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed
import dev.rubentxu.pipeline.v2.events.ArtifactEntry
import dev.rubentxu.pipeline.v2.events.HtmlReportEntry
import dev.rubentxu.pipeline.v2.events.HtmlReportPublished
import dev.rubentxu.pipeline.v2.events.RestoredEntry
import dev.rubentxu.pipeline.v2.events.StashCreated
import dev.rubentxu.pipeline.v2.events.StashRestored
import dev.rubentxu.pipeline.v2.events.StashedEntry
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import java.time.Instant

/**
 * The field and element readers of [JsonEventLog]'s decoder.
 *
 * [JsonEventLog] is an `object`, so detekt budgets it 11 functions
 * (allowedFunctionsPerObject) rather than the 25 allowedFunctionsPerClass
 * that applies to classes. This holds the fifteen decoding helpers that
 * every entry point in here was private.
 */
internal object EventJsonDecoder {

    fun decodeStringList(json: String, fieldName: String): List<String> {
        val arrStart = json.indexOf("\"$fieldName\"")
        if (arrStart == -1) return emptyList()
        val bracketPos = json.indexOf('[', arrStart)
        if (bracketPos == -1) return emptyList()
        var i = bracketPos + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] == ']') return emptyList()

        val results = mutableListOf<String>()
        while (i < json.length) {
            val ch = json[i]
            when {
                ch == '"' -> {
                    i++
                    val start = i
                    while (i < json.length && json[i] != '"') {
                        if (json[i] == '\\') i++
                        i++
                    }
                    results.add(json.substring(start, i))
                    i++ // skip closing "
                }
                ch == ']' -> break
                ch == ',' -> i++
                else -> i++
            }
        }
        return results
    }

    fun decodeArtifactEntries(json: String): List<ArtifactEntry> {
        val arrStart = json.indexOf("\"files\"")
        if (arrStart == -1) return emptyList()
        val bracketPos = json.indexOf('[', arrStart)
        if (bracketPos == -1) return emptyList()
        var i = bracketPos + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] == ']') return emptyList()

        val results = mutableListOf<ArtifactEntry>()
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
                        val entryStr = current.toString().trim()
                        if (entryStr.isNotEmpty()) {
                            parseArtifactEntry(entryStr)?.let { results.add(it) }
                        }
                        current.clear()
                    }
                    i++
                }
                ch == ',' && depth == 0 && !inString -> {
                    i++
                }
                else -> { if (depth > 0) current.append(ch); i++ }
            }
        }
        return results
    }

    fun parseArtifactEntry(s: String): ArtifactEntry? {
        val runId = EventJsonFields.stringField(s, "runId") ?: return null
        val stageName = EventJsonFields.stringField(s, "stageName") ?: return null
        val relPath = EventJsonFields.stringField(s, "relPath") ?: return null
        val sha256 = EventJsonFields.stringField(s, "sha256") ?: return null
        val size = EventJsonFields.longField(s, "size") ?: return null
        val archivedAtStr = EventJsonFields.stringField(s, "archivedAt") ?: return null
        val archivedAt = try { Instant.parse(archivedAtStr) } catch (_: Exception) { Instant.now() }
        return ArtifactEntry(runId, stageName, relPath, sha256, size, archivedAt)
    }

    fun decodeDiagnostics(json: String): List<ScriptingDiagnostic> {
        val arrStart = json.indexOf("\"diagnostics\"")
        if (arrStart == -1) return emptyList()
        val bracketPos = json.indexOf('[', arrStart)
        if (bracketPos == -1) return emptyList()
        var i = bracketPos + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] == ']') return emptyList()

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
                    i++
                }
                else -> { if (depth > 0) current.append(ch); i++ }
            }
        }
        return results
    }

    fun parseDiagnostic(s: String): ScriptingDiagnostic? {
        val severityStr = EventJsonFields.stringField(s, "severity")
        val message = EventJsonFields.stringField(s, "message") ?: ""
        // `line` and `column` are written by encodeDiagnostics as bare JSON numbers,
        // so they must be read with the numeric reader. Reading them as strings
        // silently produced 0/0 for every diagnostic, losing the source position
        // of every scripting warning and error that had ever been replayed.
        val line = EventJsonFields.intField(s, "line") ?: 0
        val column = EventJsonFields.intField(s, "column") ?: 0
        val path = EventJsonFields.stringField(s, "path") ?: ""
        val severity = severityStr?.let {
            try { ScriptDiagnosticSeverity.valueOf(it) } catch (_: Exception) { ScriptDiagnosticSeverity.INFO }
        } ?: ScriptDiagnosticSeverity.INFO
        return ScriptingDiagnostic(severity, message, line, column, path)
    }

    // WU-LPR-089 — Stash helpers (compact JSON arrays).
    // parseJsonArrayObjects (which the ArtifactArchiveFailed-family already uses).
    fun decodeStashedEntries(s: String): List<StashedEntry> {
        val arr = extractJsonArray(s, "files") ?: return emptyList()
        return arr.mapNotNull { obj ->
            val relPath = EventJsonFields.stringField(obj, "relPath") ?: return@mapNotNull null
            val sha256 = EventJsonFields.stringField(obj, "sha256") ?: return@mapNotNull null
            val sizeBytes = EventJsonFields.longField(obj, "sizeBytes") ?: 0L
            StashedEntry(relPath = relPath, sha256 = sha256, sizeBytes = sizeBytes)
        }
    }

    fun decodeRestoredEntries(s: String): List<RestoredEntry> {
        val arr = extractJsonArray(s, "entries") ?: return emptyList()
        return arr.mapNotNull { obj ->
            val relPath = EventJsonFields.stringField(obj, "relPath") ?: return@mapNotNull null
            val sha256 = EventJsonFields.stringField(obj, "sha256") ?: return@mapNotNull null
            val sizeBytes = EventJsonFields.longField(obj, "sizeBytes") ?: 0L
            RestoredEntry(relPath = relPath, sha256 = sha256, sizeBytes = sizeBytes)
        }
    }

    // WU-LPR-090 — HtmlReport helpers (compact JSON arrays, same shape as Stash).

    fun decodeHtmlReportEntries(s: String): List<HtmlReportEntry> {
        val arr = extractJsonArray(s, "entries") ?: return emptyList()
        return arr.mapNotNull { obj ->
            val relPath = EventJsonFields.stringField(obj, "relPath") ?: return@mapNotNull null
            val sha256 = EventJsonFields.stringField(obj, "sha256") ?: return@mapNotNull null
            val sizeBytes = EventJsonFields.longField(obj, "sizeBytes") ?: 0L
            HtmlReportEntry(relPath = relPath, sha256 = sha256, sizeBytes = sizeBytes)
        }
    }

    /**
     * Extracts the JSON array of objects for the named field from a single
     * event payload. Returns one string per object in the array, or null if
     * the field is missing or not an array.
     */
    fun extractJsonArray(payload: String, fieldName: String): List<String>? {
        // Find the field marker (e.g. `"files":[`)
        val marker = "\"$fieldName\":["
        val start = payload.indexOf(marker)
        if (start < 0) return null
        val arrayStart = start + marker.length
        // Walk forward, tracking BOTH bracket depth (`[`/`]`) AND brace depth (`{`/`}`)
        // because the payload we receive here is a single event object (already extracted
        // from the outer JSON array), and the array-of-objects inside it must be balanced
        // through brace depth (the opening `[` is consumed by the marker itself).
        // WU-LPR-090 fix: prior implementation only tracked `[`/`]`, which caused arrays
        // of objects (StashCreated/StashRestored entries, HtmlReportPublished entries) to
        // decode as empty lists — the outer `]` at end-of-array decremented past 0 and
        // never matched `depth == 0`.
        // `bracketDepth` starts at 1 because the opening `[` of the array is consumed by the
        // marker itself; we only see the matching `]` once on the closing side.
        var bracketDepth = 1
        var braceDepth = 0
        var i = arrayStart
        var inString = false
        var escape = false
        while (i < payload.length) {
            val c = payload[i]
            if (escape) { escape = false; i++; continue }
            if (c == '\\') { escape = true; i++; continue }
            if (c == '"') { inString = !inString; i++; continue }
            if (inString) { i++; continue }
            when (c) {
                '[' -> bracketDepth++
                ']' -> {
                    bracketDepth--
                    if (bracketDepth == 0 && braceDepth == 0) {
                        val arrayText = payload.substring(arrayStart, i)
                        // Split top-level objects by tracking brace depth.
                        return splitTopLevelObjects(arrayText)
                    }
                }
                '{' -> braceDepth++
                '}' -> braceDepth--
            }
            i++
        }
        return null
    }

    /** Splits a JSON array body like `{...},{...},{...}` into one string per top-level object. */
    fun splitTopLevelObjects(arrayText: String): List<String> {
        val results = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escape = false
        var i = 0
        while (i < arrayText.length) {
            val c = arrayText[i]
            if (escape) { escape = false; i++; continue }
            if (c == '\\') { escape = true; i++; continue }
            if (c == '"') { inString = !inString; i++; continue }
            if (inString) { i++; continue }
            when (c) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        results.add(arrayText.substring(start, i + 1))
                        start = -1
                    }
                }
                ',' -> if (depth == 0) { /* skip separators between objects */ }
            }
            i++
        }
        return results
    }

    /**
     * Serializes a string to a quoted JSON literal, escaping per RFC 8259.
     */
}
