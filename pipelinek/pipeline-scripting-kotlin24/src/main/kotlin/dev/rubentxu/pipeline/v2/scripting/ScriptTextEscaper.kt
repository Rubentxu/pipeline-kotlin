package dev.rubentxu.pipeline.v2.scripting

/**
 * Idempotent, brace-depth-aware escaper that protects environment-variable
 * references from premature Kotlin string-template expansion inside `${...}` blocks.
 * The algorithm scans character-by-character and tracks:
 * - **brace depth** — depth=0 outside `${...}`; depth>=1 inside
 * - **string context** — inside `"..."`, `'...'`, `//`, or `/**/`
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
 * - `\$VAR` (an ODD number of backslashes before the `$`) → left as-is — the
 *   author's escape already makes the dollar literal; rewriting it would plant
 *   the `${'$'}` text in the compiled string, which the shell reads verbatim
 * - `\\$VAR` (an EVEN number of backslashes) → escaped: the backslashes are a
 *   literal, and the dollar behind them is still a live template
 */
internal object ScriptTextEscaper {

    /**
     * Escapes all `$VAR` references in [scriptText] that correspond to env vars in [envVars],
     * but only when they appear outside of `${...}` Kotlin string template blocks.
     * @param scriptText the raw pipeline script content
     * @param envVars set of env-var names to protect (e.g. `{"USERNAME", "PASSWORD"}`)
     * @return the escaped script text (idempotent — re-running produces the same result)
*/
    fun escape(scriptText: String, envVars: Set<String>): String {
        if (envVars.isEmpty()) return scriptText

        val result = StringBuilder(scriptText.length + envVars.size * 4)
        var braceDepth = 0
        var i = 0

        while (i < scriptText.length) {
            val c = scriptText[i]

            // Track brace depth when not inside a string
            if (c == '$' && i + 1 < scriptText.length) {
                val next = scriptText[i + 1]
                if (next == '{') {
                    // Could be ${...} block
                    if (!isInsideString(scriptText, i)) {
                        braceDepth++
                    }
                }
            }

            // Handle $ identifier references
            if (c == '$' && !isInsideString(scriptText, i)) {
                val idPair = tryReadIdentifier(scriptText, i + 1)
                if (idPair != null) {
                    val (identifier, identifierEnd) = idPair
                    if (identifier in envVars && braceDepth == 0) {
                        if (authorAlreadyEscaped(scriptText, i)) {
                            // The author's backslash already makes this dollar
                            // literal: the compiler consumes `\$` as an escape,
                            // and a rewrite inserted AFTER that backslash would
                            // never form a template — the `${'$'}` text itself
                            // would reach the shell and bash would reject it as
                            // a bad substitution. The pair of backslashes case
                            // is what keeps this a parity rule: two backslashes
                            // are a literal backslash followed by a live dollar
                            // that still needs protection.
                            result.append(c)
                            i++
                            continue
                        }
                        // Escape: $VAR -> ${'$'}VAR
                        result.append("\${'$'}")
                        result.append(identifier)
                        i = identifierEnd
                        continue
                    } else {
                        // Not an env var or inside braces — copy as-is
                        result.append(c)
                        i++
                        continue
                    }
                }
            }

            // Decrement brace depth when closing
            if (c == '}' && !isInsideString(scriptText, i)) {
                if (braceDepth > 0) braceDepth--
            }

            result.append(c)
            i++
        }

        return result.toString()
    }

    /**
     * Returns true if the `$` at [pos] is protected by the author's own escape:
     * an ODD number of consecutive backslashes immediately before it. One
     * backslash is the author's `\$` — the compiler resolves it to a literal
     * dollar and no protection is needed; an even count (including none) leaves
     * a live dollar behind the backslashes, which still needs the escaper.
*/
    private fun authorAlreadyEscaped(text: String, pos: Int): Boolean {
        var backslashes = 0
        var i = pos - 1
        while (i >= 0 && text[i] == '\\') {
            backslashes++
            i--
        }
        return backslashes % 2 == 1
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
                if (i == pos) return true
                if (text[i] == '\n') inLineComment = false
                i++
                continue
            }
            if (inBlockComment) {
                if (i == pos) return true
                if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '/') {
                    inBlockComment = false
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (inDoubleString) {
                // Double-quoted Kotlin strings are exactly WHERE env-var
                // references must be escaped (template-expansion protection), so a
                // position inside them is processable (return false via the tail).
                if (i == pos) return false
                if (text[i] == '"' && (i == 0 || text[i - 1] != '\\')) {
                    inDoubleString = false
                }
                i++
                continue
            }
            if (inSingleString) {
                if (i == pos) return true
                if (text[i] == '\'' && (i == 0 || text[i - 1] != '\\')) {
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
