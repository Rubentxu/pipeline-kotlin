package dev.rubentxu.pipeline.v2.events

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
        return if (stringEnd > i + 1) json.substring(i + 1, stringEnd) else ""
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
