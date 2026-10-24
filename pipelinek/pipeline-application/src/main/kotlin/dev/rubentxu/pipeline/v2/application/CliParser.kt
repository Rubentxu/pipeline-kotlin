package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.CompiledObservationQuery
import dev.rubentxu.pipeline.v2.application.observation.FormatParseResult
import dev.rubentxu.pipeline.v2.application.observation.SelectorCompileResult
import dev.rubentxu.pipeline.v2.application.observation.LineSelector
import dev.rubentxu.pipeline.v2.application.observation.compileQuery
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.observation.ObservationQuery
import dev.rubentxu.pipeline.v2.application.observation.ObservedOutcome
import dev.rubentxu.pipeline.v2.application.observation.RecordBudget
import dev.rubentxu.pipeline.v2.application.observation.RunObservationOutput
import dev.rubentxu.pipeline.v2.application.observation.TextSelector
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import dev.rubentxu.pipeline.v2.application.observation.ViewParseResult
import dev.rubentxu.pipeline.v2.application.observation.resolveView
import dev.rubentxu.pipeline.v2.domain.CorrelationId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import dev.rubentxu.pipeline.v2.events.identity.EventField

// Parser unification. The four verb mains used to own their own admission
// parsers; their bodies lived next to the verb and drifted the moment a flag gained
// a case. `parseConsole`, `parseEvents`, `parseInspect` are now the single entry
// point for argv-to-typed-request conversion; the verb mains translate the typed
// result into the wire format they have always used. Refusal prefixes
// (`console-refused:`, `evt-refusal-v1:`, `Error:`) live in the renderer / the
// verb main, NOT in the parser — preserving them is a property of the call site.

const val NON_CANONICAL_CANONICAL_BRIDGE_ERROR: String =
    "Error: script uses non-canonical plugins; canonical bridge requires " +
        "core.sh/core.echo/core.sleep/core.file.writeFile/core.emit.event/core.milestone/" +
        "core.deleteDir/core.cleanWs/core.load/core.pwd/core.waitUntil."

/** The finite set of commands accepted by the application CLI. */
enum class CliCommand {
    VALIDATE,
    RUN,
}

/** Typed CLI flags produced by [CliParser]. */
data class CliFlags(
    val command: CliCommand,
    val dbPath: String?,
    val durableRunPolicy: DurableRunPolicy,
    val scriptPath: String,
    val controlRoot: String? = null,
    /** Project workspace base directory (--workspace). */
    val workspace: String? = null,
    /**
     * Request the historical PipelineK-managed scratch
     * workspace instead of the default attached invocation directory.
     * Mutually exclusive with [workspace]; the parser rejects the combination
     * rather than applying a silent precedence rule.
*/
    val isolated: Boolean = false,
    val sandboxProfile: SandboxProfile = SandboxProfile.NONE,
    /**
     * Whether this run may open outbound
     * connections. FALSE unless the operator asks for it.
     * Default-deny is the whole point, and it is enforced as a MISSING
     * capability rather than as a check inside a Step: a Step that declares
     * `NETWORK_EGRESS_CAPABILITY` is rejected at prepare-time when the runtime
     * did not produce the verdict, so there is no code path in which a Step
     * reaches the network on a default run.
*/
    val allowNetwork: Boolean = false,
    /** External plugin JARs: one list feeds compilation and runtime discovery. */
    val pluginJars: List<String> = emptyList(),
    /**
     * The operator's plugin trust file, when they asked for a trust policy.
     *
     * Naming this file turns the trust policy ON: every artifact the operator brings with
     * `--plugin-jar` has to be the one this file pins, signed by the publisher this file names.
     * Leaving it unset keeps the previous behaviour — plugins are admitted on their manifest and
     * their API range — because a policy an operator cannot decline is not a policy.
     */
    val pluginTrust: String? = null,
    /**
     * External invoker correlation token. When
     * supplied, the runtime binds it to the [RunId] in durable state
     * and emits it on the [dev.rubentxu.pipeline.v2.events.RunCorrelated]
     * event. The raw string is held here; conversion to [CorrelationId]
     * happens after parsing succeeds.
*/
    val correlationId: String? = null,
    /**
     * Which families of observation reach the reader. Orthogonal to
     * [format]; neither changes execution.
*/
    val view: ObservationView = ObservationView.NORMAL,
    /**
     * Wire encoding. `TEXT` is human and the default; JSON and JSONL
     * are opt-in, so a machine consumer must ask for them explicitly instead of
     * discovering them by accident.
*/
    val format: ObservationFormat = ObservationFormat.TEXT,
    /**
     * Opt-in versioned output
     * protocol. The parser holds the raw token(s) the operator
     * supplied (last token wins on repetition). Validation happens
     * in [dev.rubentxu.pipeline.v2.application.Main] which translates
     * an unknown id to a typed CliExit.InvalidArgument. Empty list
     * means "no opt-in", which falls back to [OutputProtocol.DEFAULT]
     * in Main.
*/
    val outputProtocolTokens: List<String> = emptyList(),
    /**
     * Normalized read-side selection. Identity by default
     * ## The KDoc that used to say `channel` could not exist
     * This field once carried the line "channel is deliberately absent: no durable carrier exists
     * for stdout vs stderr, so offering the dimension would be a filter that cannot filter."
     * That claim became false at.3, when the merge was removed and two independent pumps
     * began attributing every committed range to its channel, durably. What was left behind was the
     * worse half: [ObservationQuery.channels] had been implemented and consumed all along, so the
     * dimension was REAL and UNREACHABLE — a filter nobody could type. The correction is not a new
     * field here, it is the removal of a sentence that had stopped being true.
     * [ObservationQuery] carries the same history and what it means for a record that has no
     * channel to carry.
*/
    val query: ObservationQuery = ObservationQuery(),
)

/** Typed failures for CLI admission. */
sealed interface CliError {
    data object MissingCommand : CliError
    data class InvalidCommand(val value: String) : CliError
    data class MissingOptionValue(val option: String) : CliError
    data object ConflictingDurablePolicies : CliError

    /**
     * `--isolated` and `--workspace` request two
     * different workspace origins. Applying a silent precedence would let a user
     * believe they attached a workspace while running in scratch, so the
     * combination fails closed at admission.
*/
    data class ConflictingWorkspaceModes(val workspace: String) : CliError {
        override fun toString(): String =
            "ConflictingWorkspaceModes(workspace=$workspace): --isolated requests a " +
                "PipelineK-managed scratch workspace while --workspace attaches a " +
                "user directory; choose one."
    }
    data class InvalidSandboxProfile(val value: String) : CliError
    data class UnsupportedSandboxProfile(val value: String) : CliError {
        // Enrich the typed error message with cross-references so the
        // fail-closed CLI surface cites the contract documents the operator
        // Expects to see at the boundary (sandbox policy, OS-level
        // Isolation, multi-tenant gate). The value token echoes the rejected
        // input verbatim so log scrapers can correlate.
        override fun toString(): String =
            "UnsupportedSandboxProfile(value=$value: " +
                "'os' requires OS-level isolation which is out of scope for L3; " +
                "use 'none' or 'local' instead."
    }
    data class UnknownOption(val value: String) : CliError
    data object MissingScriptPath : CliError

    /**
     * An option appeared AFTER the script path.
     * The scan used to stop at the first non-`--` argument, so
     * `pipeline run s.kts --format text` discarded everything after the script
     * and still ran, producing the default output. A flag the user believed was
     * applied was silently ignored — the fail-open shape the observation work
     * exists to remove. Trailing options are now rejected before any effect.
*/
    data class TrailingOption(val value: String) : CliError {
        override fun toString(): String =
            "TrailingOption($value): options must appear BEFORE the script path. " +
                "Got '$value' after the script, which used to be discarded silently."
    }

    /** `--view` named a value that is not a view. */
    data class InvalidView(val value: String) : CliError {
        override fun toString(): String =
            "InvalidView($value): expected one of " +
                ObservationView.entries.joinToString(", ") { it.name.lowercase() }
    }

    /**
     * `--view` named a real view this build cannot deliver.
     * Refused rather than downgraded: silently substituting another view tells
     * the operator they observed something they did not observe.
*/
    data class UnavailableView(val view: ObservationView) : CliError {
        override fun toString(): String =
            "UnavailableView($view): this build cannot deliver the '$view' view. " +
                "Process transcript belongs to `pipeline console`; `full` interleaving " +
                "has no decided rule yet."
    }

    /** `--format` named a value that is not a format. */
    data class InvalidFormat(val value: String) : CliError {
        override fun toString(): String =
            "InvalidFormat($value): expected one of " +
                ObservationFormat.entries.joinToString(", ") { it.wire }
    }

    /**
     * `--grep` or `--grep-regex` was given an empty value.
     * Refused rather than resolved: under substring semantics an empty literal
     * matches EVERY line, so accepting it would turn a typo into a filter that
     * silently keeps everything.
*/
    data class EmptyTextFilter(val option: String) : CliError {
        override fun toString(): String =
            "EmptyTextFilter($option): an empty pattern matches every line under " +
                "substring semantics; refusing it rather than returning everything."
    }

    /** `--grep-invert` with no `--grep` to invert. There is no group to negate. */
    data object InvertWithoutGrep : CliError {
        override fun toString(): String =
            "InvertWithoutGrep: --grep-invert needs at least one --grep/--grep-regex to negate."
    }

    /**
     * `--channel` named no channel.
     * Refused rather than dropped, for the same reason [EmptyTextFilter] is: a token that matches
     * nothing would silently become a filter that keeps everything, and the next run would look
     * like a data problem instead of a typo. [OutputChannel.fromToken] answers `null` rather than
     * throwing because a durable store may name a channel this build does not know, and that is a
     * fact a reader can act on — but a human typing a flag is not reading a store, and gets a name.
*/
    data class InvalidChannel(val value: String) : CliError {
        override fun toString(): String =
            "InvalidChannel: --channel '$value' is not a channel. Known: " +
                OutputChannel.entries.joinToString(", ") { it.token }
    }

    /**
     * A flag that changes what a run DOES was passed to a verb that only reads.
     * Refused rather than ignored, and the wording matters: `--resume` on a reader has no meaning
     * at all, so the user is not being denied a feature — they are being told that the verb they
     * reached for is not the one that does this.
*/
    data class OptionNotReadable(val option: String) : CliError {
        override fun toString(): String =
            "OptionNotReadable: '$option' changes what a run DOES, and this command only reads. " +
                "It has no meaning here, and dropping it silently would mean acting on something " +
                "the caller did not ask for."
    }

    /**
     * The assembled query is not usable — most often an uncompilable
     * `--grep-regex`. Caught here so no process, store or journal is created
     * for a run that could never be filtered as asked.
*/
    data class InvalidQuery(val reason: String) : CliError {
        override fun toString(): String = "InvalidQuery: $reason"
    }

    /**
     * `--follow` in a format that has no continuation.
     * Refused because the combination has no honest answer, not because it is unusual: a document
     * format is complete only when its closing bracket arrives, and following is by definition
     * stopping before you know whether that has happened.
*/
    data class FollowNeedsAnIncrementalFormat(val format: String) : CliError {
        override fun toString(): String =
            "FollowNeedsAnIncrementalFormat: --follow with --format $format would emit one document " +
                "per round, which is not a continuation of the first. Use --format jsonl, where every " +
                "line stands alone and needs no closing bracket."
    }

    /**
     * A reader's option reached `run`.
     * The inverse of [OptionNotReadable], and refused for a sharper reason than symmetry. A flag that
     * is parsed, remembered and never read is a dead semantic parameter; `--follow` on `run` is
     * exactly that, because `run` already reads its run as it happens. `--limit` is worse than dead:
     * it would truncate the live transcript and `run` has no way to say the transcript was
     * truncated, which is the failure a reader that reports itself complete already causes elsewhere.
*/
    data class OptionBelongsToObserve(val option: String) : CliError {
        override fun toString(): String =
            "OptionBelongsToObserve: '$option' is what a READER does, and 'run' cannot report that it " +
                "showed you only part of what happened. Read the run after it finishes instead: " +
                "'pipelinek observe <runId> $option'."
    }

    /**
     * `--limit` that is not a count.
     * Refused rather than defaulted, and the legacy `events` verb already learned this the hard way:
     * `--limit abc` used to read as 100, so a typo silently became a different query than the one
     * the caller believed they were making.
*/
    /**
     * `--tail-bytes` on a view that carries no process bytes.
     * Refused rather than ignored for the reason `--follow` on `run` is: a flag that changes nothing
     * observable is a parameter the caller believed in. `console` is the only view whose records are
     * bytes, so it is the only one where a byte budget has anything to bound.
*/
    data class TailNeedsTheOutputLane(val view: String) : CliError {
        override fun toString(): String =
            "TailNeedsTheOutputLane: --tail-bytes bounds committed process bytes, and --view $view " +
                "shows none, so it would bound nothing. Use --view console."
    }

    data class InvalidTailBytes(val value: String) : CliError {
        override fun toString(): String =
            "InvalidTailBytes: --tail-bytes needs a positive whole number of bytes, got '$value'. " +
                "Omit it to read the whole plane."
    }

    /**
     * `--outcome` that names no outcome.
     * Refused rather than treated as a wildcard, because the whole point of typing the vocabulary
     * was that `--outcome` stops answering "which spelling did this producer use". A typo that
     * silently matched everything would hand the caller the opposite of what they asked for.
*/
    data class InvalidOutcome(val value: String) : CliError {
        override fun toString(): String =
            "InvalidOutcome: --outcome names how something ended. Known: " +
                ObservedOutcome.tokens.joinToString(", ") + ". Got '$value'."
    }

    data class InvalidLimit(val value: String) : CliError {
        override fun toString(): String =
            "InvalidLimit: --limit needs a positive whole number of records, got '$value'. " +
                "Omit it to show everything."
    }

    /**
     * A valued option was handed a token that is itself an option of this CLI.
     * Refused rather than consumed, and the severity is not uniform. `run --db --resume script.kts`
     * stored the literal `"--resume"` as a database path and left the durable policy at its default,
     * exiting 0: the caller asked for two things and one of them was quietly not done.
     * `--control-root --allow-network script.kts` is the same shape with the egress flag on the
     * losing side — the run stayed network-denied while the caller believed they had opened it.
     * Scoped to tokens this build KNOWS, deliberately. Refusing every value that begins with `--`
     * would make `--grep --verbose-stop` unexpressible, trading one silent drop for a new surprise;
     * a token that names nothing is far more likely to be a pattern than a mistyped flag.
*/
    data class OptionValueIsAnOption(val option: String, val value: String) : CliError {
        override fun toString(): String =
            "OptionValueIsAnOption: '$option' was given '$value', which is another option of this CLI. " +
                "Refusing rather than storing '$value' as the value of '$option', which would have " +
                "dropped a flag you asked for and exited 0."
    }
}

/**
 * Normalizes collected query values into the typed [ObservationQuery].
 * Pure. The parser may offer convenience flags, but what reaches the runtime is
 * an ADT — never a bag of flags whose meaning is reconstructed downstream.
 * `Only` and `Except` are distinct cases rather than a `negate` boolean, so the
 * blacklist reading of `--grep-invert` is visible in the type.
*/
fun buildObservationQuery(
    grepSelectors: List<TextSelector>,
    grepInvert: Boolean,
    stageNames: Set<String>,
    stepNames: Set<String>,
    eventKinds: Set<String>,
    channels: Set<OutputChannel> = emptySet(),
    outcomes: Set<ObservedOutcome> = emptySet(),
): ObservationQuery = ObservationQuery(
    stageNames = stageNames.toSet(),
    stepNames = stepNames.toSet(),
    eventKinds = eventKinds.toSet(),
    outcomes = outcomes.toSet(),
    channels = channels.toSet(),
    lines = when {
        grepSelectors.isEmpty() -> LineSelector.All
        grepInvert -> LineSelector.Except(grepSelectors.toList())
        else -> LineSelector.Only(grepSelectors.toList())
    },
)

/** Closed result of pure CLI decoding. */
sealed interface CliParseResult {
    data class Parsed(val flags: CliFlags) : CliParseResult
    data class Rejected(val error: CliError) : CliParseResult
}

/**
 * What a read-only verb was asked for, already compiled.
 * The query arrives COMPILED so a reader cannot re-validate it and disagree with the parser about
 * whether a pattern was usable — the same reasoning [RunObservationOutput.encode] documents for its
 * own parameter.
*/
sealed interface ObservationParseResult {
    data class Parsed(
        val query: ObservationQuery,
        val compiled: CompiledObservationQuery,
        val view: ObservationView,
        val format: ObservationFormat,
        /** Keep reading after the durable authority is exhausted. */
        val follow: Boolean,
        /** How many selected records this read may emit. A budget, never a filter. */
        val budget: RecordBudget,
        /** Where the output lane starts, in bytes of the run's observation order. Null means the start. */
        val tailBytes: Long?,
        /** Where the event lane is. Null means no durable event store was named. */
        val dbPath: String?,
        /** Where the Output Plane is. Null means it was not named. */
        val controlRoot: String?,
    ) : ObservationParseResult

    data class Rejected(val error: CliError) : ObservationParseResult
}

sealed interface DurableRunPolicy {
    data object ReusePriorRun : DurableRunPolicy
    data object ResumePriorRun : DurableRunPolicy
    data object StartFreshRun : DurableRunPolicy
}

sealed interface DurableRunSelection {
    val runId: RunId

    data class Reused(override val runId: RunId, val correlationId: CorrelationId? = null) : DurableRunSelection
    data class StartedFresh(override val runId: RunId, val correlationId: CorrelationId? = null) : DurableRunSelection
}

/** Outcome of processing one CLI option within [CliParser.applyOption]. */
private sealed interface ApplyOutcome {
    /** The option consumed [nextIndex] positions of [args]. */
    data class Applied(val nextIndex: Int) : ApplyOutcome
    /** The parser must reject with [error] and stop. */
    data class Rejected(val error: CliError) : ApplyOutcome
}

/** Mutable accumulator threaded through [CliParser.applyOption]. */
private class ParseState(
    var dbPath: String? = null,
    var durableRunPolicy: DurableRunPolicy = DurableRunPolicy.ReusePriorRun,
    var controlRoot: String? = null,
    var workspace: String? = null,
    /** `--isolated` requests managed scratch. */
    var isolated: Boolean = false,
    var sandboxProfile: SandboxProfile = SandboxProfile.NONE,
    /**
     * `--allow-network` permits outbound egress for this run.
     * FALSE by default, so a pipeline that reaches for the network without the
     * flag is rejected at capability admission rather than quietly succeeding.
*/
    var allowNetwork: Boolean = false,
    val pluginJars: MutableList<String> = mutableListOf(),
    /**
     * The operator's plugin trust file, set by `--plugin-trust <file>`. Naming it turns the
     * trust policy on for this run.
     */
    var pluginTrust: String? = null,
    /**
     * External invoker correlation token, set by
     * `--correlation-id <uuid>`. The raw string lives here; conversion
     * to a typed [CorrelationId] happens after parsing succeeds so
     * a malformed value surfaces as a typed [InvalidCorrelationId]
     * rather than crashing the parser.
*/
    var correlationId: String? = null,
    /**
     * Every `--output-protocol`
     * token the operator supplied. The last one wins. Validation
     * against [dev.rubentxu.pipeline.v2.application.OutputProtocol.SUPPORTED_IDS]
     * happens in Main.kt — a missing or unknown id surfaces as
     * `CliExit.InvalidArgument` (exit 2), never as silent
     * degradation.
*/
    var outputProtocolTokens: List<String> = emptyList(),
    /** `--view`. Resolved to a DELIVERABLE view at parse time */
    var view: ObservationView = ObservationView.NORMAL,
    /**
     * Which views THIS parse may deliver.
     * In the state so `--view` keeps ONE handler for every verb: a reader that also reads the output
     * lane widens this before parsing and gets `--view console`, `run` does not and keeps refusing
     * it. Branching on the verb inside the `--view` handler instead would have been a second place
     * where a flag's meaning is decided, which is how one flag ends up meaning two things.
*/
    var deliverableViews: Set<ObservationView> = ObservationView.EVENT_LANE_VIEWS,
    /**
     * `--limit N`. A read-side budget over SELECTED records, never a filter: see [RecordBudget].
*/
    var budget: RecordBudget = RecordBudget.All,
    /**
     * `--outcome`. A closed vocabulary, so unknown names are refused rather than carried.
     * A plain `mutableSetOf` and not `sortedSetOf` like `channels`: the order a set of outcomes is
     * collected in is not information, and the ADT is not `Comparable` because nothing needs it to
     * be. Asking for it anyway would have put a phantom requirement in the parser.
*/
    var outcomes: MutableSet<ObservedOutcome> = mutableSetOf(),
    /**
     * `--tail-bytes N`. Where the output lane STARTS, not what it prints.
     * A start position and a budget are different things and compose: read the last N bytes, then
     * print at most `--limit` of what that yields.
*/
    var tailBytes: Long? = null,
    /**
     * `--follow`. Read-only and therefore legal here and on `observe`, unlike the execution options.
     * A flag rather than a mode: following is how long you read, not what you read, and the view
     * still decides the lane.
*/
    var follow: Boolean = false,
    /** `--format`. JSON/JSONL are opt-in; text is the default */
    var format: ObservationFormat = ObservationFormat.TEXT,
    /**
     * Query dimensions. AND across dimensions, OR within each one
     * [grepSelectors] accumulates repeated `--grep`/`--grep-regex` into an OR
     * group; [grepInvert] turns that whole group into a blacklist at build time.
     * No [LineSelector] is stored here: the parser collects raw values and the
     * normalized ADT is produced once, by [buildObservationQuery].
*/
    val grepSelectors: MutableList<TextSelector> = mutableListOf(),
    var grepInvert: Boolean = false,
    val stageNames: MutableSet<String> = sortedSetOf(),
    val stepNames: MutableSet<String> = sortedSetOf(),
    val eventKinds: MutableSet<String> = sortedSetOf(),
    /**
     * Collected as the parsed [OutputChannel], never as the raw token.
     * The parser is the boundary where a human's string becomes a domain value, so this is where an
     * unknown token is refused. Storing `stdout` as a `String` and letting [ObservationQuery] decide
     * would push the vocabulary check downstream, where a miss reads as "no stderr in this run"
     * instead of "that was not a channel".
*/
    val channels: MutableSet<OutputChannel> = sortedSetOf(),
)

/**
 * Pure parser for the application CLI.
 *  added three more public entry points — [parseConsole], [parseEvents]
 * [parseInspect] — alongside the original [parse] / [parseObservation]. The object now exposes
 * five verb parsers; the per-verb private helpers (one per verb) keep each verb's
 * CyclomaticComplexMethod under the configured ceiling. The five public functions plus
 * `applyOption` push the object flavour of [TooManyFunctions] past the default 11; that is
 * Structural to the unification — a parser owns every verb's argv-to-typed-request
 * translation, and a sub-object per verb would re-introduce the multi-authority defect the
 * slice exists to remove.
*/
@Suppress("TooManyFunctions")
object CliParser {
    fun parse(args: Array<String>): CliParseResult {
        if (args.isEmpty()) return CliParseResult.Rejected(CliError.MissingCommand)

        val command = when (args[0]) {
            "validate" -> CliCommand.VALIDATE
            "run" -> CliCommand.RUN
            else -> return CliParseResult.Rejected(CliError.InvalidCommand(args[0]))
        }

        val state = ParseState()
        var index = 1

        while (index < args.size && args[index].startsWith("--")) {
            when (val outcome = applyOption(args[index], args, index, state)) {
                is ApplyOutcome.Applied -> index = outcome.nextIndex
                is ApplyOutcome.Rejected -> return CliParseResult.Rejected(outcome.error)
            }
        }

        val scriptPath = args.getOrNull(index)
            ?: return CliParseResult.Rejected(CliError.MissingScriptPath)

        // Fail closed on trailing options. The scan above stops at the first
        // non-`--` argument, so before this check `run s.kts --format text`
        // discarded everything after the script and still ran, producing the
        // default output. The operator believed a flag was applied; it was not.
        // Options must precede the script path, and an unknown one anywhere is
        // an error rather than a silent no-op.
        for (tail in args.drop(index + 1)) {
            if (tail.startsWith("--")) {
                return CliParseResult.Rejected(CliError.TrailingOption(tail))
            }
        }

        // Fail closed on the incompatible pair.
        state.workspace?.let { explicit ->
            if (state.isolated) {
                return CliParseResult.Rejected(CliError.ConflictingWorkspaceModes(explicit))
            }
        }

        // `applyOption` is shared with `parseObservation`, so the reader's options land here too —
        // into a `CliFlags` that has no field for them. Refused by name rather than dropped: a
        // truncated live transcript that cannot say it was truncated is the one output shape this
        // repository refuses to produce.
        if (state.follow) {
            return CliParseResult.Rejected(CliError.OptionBelongsToObserve("--follow"))
        }
        if (state.budget !is RecordBudget.All) {
            return CliParseResult.Rejected(CliError.OptionBelongsToObserve("--limit"))
        }
        if (state.tailBytes != null) {
            return CliParseResult.Rejected(CliError.OptionBelongsToObserve("--tail-bytes"))
        }
        if (state.outcomes.isNotEmpty()) {
            return CliParseResult.Rejected(CliError.OptionBelongsToObserve("--outcome"))
        }

        // `--grep-invert` negates a group; with no group there is nothing to
        // negate. Rejected rather than defaulted to "exclude everything".
        if (state.grepInvert && state.grepSelectors.isEmpty()) {
            return CliParseResult.Rejected(CliError.InvertWithoutGrep)
        }

        val assembledQuery = buildObservationQuery(
            grepSelectors = state.grepSelectors,
            grepInvert = state.grepInvert,
            stageNames = state.stageNames,
            stepNames = state.stepNames,
            eventKinds = state.eventKinds,
            channels = state.channels,
            outcomes = state.outcomes,
        )

        // Compile here, before any effect: an uncompilable regex is refused
        // rather than discovered at the moment output is being produced.
        val compiledQuery = compileQuery(assembledQuery)
        if (compiledQuery is SelectorCompileResult.Invalid) {
            return CliParseResult.Rejected(CliError.InvalidQuery(compiledQuery.reason))
        }

        return CliParseResult.Parsed(
            CliFlags(
                command = command,
                dbPath = state.dbPath,
                durableRunPolicy = state.durableRunPolicy,
                scriptPath = scriptPath,
                controlRoot = state.controlRoot,
                workspace = state.workspace,
                isolated = state.isolated,
                sandboxProfile = state.sandboxProfile,
                allowNetwork = state.allowNetwork,
                pluginJars = state.pluginJars.toList(),
                pluginTrust = state.pluginTrust,
                correlationId = state.correlationId,
                view = state.view,
                format = state.format,
                outputProtocolTokens = state.outputProtocolTokens,
                query = buildObservationQuery(
                    grepSelectors = state.grepSelectors,
                    grepInvert = state.grepInvert,
                    stageNames = state.stageNames,
                    stepNames = state.stepNames,
                    eventKinds = state.eventKinds,
                    channels = state.channels,
                    outcomes = state.outcomes,
                ),
            ),
        )
    }

    /**
     * The OBSERVATION subset of the CLI surface, for a verb that reads rather than executes.
     * ## Why this lives here and not in the reader
     * `observe` selects with the same dimensions `run` does, so the meaning of `--channel` has to
     * be decided in exactly one place. A second option parser would be a second authority for what a
     * flag means, and the two would drift the moment one of them gains a case. So this runs the same
     * [applyOption] over the same [ParseState] and hands back the same assembled query — a verb that
     * reads cannot quietly accept a different `--grep` than the verb that runs.
     * ## Why execution options are refused rather than ignored
     * `--resume` and `--rerun` decide which run exists; `--sandbox-profile` and `--allow-network`
     * decide what a run may do. None of them means anything to a reader, and an option that is
     * silently dropped is an option the user believed in. They are rejected by name, so
     * `observe --resume RUN` says so instead of replaying a different run than the one asked for.
*/
    fun parseObservation(args: Array<String>): ObservationParseResult {
        val state = ParseState(deliverableViews = READS_BOTH_LANES)
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            if (arg in EXECUTION_ONLY_OPTIONS) {
                return ObservationParseResult.Rejected(CliError.OptionNotReadable(arg))
            }
            if (!arg.startsWith("--")) {
                return ObservationParseResult.Rejected(CliError.TrailingOption(arg))
            }
            when (val outcome = applyOption(arg, args, index, state)) {
                is ApplyOutcome.Applied -> index = outcome.nextIndex
                is ApplyOutcome.Rejected -> return ObservationParseResult.Rejected(outcome.error)
            }
        }
        if (state.grepInvert && state.grepSelectors.isEmpty()) {
            return ObservationParseResult.Rejected(CliError.InvertWithoutGrep)
        }
        // Before any store is opened. `RunObservationOutput.isIncremental` is the decision; this is
        // only its first of two refusals, so that the follow itself does not have to trust it.
        // After admission, not before: `--view` is parsed in the same pass, and a caller who writes
        // the flag before the view is not making a mistake worth a diagnostic.
        if (state.tailBytes != null && state.view != ObservationView.CONSOLE) {
            return ObservationParseResult.Rejected(
                CliError.TailNeedsTheOutputLane(state.view.name.lowercase()),
            )
        }
        if (state.follow && !RunObservationOutput.isIncremental(state.format)) {
            return ObservationParseResult.Rejected(
                CliError.FollowNeedsAnIncrementalFormat(state.format.wire),
            )
        }
        val query = buildObservationQuery(
            grepSelectors = state.grepSelectors,
            grepInvert = state.grepInvert,
            stageNames = state.stageNames,
            stepNames = state.stepNames,
            eventKinds = state.eventKinds,
            channels = state.channels,
            outcomes = state.outcomes,
        )
        return when (val compiled = compileQuery(query)) {
            is SelectorCompileResult.Ok -> ObservationParseResult.Parsed(
                query = query,
                compiled = compiled.value,
                view = state.view,
                format = state.format,
                follow = state.follow,
                budget = state.budget,
                tailBytes = state.tailBytes,
                dbPath = state.dbPath,
                controlRoot = state.controlRoot,
            )

            is SelectorCompileResult.Invalid ->
                ObservationParseResult.Rejected(CliError.InvalidQuery(compiled.reason))
        }
    }

    // --------------------------------------------------------------- verb parsers

    /**
     * `pipelinek inspect <runId> [opts]`.
     * Body verbatim from [MainInspectCli.parseInspect]; the verb main now calls this and translates
     * the typed result into the `inspect: ...` stderr line + exit 2 it has always used. The
     * `Error:` prefix is not the inspect prefix — inspect prefixes with `inspect:`, and this
     * separation is intentional (events and console share `Error:` as their CLI-admission prefix).
     * Body kept inline rather than delegated so the cyclomatic cost [MainInspectCli.parseInspect]
     * used to wear is gone with it; the same option-table layout justifies the same
     * `@Suppress("CyclomaticComplexMethod")`. See [MainInspectCli.parseInspect] for the option
     * table and the per-option rationale.
*/
    @Suppress("CyclomaticComplexMethod")
    fun parseInspect(args: Array<String>): InspectParseResult {
        var failed = false
        var kind: String? = null
        var stage: String? = null
        var step: Long? = null
        var context = 0
        var logTail = 0
        var opId: String? = null
        var fields: List<MainInspectCli.InspectField>? = null
        var format: MainInspectCli.InspectFormat = MainInspectCli.InspectFormat.TEXT
        var controlRoot: String? = null
        var dbPath: String? = null
        var state = false
        var profile = false
        var explain = false

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when (a) {
                "--failed" -> failed = true
                "--state" -> state = true
                "--profile" -> profile = true
                "--explain" -> explain = true
                "--kind" -> {
                    kind = args.getOrNull(++i) ?: return rejectInspect("--kind requires a value")
                }
                "--stage" -> {
                    stage = args.getOrNull(++i) ?: return rejectInspect("--stage requires a value")
                }
                "--step" -> {
                    val raw = args.getOrNull(++i) ?: return rejectInspect("--step requires a value")
                    step = raw.toLongOrNull() ?: return rejectInspect("--step must be a Long, got '$raw'")
                }
                "--context" -> {
                    val raw = args.getOrNull(++i) ?: return rejectInspect("--context requires a value")
                    context = raw.toIntOrNull() ?: return rejectInspect("--context must be an Int, got '$raw'")
                    if (context < 0) return rejectInspect("--context must be >= 0, got $context")
                }
                "--log-tail" -> {
                    val raw = args.getOrNull(++i) ?: return rejectInspect("--log-tail requires a value")
                    logTail = raw.toIntOrNull() ?: return rejectInspect("--log-tail must be an Int, got '$raw'")
                    if (logTail < 0) return rejectInspect("--log-tail must be >= 0, got $logTail")
                }
                "--log-tail-op" -> {
                    val raw = args.getOrNull(++i) ?: return rejectInspect("--log-tail-op requires a value")
                    if (raw.isBlank()) return rejectInspect("--log-tail-op must not be blank")
                    opId = raw
                }
                "--fields" -> {
                    val raw = args.getOrNull(++i) ?: return rejectInspect("--fields requires a value")
                    fields = raw.split(',').map { token ->
                        val upper = token.trim().uppercase()
                        MainInspectCli.InspectField.parse(upper)
                            ?: return rejectInspect("--fields has unknown token '$token'")
                    }
                }
                "--format" -> {
                    val raw = args.getOrNull(++i) ?: return rejectInspect("--format requires a value")
                    format = MainInspectCli.InspectFormat.parse(raw) ?: return rejectInspect(
                        "--format must be one of ${MainInspectCli.InspectFormat.values().joinToString { it.cli }}",
                    )
                }
                "--control-root" -> {
                    controlRoot = args.getOrNull(++i)
                        ?: return rejectInspect("--control-root requires a value")
                }
                "--db" -> {
                    dbPath = args.getOrNull(++i) ?: return rejectInspect("--db requires a value")
                }
                else -> if (a.startsWith("--")) {
                    return rejectInspect("unknown option '$a'")
                } else {
                    return rejectInspect("unexpected positional argument '$a'")
                }
            }
            i++
        }

        // Whole-run reports are modes, and a query carries one: two reports
        // in one invocation would have to be ordered in an order the caller
        // never chose.
        val modes = buildList {
            if (state) add("--state")
            if (profile) add("--profile")
            if (explain) add("--explain")
        }
        if (modes.size > 1) {
            return rejectInspect("a query carries one whole-run report; got ${modes.joinToString(" + ")}")
        }

        return InspectParseResult.Parsed(
            CompiledInspectionQuery(
                failed = failed,
                kind = kind,
                stage = stage,
                step = step,
                context = context,
                logTail = logTail,
                opId = opId,
                fields = fields,
                format = format,
                controlRoot = controlRoot,
                dbPath = dbPath,
                state = state,
                profile = profile,
                explain = explain,
            ),
        )
    }

    private fun rejectInspect(message: String): InspectParseResult.Rejected =
        InspectParseResult.Rejected(message)

    /**
     * `pipelinek events --db <path> <runId> [opts]`.
     * Body verbatim from [MainEventsCli.parseMainArgs]; the verb main calls this and renders the
     * Typed rejection as `Error: <reason>` (the / contract the events verb has used
     * Since). The follow-up flags (`--follow`, `--fields`, `--context`, `--tail`) ride on
     * [MainEventsCli.parseEventsInspectionFlags], which stays in the verb because it has its own
     * closed ADT (`EventsInspectionFlag`) the existing tests exercise directly.
*/
    @Suppress("CyclomaticComplexMethod")
    fun parseEvents(args: Array<String>): EventsCliParseResult {
        var db: String? = null
        var runId: String? = null
        var kind: String? = null
        var subjectCanonical: String? = null
        var limit = EVENTS_DEFAULT_LIMIT
        var afterCursor: String? = null
        var limitArg: String? = null
        var projection: EventsRowProjection = EventsRowProjection.Envelope
        var follow = false
        var fieldsWhitelist: List<EventField>? = null
        var contextWindow = 0
        var tailCount = 0

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when (arg) {
                "--db" -> db = args.getOrNull(++i)
                "--kind" -> kind = args.getOrNull(++i)
                "--subject" -> subjectCanonical = args.getOrNull(++i)
                "--limit" -> limitArg = args.getOrNull(++i)
                "--after-cursor" -> afterCursor = args.getOrNull(++i)
                "--typed" -> projection = EventsRowProjection.Typed
                "--follow" -> follow = true
                "--fields" -> {
                    val raw = args.getOrNull(++i)
                        ?: return EventsCliParseResult.Rejected("--fields requires a value")
                    val parsed = parseEventsFieldsToken(raw)
                        ?: return EventsCliParseResult.Rejected("--fields has unknown token(s): $raw")
                    fieldsWhitelist = parsed
                }
                "--context" -> {
                    val raw = args.getOrNull(++i)
                        ?: return EventsCliParseResult.Rejected("--context requires a value")
                    val n = parseEventsNonNegativeInt(raw)
                        ?: return EventsCliParseResult.Rejected(
                            "--context must be a non-negative Int, got '$raw'",
                        )
                    contextWindow = n
                }
                "--tail" -> {
                    val raw = args.getOrNull(++i)
                        ?: return EventsCliParseResult.Rejected("--tail requires a value")
                    val n = parseEventsNonNegativeInt(raw)
                        ?: return EventsCliParseResult.Rejected(
                            "--tail must be a non-negative Int, got '$raw'",
                        )
                    tailCount = n
                }
                else -> when {
                    arg.startsWith("--") -> return EventsCliParseResult.Rejected("unknown option: $arg")
                    runId != null -> return EventsCliParseResult.Rejected("unexpected extra argument: $arg")
                    else -> runId = arg
                }
            }
            i++
        }
        if (db == null || runId == null) {
            return EventsCliParseResult.Rejected(
                "Usage: pipeline events --db <path> <runId> [--kind K] " +
                    "[--subject v1:run:ID|v1:stage:ID:N|...] [--limit N] " +
                    "[--after-cursor TOKEN] [--follow] " +
                    "[--fields SEQ,KIND,EVENTID,SUBJECT,SOURCE] " +
                    "[--context N] [--tail N] " +
                    "[--typed]",
            )
        }
        val parsedLimit = if (limitArg != null) {
            val p = limitArg.toIntOrNull()
            if (p == null || p <= 0) {
                return EventsCliParseResult.Rejected("--limit must be a positive integer, got: $limitArg")
            }
            p
        } else limit
        if (projection == EventsRowProjection.Typed &&
            (fieldsWhitelist != null || contextWindow > 0 || tailCount > 0)
        ) {
            return EventsCliParseResult.Rejected(
                "--typed is mutually exclusive with --fields / --context / --tail " +
                    "(the EventField ADT is bound to envelopes, not to DomainEvent)",
            )
        }
        return EventsCliParseResult.Parsed(
            EventsCliArgs(
                db = db,
                runId = runId,
                kind = kind,
                subjectCanonical = subjectCanonical,
                limit = parsedLimit,
                afterCursor = afterCursor,
                projection = projection,
                follow = follow,
                fieldsWhitelist = fieldsWhitelist,
                contextWindow = contextWindow,
                tailCount = tailCount,
            ),
        )
    }

    /**
     * `--fields` token parse for the events verb. Kept private on the
     * parser; the closed vocabulary is the parser's contract, not the verb's.
*/
    private fun parseEventsFieldsToken(raw: String): List<EventField>? {
        if (raw.isBlank()) return null
        val out = mutableListOf<EventField>()
        for (token in raw.split(',')) {
            val upper = token.trim().uppercase()
            val parsed = when (upper) {
                "SEQ", "SEQUENCE" -> EventField.Sequence
                "KIND" -> EventField.Kind
                "EVENTID", "EVENT_ID" -> EventField.EventId
                "SUBJECT" -> EventField.Subject
                "SOURCE" -> EventField.Source
                else -> return null
            }
            out.add(parsed)
        }
        return out
    }

    /** Non-negative Int parse for `--context` / `--tail`. */
    private fun parseEventsNonNegativeInt(raw: String): Int? {
        val n = raw.toIntOrNull() ?: return null
        if (n < 0) return null
        return n
    }

    /**
     * `pipelinek console --control-dir <path> <runId> <opId> [opts]`.
     * Body verbatim from the now-removed [MainConsoleCliAdmission]. The verb main calls this and
     * renders the typed [ConsoleCliRefusal] through [renderConsoleCliRefusal], which produces the
     * `Error: ...` wire line for argument refusals. The data-plane refusals
     * (`console-refused: foreign-stream ...`, etc.) are produced by [ConsoleReadService.renderRefusal]
     * at the read boundary and are NOT touched here — they are independent observable contracts.
     * `--range`, `--frames`, `--after-cursor`, `--max-bytes`, `--after-ordinal` and `--frame-limit`
     * Remain exclusive in the combinations [validate] documents; is a relocation, not a
     * relaxation.
*/
    @Suppress("CyclomaticComplexMethod")
    fun parseConsole(args: Array<String>): ConsoleCliParseResult {
        var controlDir: String? = null
        var runId: String? = null
        var opId: String? = null
        var maxBytes: String? = null
        var afterCursor: String? = null
        var range: String? = null
        var formatToken: String? = null
        var frames = false
        var frameLimit: String? = null
        var afterOrdinal: String? = null
        // `--follow` over the per-frame path. Boolean gates the dispatch;
        // the two valued options (`--follow-poll-interval`, `--follow-max-polls`) carry into
        // [consoleAdmitFollowFrames] via [consoleValidate] and become [FollowConfig].
        var follow = false
        var followPollInterval: String? = null
        var followMaxPolls: String? = null
        // Content filters on the per-frame path. Repeated flags OR within an
        // axis; the three axes AND together (a frame must satisfy every non-empty axis). The
        // sets are populated here and lowered into the closed [ConsoleFrameFilters.Active] ADT
        // in [consoleValidate]. `--run` is the only one of the four that is not a filter — it
        // is an alternative to the positional `<runId>` and is mutually exclusive with it.
        var stages: MutableSet<String> = LinkedHashSet()
        var steps: MutableSet<String> = LinkedHashSet()
        var kinds: MutableSet<String> = LinkedHashSet()
        var runOverride: String? = null
        // The events journal. It is the ONLY authority that knows a stage/step
        // NAME or a step's kind; without it those axes are refused rather than ignored. It is a
        // path here and an opened store in `main`, so admission still performs no effect.
        var dbPath: String? = null
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when (arg) {
                "--control-dir", "--max-bytes", "--after-cursor", "--range",
                "--frame-limit", "--after-ordinal", "--format",
                "--follow-poll-interval", "--follow-max-polls",
                "--stage", "--step", "--kind", "--run", "--db" -> {
                    val value = args.getOrNull(index + 1)
                        ?: return ConsoleCliParseResult.Rejected(ConsoleCliRefusal.MissingOptionValue(arg))
                    when (arg) {
                        "--control-dir" -> controlDir = value
                        "--max-bytes" -> maxBytes = value
                        "--after-cursor" -> afterCursor = value
                        "--range" -> range = value
                        "--frame-limit" -> frameLimit = value
                        "--after-ordinal" -> afterOrdinal = value
                        "--format" -> formatToken = value
                        "--follow-poll-interval" -> followPollInterval = value
                        "--follow-max-polls" -> followMaxPolls = value
                        "--stage" -> stages += value
                        "--step" -> steps += value
                        "--kind" -> kinds += value
                        "--run" -> runOverride = value
                        "--db" -> dbPath = value
                    }
                    index++
                }
                "--frames" -> frames = true
                "--follow" -> follow = true
                else -> {
                    // `--run` and a positional `<runId>` are mutually
                    // exclusive, and a positional that follows `--run` is interpreted as
                    // the opId (the runId comes from the flag). The second positional,
                    // if any, IS a positional runId: the user typed it after the opId
                    // they meant, and the two would collide on the runId slot. Refuse
                    // before [consoleValidate] so the typed `MutuallyExclusiveRunId`
                    // surfaces with both tokens named.
                    val refusal = consolePositional(arg, runId, opId, runOverride != null)
                    if (refusal != null) return ConsoleCliParseResult.Rejected(refusal)
                    if (runOverride != null) {
                        if (opId == null) {
                            opId = arg
                        } else {
                            // opId is already set, so this second positional is a
                            // positional runId; `--run` and the positional are
                            // mutually exclusive.
                            return ConsoleCliParseResult.Rejected(
                                ConsoleCliRefusal.MutuallyExclusiveRunId(runId = arg, runOverride = runOverride),
                            )
                        }
                    } else if (runId == null) {
                        runId = arg
                    } else {
                        opId = arg
                    }
                }
            }
            index++
        }
        return consoleValidate(
            controlDir, runId, opId, maxBytes, afterCursor, range, formatToken,
            frames, frameLimit, afterOrdinal,
            follow, followPollInterval, followMaxPolls,
            stages, steps, kinds, runOverride, dbPath,
        )
    }

    private fun consolePositional(arg: String, runId: String?, opId: String?, hasRunOverride: Boolean): ConsoleCliRefusal? = when {
        arg.startsWith("--") -> ConsoleCliRefusal.UnknownOption(arg)
        // Without `--run`, the slots are runId then opId. Both filled → unexpected.
        !hasRunOverride && runId != null && opId != null -> ConsoleCliRefusal.UnexpectedArgument(arg)
        // With `--run`, the slot is opId only. A second positional is the runId conflict
        // and the main loop in [parseConsole] handles it as [MutuallyExclusiveRunId] rather
        // than as a generic unexpected argument, so the refusal names both tokens. The
        // positional-pivot here only needs to admit the FIRST positional under `--run`.
        hasRunOverride && opId != null -> null
        else -> null
    }

    private fun consoleValidate(
        controlDir: String?,
        runId: String?,
        opId: String?,
        maxBytes: String?,
        afterCursor: String?,
        range: String?,
        formatToken: String?,
        frames: Boolean,
        frameLimit: String?,
        afterOrdinal: String?,
        follow: Boolean,
        followPollInterval: String?,
        followMaxPolls: String?,
        stages: Set<String>,
        steps: Set<String>,
        kinds: Set<String>,
        runOverride: String?,
        dbPath: String?,
    ): ConsoleCliParseResult {
        // The shape: four orthogonal validators, each a single `if refused return`, then a final
        // "admit" dispatcher that knows every read verb. Splitting them keeps each method under
        // detekt's complexity ceiling and makes the refusal surface of every axis visible at a
        // glance — which is what an operator trying to debug a typed rejection needs.
        val cd = controlDir ?: return consoleReject(ConsoleCliRefusal.MissingArgument)
        val hasFilter = stages.isNotEmpty() || steps.isNotEmpty() || kinds.isNotEmpty()

        val runAxis = resolveRunAxis(runId, runOverride)
        if (runAxis is ResolvedRunAxis.Refused) return consoleReject(runAxis.refusal)
        val effectiveRunId = (runAxis as ResolvedRunAxis.Admitted).effectiveRunId
        val oid = opId ?: if (hasFilter) null else return consoleReject(ConsoleCliRefusal.MissingArgument)

        validateRangeIncompatibilities(range, maxBytes, afterCursor)?.let { return it }
        validateFramesIncompatibilities(frames, range, maxBytes, afterCursor)?.let { return it }
        validateFollowShape(follow, frames)?.let { return it }
        validateFilterShape(hasFilter, frames, stages, steps, kinds)?.let { return it }

        val format = formatToken?.let { token ->
            MainConsoleCli.ConsoleFormat.parse(token)
                ?: return consoleReject(
                    ConsoleCliRefusal.UnknownFormat(
                        value = token,
                        accepted = MainConsoleCli.ConsoleFormat.values().map { it.token },
                    ),
                )
        } ?: MainConsoleCli.ConsoleFormat.TEXT

        return consoleDecideVerb(
            cd = cd,
            effectiveRunId = effectiveRunId,
            oid = oid,
            range = range,
            frames = frames,
            hasFilter = hasFilter,
            stages = stages,
            steps = steps,
            kinds = kinds,
            format = format,
            frameLimit = frameLimit,
            afterOrdinal = afterOrdinal,
            follow = follow,
            followPollInterval = followPollInterval,
            followMaxPolls = followMaxPolls,
            maxBytes = maxBytes,
            afterCursor = afterCursor,
            dbPath = dbPath,
        )
    }

    /**
     * The result of resolving the run id axis: either an effective run id, or a refusal.
     * `Either<Refusal, String>` would say the same thing; this is a `when`-friendly sealed pair
     * with a `refusal()` accessor so the call site can `?: return refusal`.
*/
    private sealed interface ResolvedRunAxis {
        fun refusal(): ConsoleCliRefusal
        data class Admitted(val effectiveRunId: String) : ResolvedRunAxis {
            override fun refusal(): ConsoleCliRefusal = throw IllegalStateException("admitted")
        }
        data class Refused(val refusal: ConsoleCliRefusal) : ResolvedRunAxis {
            override fun refusal(): ConsoleCliRefusal = refusal
        }
    }

    private fun resolveRunAxis(runId: String?, runOverride: String?): ResolvedRunAxis? {
        // `--run` replaces the positional <runId>; both is a category error
        // that the verb main has no honest answer to, so it is refused at admission.
        if (runOverride != null && runId != null) {
            return ResolvedRunAxis.Refused(ConsoleCliRefusal.MutuallyExclusiveRunId(runId, runOverride))
        }
        // Either a positional runId or a `--run` override is required; the existing
        // MissingArgument refusal covers "neither" and is reused unchanged.
        val effective = runId ?: runOverride
            ?: return ResolvedRunAxis.Refused(ConsoleCliRefusal.MissingArgument)
        return ResolvedRunAxis.Admitted(effective)
    }

    private fun validateRangeIncompatibilities(
        range: String?,
        maxBytes: String?,
        afterCursor: String?,
    ): ConsoleCliParseResult? {
        val incompatible = when {
            range == null -> null
            afterCursor != null -> "--after-cursor"
            else -> "--max-bytes".takeIf { maxBytes != null }
        }
        if (range != null && incompatible != null) {
            return consoleReject(ConsoleCliRefusal.IncompatibleWithRange(incompatible))
        }
        return null
    }

    private fun validateFramesIncompatibilities(
        frames: Boolean,
        range: String?,
        maxBytes: String?,
        afterCursor: String?,
    ): ConsoleCliParseResult? {
        if (!frames) return null
        val framesIncompatible = when {
            range != null -> "--range"
            maxBytes != null -> "--max-bytes"
            afterCursor != null -> "--after-cursor"
            else -> null
        }
        return framesIncompatible?.let { consoleReject(ConsoleCliRefusal.IncompatibleWithFrames(it)) }
    }

    private fun validateFollowShape(follow: Boolean, frames: Boolean): ConsoleCliParseResult? {
        // `--follow` requires `--frames`. Refused rather than defaulted: a
        // `--follow` against the byte path would silently emit the merged page once and then
        // poll forever on a byte cursor, which is a different contract from what the operator
        // asked for.
        return if (follow && !frames) consoleReject(ConsoleCliRefusal.FollowRequiresFrames) else null
    }

    private fun validateFilterShape(
        hasFilter: Boolean,
        frames: Boolean,
        stages: Set<String>,
        steps: Set<String>,
        kinds: Set<String>,
    ): ConsoleCliParseResult? {
        if (!hasFilter || frames) return null
        // A content filter without `--frames` is a category error: filters
        // operate on per-frame records, and the byte-budgeted (Paged) and byte-span (Range)
        // paths produce no per-frame records. The refusal names every offending flag so the
        // caller adds `--frames` rather than guessing why their filter did not narrow the
        // answer.
        val offending = buildList {
            if (stages.isNotEmpty()) add("--stage")
            if (steps.isNotEmpty()) add("--step")
            if (kinds.isNotEmpty()) add("--kind")
        }
        return consoleReject(ConsoleCliRefusal.FilterRequiresFrames(offending))
    }

    /**
     * Selects which [ConsoleReadRequest] shape the call admits, after every axis validator has
     * accepted the input.
     * Audit. `--follow` used to be dispatched FIRST, so
     * `--frames --follow --stage build` admitted a filterless FollowFrames and dropped the
     * filter in silence. Filters and following are INDEPENDENT axes — following is how long you
     * read, filtering is what you read — so there is no reason for one to shadow the other.
*/
    private fun consoleDecideVerb(
        cd: String,
        effectiveRunId: String,
        oid: String?,
        range: String?,
        frames: Boolean,
        hasFilter: Boolean,
        stages: Set<String>,
        steps: Set<String>,
        kinds: Set<String>,
        format: MainConsoleCli.ConsoleFormat,
        frameLimit: String?,
        afterOrdinal: String?,
        follow: Boolean,
        followPollInterval: String?,
        followMaxPolls: String?,
        maxBytes: String?,
        afterCursor: String?,
        dbPath: String?,
    ): ConsoleCliParseResult {
        if (follow) {
            return consoleAdmitFollowFrames(
                cd, effectiveRunId, oid, frameLimit, afterOrdinal, followPollInterval, followMaxPolls, format,
                stages = stages, steps = steps, kinds = kinds, dbPath = dbPath,
            )
        }
        if (hasFilter) {
            return consoleAdmitFramesFiltered(
                cd, effectiveRunId, oid, frameLimit, afterOrdinal, format,
                stages, steps, kinds, dbPath = dbPath,
            )
        }
        if (frames) return consoleAdmitFrames(cd, effectiveRunId, oid, frameLimit, afterOrdinal, format)
        // The byte paths address ONE operation by construction — a cursor, a range and a page are
        // all positions WITHIN an operation's stream, so "which operation" is not a question they
        // can answer differently. `oid` is null only when a filter was admitted, and a filter
        // without `--frames` was already refused above, so this cannot fire; it is written out
        // rather than assumed so the invariant is visible where it is relied on.
        val byteScopedOpId = oid ?: return consoleReject(ConsoleCliRefusal.MissingArgument)
        if (range != null) return consoleAdmitRange(cd, effectiveRunId, byteScopedOpId, range, format)
        return consoleAdmitPage(cd, effectiveRunId, byteScopedOpId, maxBytes, afterCursor, format)
    }

    private fun consoleReject(refusal: ConsoleCliRefusal): ConsoleCliParseResult =
        ConsoleCliParseResult.Rejected(refusal)

    private fun consoleAdmitRange(
        controlDir: String,
        runId: String,
        opId: String,
        range: String,
        format: MainConsoleCli.ConsoleFormat,
    ): ConsoleCliParseResult {
        val bounds = range.split(':')
        val from = bounds.getOrNull(0)?.toLongOrNull()
        val to = bounds.getOrNull(1)?.toLongOrNull()
        if (bounds.size != 2 || from == null || to == null) {
            return consoleReject(ConsoleCliRefusal.NotARange(range))
        }
        return ConsoleCliParseResult.Admitted(ConsoleReadRequest.Range(controlDir, runId, opId, from, to, format))
    }

    private fun consoleAdmitPage(
        controlDir: String,
        runId: String,
        opId: String,
        maxBytes: String?,
        afterCursor: String?,
        format: MainConsoleCli.ConsoleFormat,
    ): ConsoleCliParseResult {
        val max = maxBytes?.let { value ->
            value.toIntOrNull()?.takeIf { it > 0 }
                ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--max-bytes", value))
        } ?: ConsoleReadService.DEFAULT_PAGE_BYTES

        val after = afterCursor?.let { token ->
            OutputCursor.decode(token) ?: return consoleReject(ConsoleCliRefusal.NotACursorToken(token))
        }
        return ConsoleCliParseResult.Admitted(
            ConsoleReadRequest.Paged(controlDir, runId, opId, after, max, format),
        )
    }

    private fun consoleAdmitFrames(
        controlDir: String,
        runId: String,
        opId: String?,
        frameLimit: String?,
        afterOrdinal: String?,
        format: MainConsoleCli.ConsoleFormat,
    ): ConsoleCliParseResult {
        val limit = frameLimit?.let { value ->
            value.toIntOrNull()?.takeIf { it > 0 }
                ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--frame-limit", value))
        } ?: ConsoleReadService.DEFAULT_FRAME_LIMIT

        val ordinal = afterOrdinal?.let { value ->
            value.toLongOrNull()?.takeIf { it >= -1L }
                ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--after-ordinal", value))
        } ?: -1L

        return ConsoleCliParseResult.Admitted(
            ConsoleReadRequest.Frames(controlDir, runId, opId, ordinal, limit, format),
        )
    }

    /**
     * Admit the [ConsoleReadRequest.FollowFrames] request.
     * The per-poll bound (`--frame-limit`) and the resume cursor (`--after-ordinal`) share their
     * parsing with [consoleAdmitFrames]; only the [FollowConfig] payload is new. Both new
     * valued options refuse rather than default to match the policy the rest of the console
     * parser applies: a malformed `--follow-*` is a typed refusal, not a silent degradation.
     * Closed payload: [FollowConfig] constructor refuses non-positive values, so a
     * `0` or negative `--follow-poll-interval` / `--follow-max-polls` is a typed refusal
     * BEFORE the constructor runs — the [NotAnInteger] refusal covers the "not an integer"
     * shape, and the constructor covers the "parsed but non-positive" shape.
*/
    private fun consoleAdmitFollowFrames(
        controlDir: String,
        runId: String,
        opId: String?,
        frameLimit: String?,
        afterOrdinal: String?,
        followPollInterval: String?,
        followMaxPolls: String?,
        format: MainConsoleCli.ConsoleFormat,
        stages: Set<String>,
        steps: Set<String>,
        kinds: Set<String>,
        dbPath: String?,
    ): ConsoleCliParseResult {
        val limit = frameLimit?.let { value ->
            value.toIntOrNull()?.takeIf { it > 0 }
                ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--frame-limit", value))
        } ?: ConsoleReadService.DEFAULT_FRAME_LIMIT

        val ordinal = afterOrdinal?.let { value ->
            value.toLongOrNull()?.takeIf { it >= -1L }
                ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--after-ordinal", value))
        } ?: -1L

        val pollIntervalMillis = followPollInterval?.let { value ->
            val n = value.toLongOrNull()
                ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--follow-poll-interval", value))
            n.takeIf { it > 0L }
                ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--follow-poll-interval", value))
        } ?: FollowConfig.DEFAULT_POLL_INTERVAL_MILLIS

        val maxPolls = followMaxPolls?.let { value ->
            val n = value.toIntOrNull()
                ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--follow-max-polls", value))
            n.takeIf { it > 0 }
                ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--follow-max-polls", value))
        } ?: FollowConfig.DEFAULT_MAX_POLLS

        return ConsoleCliParseResult.Admitted(
            ConsoleReadRequest.FollowFrames(
                controlDir,
                runId,
                opId,
                ordinal,
                limit,
                FollowConfig(pollIntervalMillis, maxPolls),
                format,
                filters = consoleFilters(stages, steps, kinds),
                dbPath = dbPath,
            ),
        )
    }

    /**
     * Lower the three raw token sets into the closed [ConsoleFrameFilters] ADT.
     * One function so `--follow` and a single filtered read cannot disagree about what a filter
     * IS. All-empty axes produce [ConsoleFrameFilters.None] rather than an `Active` with nothing
     * in it — the closed ADT refuses that, correctly, because "no axis constrains" and "a filter
     * is active" are different claims.
*/
    private fun consoleFilters(
        stages: Set<String>,
        steps: Set<String>,
        kinds: Set<String>,
    ): ConsoleFrameFilters =
        if (stages.isEmpty() && steps.isEmpty() && kinds.isEmpty()) {
            ConsoleFrameFilters.None
        } else {
            ConsoleFrameFilters.Active(stages, steps, kinds)
        }

    /**
     * Admit a filtered per-frame read.
     * Reuses the per-frame integer rules from [consoleAdmitFrames] and lowers the three filter
     * sets into the closed [ConsoleFrameFilters.Active] ADT. The closed ADT
     * rejects an "active with no axis set" at construction; we gate the call here so the
     * failure shows up as a parser-level refusal rather than a runtime exception.
*/
    private fun consoleAdmitFramesFiltered(
        controlDir: String,
        runId: String,
        opId: String?,
        frameLimit: String?,
        afterOrdinal: String?,
        format: MainConsoleCli.ConsoleFormat,
        stages: Set<String>,
        steps: Set<String>,
        kinds: Set<String>,
        dbPath: String?,
    ): ConsoleCliParseResult {
        if (stages.isEmpty() && steps.isEmpty() && kinds.isEmpty()) {
            // The `hasFilter` check at the call site already guarantees a non-empty axis;
            // this branch is the typed "Active with all-empty axes" guard the closed ADT
            // itself enforces. Kept here for symmetry with the ClosedValueOptions
            // dispatch; the call-site check is the one that fires on the user's input.
            return consoleReject(ConsoleCliRefusal.MissingArgument)
        }
        val limit = frameLimit?.let { value ->
            value.toIntOrNull()?.takeIf { it > 0 }
                ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--frame-limit", value))
        } ?: ConsoleReadService.DEFAULT_FRAME_LIMIT

        val ordinal = afterOrdinal?.let { value ->
            value.toLongOrNull()?.takeIf { it >= -1L }
                ?: return consoleReject(ConsoleCliRefusal.NotAnInteger("--after-ordinal", value))
        } ?: -1L

        val filters = ConsoleFrameFilters.Active(stages, steps, kinds)
        return ConsoleCliParseResult.Admitted(
            ConsoleReadRequest.FramesFiltered(
                controlDir, runId, opId, ordinal, limit, filters, format, dbPath = dbPath,
            ),
        )
    }

    private val EXECUTION_ONLY_OPTIONS: Set<String> =
        (EXECUTION_ONLY_FLAGS.map { it.token } + EXECUTION_ONLY_VALUED.map { it.token }).toSet()

    /**
     * What a reader that reads BOTH lanes can deliver, which is what `observe` parses against.
     * Still excluding [ObservationView.FULL]: events and output have no total order, so a view
     * claiming to be both would be claiming an interleaving this build cannot produce.
*/
    private val READS_BOTH_LANES: Set<ObservationView> =
        ObservationView.EVENT_LANE_VIEWS + ObservationView.CONSOLE

    /**
     * Process one CLI option at [index] of [args], mutating [state] and returning the next index or
     * a typed rejection.
     * Recognition, consumption and application are three separate steps, and the split is the fix
     * rather than a style preference:
     * 1. **Recognition** — [recognise] looks the token up in [FlagOption] / [ValuedOption]. An unknown
     *    token is refused here, before `args[index + 1]` is read at all.
     * 2. **Consumption** — the arity in that table decides whether [args] is consulted. A [FlagOption]
     *    never looks at the next token. That is what makes `run --follow` complain about the missing
     *    SCRIPT instead of the missing value, and what stops `--control-root` from eating
     *    `--allow-network`.
     * 3. **Application** — [FlagOptions] and [ValuedOptions] decide what an option MEANS, grouped by
     *    the shape of its value so neither is a twenty-five-branch switch.
     * This used to open with `val value = args.getOrNull(index + 1) ?: return MissingOptionValue`
     * BEFORE dispatching on the name, which made every option take a value and nothing stop a valued
     * option from swallowing a flag. The arity of every option is now carried by the
     * [FlagOptions] / [ValuedOptions] tables above, and those tables are the whole record of the
     * fix.
     * **Nothing observes that split.** The row-per-row record of the arity was removed with
     * the rest of the test suite, so a regression in the tables would not be caught by anything
     * today.
     * Shared with [parseObservation] on purpose: one table of what each option means, whatever verb
     * is being parsed.
*/
    private fun applyOption(
        option: String,
        args: Array<String>,
        index: Int,
        state: ParseState,
    ): ApplyOutcome {
        val recognised = recognise(option)
            ?: return ApplyOutcome.Rejected(CliError.UnknownOption(option))
        return when (recognised) {
            is RecognisedOption.Flag -> FlagOptions.apply(recognised.option, index, state)
            is RecognisedOption.Valued -> {
                val value = args.getOrNull(index + 1)
                    ?: return ApplyOutcome.Rejected(CliError.MissingOptionValue(option))
                if (value in KNOWN_OPTIONS) {
                    return ApplyOutcome.Rejected(CliError.OptionValueIsAnOption(option, value))
                }
                ValuedOptions.apply(recognised.option, value, index, state)
            }
        }
    }

    /** Events verb default page size; the same value [MainEventsCli] used locally. */
    private const val EVENTS_DEFAULT_LIMIT: Int = 100
}

/** The execution-only half of each table. Split by arity because the two enums are distinct types. */
private val EXECUTION_ONLY_FLAGS: List<FlagOption> =
    listOf(FlagOption.RESUME, FlagOption.RERUN, FlagOption.ISOLATED, FlagOption.ALLOW_NETWORK)

private val EXECUTION_ONLY_VALUED: List<ValuedOption> =
    listOf(ValuedOption.WORKSPACE, ValuedOption.SANDBOX_PROFILE, ValuedOption.PLUGIN_JAR)

/**
 * The whole option surface of this CLI, in one table.
 * Two enums rather than one, and the reason is exhaustiveness: [FlagOptions] matches a
 * `when (FlagOption)` over six cases and [ValuedOptions] a `when (ValuedOption)` over fifteen, both
 * with no `else`. One enum would force an `else` into each, and an `else` here is the defect this
 * refactor exists to remove — an unrecognised option is exactly what must never fall through.
*/
private enum class FlagOption(val token: String) {
    RESUME("--resume"),
    RERUN("--rerun"),

    /** Managed scratch workspace. */
    ISOLATED("--isolated"),

    /**
     * A BOOLEAN with no value, deliberately: the only question is whether this run
     * may egress at all, and a `--allow-network=<something>` spelling would invite a per-host
     * allowlist this runtime does not implement. Until it does, an all-or-nothing switch is the
     * honest surface.
*/
    ALLOW_NETWORK("--allow-network"),
    GREP_INVERT("--grep-invert"),
    FOLLOW("--follow"),
    ;

    companion object {
        val BY_TOKEN: Map<String, FlagOption> = entries.associateBy { it.token }
    }
}

/** Options that take exactly one value. The split from [FlagOption] is what keeps both `when`s total. */
private enum class ValuedOption(val token: String) {
    DB("--db"),
    CONTROL_ROOT("--control-root"),
    WORKSPACE("--workspace"),
    PLUGIN_JAR("--plugin-jar"),
    // The operator's plugin trust file. Naming it turns the trust policy on: every artifact
    // brought with `--plugin-jar` has to be the one this file pins, signed by the publisher it
    // names.
    PLUGIN_TRUST("--plugin-trust"),
    SANDBOX_PROFILE("--sandbox-profile"),
    VIEW("--view"),
    FORMAT("--format"),
    GREP("--grep"),
    GREP_REGEX("--grep-regex"),
    STAGE("--stage"),
    STEP("--step"),
    KIND("--kind"),
    OUTCOME("--outcome"),
    TAIL_BYTES("--tail-bytes"),
    LIMIT("--limit"),
    CHANNEL("--channel"),
    // External invoker correlation token.
    // The string is held as-is and converted to a typed
    // [CorrelationId] after parsing; the parser refuses blank
    // values via the same [MissingOptionValue] path as every
    // other valued flag.
    CORRELATION_ID("--correlation-id"),
    // Opt-in versioned
    // output protocol. Repeating the flag collects the tokens;
    // Main.kt validates the last-supplied id against
    // [OutputProtocol.SUPPORTED_IDS] and exits InvalidArgument
    // (exit 2) on an unknown id. No silent degradation.
    OUTPUT_PROTOCOL("--output-protocol"),
    ;

    companion object {
        val BY_TOKEN: Map<String, ValuedOption> = entries.associateBy { it.token }
    }
}

/** A recognised option and its arity, before any value has been read. */
private sealed interface RecognisedOption {
    data class Flag(val option: FlagOption) : RecognisedOption
    data class Valued(val option: ValuedOption) : RecognisedOption
}

/** Every token this build knows, used to stop a valued option from eating a flag. */
private val KNOWN_OPTIONS: Set<String> = FlagOption.BY_TOKEN.keys + ValuedOption.BY_TOKEN.keys

/** Look the token up. `null` means this build has no such option, and that is an error, not a skip. */
private fun recognise(option: String): RecognisedOption? =
    FlagOption.BY_TOKEN[option]?.let { RecognisedOption.Flag(it) }
        ?: ValuedOption.BY_TOKEN[option]?.let { RecognisedOption.Valued(it) }

/** Options with no value. Their outcome never depends on what follows them. */
private object FlagOptions {
    fun apply(option: FlagOption, index: Int, state: ParseState): ApplyOutcome = when (option) {
        FlagOption.RESUME -> setDurablePolicy(DurableRunPolicy.ResumePriorRun, index, state)
        FlagOption.RERUN -> setDurablePolicy(DurableRunPolicy.StartFreshRun, index, state)
        FlagOption.ISOLATED -> flag(index) { state.isolated = true }
        FlagOption.ALLOW_NETWORK -> flag(index) { state.allowNetwork = true }
        FlagOption.GREP_INVERT -> flag(index) { state.grepInvert = true }
        FlagOption.FOLLOW -> flag(index) { state.follow = true }
    }

    /** A flag occupies exactly one position, so its outcome never depends on what follows it. */
    private inline fun flag(index: Int, set: () -> Unit): ApplyOutcome {
        set()
        return ApplyOutcome.Applied(index + 1)
    }

    private fun setDurablePolicy(policy: DurableRunPolicy, index: Int, state: ParseState): ApplyOutcome {
        if (state.durableRunPolicy != DurableRunPolicy.ReusePriorRun) {
            return ApplyOutcome.Rejected(CliError.ConflictingDurablePolicies)
        }
        state.durableRunPolicy = policy
        return ApplyOutcome.Applied(index + 1)
    }
}

/**
 * Options with a value, dispatched by the shape of that value.
 * The three groups are not decoration: each has a different failure mode, and collapsing them is how
 * a parser ends up with one error vocabulary for three unrelated mistakes.
*/
private object ValuedOptions {
    fun apply(
        option: ValuedOption,
        value: String,
        index: Int,
        state: ParseState,
    ): ApplyOutcome = when (option) {
        // Paths. Existence is not checked here: a path that does not exist yet is a legitimate thing
        // to name, and the effect boundary reports it. Naming a FLAG is not legitimate — refused above.
        ValuedOption.DB -> { state.dbPath = value; ApplyOutcome.Applied(index + 2) }
        ValuedOption.CONTROL_ROOT -> { state.controlRoot = value; ApplyOutcome.Applied(index + 2) }
        ValuedOption.WORKSPACE -> { state.workspace = value; ApplyOutcome.Applied(index + 2) }
        ValuedOption.PLUGIN_JAR -> { state.pluginJars += value; ApplyOutcome.Applied(index + 2) }
        ValuedOption.PLUGIN_TRUST -> { state.pluginTrust = value; ApplyOutcome.Applied(index + 2) }
        // Blank values are rejected here at
        // the boundary; downstream the CliFlags.correlationId field
        // converts the string to a typed [CorrelationId] with the
        // same init guard.
        ValuedOption.CORRELATION_ID -> {
            if (value.isBlank()) {
                ApplyOutcome.Rejected(CliError.MissingOptionValue("--correlation-id"))
            } else {
                state.correlationId = value
                ApplyOutcome.Applied(index + 2)
            }
        }
        // Collect every supplied
        // --output-protocol token in the order the operator passed them.
        // The list is the client's priority for the negotiation. Resolution
        // (intersection with OutputProtocol.SUPPORTED_IDS, fail-closed on
        // no intersection) lives in Main.kt#negotiateOutputProtocolOrExit.
        ValuedOption.OUTPUT_PROTOCOL -> {
            if (value.isBlank()) {
                ApplyOutcome.Rejected(CliError.MissingOptionValue("--output-protocol"))
            } else {
                state.outputProtocolTokens += value
                ApplyOutcome.Applied(index + 2)
            }
        }

        // Closed vocabulary or a number, resolved HERE at the boundary so nothing downstream re-decides
        // what a token names. Repeating a flag unions, per the AND-across/OR-within rule.
        ValuedOption.SANDBOX_PROFILE -> ClosedValueOptions.applySandboxProfile(value, index, state)
        ValuedOption.VIEW -> ClosedValueOptions.applyView(value, index, state)
        ValuedOption.FORMAT -> ClosedValueOptions.applyFormat(value, index, state)
        ValuedOption.OUTCOME -> ClosedValueOptions.applyOutcomeToken(value, index, state)
        ValuedOption.CHANNEL -> ClosedValueOptions.applyChannelToken(value, index, state)
        ValuedOption.TAIL_BYTES -> ClosedValueOptions.applyTailBytes(value, index, state)
        ValuedOption.LIMIT -> ClosedValueOptions.applyLimit(value, index, state)

        // Free text. Empty is refused: under substring semantics it matches EVERY line, which is a
        // typo promoted to a filter that keeps everything.
        ValuedOption.GREP -> TextFilterOptions.addGrep(option.token, value, index, state, TextSelector::Literal)
        ValuedOption.GREP_REGEX -> TextFilterOptions.addGrep(option.token, value, index, state, TextSelector::Pattern)
        ValuedOption.STAGE -> TextFilterOptions.addTextFilter(option.token, value, index, state.stageNames)
        ValuedOption.STEP -> TextFilterOptions.addTextFilter(option.token, value, index, state.stepNames)
        ValuedOption.KIND -> TextFilterOptions.addTextFilter(option.token, value, index, state.eventKinds)
    }
}

/**
 * Values that name a closed vocabulary or a count, and therefore have exactly one right answer.
 * Every function here either resolves the token into a typed field of [ParseState] or refuses it by
 * name. None of them clamps, defaults or falls through: an unrecognised name in a closed vocabulary
 * is the caller's typo, and answering it with something plausible is how `--limit abc` became 100.
*/
private object ClosedValueOptions {
    fun applySandboxProfile(value: String, index: Int, state: ParseState): ApplyOutcome {
        state.sandboxProfile = when (value) {
            "none" -> SandboxProfile.NONE
            "local" -> SandboxProfile.LOCAL
            "os" -> return ApplyOutcome.Rejected(CliError.UnsupportedSandboxProfile(value))
            else -> return ApplyOutcome.Rejected(CliError.InvalidSandboxProfile(value))
        }
        return ApplyOutcome.Applied(index + 2)
    }

    fun applyView(value: String, index: Int, state: ParseState): ApplyOutcome =
        // Resolved HERE, not at the effect boundary: an unusable view is rejected before a process,
        // a store or a journal is created.
        when (val resolved = resolveView(value, state.deliverableViews)) {
            is ViewParseResult.Parsed -> {
                state.view = resolved.view
                ApplyOutcome.Applied(index + 2)
            }
            is ViewParseResult.Invalid ->
                ApplyOutcome.Rejected(CliError.InvalidView(resolved.value))
            is ViewParseResult.Unavailable ->
                ApplyOutcome.Rejected(CliError.UnavailableView(resolved.view))
        }

    fun applyFormat(value: String, index: Int, state: ParseState): ApplyOutcome =
        when (val resolved = ObservationFormat.parseFormat(value)) {
            is FormatParseResult.Parsed -> {
                state.format = resolved.format
                ApplyOutcome.Applied(index + 2)
            }
            is FormatParseResult.Invalid ->
                ApplyOutcome.Rejected(CliError.InvalidFormat(resolved.value))
        }

    fun applyOutcomeToken(value: String, index: Int, state: ParseState): ApplyOutcome {
        val outcome = ObservedOutcome.fromToken(value)
            ?: return ApplyOutcome.Rejected(CliError.InvalidOutcome(value))
        state.outcomes += outcome
        return ApplyOutcome.Applied(index + 2)
    }

    fun applyChannelToken(value: String, index: Int, state: ParseState): ApplyOutcome {
        val channel = OutputChannel.fromToken(value)
            ?: return ApplyOutcome.Rejected(CliError.InvalidChannel(value))
        state.channels += channel
        return ApplyOutcome.Applied(index + 2)
    }

    fun applyTailBytes(value: String, index: Int, state: ParseState): ApplyOutcome {
        val bytes = value.toLongOrNull()
        if (bytes == null || bytes <= 0L) {
            return ApplyOutcome.Rejected(CliError.InvalidTailBytes(value))
        }
        state.tailBytes = bytes
        return ApplyOutcome.Applied(index + 2)
    }

    fun applyLimit(value: String, index: Int, state: ParseState): ApplyOutcome {
        val records = value.toIntOrNull()
        if (records == null || records <= 0) {
            return ApplyOutcome.Rejected(CliError.InvalidLimit(value))
        }
        state.budget = RecordBudget.UpTo(records)
        return ApplyOutcome.Applied(index + 2)
    }
}

/** Free-text values, which accumulate into an OR group and share one refusal for being empty. */
private object TextFilterOptions {
    /**
     * Repeated `--grep` / `--grep-regex` OR into one group; the group's sense (whitelist or
     * blacklist) is decided once, at normalization, so `Only` and `Except` stay distinct cases of
     * [LineSelector] rather than a flag that travels downstream.
*/
    fun addGrep(
        token: String,
        value: String,
        index: Int,
        state: ParseState,
        selector: (String) -> TextSelector,
    ): ApplyOutcome {
        if (value.isEmpty()) {
            return ApplyOutcome.Rejected(CliError.EmptyTextFilter(token))
        }
        state.grepSelectors += selector(value)
        return ApplyOutcome.Applied(index + 2)
    }

    fun addTextFilter(
        token: String,
        value: String,
        index: Int,
        into: MutableSet<String>,
    ): ApplyOutcome {
        if (value.isEmpty()) {
            return ApplyOutcome.Rejected(CliError.EmptyTextFilter(token))
        }
        into += value
        return ApplyOutcome.Applied(index + 2)
    }
}

// ====================================================================== types

/**
 * Typed result of [CliParser.parseConsole].
 * Same shape the verb used locally; promoted to a top-level type so the parser and the verb main
 * share one result algebra. The wire prefixes (`Error:` for argument refusals,
 * `console-refused:` for data-plane refusals) are NOT carried here — they belong to
 * [renderConsoleCliRefusal] and [ConsoleReadService.renderRefusal] respectively, and the parser
 * does not decide what the caller sees on stderr.
*/
sealed interface ConsoleCliParseResult {
    data class Admitted(val request: ConsoleReadRequest) : ConsoleCliParseResult
    data class Rejected(val refusal: ConsoleCliRefusal) : ConsoleCliParseResult
}

/**
 * Closed ADT of what `pipelinek events` may emit per row.
 * Same shape [MainEventsCli.RowProjection] had locally; promoted to a top-level type because the
 * parser and the verb main now share the type. The two cases are deliberately separate: a future
 * projection (e.g. a CSV shape) is a compile-time break here and at every consumer of this ADT,
 * which is the discipline that keeps `--typed` from re-introducing a third wire shape.
*/
sealed interface EventsRowProjection {
    /** The envelope wire form. Byte-for-byte unchanged by `--typed` */
    data object Envelope : EventsRowProjection

    /** The stored [dev.rubentxu.pipeline.v2.events.DomainEvent], one JSON object per line. */
    data object Typed : EventsRowProjection
}

/**
 * Typed arguments [CliParser.parseEvents] hands the verb main.
 * Every field is the value the runtime needs to issue a [dev.rubentxu.pipeline.v2.events.identity.EventQuery],
 * a follow, or a typed projection; nothing here is a positional placeholder. The fields mirror
 * the legacy `MainArgs.Parsed` shape, with no semantic change. Rejecting events on any of these
 * Would be a separate audit; is relocation, not relaxation
*/
data class EventsCliArgs(
    val db: String,
    val runId: String,
    val kind: String?,
    val subjectCanonical: String?,
    val limit: Int,
    val afterCursor: String?,
    val projection: EventsRowProjection,
    val follow: Boolean,
    val fieldsWhitelist: List<EventField>?,
    val contextWindow: Int,
    val tailCount: Int,
)

/**
 * Typed result of [CliParser.parseEvents].
 * Same two-case shape as the verb's local `MainArgs`. The verb main translates a [Rejected] into
 * `Error: <reason>` on stderr; a [Parsed] flows into the typed [runWithArgs] path the events
 * Verb has used since
*/
sealed interface EventsCliParseResult {
    data class Parsed(val args: EventsCliArgs) : EventsCliParseResult
    data class Rejected(val reason: String) : EventsCliParseResult
}
