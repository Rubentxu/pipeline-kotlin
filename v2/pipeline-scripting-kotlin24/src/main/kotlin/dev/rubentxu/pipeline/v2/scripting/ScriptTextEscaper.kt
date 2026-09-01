package dev.rubentxu.pipeline.v2.scripting

/**
 * Idempotent, brace-depth-aware escaper that protects environment-variable
 * references from premature Kotlin string-template expansion inside `${...}` blocks.
 *
 * The algorithm scans character-by-character and tracks:
 * - **brace depth** — depth=0 outside `${...}`; depth>=1 inside
 * - **string context** — inside `"..."`, `'...'`, `//`, or `/* */`
 *
 * When a `$` followed by a valid identifier is found:
 * - If **depth=0** and the identifier is in [envVars] → replaces `$VAR` with `${'$'}VAR`
 *   (Kotlin literal dollar followed by the identifier, not a template)
 * - If **depth>=1** or identifier is **not** in [envVars] → leaves unchanged (idempotent)
 *
 * Edge cases handled:
 * - `$` alone or followed by non-identifier → left as-is
 * - `$$` → left as-is (literal dollar)
 * - `$_` → left as-is (not a valid identifier)
 * - `${'$'}VAR` → depth=1, left as-is
 */
internal object ScriptTextEscaper {

    /**
     * Escapes all `$VAR` references in [scriptText] that correspond to env vars in [envVars],
     * but only when they appear outside of `${...}` Kotlin string template blocks.
     *
     * @param scriptText the raw pipeline script content
     * @param envVars set of env-var names to protect (e.g. `{"USERNAME", "PASSWORD"}`)
     * @return the escaped script text (idempotent — re-running produces the same result)
     */
    fun escape(scriptText: String, envVars: Set<String>): String {
        if (envVars.isEmpty()) return scriptText

        val result = StringBuilder(scriptText.length + envVars.size * 4)
        var braceDepth = 0
        var inLineComment = false
        var inBlockComment = false
        var inDoubleString = false
        var inSingleString = false
        var i = 0

        while (i < scriptText.length) {
            val c = scriptText[i]

            // Track line comment state
            if (!inLineComment && !inBlockComment && !inDoubleString && !inSingleString && c == '/' && i + 1 < scriptText.length && scriptText[i + 1] == '/') {
                inLineComment = true
                result.append(c)
                i++
                continue
            }

            // Track block comment state
            if (!inLineComment && !inBlockComment && !inDoubleString && !inSingleString && c == '/' && i + 1 < scriptText.length && scriptText[i + 1] == '*') {
                inBlockComment = true
                result.append(c)
                i++
                continue
            }

            // Exit line comment on newline
            if (inLineComment && c == '\n') {
                inLineComment = false
                result.append(c)
                i++
                continue
            }

            // Exit block comment on */
            if (inBlockComment && c == '*' && i + 1 < scriptText.length && scriptText[i + 1] == '/') {
                inBlockComment = false
                result.append(c)
                i++
                continue
            }

            // Track double-quoted string
            if (!inLineComment && !inBlockComment && !inSingleString && c == '"') {
                if (inDoubleString) {
                    // Check if this quote is escaped (preceded by odd number of backslashes)
                    var j = i - 1
                    var escapeCount = 0
                    while (j >= 0 && scriptText[j] == '\\') {
                        escapeCount++
                        j--
                    }
                    if (escapeCount % 2 == 0) {
                        // Not escaped — this closes the string
                        inDoubleString = false
                    }
                    // If escaped (odd backslashes), stay in string
                } else {
                    // Opening quote
                    inDoubleString = true
                }
            }

            // Track single-quoted string
            if (!inLineComment && !inBlockComment && !inDoubleString && c == '\'') {
                if (inSingleString) {
                    var j = i - 1
                    var escapeCount = 0
                    while (j >= 0 && scriptText[j] == '\\') {
                        escapeCount++
                        j--
                    }
                    if (escapeCount % 2 == 0) {
                        inSingleString = false
                    }
                } else {
                    inSingleString = true
                }
            }

            // Track brace depth (only outside strings and comments)
            if (!inLineComment && !inBlockComment && !inDoubleString && !inSingleString) {
                if (c == '{') {
                    braceDepth++
                } else if (c == '}') {
                    braceDepth = maxOf(0, braceDepth - 1)
                }
            }

            // Handle $ identifier references (only at top-level, not in strings/comments/braces)
            if (c == '$' && !inLineComment && !inBlockComment && !inDoubleString && !inSingleString && braceDepth == 0) {
                val idPair = tryReadIdentifier(scriptText, i + 1)
                if (idPair != null) {
                    val (identifier, identifierEnd) = idPair
                    if (identifier in envVars) {
                        // Escape: $VAR -> ${'$'}VAR
                        result.append("\${'$'}")
                        result.append(identifier)
                        i = identifierEnd
                        continue
                    }
                }
            }

            result.append(c)
            i++
        }

        return result.toString()
    }

    /**
     * Attempts to read a Kotlin identifier starting at [start].
     * Returns the identifier text and the index after the last character,
     * or `null` if no valid identifier starts at [start].
     */
    private fun tryReadIdentifier(text: String, start: Int): Pair<String, Int>? {
        if (start >= text.length) return null
        val first = text[start]
        if (first != '_' && !first.isLetter()) return null

        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            if (c == '_' || c.isLetterOrDigit()) {
                i++
            } else {
                break
            }
        }
        return Pair(text.substring(start, i), i)
    }

    /**
     * Returns true if position [pos] in [text] is inside a string literal,
     * character literal, or comment.
     */
    private fun isInsideString(text: String, pos: Int): Boolean {
        var inDoubleString = false
        var inSingleString = false
        var inLineComment = false
        var inBlockComment = false
        var i = 0

        while (i < text.length) {
            if (inLineComment) {
                if (text[i] == '\n') inLineComment = false
                i++
                continue
            }
            if (inBlockComment) {
                if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '/') {
                    inBlockComment = false
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (inDoubleString) {
                if (text[i] == '\\' && i + 1 < text.length) {
                    // Skip escaped character
                    i += 2
                    continue
                }
                if (text[i] == '"') {
                    inDoubleString = false
                }
                i++
                continue
            }
            if (inSingleString) {
                if (text[i] == '\\' && i + 1 < text.length) {
                    // Skip escaped character
                    i += 2
                    continue
                }
                if (text[i] == '\'') {
                    inSingleString = false
                }
                i++
                continue
            }

            // Not in any string/comment
            when {
                text.startsWith("//", i) -> { inLineComment = true; i += 2 }
                text.startsWith("/*", i) -> { inBlockComment = true; i += 2 }
                text[i] == '"' -> { inDoubleString = true; i++ }
                text[i] == '\'' -> { inSingleString = true; i++ }
                else -> {
                    if (i == pos) return false
                    i++
                }
            }
        }

        return false
    }
}
