package dev.rubentxu.pipeline.v2.application.observation

/**
 * Text selection — the `--grep` family — as a PURE decision type plus a
 * separately compiled matcher.
 *
 * ## Why the split
 *
 * The semantic constitution puts decision before interpretation:
 *
 * ```text
 * input -> pure decode/validation -> pure ADT decision -> interpreter -> effect
 * ```
 *
 * A [TextSelector] is the decision and holds only values. [CompiledTextSelector]
 * is the interpretation and owns the compiled regex. `Regex` already carries its
 * own options, so a selector that ALSO stored a case policy would have two
 * sources of truth that could contradict each other; the pattern is stored as a
 * `String` and compiled exactly once, here.
 *
 * ## Why `Only` and `Except` are separate cases
 *
 * They are not `selector + negate: Boolean`. An empty [Except] means "everything"
 * and an empty [Only] means "nothing" — collapsing them into a boolean makes
 * those two shapes indistinguishable and encodes a flag bag, which the semantic
 * constitution rejects. Both carry a NON-EMPTY selector list, validated at the
 * boundary.
 */
sealed interface TextSelector {
    /** Substring match, like `grep -F`. */
    data class Literal(val value: String, val ignoreCase: Boolean = false) : TextSelector

    /** Regular expression. Compiled by [compileTextSelector], never stored compiled. */
    data class Pattern(val pattern: String, val ignoreCase: Boolean = false) : TextSelector
}

/** Which lines a text filter keeps. */
sealed interface LineSelector {
    /** No text filtering. */
    data object All : LineSelector

    /** Keep only lines matching any selector (OR within this dimension). */
    data class Only(val selectors: List<TextSelector>) : LineSelector

    /** Keep every line except those matching any selector. */
    data class Except(val selectors: List<TextSelector>) : LineSelector
}

/** Compiled matcher. Interpretation, not decision. */
fun interface CompiledTextSelector {
    fun matches(text: String): Boolean
}

fun interface CompiledLineSelector {
    /** Whether [text] survives. */
    fun accepts(text: String): Boolean
}

/** Typed compile outcome — a bad pattern is a value, not an exception at the CLI. */
sealed interface CompileResult<out T> {
    data class Ok<T>(val value: T) : CompileResult<T>
    data class Invalid(val reason: String) : CompileResult<Nothing>
}

/**
 * Compiles one selector. Pure in the sense that it reads no ambient state; it
 * allocates a compiled matcher, which is why it is the boundary and not the type.
 */
fun compileTextSelector(selector: TextSelector): CompileResult<CompiledTextSelector> = when (selector) {
    is TextSelector.Literal -> CompileResult.Ok(
        object : CompiledTextSelector {
            override fun matches(text: String): Boolean =
                if (selector.ignoreCase) text.contains(selector.value, ignoreCase = true)
                else text.contains(selector.value)
        },
    )

    is TextSelector.Pattern -> {
        val options = if (selector.ignoreCase) {
            setOf(RegexOption.IGNORE_CASE)
        } else {
            emptySet()
        }
        val compiled = runCatching { Regex(selector.pattern, options) }
        if (compiled.isSuccess) {
            CompileResult.Ok(
                object : CompiledTextSelector {
                    override fun matches(text: String): Boolean = compiled.getOrThrow().containsMatchIn(text)
                },
            )
        } else {
            CompileResult.Invalid("invalid regular expression '${selector.pattern}': ${compiled.exceptionOrNull()?.message}")
        }
    }
}

/**
 * Compiles a [LineSelector] into an accepting predicate.
 *
 * Rejects an EMPTY selector list rather than guessing what it meant. Under
 * substring semantics an empty literal matches EVERY line, so silently reading
 * an empty `--grep` as "match all" would hide a user error behind a plausible
 * result — and reading it as "match none" would silently empty the output. Both
 * readings are wrong, so the input is refused.
 */
fun compileLineSelector(selector: LineSelector): CompileResult<CompiledLineSelector> = when (selector) {
    LineSelector.All -> CompileResult.Ok(acceptAll)

    is LineSelector.Only -> compileGroup(selector.selectors) { matchers ->
        CompiledLineSelector { text -> matchers.any { it.matches(text) } }
    }

    is LineSelector.Except -> compileGroup(selector.selectors) { matchers ->
        CompiledLineSelector { text -> matchers.none { it.matches(text) } }
    }
}

private val acceptAll = CompiledLineSelector { true }

private inline fun compileGroup(
    selectors: List<TextSelector>,
    build: (List<CompiledTextSelector>) -> CompiledLineSelector,
): CompileResult<CompiledLineSelector> {
    if (selectors.isEmpty()) {
        return CompileResult.Invalid("empty selector list: refusing to guess between 'match all' and 'match none'")
    }
    val compiled = ArrayList<CompiledTextSelector>(selectors.size)
    for (selector in selectors) {
        when (val result = compileTextSelector(selector)) {
            is CompileResult.Invalid -> return result
            is CompileResult.Ok -> compiled += result.value
        }
    }
    return CompileResult.Ok(build(compiled))
}