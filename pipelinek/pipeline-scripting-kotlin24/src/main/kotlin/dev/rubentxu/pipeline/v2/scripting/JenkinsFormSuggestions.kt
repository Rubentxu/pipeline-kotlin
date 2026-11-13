package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.domain.step.JenkinsFormShape
import dev.rubentxu.pipeline.v2.domain.step.JenkinsForms

/**
 * The suggestion that a diagnostic about a form of Jenkins carries.
 *
 * ## What it reads
 *
 * The SOURCE the compiler saw and the position the diagnostic pointed at. The
 * TEXT of the diagnostic is not read: the compiler may reword it, and the code
 * it publishes carries no id — measured, it is `-1` in every case. The position,
 * on the other hand, points at the token that caused the diagnostic, and the
 * token is what says which form of Jenkins the author wrote.
 *
 * ## What it refuses
 *
 * A token that is not one of the declared shapes produces no suggestion, and
 * the diagnostic keeps the words the compiler gave it. Nothing here reads the
 * whole script: every rule looks at the position and at what surrounds it, so
 * a form is recognised by its own shape and not by a scan that could match it
 * inside a string or a comment.
 */
internal object JenkinsFormSuggestions {

    /**
     * The words that introduce an object expression in Kotlin, and not an
     * argument: `object : Foo()` writes a colon that names a supertype.
     */
    private val NOT_AN_ARGUMENT = setOf("object")

    /**
     * The suggestion for the position [line] and [column] of the [source], or
     * null when there is none to give.
     *
     * @param source the text the compiler saw, which is where the positions of
     *   the diagnostics point.
     * @param line the line of the diagnostic, counted from one.
     * @param column the column of the diagnostic, counted from one.
     */
    fun suggestionFor(source: String, line: Int, column: Int): String? {
        val text = lineAt(source, line) ?: return null
        val index = column - 1
        if (index !in text.indices) return null
        val recognition = recognize(text, index) ?: return null
        val form = JenkinsForms.of(recognition.shape) ?: return null
        return form.suggestion.replace(JenkinsForms.VALUE_PLACEHOLDER, recognition.value)
    }

    /**
     * The shape the token at [index] holds, with the value the suggestion
     * names, or null when the token is not a declared shape.
     */
    private fun recognize(text: String, index: Int): Recognition? {
        val character = text[index]
        return when {
            character == '\'' -> literalContent(text, index)
                ?.let { content -> Recognition(JenkinsFormShape.SINGLE_QUOTED_LITERAL, kotlinString(content)) }

            character == ':' && text.getOrNull(index + 1) != ':' ->
                namedArgumentAt(text, index)
                    ?.let { identifier -> Recognition(JenkinsFormShape.NAMED_ARGUMENT_COLON, identifier) }

            isIdentifierPart(character) ->
                propertyOfEnvAt(text, index)
                    ?.let { property -> Recognition(JenkinsFormShape.ENV_PROPERTY, property) }

            else -> null
        }
    }

    /**
     * The content of the literal of single quotes at [index], or null when the
     * quotes do not close on this line.
     *
     * The compiler points at the opening quote of the literal, and a literal
     * that is not closed is not a literal: the backward search is there for the
     * case where the diagnostic points at the closing one.
     */
    private fun literalContent(text: String, index: Int): String? {
        val closing = closingQuote(text, index)
        if (closing >= 0) return text.substring(index + 1, closing)
        val opening = openingQuote(text, index)
        if (opening >= 0) return text.substring(opening + 1, index)
        return null
    }

    /** The quote that closes the literal opened at [index], or -1. */
    private fun closingQuote(text: String, index: Int): Int {
        var position = index + 1
        while (position < text.length) {
            val character = text[position]
            if (character == '\\') {
                position += 2
                continue
            }
            if (character == '\'') return position
            position++
        }
        return -1
    }

    /** The quote that opens the literal closed at [index], or -1. */
    private fun openingQuote(text: String, index: Int): Int {
        var position = index - 1
        while (position >= 0) {
            val character = text[position]
            if (character == '\\') {
                position -= 2
                continue
            }
            if (character == '\'') return position
            position--
        }
        return -1
    }

    /**
     * The content of the literal of single quotes, as the string of Kotlin.
     *
     * The escapes of Groovy travel as they are, because Kotlin reads the same
     * ones, with two exceptions: the escaped quote of Groovy has no meaning in
     * a string of Kotlin, and the dollar and the double quote do, so they are
     * escaped on the way.
     */
    private fun kotlinString(content: String): String {
        val text = StringBuilder(content.length + 2)
        text.append('"')
        var index = 0
        while (index < content.length) {
            val character = content[index]
            if (character == '\\' && index + 1 < content.length) {
                val escaped = content[index + 1]
                if (escaped == '\'') {
                    text.append('\'')
                } else {
                    text.append('\\').append(escaped)
                }
                index += 2
                continue
            }
            when (character) {
                '"' -> text.append("\\\"")
                '$' -> text.append("\\$")
                else -> text.append(character)
            }
            index++
        }
        text.append('"')
        return text.toString()
    }

    /**
     * The name of the argument the colon at [index] names, or null when the
     * colon is not the one of a named argument.
     *
     * A named argument is the identifier that follows an opening parenthesis
     * or a comma: that is what tells it apart from the colon of an object
     * expression and from the one of a declaration.
     */
    private fun namedArgumentAt(text: String, index: Int): String? {
        var start = index
        while (start > 0 && isIdentifierPart(text[start - 1])) start--
        if (start == index) return null
        val identifier = text.substring(start, index)
        if (identifier in NOT_AN_ARGUMENT) return null
        var before = start - 1
        while (before >= 0 && text[before].isWhitespace()) before--
        if (before < 0) return null
        return when (text[before]) {
            '(', ',' -> identifier
            else -> null
        }
    }

    /**
     * The property of the environment that the token at [index] reads, or null
     * when the token is not the `env` of Jenkins.
     *
     * The identifier has to be `env` and nothing else, and a dot has to follow
     * it: `environment` is another word, and a property of nothing is not a
     * property of the environment.
     */
    private fun propertyOfEnvAt(text: String, index: Int): String? {
        if (text.getOrNull(index + 3) != '.') return null
        if (text.substring(index, index + 3) != "env") return null
        val start = index + 4
        var end = start
        while (end < text.length && isIdentifierPart(text[end])) end++
        if (end == start) return null
        return text.substring(start, end)
    }

    /** The line [line] of the [source], counted from one, or null when there is none. */
    private fun lineAt(source: String, line: Int): String? {
        if (line < 1) return null
        var start = 0
        var current = 1
        while (current < line) {
            val next = source.indexOf('\n', start)
            if (next < 0) return null
            start = next + 1
            current++
        }
        val end = source.indexOf('\n', start)
        return source.substring(start, if (end < 0) source.length else end)
    }

    /** Whether the character is part of an identifier of Kotlin. */
    private fun isIdentifierPart(character: Char): Boolean =
        character == '_' || character.isLetterOrDigit()

    /**
     * The shape that was recognised and the value the suggestion names.
     */
    private data class Recognition(val shape: JenkinsFormShape, val value: String)
}
