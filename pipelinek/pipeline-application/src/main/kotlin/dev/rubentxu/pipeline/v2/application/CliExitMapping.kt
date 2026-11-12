package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import dev.rubentxu.pipeline.v2.application.observation.ObservedOutcome
import dev.rubentxu.pipeline.v2.domain.isolation.DenialReason
import dev.rubentxu.pipeline.v2.domain.isolation.IsolationCapability
import dev.rubentxu.pipeline.v2.output.OutputChannel
import java.io.PrintStream

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
        message = "unknown command: ${renderValue(value)}",
        hint = "expected one of: ${CLI_VERBS.joinToString(", ") { it.label }}; run 'pipelinek --help'",
    )
    is CliError.MissingOptionValue -> CliExit.InvalidArgument("option $option requires a value")
    CliError.ConflictingDurablePolicies -> CliExit.InvalidArgument("--resume and --rerun are mutually exclusive")
    is CliError.ConflictingWorkspaceModes -> CliExit.InvalidArgument(
        message = "conflicting workspace modes: ${renderValue(workspace)}",
        hint = "--isolated requests a PipelineK-managed scratch workspace while --workspace attaches a " +
            "user directory; choose one",
    )
    is CliError.InvalidSandboxProfile -> CliExit.InvalidArgument("invalid sandbox profile: ${renderValue(value)}")
    is CliError.UnsupportedSandboxProfile -> CliExit.RuntimeNotAvailable(
        message = "sandbox profile not supported in this build: ${renderValue(value)}",
        hint = "use 'none', 'local' or 'confined' instead, or rebuild the runtime with the requested " +
            "profile enabled",
    )
    is CliError.IsolationRequestRefused -> isolationRefusalExit(reason, required)
    is CliError.UnknownOption -> CliExit.InvalidArgument("unknown option: ${renderValue(value)}")
    CliError.MissingScriptPath -> CliExit.InvalidArgument("missing script path")
    is CliError.TrailingOption -> CliExit.InvalidArgument(
        message = "trailing option without value: ${renderValue(value)}",
        hint = "options must appear before the script path, not after it",
    )
    is CliError.InvalidView -> CliExit.InvalidArgument(
        message = "invalid view: ${renderValue(value)}",
        hint = "known views: ${ObservationView.entries.joinToString(", ") { it.name.lowercase() }}",
    )
    is CliError.UnavailableView -> CliExit.RuntimeNotAvailable(
        message = "view not available: $view",
        hint = "this view is not compiled into the current runtime; the process transcript belongs to " +
            "'pipeline console'",
    )
    is CliError.InvalidFormat -> CliExit.InvalidArgument(
        message = "invalid format: ${renderValue(value)}",
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
        message = "invalid channel: ${renderValue(value)}",
        hint = "known channels: ${OutputChannel.entries.joinToString(", ") { it.token }}",
    )
    is CliError.OptionNotReadable -> CliExit.InvalidArgument(
        message = "option $option is not readable",
        hint = "'$option' changes what a run DOES, and this command only reads; dropping it silently " +
            "would mean acting on something the caller did not ask for",
    )
    is CliError.InvalidQuery -> CliExit.InvalidArgument(message = "invalid query: $reason")
    is CliError.FollowNeedsAnIncrementalFormat -> CliExit.InvalidArgument(
        message = "--follow requires an incremental format; got ${renderValue(format)}",
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
        message = "invalid --tail-bytes value: ${renderValue(value)}",
        hint = "omit it to read the whole plane",
    )
    is CliError.InvalidOutcome -> CliExit.InvalidArgument(
        message = "invalid --outcome filter: ${renderValue(value)}",
        hint = "known outcomes: ${ObservedOutcome.tokens.joinToString(", ")}",
    )
    is CliError.InvalidLimit -> CliExit.InvalidArgument(
        message = "invalid --limit value: ${renderValue(value)}",
        hint = "omit it to show everything",
    )
    is CliError.OptionValueIsAnOption -> CliExit.InvalidArgument(
        message = "value for option $option looks like another option: ${renderValue(value)}",
        hint = "this build refuses a value spelled like one of its own options; quoting it or writing " +
            "'$option=$value' does not change that, so pass a different value",
    )
    is CliError.UnexpectedPositional -> CliExit.InvalidArgument(
        message = "unexpected argument: ${renderValue(value)}",
        hint = "this command takes no positional argument",
    )
    is CliError.TooManyPositionals -> CliExit.InvalidArgument(
        message = "unexpected argument: ${renderValue(value)}",
        hint = "this command takes one positional argument",
    )
    is CliError.MissingRequiredArgument -> CliExit.InvalidArgument("missing required argument: $argument", why)
    is CliError.InvalidOptionValue -> CliExit.InvalidArgument("invalid value for $option: $reason")
    is CliError.InvalidCombination -> CliExit.InvalidArgument("invalid combination: $reason")
    is CliError.OptionBelongsToVerb -> CliExit.InvalidArgument("option $option belongs to '$owner': $why")
    is CliError.UnknownSubcommand -> unknownSubcommandExit(value, expected)
    is CliError.UnknownStep -> unknownStepExit(this)
    is CliError.RunIdCannotNameADirectory -> CliExit.InvalidArgument(
        message = "run id '$runId' cannot name a durable directory",
        hint = "a run id may contain only letters, digits, '.', '_' and '-'",
    )
    is CliError.SubstrateRefusal -> substrateExit(this)
    is CliError.ServiceUnavailable -> serviceUnavailableExit(reason, detail)
    is IdeProjectRefusal -> ideProjectExit(this)
}

/**
 * The exit a refusal about the substrate becomes.
 *
 * ## Why the two refusals share a door
 *
 * Because they are the two moments of ONE fact: the substrate is not there to
 * be served, or it could not be prepared. Both of them are about the same
 * file, and both of them send the reader to the same step —the one that
 * prepares it— while the words say WHICH of the two happened.
 *
 * ## Why the hint of the first one says that the service does not create it
 *
 * Because that is the promise the service makes, and a reader who reads it
 * learns the shape of the boundary: the file is made by a step somebody says
 * so, and never by the act of serving it.
 */
private fun substrateExit(refusal: CliError.SubstrateRefusal): CliExit = when (refusal) {
    is CliError.SubstrateUnavailable -> CliExit.RuntimeNotAvailable(
        message = "the substrate cannot be served: ${refusal.reason}",
        hint = "the service reads the substrate it is given; it does not create one",
    )

    is CliError.SubstrateNotPrepared -> CliExit.RuntimeNotAvailable(
        message = "the substrate cannot be prepared: ${refusal.reason}",
        hint = "the substrate is prepared by 'pipelinek substrate init --db <path>', " +
            "and by nothing else; the service reads the one it is given",
    )
}

/**
 * The exit a refusal of `ide init` becomes.
 *
 * A directory that holds other files, or a directory or plugin jar whose path Gradle cannot read,
 * is the operator's to sort out, so it is an invalid argument; an installation that is not there or that Gradle cannot reach,
 * or a file system that refuses the write, is a fact about the host.
 */
private fun ideProjectExit(refusal: IdeProjectRefusal): CliExit = when (refusal) {
    is IdeProjectRefusal.NotAnInstallation -> CliExit.RuntimeNotAvailable(
        message = "no installation to compile against: ${refusal.reason}",
        hint = "run 'ide init' with the bin/pipelinek launcher of an installed distribution",
    )
    is IdeProjectRefusal.Different -> CliExit.InvalidArgument(
        message = "the directory already holds other contents at: ${refusal.paths.joinToString(", ")}",
        hint = "'ide init' never replaces a file; move them aside, or name another directory with --dir",
    )
    is IdeProjectRefusal.Unwritable -> CliExit.RuntimeNotAvailable(
        message = "the project cannot be written at ${refusal.path}: ${refusal.detail}",
        hint = "'ide init' keeps nothing it wrote when it refuses; name a directory this user can write with --dir",
    )
    is IdeProjectRefusal.DirectoryOutOfGradleReach -> CliExit.InvalidArgument(
        message = "Gradle cannot reach ${refusal.directory}: it reads the backslash in the path as a separator",
        hint = "name a directory without a backslash in its path with --dir",
    )
    is IdeProjectRefusal.InstallationOutOfGradleReach -> CliExit.RuntimeNotAvailable(
        message = "Gradle cannot reach the installation at ${refusal.lib}: " +
            "it reads the backslash in the path as a separator",
        hint = "install the distribution under a path without a backslash",
    )
    is IdeProjectRefusal.PluginJarOutOfGradleReach -> CliExit.InvalidArgument(
        message = "Gradle cannot reach the plugin jar at ${refusal.jar}: " +
            "it reads the backslash in the path as a separator",
        hint = "name a plugin jar without a backslash in its path with --plugin-jar",
    )
}

/**
 * The exit a refusal of the startup becomes.
 *
 * The REASON is a word, because every way this fails has a different remedy,
 * and the detail is the hint: it says what the machine answered.
 */
private fun serviceUnavailableExit(reason: String, detail: String): CliExit = CliExit.RuntimeNotAvailable(
    message = "the service cannot start: $reason",
    hint = detail,
)

/**
 * The exit a refusal from the admission core becomes.
 *
 * It is the runtime-not-available case: the request asked for a boundary this
 * build cannot host, and that is a fact about the host, not about the word the
 * operator typed. The hint names the two postures this build DOES serve, so the
 * operator has something to act on.
 */
private fun isolationRefusalExit(
    reason: DenialReason,
    required: List<IsolationCapability>,
): CliExit = CliExit.RuntimeNotAvailable(
    message = isolationRefusalMessage(reason, required),
    hint = isolationRefusalHint(reason),
)

/**
 * The exit a refusal about a subcommand the command does not have becomes.
 *
 * The word the operator wrote is named, and the words the command DOES take are
 * named after it: a refusal that names the mistake without naming the way out
 * leaves the operator to guess.
 */
private fun unknownSubcommandExit(value: String, expected: List<String>): CliExit =
    CliExit.InvalidArgument(
        message = "unknown subcommand: ${renderValue(value)}",
        hint = "expected one of: ${expected.joinToString(", ") { "'$it'" }}",
    )

/**
 * The exit a refusal about the name of a step becomes.
 *
 * A name that names NOTHING and a name that names MORE THAN ONE are two
 * facts, and they get two sentences: the first says the name is unknown and
 * offers the entries that come close, the second says the name is real and
 * names the entries it points at. Answering the second with the first would
 * send the operator looking for a typo they did not make.
 */
private fun unknownStepExit(error: CliError.UnknownStep): CliExit = if (error.ambiguous) {
    CliExit.InvalidArgument(
        message = "the name ${renderValue(error.name)} names more than one entry: " +
            error.keys.joinToString(", "),
        hint = "name the entry by its key",
    )
} else {
    CliExit.InvalidArgument(
        message = "no step named ${renderValue(error.name)}",
        hint = if (error.keys.isEmpty()) {
            "run 'pipelinek steps' to see every step this build supports"
        } else {
            "near: ${error.keys.joinToString(", ")}"
        },
    )
}

/**
 * The hint a refusal carries.
 *
 * The hint depends on WHY the request was refused, because the reasons call for
 * different actions. A host that measured the boundary and could not prove all
 * of it can serve a SMALLER request; a request that asks for the untrusted
 * posture cannot be served by any word, because what it lacks is a provider
 * that proves every capability at once.
 */
private fun isolationRefusalHint(reason: DenialReason): String = when (reason) {
    is DenialReason.MissingAttestation ->
        "the request asks for more than this host can prove; 'none' and 'local' are the " +
            "postures this build has always served"
    is DenialReason.UnsupportedCapability ->
        "ask for less: this host proves a narrower boundary than the one requested"
    is DenialReason.PolicyConflict ->
        "the request asks for two things that cannot hold at once"
    is DenialReason.UntrustedPlugin ->
        "the artifacts the run would load need a trust decision"
}

/**
 * The sentence a refusal from the admission core becomes.
 *
 * ## Why the required capabilities are named
 *
 * The refusal used to say which WORD was refused and nothing else, so an
 * operator could not learn what the word asks for. The core knows both halves
 * of the answer: why the host cannot serve the request, and which capabilities
 * the request would have required. A refusal that names only the cause leaves
 * the operator to guess the size of the gap; one that names both answers the
 * question the operator actually asked.
 *
 * ## Why the missing capability is named and not the whole set
 *
 * A host with a provider may prove SOME of what a request demands. Saying
 * "this build proves none of the capabilities the request demands" would be
 * false there, and a false sentence about a boundary is worse than no sentence:
 * the operator would conclude that the host confines nothing when it confines
 * most of it. So the refusal names the capability that is missing, and the
 * demands clause names the whole set the request asked for.
 *
 * ## Why an empty set says nothing about capabilities
 *
 * The demanded set is empty only when the request demands nothing beyond the
 * legacy behaviour, and such a request is not refused for its capabilities: it
 * is refused for a conflict of the request itself or for an artifact. Naming
 * capabilities there would name a demand that was never made.
 *
 * ## Why the missing network boundary says what it costs
 *
 * A capability name is a name, and the operator has to know what running
 * without it means. The cost of running without the network confinement is
 * concrete: the run's child process keeps the HOST's network, because the
 * egress gate that denies the in-process plugins does not reach a process.
 * A refusal that named the capability and stopped there would leave the
 * operator to guess, and the guess is the one this product refuses to make
 * for them elsewhere.
 */
private fun isolationRefusalMessage(reason: DenialReason, required: List<IsolationCapability>): String {
    val cause = when (reason) {
        is DenialReason.MissingAttestation -> reason.detail
        is DenialReason.UnsupportedCapability ->
            "this host does not prove ${reason.missing.joinToString(", ")}"
        is DenialReason.PolicyConflict -> reason.detail
        is DenialReason.UntrustedPlugin ->
            "the artifacts the run would load have no attested provenance: ${reason.artifacts.joinToString(", ")}"
    }
    val demands = if (required.isEmpty()) {
        ""
    } else {
        "; the request would require a provider that proves ${required.joinToString(", ")}"
    }
    val network = if (networkConfinementIsMissing(reason)) {
        "; without it the run's child process keeps the host's network"
    } else {
        ""
    }
    return "$cause$demands$network"
}

/**
 * Whether the refusal leaves the run's child process with the host's network.
 *
 * The network confinement is missing when the host could not prove it, and when
 * the host proved nothing at all. A refusal for another reason leaves the
 * network alone: a conflict of the request and an unattested artifact say
 * nothing about what this host can confine.
 */
private fun networkConfinementIsMissing(reason: DenialReason): Boolean = when (reason) {
    is DenialReason.UnsupportedCapability ->
        IsolationCapability.NetworkConfinement in reason.missing
    is DenialReason.MissingAttestation -> true
    is DenialReason.PolicyConflict, is DenialReason.UntrustedPlugin -> false
}

/**
 * The lines a verb prints when it refuses a request it did not understand.
 *
 * The message and the hint are the CLI's own words: they come from [toCliExit],
 * the one mapping from an error to a sentence. A verb that printed the error
 * itself would print the sealed type's constructor syntax instead, which is
 * how `observe` came to answer `UnknownOption(value=--bogus-flag)`.
 *
 * The words are rendered by [refusalLines] on the CASE, so a refusal that
 * starts from an error and a refusal that starts from a case say the same
 * thing in the same words: one renderer, two doors into it.
 */
internal fun CliError.refusalLines(verb: String): List<String> =
    toCliExit().refusalLines(verb)

/**
 * The lines a refusal prints, rendered from the case that owns it.
 *
 * ## Why the case owns the rendering
 *
 * The case carries the message and the hint, so the case is what can render
 * them. An error that rendered its own words would need a second copy of
 * every sentence, and the two copies would be free to disagree.
 */
internal fun CliExit.refusalLines(verb: String): List<String> =
    listOfNotNull("$verb: $message", hint)

/**
 * The case that carries [code] to the process.
 *
 * ## Why the number is mapped and not kept
 *
 * Ten of the read-only verbs are CLIs of their own, and their entry point
 * answers with a number. Changing that contract would reach every call site
 * of every one of them for no gain: the number is the fact, and the taxonomy
 * owns what a number means.
 *
 * The case is the carrier. Its WORDS are not this function's to write — a verb
 * that refused a request printed its own refusal already — so the case
 * travels from here to the exit code and no further.
 *
 * ## Why a number this taxonomy does not declare is refused
 *
 * Measured over the twelve read-only verbs, every number the product answers
 * with is inside the taxonomy, so this refusal is unreachable today. It is
 * here anyway: a code nobody declared is a defect, and failing closed with a
 * typed refusal is what a defect deserves. Quietly keeping the number would
 * let an undeclared code reach the operator as if the taxonomy had meant it.
 */
internal fun verbExit(code: Int): CliExit =
    CliExit.of(code) ?: CliExit.RuntimeNotAvailable(
        message = "the verb answered with exit code $code, which this taxonomy does not declare",
    )

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
internal fun refuse(error: CliError, verb: String, err: PrintStream): Int {
    error.refusalLines(verb).forEach { err.println(it) }
    return error.toCliExit().code
}
