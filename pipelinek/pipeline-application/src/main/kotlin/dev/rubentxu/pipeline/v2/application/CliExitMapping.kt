package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import dev.rubentxu.pipeline.v2.application.observation.ObservedOutcome
import dev.rubentxu.pipeline.v2.output.OutputChannel

/**
 * Exhaustive mapping from [CliError] to [CliExit].
 * Every [CliError] case is mapped to exactly one [CliExit] case. The
 * mapping is total: a missing branch is a compile error. New [CliError]
 * cases added without a corresponding branch here will fail to compile,
 * forcing the author to decide the exit taxonomy.
 *
 * ## This is the ONE place a CliError becomes words
 *
 * A [CliError] is a VALUE: it says what went wrong, and it says it in no
 * particular words. The sentence the operator reads is written here, once,
 * and every verb that refuses a request goes through here.
 *
 * That is why the sealed type carries no `toString()` of its own. A second
 * rendering is free to disagree with this one, and it did: `observe` printed
 * `UnknownOption(value=--bogus-flag)` and `InvalidView(bogus): expected one
 * of …` while `run` printed `invalid view: bogus` for the same family of
 * mistake. One error, two sentences, and the one that looked like a stack
 * trace was the one an operator read.
 *
 * ## The shape of a sentence
 *
 * The `message` says WHAT was wrong. The `hint` says WHAT TO DO about it,
 * and it is where the vocabulary of THIS build lives: the views, the formats,
 * the channels and the outcomes that are actually accepted. A refusal that
 * names only the offending token leaves the operator to work out what the
 * valid ones are, which is the work the refusal was supposed to save.
 *
 * A hint never promises a form the CLI does not accept. The refusal for a
 * value that is spelled like an option used to say "prefix with '=' or quote
 * it", and neither form works here: `--control-root=/tmp/x` is an unknown
 * option, and quoting `--format` delivers the same token. The hint now says
 * what is actually true.
 */
fun CliError.toCliExit(): CliExit = when (this) {
    CliError.MissingCommand -> CliExit.InvalidArgument(
        message = "no command provided; expected one of: ${CLI_VERBS.joinToString(", ") { it.label }}",
        hint = "run 'pipelinek --help'",
    )
    is CliError.InvalidCommand -> CliExit.InvalidArgument(
        message = "unknown command: $value",
        hint = "expected one of: ${CLI_VERBS.joinToString(", ") { it.label }}; run 'pipelinek --help'",
    )
    is CliError.MissingOptionValue -> CliExit.InvalidArgument(
        message = "option $option requires a value",
    )
    CliError.ConflictingDurablePolicies -> CliExit.InvalidArgument(
        message = "--resume and --rerun are mutually exclusive",
    )
    is CliError.ConflictingWorkspaceModes -> CliExit.InvalidArgument(
        message = "conflicting workspace modes: $workspace",
        hint = "--isolated requests a PipelineK-managed scratch workspace while --workspace attaches a " +
            "user directory; choose one",
    )
    is CliError.InvalidSandboxProfile -> CliExit.InvalidArgument(
        message = "invalid sandbox profile: $value",
    )
    is CliError.UnsupportedSandboxProfile -> CliExit.RuntimeNotAvailable(
        message = "sandbox profile not supported in this build: $value",
        hint = "use 'none' or 'local' instead, or rebuild the runtime with the requested profile enabled",
    )
    is CliError.UnknownOption -> CliExit.InvalidArgument(
        message = "unknown option: $value",
    )
    CliError.MissingScriptPath -> CliExit.InvalidArgument(
        message = "missing script path",
    )
    is CliError.TrailingOption -> CliExit.InvalidArgument(
        message = "trailing option without value: $value",
        hint = "options must appear before the script path, not after it",
    )
    is CliError.InvalidView -> CliExit.InvalidArgument(
        message = "invalid view: $value",
        hint = "known views: ${ObservationView.entries.joinToString(", ") { it.name.lowercase() }}",
    )
    is CliError.UnavailableView -> CliExit.RuntimeNotAvailable(
        message = "view not available: $view",
        hint = "this view is not compiled into the current runtime; the process transcript belongs to " +
            "'pipeline console'",
    )
    is CliError.InvalidFormat -> CliExit.InvalidArgument(
        message = "invalid format: $value",
        hint = "known formats: ${ObservationFormat.entries.joinToString(", ") { it.wire }}",
    )
    is CliError.EmptyTextFilter -> CliExit.InvalidArgument(
        message = "empty text filter for $option",
        hint = "an empty pattern matches every line under substring semantics; refusing it rather " +
            "than returning everything",
    )
    CliError.InvertWithoutGrep -> CliExit.InvalidArgument(
        message = "--grep-invert requires --grep",
        hint = "--grep-invert needs at least one --grep/--grep-regex to negate",
    )
    is CliError.InvalidChannel -> CliExit.InvalidArgument(
        message = "invalid channel: $value",
        hint = "known channels: ${OutputChannel.entries.joinToString(", ") { it.token }}",
    )
    is CliError.OptionNotReadable -> CliExit.InvalidArgument(
        message = "option $option is not readable",
        hint = "'$option' changes what a run DOES, and this command only reads; dropping it silently " +
            "would mean acting on something the caller did not ask for",
    )
    is CliError.InvalidQuery -> CliExit.InvalidArgument(message = "invalid query: $reason")
    is CliError.FollowNeedsAnIncrementalFormat -> CliExit.InvalidArgument(
        message = "--follow requires an incremental format; got $format",
        hint = "use --format jsonl, where every line stands alone and needs no closing bracket",
    )
    is CliError.OptionBelongsToObserve -> CliExit.InvalidArgument(
        message = "option $option belongs to 'observe' subcommand",
        hint = "read the run after it finishes instead: 'pipelinek observe <runId> $option'",
    )
    is CliError.TailNeedsTheOutputLane -> CliExit.InvalidArgument(
        message = "--tail requires the 'output' lane; got view '$view'",
        hint = "use --view console",
    )
    is CliError.InvalidTailBytes -> CliExit.InvalidArgument(
        message = "invalid --tail-bytes value: $value",
        hint = "omit it to read the whole plane",
    )
    is CliError.InvalidOutcome -> CliExit.InvalidArgument(
        message = "invalid --outcome filter: $value",
        hint = "known outcomes: ${ObservedOutcome.tokens.joinToString(", ")}",
    )
    is CliError.InvalidLimit -> CliExit.InvalidArgument(
        message = "invalid --limit value: $value",
        hint = "omit it to show everything",
    )
    is CliError.OptionValueIsAnOption -> CliExit.InvalidArgument(
        message = "value for option $option looks like another option: $value",
        hint = "this build refuses a value spelled like one of its own options; quoting it or writing " +
            "'$option=$value' does not change that, so pass a different value",
    )
    is CliError.UnexpectedPositional -> CliExit.InvalidArgument(
        message = "unexpected argument: $value",
        hint = "this command takes no positional argument",
    )
    is CliError.TooManyPositionals -> CliExit.InvalidArgument(
        message = "unexpected argument: $value",
        hint = "this command takes one positional argument",
    )
    is CliError.MissingRequiredArgument -> CliExit.InvalidArgument("missing required argument: $argument", why)
    is CliError.InvalidOptionValue -> CliExit.InvalidArgument("invalid value for $option: $reason")
    is CliError.InvalidCombination -> CliExit.InvalidArgument("invalid combination: $reason")
    is CliError.OptionBelongsToVerb -> CliExit.InvalidArgument("option $option belongs to '$owner': $why")
    is CliError.UnknownSubcommand -> CliExit.InvalidArgument("unknown subcommand: $value", "expected one of: ${expected.joinToString(", ") { "'$it'" }}")
}

/**
 * The lines a verb prints when it refuses a request it did not understand.
 *
 * The message and the hint are the CLI's own words: they come from [toCliExit],
 * the one mapping from an error to a sentence. A verb that printed the error
 * itself would print the sealed type's constructor syntax instead, which is
 * how `observe` came to answer `UnknownOption(value=--bogus-flag)`.
 */
internal fun CliError.refusalLines(verb: String): List<String> {
    val exit = toCliExit()
    return listOfNotNull("$verb: ${exit.message}", exit.hint)
}

/**
 * The refusal of a verb, on stderr, and the code the taxonomy declares for it.
 *
 * ## Why the code comes from here and not from the verb
 *
 * A verb that answers `2` itself has answered a question the taxonomy has
 * already answered, and the two answers can drift apart: the day
 * [CliExit.InvalidArgument] moves, the verb that wrote the literal keeps the
 * old number and a consumer that branches on it reads a refusal as something
 * else. Measured on the built distribution, four verbs wrote the literal while
 * the rest of the CLI read it from the table, and the number happened to be
 * right — which is why the drift is invisible until it is not.
 *
 * The words come from [refusalLines], so a verb that refuses a request cannot
 * invent its own sentence either.
 */
internal fun refuse(error: CliError, verb: String): Int {
    error.refusalLines(verb).forEach { System.err.println(it) }
    return error.toCliExit().code
}
