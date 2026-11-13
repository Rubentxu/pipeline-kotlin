package dev.rubentxu.pipeline.v2.scripting

/**
 * The advice that a declaration written in the wrong place carries.
 *
 * ## What it reads
 *
 * The SOURCE the compiler saw and the position the diagnostic pointed at. The
 * TEXT of the diagnostic is not read: the compiler may reword it, and the code
 * it publishes carries no id — measured, it is `-1` in every case. The position
 * points at the NAME the author wrote, and the name is what says which
 * declaration of the stage was written where it does not belong.
 *
 * ## What it recognises
 *
 * - A declaration that belongs to the STAGE: the configuration of the stage and
 *   the blocks the stage opens. Written inside a body, the compiler refuses it,
 *   because a body that carries steps has no authority over the stage.
 * - A form the language RETIRED: it is not a declaration any more, and the
 *   advice says so instead of leaving the author with "unresolved reference".
 *
 * ## What it refuses
 *
 * - A name that is not the CALLEE of a call. A reference to a value is not a
 *   call, and the advice of a call would send an author who is already in the
 *   right place to the right place again.
 * - A name the compiler RESOLVED: a resolved call produces no diagnostic, so
 *   there is nothing to advise. This is what separates a call from a text, from
 *   a comment, from a function with the same name and from a plugin's step: the
 *   compiler resolved those, and this object only speaks about what it refused.
 *
 * ## Where it lives
 *
 * In the COMPILER ADAPTER, and not in the domain: the advice reads the text the
 * compiler saw, and the domain has no business knowing that text.
 */
internal object StageDirectiveAdvice {

    /**
     * The declarations that belong to the STAGE.
     *
     * They are the CONFIGURATION of the stage — its directives, its gates, its
     * options, its post conditions and its declarative environment — and the
     * BLOCKS the stage opens. A body that carries steps declares none of them,
     * and the compiler refuses the ones written inside it.
     *
     * The list is the DSL's own surface, MEASURED: it is what the scope of the
     * stage declares and the contract of a body does not, plus the declarative
     * `environment`, whose NAME the body carries for the wrapper that binds a
     * credential to a variable. The compiler tells the two apart by their
     * arity, so the advice of the declarative one is never given to the
     * wrapper.
     */
    internal val DECLARATIONS_OF_THE_STAGE: Set<String> = setOf(
        "directives",
        "environment",
        "options",
        "parallel",
        "post",
        "script",
        "steps",
        "toStageBuilder",
        "whenEnvIs",
        "whenEnvPresent",
        "whenGate",
    )

    /**
     * The forms that asked for an execution target and that the language
     * RETIRED.
     *
     * They are not declarations of the stage, and the advice does not say that
     * they are: it says that the form is gone and that the requirement of the
     * stage is a directive of the plan, not a block of the script.
     */
    internal val RETIRED: Map<String, String> = mapOf(
        "agent" to
            "In PipelineK: `agent` is not a declaration anymore: the stage asks for its " +
                "execution target with the `core.agent` directive, and the language offers " +
                "no way to author it.",
        "node" to
            "In PipelineK: `node` is not a declaration anymore: the DSL retired the " +
                "`node(...) { ... }` form, and the stage asks for its execution target with " +
                "the `core.agent` directive.",
    )

    /**
     * The advice for the position [line] and [column] of the [source], or null
     * when there is none to give.
     *
     * @param source the text the compiler saw, which is where the positions of
     *   the diagnostics point.
     * @param line the line of the diagnostic, counted from one.
     * @param column the column of the diagnostic, counted from one.
     */
    fun adviceFor(source: String, line: Int, column: Int): String? {
        val bounds = lineBounds(source, line) ?: return null
        val index = bounds.first + (column - 1)
        if (index !in bounds) return null

        val name = identifierAt(source, index, bounds.last + 1) ?: return null
        val advice = adviceOf(name) ?: return null
        if (!isCallee(source, index + name.length)) return null
        return advice
    }

    /**
     * The advice for the declaration [name], or null when the product has none
     * to give about it.
     */
    private fun adviceOf(name: String): String? =
        if (name in DECLARATIONS_OF_THE_STAGE) {
            "In PipelineK: `$name` belongs to the STAGE: declare it in the body of the " +
                "stage, outside every block that carries steps."
        } else {
            RETIRED[name]
        }

    /**
     * The characters of the line [line] of the [source], counted from one, or
     * null when the source has no such line.
     *
     * The bounds are ABSOLUTE, because the advice of a name reads the character
     * that follows it, and that character may be on another line.
     */
    private fun lineBounds(source: String, line: Int): IntRange? {
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
        val last = if (end < 0) source.length else end
        return start until last
    }

    /**
     * The identifier that starts at [index], or null when there is none.
     *
     * The compiler points at the FIRST character of the name, so the identifier
     * is read forwards. A position in the middle of a name would read a
     * truncated one, and a truncated name is not in any table: the advice is
     * withheld rather than given for a name nobody wrote.
     */
    private fun identifierAt(source: String, index: Int, limit: Int): String? {
        if (index >= limit) return null
        val first = source[index]
        if (!first.isLetter() && first != '_') return null
        var end = index
        while (end < limit && isIdentifierPart(source[end])) end++
        return source.substring(index, end)
    }

    /**
     * Whether the name that ends at [index] is the CALLEE of a call.
     *
     * A call opens its arguments with a parenthesis or a lambda with a brace.
     * Anything else is not a call: a reference to a value is a value, and the
     * advice of a call would send an author who wrote a value to write a call
     * somewhere else.
     */
    private fun isCallee(source: String, index: Int): Boolean {
        var position = index
        while (position < source.length) {
            val character = source[position]
            when {
                character.isWhitespace() -> position++
                character == ';' -> return false
                character == '(' || character == '{' -> return true
                else -> return false
            }
        }
        return false
    }

    /** Whether the character is part of an identifier of Kotlin. */
    private fun isIdentifierPart(character: Char): Boolean =
        character == '_' || character.isLetterOrDigit()
}
