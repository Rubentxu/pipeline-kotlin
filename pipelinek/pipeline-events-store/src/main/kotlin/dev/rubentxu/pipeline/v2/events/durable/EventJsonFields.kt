package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.scripting.CacheKey

/**
 * The primitive field readers of the [JsonEventLog] wire format: string,
 * long, int, boolean and the embedded cache key.
 * [JsonEventLog] is an `object`, so detekt budgets it 11 functions
 * (allowedFunctionsPerObject) rather than the 25 allowedFunctionsPerClass
 * that applies to classes. These readers were private there and are shared
 * by every event constructor in [EventJsonDecoder].
*/
/**
 * The OPTIONAL string fields of the event contract, by the name they travel under.
 *
 * ## Why this set is closed
 *
 * The writer and the decoder used to spell every optional field independently: a
 * literal `,"detail":` on one side and a literal `"detail"` on the other, with
 * nothing saying they had to agree. A field added on one side only compiled, ran,
 * and silently decoded wrong — the value simply vanished on the way back, and a
 * durable record that reads back without its payload looks exactly like a record
 * that never carried one.
 *
 * So the names live here, in ONE place, and both sides read them from here. The
 * set is an enum on purpose: it is CLOSED. An optional field that is not a case
 * of this enum cannot be written or read at all, which is what makes "the two
 * sides cover the same field set" a property of the type rather than a promise
 * in a comment.
 *
 * ## Why the prefix says OPTIONAL
 *
 * The same wire name can be optional on one event and required on another:
 * `message` is optional on `InputProceed` and required on `InputRequested`.
 * Those are two different contracts, and this enum keeps them apart.
 */
internal enum class OptionalEventField(val wireName: String) {
    /** The verdict of a step. Absent means "no verdict", not "an empty verdict". */
    OUTCOME("outcome"),

    /** The remote URI of a resolved agent. */
    REMOTE_URI("remoteUri"),

    /** The name of the step a timeout was scheduled for. */
    STEP_NAME("stepName"),

    /** The type of the step a timeout was scheduled for. */
    STEP_TYPE("stepType"),

    /** The build result a catchError scope declared. */
    BUILD_RESULT("buildResult"),

    /** The message a catchError scope or an input answer carries. */
    MESSAGE("message"),

    /** The label of a milestone. */
    LABEL("label"),

    /** The reason a lock was requested, or the reason a Saga ended. */
    REASON("reason"),

    /** Who answered an input. Attribution, never authorization. */
    SUBMITTER("submitter"),

    /** The identifier of the question that was asked. */
    ID("id"),

    /** The detail of a compensation. A confirmation has nothing to add. */
    DETAIL("detail"),
}

internal object EventJsonFields {

    /**
     * How "this optional field was not declared" travels on the wire.
     * An optional field is written as the empty string, and read back as `null`. That pair used
     * to be two independent literals: thirteen `?: ""` in [EventJsonWriter] and thirteen
     * `takeIf { it.isNotEmpty() }` in [JsonEventLog]. They happened to correspond, and nothing
     * asserted that they did.
     * That is the shape of the defect exists to close, in its mildest form. Add a writer
     * that forgets the reader and the field decodes as the empty string — a fabricated value
     * that reads back as a real one. Add a reader without the writer and absence silently
     * becomes `null`, which happens to be right for the wrong reason. Both compile, both run,
     * and neither is noticed until a consumer distinguishes "" from "not declared".
     * The empty string is kept rather than a JSON `null` because it is the encoding every
     * historical record already uses, and wire/history compatibility outranks elegance. What
     * changed is that the convention now has a name, ONE implementation on each side, and a
     * permanent test that walks every optional field of the contract and asserts that the two
     * sides cover the same field set.
*/
    const val ABSENT_ON_WIRE = ""

    /**
     * Reads an OPTIONAL string field, mapping the wire encoding of absence back to `null`.
     * Use [stringField] for a field that is required: for those, absence is corruption and must
     * fail closed rather than be invented into a value.
     *
     * The field is an [OptionalEventField] and not a `String`: an optional field is read by the
     * name the contract declares, or not at all.
*/
    fun optionalStringField(json: String, field: OptionalEventField): String? =
        stringField(json, field.wireName)?.takeIf { it != ABSENT_ON_WIRE }

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
