package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.CompiledObservationQuery
import dev.rubentxu.pipeline.v2.application.observation.LineSelector
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.observation.ObservationQuery
import dev.rubentxu.pipeline.v2.application.observation.ObservedOutcome
import dev.rubentxu.pipeline.v2.application.observation.RecordBudget
import dev.rubentxu.pipeline.v2.application.observation.TextSelector
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import dev.rubentxu.pipeline.v2.domain.CorrelationId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import dev.rubentxu.pipeline.v2.events.identity.EventField

// The contract every CLI verb shares.
//
// One file for the shapes the verbs agree on: the flag set, the refusals, the
// typed results. A verb parser never redeclares what another verb already
// decided, and the split into one parser per verb means a verb can change its
// own arguments without touching anybody else's file.
//
// The verb parsers are `CliExecutionParser` (run/validate),
// `CliObservationParser` (observe), `CliInspectParser` (inspect),
// `CliEventsParser` (events) and `CliConsoleParser` (console). The option
// vocabulary they all read lives in `CliOptionVocabulary`.

const val NON_CANONICAL_CANONICAL_BRIDGE_ERROR: String =
    "Error: script uses non-canonical plugins; canonical bridge requires " +
        "core.sh/core.echo/core.sleep/core.file.writeFile/core.emit.event/core.milestone/" +
        "core.deleteDir/core.cleanWs/core.load/core.pwd/core.waitUntil."

/** The finite set of commands accepted by the application CLI. */
enum class CliCommand {
    VALIDATE,
    RUN,
}

/**
 * A command the parser admits, and the line the help prints for it.
 */
internal data class CliParserCommand(val command: CliCommand, val summary: String)

/**
 * The commands this CLI admits.
 *
 * The parser reads its spellings from here, so a command cannot be admitted
 * under one name and described under another. The help reads the same table,
 * which is why the summary travels with the spelling: the two surfaces were
 * free to disagree, and the help lost a form because of it.
 */
internal val CLI_PARSER_COMMANDS: Map<String, CliParserCommand> = mapOf(
    "validate" to CliParserCommand(
        command = CliCommand.VALIDATE,
        summary = "compile a script and report every diagnostic",
    ),
    "run" to CliParserCommand(
        command = CliCommand.RUN,
        summary = "execute a script durably (--db, --resume|--rerun, --control-root)",
    ),
)

/** Typed CLI flags produced by [CliExecutionParser.parse]. */
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
     *
     * ## The KDoc that used to say `channel` could not exist
     *
     * This field once carried the line "channel is deliberately absent: no durable carrier exists
     * for stdout vs stderr, so offering the dimension would be a filter that cannot filter."
     * That claim became false when the merge was removed and two independent pumps
     * began attributing every committed range to its channel, durably. What was left behind was the
     * worse half: [ObservationQuery.channels] had been implemented and consumed all along, so the
     * dimension was REAL and UNREACHABLE — a filter nobody could type. The correction is not a new
     * field here, it is the removal of a sentence that had stopped being true.
     * [ObservationQuery] carries the same history and what it means for a record that has no
     * channel to carry.
*/
    val query: ObservationQuery = ObservationQuery(),
)

/**
 * Typed failures for CLI admission.
 *
 * A case here is a VALUE: it says what went wrong and it carries the tokens
 * that went wrong. The words the operator reads are NOT here — they are
 * rendered by [toCliExit], the one mapping from an error to a sentence, so
 * that every verb refuses the same mistake the same way.
 *
 * This type deliberately carries no `toString()` of its own. A second
 * rendering is free to disagree with the first, and it did: `observe` printed
 * `UnknownOption(value=--bogus-flag)` where `run` printed `invalid option`.
 */
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
    data class ConflictingWorkspaceModes(val workspace: String) : CliError
    data class InvalidSandboxProfile(val value: String) : CliError
    /**
     * A sandbox profile this build does not carry.
     *
     * The fail-closed surface cites the contract documents the operator expects
     * to see at the boundary (sandbox policy, OS-level isolation, multi-tenant
     * gate). The value token echoes the rejected input verbatim so log scrapers
     * can correlate. The sentence the operator reads is rendered by
     * [toCliExit], which is the one place a [CliError] becomes words.
     */
    data class UnsupportedSandboxProfile(val value: String) : CliError
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
    data class TrailingOption(val value: String) : CliError

    /** `--view` named a value that is not a view. */
    data class InvalidView(val value: String) : CliError

    /**
     * `--view` named a real view this build cannot deliver.
     * Refused rather than downgraded: silently substituting another view tells
     * the operator they observed something they did not observe.
*/
    data class UnavailableView(val view: ObservationView) : CliError

    /** `--format` named a value that is not a format. */
    data class InvalidFormat(val value: String) : CliError

    /**
     * `--grep` or `--grep-regex` was given an empty value.
     * Refused rather than resolved: under substring semantics an empty literal
     * matches EVERY line, so accepting it would turn a typo into a filter that
     * silently keeps everything.
*/
    data class EmptyTextFilter(val option: String) : CliError

    /** `--grep-invert` with no `--grep` to invert. There is no group to negate. */
    data object InvertWithoutGrep : CliError

    /**
     * `--channel` named no channel.
     * Refused rather than dropped, for the same reason [EmptyTextFilter] is: a token that matches
     * nothing would silently become a filter that keeps everything, and the next run would look
     * like a data problem instead of a typo. [OutputChannel.fromToken] answers `null` rather than
     * throwing because a durable store may name a channel this build does not know, and that is a
     * fact a reader can act on — but a human typing a flag is not reading a store, and gets a name.
*/
    data class InvalidChannel(val value: String) : CliError

    /**
     * A flag that changes what a run DOES was passed to a verb that only reads.
     * Refused rather than ignored, and the wording matters: `--resume` on a reader has no meaning
     * at all, so the user is not being denied a feature — they are being told that the verb they
     * reached for is not the one that does this.
*/
    data class OptionNotReadable(val option: String) : CliError

    /**
     * The assembled query is not usable — most often an uncompilable
     * `--grep-regex`. Caught here so no process, store or journal is created
     * for a run that could never be filtered as asked.
*/
    data class InvalidQuery(val reason: String) : CliError

    /**
     * `--follow` in a format that has no continuation.
     * Refused because the combination has no honest answer, not because it is unusual: a document
     * format is complete only when its closing bracket arrives, and following is by definition
     * stopping before you know whether that has happened.
*/
    data class FollowNeedsAnIncrementalFormat(val format: String) : CliError

    /**
     * A reader's option reached `run`.
     * The inverse of [OptionNotReadable], and refused for a sharper reason than symmetry. A flag that
     * is parsed, remembered and never read is a dead semantic parameter; `--follow` on `run` is
     * exactly that, because `run` already reads its run as it happens. `--limit` is worse than dead:
     * it would truncate the live transcript and `run` has no way to say the transcript was
     * truncated, which is the failure a reader that reports itself complete already causes elsewhere.
*/
    data class OptionBelongsToObserve(val option: String) : CliError

    /**
     * `--tail-bytes` on a view that carries no process bytes.
     * Refused rather than ignored for the reason `--follow` on `run` is: a flag that changes nothing
     * observable is a parameter the caller believed in. `console` is the only view whose records are
     * bytes, so it is the only one where a byte budget has anything to bound.
*/
    data class TailNeedsTheOutputLane(val view: String) : CliError

    data class InvalidTailBytes(val value: String) : CliError

    /**
     * `--outcome` that names no outcome.
     * Refused rather than treated as a wildcard, because the whole point of typing the vocabulary
     * was that `--outcome` stops answering "which spelling did this producer use". A typo that
     * silently matched everything would hand the caller the opposite of what they asked for.
*/
    data class InvalidOutcome(val value: String) : CliError

    /**
     * `--limit` that is not a count.
     * Refused rather than defaulted, and the legacy `events` verb already learned this the hard way:
     * `--limit abc` used to read as 100, so a typo silently became a different query than the one
     * the caller believed they were making.
*/
    data class InvalidLimit(val value: String) : CliError

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
    data class OptionValueIsAnOption(val option: String, val value: String) : CliError

    /**
     * A positional argument in a request whose verb owns none.
     *
     * Refused rather than dropped, for the reason [OptionValueIsAnOption] is:
     * a token the caller typed and the verb silently discarded is a request
     * the verb answered in a way nobody asked for. Measured on the built
     * distribution, `doctor foo`, `version foo`, `capabilities foo` and
     * `sdk-info foo` all answered 0 and printed exactly what the bare verb
     * prints — a confident answer about a request that was never made.
     *
     * A positional argument is not an option, so it does not reuse
     * [UnknownOption]: the token has to be named as what it is, or the
     * operator is sent looking for a flag they never typed.
     */
    data class UnexpectedPositional(val value: String) : CliError

    /**
     * A positional argument BEYOND the ones the verb owns.
     *
     * This is not the same fact as [UnexpectedPositional]: there the verb
     * owns NO positional argument, and here it owns exactly one and the
     * request carries another. The refusal has to say a different thing,
     * because the operator of a verb that takes one positional has to be
     * told that the verb takes ONE, not none.
     *
     * Measured on the built distribution, `operations <runId> extra-arg`
     * published the operations of `<runId>` and said nothing at all about
     * `extra-arg`: the operator asked about two runs and read an answer about
     * one. Among the verbs that read through the admission, `operations` was
     * the only one that swallowed one.
     *
     * The verbs that read through the shared parser's script-path entry point
     * — `run` and `validate` — swallowed one the same way: measured, `run
     * s.kts extra-arg` RAN the pipeline and said nothing about `extra-arg`,
     * and `validate s.kts extra-arg` answered "VALIDATION SUCCESSFUL" with
     * the byte-identical output of the request that carries no extra
     * argument. `observe`, `inspect`, `runs cancel`, `saga inspect` and
     * `events` each refuse one, each with its own sentence.
     *
     * The token is named for the same reason it is named in
     * [UnexpectedPositional]: it is what the operator has to remove.
     */
    data class TooManyPositionals(val value: String) : CliError

    /**
     * A required argument the request did not carry.
     *
     * The verbs that refuse a request used to name the absence in their own
     * words — "a run id is required." — and the shared table had no way to
     * say it, because the case did not exist. The words the operator reads
     * belong to the table, so the table grows the case that carries the fact.
     *
     * [why] is the CONSEQUENCE of the absence and it renders as a hint: it
     * says what the argument was FOR, never what form to write it in.
     */
    data class MissingRequiredArgument(val argument: String, val why: String? = null) : CliError

    /**
     * A value an option cannot carry.
     *
     * Distinct from [InvalidFormat] and its siblings, which name a CLOSED
     * VOCABULARY this build publishes — views, formats, channels, outcomes —
     * and whose hint enumerates the accepted values. That vocabulary is the
     * observation family's, and reusing those cases for a verb whose options
     * are its own would publish a list the verb does not accept. This case
     * carries the reason the request gave, and no enumeration it would have
     * to keep in step with.
     */
    data class InvalidOptionValue(val option: String, val reason: String) : CliError

    /**
     * Two options that cannot be honoured together.
     *
     * Refused rather than resolved by a precedence: a combination with no
     * honest answer has to say so, because a silent precedence answers a
     * request nobody made.
     */
    data class InvalidCombination(val reason: String) : CliError

    /**
     * An option another verb owns.
     *
     * [OptionBelongsToObserve] is the instance of this for the reader family,
     * and it names one fixed owner. This one names the owner the request
     * named, because the runs family has two verbs that own each other's
     * options: `--reason` belongs to `runs cancel` and `--dry-run` belongs to
     * `runs recover`. A refusal that named the wrong owner would send the
     * operator to the verb that cannot do what they asked.
     */
    data class OptionBelongsToVerb(val option: String, val owner: String, val why: String) : CliError

    /**
     * A subcommand the family does not have.
     *
     * The family word ALONE is a different fact — a MISSING subcommand, not
     * an unknown one — and it says so through [MissingRequiredArgument].
     */
    data class UnknownSubcommand(val value: String, val expected: List<String>) : CliError
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
        /**
         * The run this request is about, read from the ONE positional argument this verb
         * owns. Null means the operator wrote none.
         *
         * It travels HERE and not in the verb because the parser is the only place that
         * knows which options carry a value: the run id is whichever argument is not an
         * option and not an option's value, and a verb that looked for it by position
         * would have to guess.
         */
        val runId: String?,
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

/**
 * Typed result of [CliConsoleParser.parse].
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
 * Same shape the events verb used to carry in a nested type; promoted to a top-level type because the
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
 * Typed arguments [CliEventsParser.parse] hands the verb main.
 * Every field is the value the runtime needs to issue a [dev.rubentxu.pipeline.v2.events.identity.EventQuery],
 * a follow, or a typed projection; nothing here is a positional placeholder. The fields mirror
 * the legacy `MainArgs.Parsed` shape, with no semantic change. Rejecting events on any of these
 * would be a separate audit; this is relocation, not relaxation.
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
 * Typed result of [CliEventsParser.parse].
 * Same two-case shape as the verb's local `MainArgs`. The verb main translates a [Rejected] into
 * `Error: <reason>` on stderr; a [Parsed] flows into the typed [runWithArgs] path the events
 * verb has always used.
 */
sealed interface EventsCliParseResult {
    data class Parsed(val args: EventsCliArgs) : EventsCliParseResult
    data class Rejected(val reason: String) : EventsCliParseResult
}
