package dev.rubentxu.pipeline.v2.application

/**
 * Escapes a string for inclusion inside a JSON string literal.
 *
 * JSON forbids raw control characters inside strings, so every code point
 * below U+0020 travels escaped: the five with short names, and the rest as
 * their four-digit unicode form. Without the full set, a failure message
 * carrying a tab or a carriage return renders a line that looks like JSON
 * and stops being it — the worst shape a report can take, because the
 * consumer's parser fails after trusting the prefix.
 */
internal fun escapeJsonText(value: String): String = buildString(value.length) {
    for (character in value) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else ->
                if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
        }
    }
}
