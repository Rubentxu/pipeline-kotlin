package dev.rubentxu.pipeline.v2.application

/**
 * PK-1 / ADR-PK1-IDENTITY — exhaustive mapping from [CliError] to [CliExit].
 *
 * Every [CliError] case is mapped to exactly one [CliExit] case. The
 * mapping is total: a missing branch is a compile error. New [CliError]
 * cases added without a corresponding branch here will fail to compile,
 * forcing the author to decide the exit taxonomy.
 */
fun CliError.toCliExit(): CliExit = when (this) {
    CliError.MissingCommand -> CliExit.InvalidArgument(
        message = "no command provided; expected one of: ${CLI_VERBS.joinToString(", ") { it.first }}",
        hint = "run 'pipelinek --help'",
    )
    is CliError.InvalidCommand -> CliExit.InvalidArgument(
        message = "unknown command: $value",
        hint = "expected one of: ${CLI_VERBS.joinToString(", ") { it.first }}; run 'pipelinek --help'",
    )
    is CliError.MissingOptionValue -> CliExit.InvalidArgument(
        message = "option $option requires a value",
    )
    CliError.ConflictingDurablePolicies -> CliExit.InvalidArgument(
        message = "--resume and --rerun are mutually exclusive",
    )
    is CliError.ConflictingWorkspaceModes -> CliExit.InvalidArgument(
        message = "conflicting workspace modes: $workspace",
    )
    is CliError.InvalidSandboxProfile -> CliExit.InvalidArgument(
        message = "invalid sandbox profile: $value",
    )
    is CliError.UnsupportedSandboxProfile -> CliExit.RuntimeNotAvailable(
        message = "sandbox profile not supported in this build: $value",
        hint = "rebuild the runtime with the requested profile enabled",
    )
    is CliError.UnknownOption -> CliExit.InvalidArgument(
        message = "unknown option: $value",
    )
    CliError.MissingScriptPath -> CliExit.InvalidArgument(
        message = "missing script path",
    )
    is CliError.TrailingOption -> CliExit.InvalidArgument(
        message = "trailing option without value: $value",
    )
    is CliError.InvalidView -> CliExit.InvalidArgument(message = "invalid view: $value")
    is CliError.UnavailableView -> CliExit.RuntimeNotAvailable(
        message = "view not available: $view",
        hint = "this view is not compiled into the current runtime",
    )
    is CliError.InvalidFormat -> CliExit.InvalidArgument(message = "invalid format: $value")
    is CliError.EmptyTextFilter -> CliExit.InvalidArgument(
        message = "empty text filter for $option",
    )
    CliError.InvertWithoutGrep -> CliExit.InvalidArgument(
        message = "--grep-invert requires --grep",
    )
    is CliError.InvalidChannel -> CliExit.InvalidArgument(message = "invalid channel: $value")
    is CliError.OptionNotReadable -> CliExit.ReadFailure(
        message = "option $option is not readable",
    )
    is CliError.InvalidQuery -> CliExit.InvalidArgument(message = "invalid query: $reason")
    is CliError.FollowNeedsAnIncrementalFormat -> CliExit.InvalidArgument(
        message = "--follow requires an incremental format; got $format",
    )
    is CliError.OptionBelongsToObserve -> CliExit.InvalidArgument(
        message = "option $option belongs to 'observe' subcommand",
    )
    is CliError.TailNeedsTheOutputLane -> CliExit.InvalidArgument(
        message = "--tail requires the 'output' lane; got view '$view'",
    )
    is CliError.InvalidTailBytes -> CliExit.InvalidArgument(
        message = "invalid --tail-bytes value: $value",
    )
    is CliError.InvalidOutcome -> CliExit.InvalidArgument(
        message = "invalid --outcome filter: $value",
    )
    is CliError.InvalidLimit -> CliExit.InvalidArgument(
        message = "invalid --limit value: $value",
    )
    is CliError.OptionValueIsAnOption -> CliExit.InvalidArgument(
        message = "value for option $option looks like another option: $value",
        hint = "if you meant to pass '$value' as a value, prefix with '=' or quote it",
    )
}
