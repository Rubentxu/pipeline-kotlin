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

/**
 * Typed compile outcome — a bad pattern is a value, not an exception at the CLI.
 *
 * ## Why it is NOT called `CompileResult`
 *
 * `dev.rubentxu.pipeline.v2.domain.CompileResult` is a canonical M2 compiler symbol, and
 * `FArchM2CanonicalPipelineCompilerTest` resolves symbols by name across modules: a second
 * `CompileResult` in `pipeline-application` maps the same name to two files and fails the
 * architecture gate. That is not a cosmetic allowlist complaint — two same-named types with
 * different meanings, one in the domain and one at the presentation edge, is exactly the shape
 * that lets a reader believe the observation layer compiles through the domain's algebra when it
 * does not.
 *
 * The name therefore carries its scope: this one compiles SELECTORS, and the allowlist that caught
 * the collision is the evidence that the constraint is real rather than theoretical.
 */
sealed interface SelectorCompileResult<out T> {
    data class Ok<T>(val value: T) : SelectorCompileResult<T>
    data class Invalid(val reason: String) : SelectorCompileResult<Nothing>
}

/**
 * Compiles one selector. Pure in the sense that it reads no ambient state; it
 * allocates a compiled matcher, which is why it is the boundary and not the type.
 */
fun compileTextSelector(selector: TextSelector): SelectorCompileResult<CompiledTextSelector> = when (selector) {
    is TextSelector.Literal -> SelectorCompileResult.Ok(
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
            SelectorCompileResult.Ok(
                object : CompiledTextSelector {
                    override fun matches(text: String): Boolean = compiled.getOrThrow().containsMatchIn(text)
                },
            )
        } else {
            SelectorCompileResult.Invalid("invalid regular expression '${selector.pattern}': ${compiled.exceptionOrNull()?.message}")
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
fun compileLineSelector(selector: LineSelector): SelectorCompileResult<CompiledLineSelector> = when (selector) {
    LineSelector.All -> SelectorCompileResult.Ok(acceptAll)

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
): SelectorCompileResult<CompiledLineSelector> {
    if (selectors.isEmpty()) {
        return SelectorCompileResult.Invalid("empty selector list: refusing to guess between 'match all' and 'match none'")
    }
    val compiled = ArrayList<CompiledTextSelector>(selectors.size)
    for (selector in selectors) {
        when (val result = compileTextSelector(selector)) {
            is SelectorCompileResult.Invalid -> return result
            is SelectorCompileResult.Ok -> compiled += result.value
        }
    }
    return SelectorCompileResult.Ok(build(compiled))
}