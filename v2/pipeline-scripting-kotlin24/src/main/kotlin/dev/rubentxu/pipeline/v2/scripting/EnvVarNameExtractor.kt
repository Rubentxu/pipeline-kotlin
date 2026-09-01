package dev.rubentxu.pipeline.v2.scripting

/**
 * Recursive-descent parser that extracts environment-variable names bound inside
 * `withCredentials { StepSpec.CredentialsBinding.<factory>(..., "ENV_VAR", ...) }`
 * blocks in a pipeline script.
 *
 * Only string literals that appear as the env-var-name argument are extracted.
 * String literals inside lambdas, nested `withCredentials`, or comments are ignored.
 */
internal object EnvVarNameExtractor {

    /**
     * Extracts all environment-variable names bound by `withCredentials` in [scriptText].
     *
     * @param scriptText the raw pipeline script content
     * @return a deduplicated set of env-var names (e.g. `{"USERNAME", "PASSWORD"}`)
     */
    fun extract(scriptText: String): Set<String> {
        val result = mutableSetOf<String>()
        extractImpl(scriptText, 0, result)
        return result
    }

    private fun extractImpl(text: String, start: Int, acc: MutableSet<String>): Int {
        var pos = start

        while (pos < text.length) {
            // Find next withCredentials(
            val wcStart = text.indexOf("withCredentials(", pos, ignoreCase = false)
            if (wcStart == -1) break

            // The opening parenthesis of withCredentials( is right before the first argument.
            // We first find the lambda's opening brace (brace depth 0 scan),
            // then search backward from that brace to find the correct ) that closes withCredentials(.
            val lambdaBrace = findLambdaBrace(text, wcStart)
            if (lambdaBrace == -1) {
                pos = wcStart + "withCredentials(".length
                continue
            }

            // Search backward from lambdaBrace to find the ) that closes withCredentials(
            val blockEnd = findClosingParenBackward(text, lambdaBrace)
            if (blockEnd == -1) {
                pos = lambdaBrace + 1
                continue
            }

            // The binding call list is between afterParen and lambdaBrace
            val afterParen = wcStart + "withCredentials(".length
            scanBindingCalls(text, afterParen, lambdaBrace, acc)

            // Advance past this entire withCredentials block
            pos = blockEnd + 1
        }

        return pos
    }

    /**
     * Finds the first `{` at brace depth 0 (the lambda body's opening brace)
     * that appears after [from] within [limit].
     * Ignores braces inside strings.
     */
    private fun findLambdaBrace(text: String, from: Int, limit: Int = text.length): Int {
        var depth = 0
        var inString = false
        var stringChar: Char = 0.toChar()
        var i = from
        while (i < limit) {
            val c = text[i]
            if (!inString) {
                when (c) {
                    '{' -> if (depth == 0) return i
                    '}' -> { /* depth would go negative, but we return on depth=0 */ }
                    '"', '\'' -> { inString = true; stringChar = c }
                }
            } else {
                if (c == stringChar) inString = false
            }
            i++
        }
        return -1
    }

    /**
     * Search backward from [start] to find the `)` that closes the `withCredentials(` call.
     * The `withCredentials(` opens at or before [start], and we need its matching `)`.
     */
    private fun findClosingParenBackward(text: String, start: Int): Int {
        var depth = 0
        var i = start - 1
        while (i >= 0) {
            val c = text[i]
            if (c == ')') {
                depth++
            } else if (c == '(') {
                depth--
                if (depth == 0) return i
            }
            i--
        }
        return -1
    }

    /**
     * Finds the index of the opening `{` that starts the lambda block inside withCredentials.
     * The binding call list ends just before this brace.
     */
    private fun findOpeningBrace(text: String, from: Int, before: Int): Int {
        var depth = 0
        var inString = false
        var stringChar: Char = 0.toChar()
        var i = from
        while (i < before) {
            val c = text[i]
            if (!inString) {
                when (c) {
                    '(' -> depth++
                    ')' -> depth--
                    '{' -> if (depth == 0) return i
                }
            } else {
                if (c == stringChar && (c != '\\' || i > 0 && text[i - 1] != '\\')) {
                    inString = false
                }
            }
            i++
        }
        return -1
    }

    /**
     * Finds the matching `)` for the `(` at [openParen], respecting nested `()` and strings.
     */
    private fun findMatchingParen(text: String, openParen: Int): Int {
        var depth = 1
        var inString = false
        var stringChar: Char = 0.toChar()
        var i = openParen
        while (i < text.length && depth > 0) {
            val c = text[i]
            if (!inString) {
                when (c) {
                    '(' -> depth++
                    ')' -> depth--
                }
            } else {
                // \" and \' escapes are handled by checking if previous char is backslash
                if (c == stringChar) {
                        // check if escaped
                        val prev = if (i > 0) text[i - 1] else 0.toChar()
                        if (prev != '\\') {
                            inString = false
                        }
                }
            }
            i++
        }
        return if (depth == 0) i - 1 else -1
    }

    /**
     * Scans [text][from..before) for `StepSpec.CredentialsBinding.<factory>(..., "VAR", ...)`
     * calls and adds each "VAR" to [acc].
     */
    private fun scanBindingCalls(text: String, from: Int, before: Int, acc: MutableSet<String>) {
        var i = from

        while (i < before) {
            // Skip comments and strings
            i = skipWhitespaceAndComments(text, i, before)
            if (i >= before) break

            if (text.startsWith("//", i)) {
                i = skipLineComment(text, i, before)
                continue
            }
            if (text.startsWith("/*", i)) {
                i = skipBlockComment(text, i, before)
                continue
            }

            if (text[i] == '"') {
                i = skipString(text, i, before)
                continue
            }
            if (text[i] == '\'') {
                i = skipCharLiteral(text, i, before)
                continue
            }

            // Look for StepSpec.CredentialsBinding. identifier (
            val factory = matchFactoryCall(text, i, before)
            if (factory != null) {
                val (envVars, newI) = extractEnvVarFromFactoryCall(text, factory.parenStart + 1, before)
                acc.addAll(envVars)
                i = newI
                continue
            }

            i++
        }
    }

    private fun skipWhitespaceAndComments(text: String, from: Int, before: Int): Int {
        var i = from
        while (i < before) {
            when {
                text[i].isWhitespace() -> i++
                text.startsWith("//", i) -> i = skipLineComment(text, i, before)
                text.startsWith("/*", i) -> i = skipBlockComment(text, i, before)
                else -> break
            }
        }
        return i
    }

    private fun skipLineComment(text: String, from: Int, before: Int): Int {
        var i = from + 2
        while (i < before && text[i] != '\n') i++
        return i
    }

    private fun skipBlockComment(text: String, from: Int, before: Int): Int {
        var i = from + 2
        while (i + 1 < before) {
            if (text[i] == '*' && text[i + 1] == '/') return i + 2
            i++
        }
        return before
    }

    private fun skipString(text: String, from: Int, before: Int): Int {
        val quote = text[from]
        var i = from + 1
        while (i < before) {
            val c = text[i]
            if (c == '\\' && i + 1 < before) {
                i += 2 // skip escape
                continue
            }
            if (c == quote) return i + 1
            i++
        }
        return i
    }

    private fun skipCharLiteral(text: String, from: Int, before: Int): Int {
        return skipString(text, from, before)
    }

    /**
     * If [text] starting at [pos] begins with a `StepSpec.CredentialsBinding.<factory>(` call,
     * returns the factory name and the index of the closing paren; otherwise null.
     */
    private fun matchFactoryCall(text: String, pos: Int, before: Int): FactoryMatch? {
        val prefix = "StepSpec.CredentialsBinding."
        if (!text.startsWith(prefix, pos)) return null

        val nameStart = pos + prefix.length
        var i = nameStart
        while (i < before && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        if (i == nameStart) return null
        val factoryName = text.substring(nameStart, i)

        val paren = text.indexOf('(', i)
        if (paren == -1 || paren >= before) return null

        val closeParen = findMatchingParen(text, paren + 1)
        if (closeParen == -1) return null

        return FactoryMatch(factoryName, paren, closeParen)
    }

    private data class FactoryMatch(val name: String, val parenStart: Int, val parenEnd: Int)

    /**
     * Inside a factory call's argument list, finds ALL env-var string literals and returns them.
     * The search scans through nested parentheses. Skips non-env-var strings (e.g., credentialsId).
     */
    private fun extractEnvVarFromFactoryCall(text: String, from: Int, before: Int): Pair<Set<String>, Int> {
        var depth = 0
        var inString = false
        var stringChar: Char = 0.toChar()
        var i = from
        val envVars = mutableSetOf<String>()

        while (i < before) {
            val c = text[i]

            if (!inString) {
                when (c) {
                    '(' -> { depth++; i++; }
                    ')' -> { if (depth == 0) return Pair(envVars, i + 1); depth--; i++; }
                    '"' -> {
                        val strEnd = skipString(text, i, before)
                        val content = text.substring(i + 1, strEnd - 1)
                        if (looksLikeEnvVarName(content)) {
                            envVars.add(content)
                        }
                        i = strEnd
                    }
                    else -> i++
                }
            } else {
                if (c == stringChar) {
                    inString = false
                }
                i++
            }
        }
        return Pair(envVars, i)
    }

    private fun looksLikeEnvVarName(s: String): Boolean {
        if (s.isEmpty()) return false
        return s.all { it.isUpperCase() || it.isDigit() || it == '_' }
    }
}
