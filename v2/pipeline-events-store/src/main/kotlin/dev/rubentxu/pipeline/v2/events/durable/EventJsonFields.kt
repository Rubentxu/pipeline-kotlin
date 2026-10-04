package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.scripting.CacheKey

/**
 * The primitive field readers of the [JsonEventLog] wire format: string,
 * long, int, boolean and the embedded cache key.
 *
 * [JsonEventLog] is an `object`, so detekt budgets it 11 functions
 * (allowedFunctionsPerObject) rather than the 25 allowedFunctionsPerClass
 * that applies to classes. These readers were private there and are shared
 * by every event constructor in [EventJsonDecoder].
 */
internal object EventJsonFields {

    /**
     * Extracts a string field value from JSON by finding the field name
     * and reading until the closing quote (handling escapes).
     */
    fun stringField(json: String, name: String): String? {
        val nameStart = json.indexOf("\"$name\"")
        if (nameStart == -1) return null
        val colonPos = json.indexOf(':', nameStart)
        if (colonPos == -1) return null
        var i = colonPos + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') return null
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
        return if (stringEnd > i + 1) unescape(json.substring(i + 1, stringEnd)) else ""
    }

    /**
     * Reverses exactly the escapes [dev.rubentxu.pipeline.v2.events.durable.EventJsonWriter.jsonString]
     * emits (`\\` `"` `\n` `\r` `\t`). Any other backslash pair is left verbatim so an
     * unknown escape degrades to data, never to a lost character.
     */
    private fun unescape(raw: String): String {
        if ('\\' !in raw) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    else -> { sb.append(c); i += 1 }
                }
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }

    /**
     * Extracts the cacheKey object value from the event JSON.
     * Returns a CacheKey or null if parsing fails.
     */
    fun parseCacheKey(json: String): CacheKey? {
        val keyStart = json.indexOf("\"cacheKey\"")
        if (keyStart == -1) return null
        val bracePos = json.indexOf('{', keyStart)
        if (bracePos == -1) return null
        var depth = 0
        var i = bracePos
        while (i < json.length) {
            when (json[i]) {
                '{' -> { depth++; i++ }
                '}' -> { depth--; if (depth == 0) break; i++ }
                '"' -> {
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

    fun longField(json: String, name: String): Long? {
        val nameStart = json.indexOf("\"$name\"")
        if (nameStart == -1) return null
        val colonPos = json.indexOf(':', nameStart)
        if (colonPos == -1) return null
        var i = colonPos + 1
        while (i < json.length && json[i].isWhitespace()) i++
        var numEnd = i
        while (numEnd < json.length && (json[numEnd].isDigit() || json[numEnd] == '-')) numEnd++
        return if (numEnd > i) json.substring(i, numEnd).toLongOrNull() else null
    }

    fun intField(json: String, name: String): Int? {
        return longField(json, name)?.toInt()
    }

    /**
     * Extracts a JSON string-array field. [JsonEventLog] encodes lists only via
     * [EventJsonWriter.jsonStringList] (flat array of quoted strings), so the
     * reader is a bracket scanner plus repeated [stringField]-style unquoting.
     * Returns null when the field is absent; an empty array yields an empty
     * list. Elements are raw (no nested arrays/objects are expected or read).
     */
    fun stringListField(json: String, name: String): List<String>? {
        val nameStart = json.indexOf("\"$name\"")
        if (nameStart == -1) return null
        val colonPos = json.indexOf(':', nameStart)
        if (colonPos == -1) return null
        var i = colonPos + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '[') return null
        val open = i
        val close = json.indexOf(']', open)
        if (close == -1) return null
        val body = json.substring(open + 1, close)
        if (body.isBlank()) return emptyList()
        val values = mutableListOf<String>()
        var j = 0
        while (j < body.length) {
            if (body[j] != '"') { j++; continue }
            var valueEnd = j + 1
            var escape = false
            while (valueEnd < body.length) {
                when {
                    escape -> escape = false
                    body[valueEnd] == '\\' -> escape = true
                    body[valueEnd] == '"' -> break
                }
                valueEnd++
            }
            // Reuse the same unescape path by wrapping the raw lexeme in a
            // synthetic QUOTED key (stringField matches "name" literally).
            val raw = body.substring(j, minOf(valueEnd + 1, body.length))
            values += stringField("\"k\":$raw", "k") ?: ""
            j = valueEnd + 1
        }
        return values
    }

    fun boolField(json: String, name: String): Boolean {
        val nameStart = json.indexOf("\"$name\"")
        if (nameStart == -1) return false
        val colonPos = json.indexOf(':', nameStart)
        if (colonPos == -1) return false
        var i = colonPos + 1
        while (i < json.length && json[i].isWhitespace()) i++
        val start = i
        while (i < json.length && json[i].isLetter()) i++
        val value = json.substring(start, i)
        return value == "true"
    }
}
