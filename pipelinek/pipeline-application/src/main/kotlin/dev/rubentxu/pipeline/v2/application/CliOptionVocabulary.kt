package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.FormatParseResult
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.observation.ObservedOutcome
import dev.rubentxu.pipeline.v2.application.observation.RecordBudget
import dev.rubentxu.pipeline.v2.application.observation.TextSelector
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import dev.rubentxu.pipeline.v2.application.observation.ViewParseResult
import dev.rubentxu.pipeline.v2.application.observation.resolveView
import dev.rubentxu.pipeline.v2.domain.isolation.Admission
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile

// The vocabulary of CLI options.
//
// The only place that decides what an option MEANS and how many arguments it
// takes. `run` and `observe` read the same options, so they read them from
// here: a second table of option meanings would be a second authority, and the
// two would drift the moment one of them gained a case.
//
// The tables below are private to this file on purpose. The only surface the
// verb parsers need is [applyOption] (which mutates a [ParseState]) and
// [knownOptionArity] (which the admission of the verbs that do not use this
// parser asks).

/** Outcome of processing one CLI option within [applyOption]. */
internal sealed interface ApplyOutcome {
    /** The option consumed [nextIndex] positions of [args]. */
    data class Applied(val nextIndex: Int) : ApplyOutcome
    /** The parser must reject with [error] and stop. */
    data class Rejected(val error: CliError) : ApplyOutcome
}

/**
 * Mutable accumulator threaded through [applyOption].
 *
 * One constructor argument and the rest of the shape in the body: the state
 * carries many fields, and a constructor with that many parameters is a list
 * of things a reader has to hold in the head to know what the parser is
 * tracking.
 */
internal class ParseState(
    /**
     * Which views THIS parse may deliver.
     * In the state so `--view` keeps ONE handler for every verb: a reader that also reads the output
     * lane widens this before parsing and gets `--view console`, `run` does not and keeps refusing
     * it. Branching on the verb inside the `--view` handler instead would have been a second place
     * where a flag's meaning is decided, which is how one flag ends up meaning two things.
     */
    val deliverableViews: Set<ObservationView> = ObservationView.EVENT_LANE_VIEWS,
    /**
     * What answers "what can this host prove about a run's confinement?".
     *
     * In the state so that every verb that reads the sandbox profile asks the same
     * question of the same provider: a verb that answered with its own idea of what
     * the host can confine would decide the same request two ways.
     *
     * The default is the provider that MEASURES the host. A test that must not
     * depend on the host replaces it with one that answers from a fixture.
     */
    val provider: IsolationProvider = BwrapIsolationProvider,
) {
    var dbPath: String? = null
    var durableRunPolicy: DurableRunPolicy = DurableRunPolicy.ReusePriorRun
    var controlRoot: String? = null
    var workspace: String? = null
    /** `--isolated` requests managed scratch. */
    var isolated: Boolean = false
    var sandboxProfile: SandboxProfile = SandboxProfile.NONE
    /**
     * `--allow-network` permits outbound egress for this run.
     * FALSE by default, so a pipeline that reaches for the network without the
     * flag is rejected at capability admission rather than quietly succeeding.
     */
    var allowNetwork: Boolean = false
    val pluginJars: MutableList<String> = mutableListOf()
    /**
     * The operator's plugin trust file, set by `--plugin-trust <file>`. Naming it turns the
     * trust policy on for this run.
     */
    var pluginTrust: String? = null
    /**
     * `--require-plugin-trust` turns the trust policy from OPT-IN into MANDATORY: a plugin the
     * operator brought that no trust decision approved is refused instead of loaded with a
     * warning.
     *
     * FALSE by default, because the policy is opt-in and a run that names no plugin and no trust
     * file has nothing to verify. Naming the flag is what makes the absence of a decision a
     * refusal: an operator who asks for verification and gets silence has been told nothing.
     */
    var requirePluginTrust: Boolean = false
    /**
     * External invoker correlation token, set by
     * `--correlation-id <uuid>`. The raw string lives here; conversion
     * to a typed [CorrelationId] happens after parsing succeeds. A blank
     * value is refused at the boundary as [CliError.MissingOptionValue],
     * the same path every other valued flag takes, so the conversion
     * never crashes the parser.
     */
    var correlationId: String? = null
    /**
     * `--receipt <path>`. Where the run writes its receipt: what it concluded and what it
     * wrote, in one document a consumer reads instead of asking the product again.
     *
     * Absent means the run writes nothing. The receipt is opt-in because a run that wrote one
     * unasked would leave a file in a directory the operator never named.
     */
    var receiptPath: String? = null
    /**
     * Every `--output-protocol`
     * token the operator supplied. The last one wins. Validation
     * against [dev.rubentxu.pipeline.v2.application.OutputProtocol.SUPPORTED_IDS]
     * happens in Main.kt — a missing or unknown id surfaces as
     * `CliExit.InvalidArgument` (exit 2), never as silent
     * degradation.
     */
    var outputProtocolTokens: List<String> = emptyList()
    /** `--view`. Resolved to a DELIVERABLE view at parse time */
    var view: ObservationView = ObservationView.NORMAL
    /**
     * `--limit N`. A read-side budget over SELECTED records, never a filter: see [RecordBudget].
     */
    var budget: RecordBudget = RecordBudget.All
    /**
     * `--outcome`. A closed vocabulary, so unknown names are refused rather than carried.
     * A plain `mutableSetOf` and not `sortedSetOf` like `channels`: the order a set of outcomes is
     * collected in is not information, and the ADT is not `Comparable` because nothing needs it to
     * be. Asking for it anyway would have put a phantom requirement in the parser.
     */
    val outcomes: MutableSet<ObservedOutcome> = mutableSetOf()
    /**
     * `--tail-bytes N`. Where the output lane STARTS, not what it prints.
     * A start position and a budget are different things and compose: read the last N bytes, then
     * print at most `--limit` of what that yields.
     */
    var tailBytes: Long? = null
    /**
     * `--follow`. Read-only and therefore legal here and on `observe`, unlike the execution options.
     * A flag rather than a mode: following is how long you read, not what you read, and the view
     * still decides the lane.
     */
    var follow: Boolean = false
    /**
     * `--compile-only`. The validation compiles the script and does NOT run it.
     *
     * It belongs to `validate` and to nothing else: `run` executes the script,
     * so "compile it without running it" is not a run the operator can ask for.
     * The parser refuses it on `run` by naming the verb that owns it.
     */
    var compileOnly: Boolean = false
    /** `--format`. JSON/JSONL are opt-in; text is the default */
    var format: ObservationFormat = ObservationFormat.TEXT
    /**
     * Query dimensions. AND across dimensions, OR within each one
     * [grepSelectors] accumulates repeated `--grep`/`--grep-regex` into an OR
     * group; [grepInvert] turns that whole group into a blacklist at build time.
     * No [LineSelector] is stored here: the parser collects raw values and the
     * normalized ADT is produced once, by [buildObservationQuery].
     */
    val grepSelectors: MutableList<TextSelector> = mutableListOf()
    var grepInvert: Boolean = false
    val stageNames: MutableSet<String> = sortedSetOf()
    val stepNames: MutableSet<String> = sortedSetOf()
    val eventKinds: MutableSet<String> = sortedSetOf()
    /**
     * Collected as the parsed [OutputChannel], never as the raw token.
     * The parser is the boundary where a human's string becomes a domain value, so this is where an
     * unknown token is refused. Storing `stdout` as a `String` and letting [ObservationQuery] decide
     * would push the vocabulary check downstream, where a miss reads as "no stderr in this run"
     * instead of "that was not a channel".
     */
    val channels: MutableSet<OutputChannel> = sortedSetOf()
}

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
 * The arity of every option is carried by the [FlagOptions] / [ValuedOptions] tables
 * above: a flag never takes a value, and a valued option never swallows a flag.
 * **The arity of a row is still observed by nothing.** The row-per-row record of the arity
 * was removed with the rest of the test suite. [CliVerbOptionContractTest] observes WHICH
 * verb admits an option and WHICH refuses it, and that is a different fact: a row that
 * flipped between flag and valued would still be admitted by the same verb, and the test
 * would read the change as a refusal with the wrong name, never as the arity itself.
 * Shared with [CliObservationParser.parse] on purpose: one table of what each option means, whatever verb
 * is being parsed.
*/
internal fun applyOption(
    option: String,
    args: Array<String>,
    index: Int,
    state: ParseState,
): ApplyOutcome {
    val recognised = recognise(option)
        ?: return ApplyOutcome.Rejected(CliError.UnknownOption(option))
    return when (recognised) {
        is RecognisedOption.Flag -> FlagOptions.apply(recognised.option, index, state)
        is RecognisedOption.Valued -> when (val outcome = readOptionValue(option, args, index + 1)) {
            is OptionValueOutcome.Present -> ValuedOptions.apply(recognised.option, outcome.value, index, state)
            is OptionValueOutcome.Refused -> ApplyOutcome.Rejected(outcome.error)
        }
    }
}

/**
 * The value of a valued option, or the refusal that there is none.
 *
 * Two shapes, so every caller reads them with an exhaustive `when`: the value the
 * operator wrote, or the typed refusal that says the request was not one this verb
 * can read.
 */
internal sealed interface OptionValueOutcome {
    /** The option carried a value this request can use. */
    data class Present(val value: String) : OptionValueOutcome

    /** The option did not carry a value this request can use. */
    data class Refused(val error: CliError) : OptionValueOutcome
}

/**
 * The value of the valued option [token], read from [args] at [valueIndex].
 *
 * ## THE ONE owner of this decision
 *
 * A valued option consumes the next argument. WHICH argument it may consume is a
 * decision, and it is taken HERE, once: every verb reads its values through this
 * function, so the same option means the same thing in every verb.
 *
 * `events --db --limit 5 run-1` names the option that would have swallowed the other
 * one, and `events --kind` with no value is refused instead of being dropped and
 * answered as if it had never been written.
 *
 * ## What it decides, and only this
 *
 * Three facts, and only three:
 *
 *  - the value is NOT THERE -> [CliError.MissingOptionValue];
 *  - the value IS spelled like an option -> [CliError.OptionValueIsAnOption];
 *  - the value IS BLANK and the option names a location -> [CliError.InvalidOptionValue].
 *
 * The scope of the second check is the tokens THIS BUILD knows ([knownOptionArity])
 * plus the tokens the calling verb owns ([ownedTokens]). A token that names nothing
 * is far more likely to be a path than a mistyped flag, so a value that is not an
 * option of this build is accepted: refusing every value that begins with `--`
 * would make `--grep --verbose-stop` unexpressible, trading one silent drop for a
 * new surprise.
 *
 * ## Why the THIRD fact is here and not downstream
 *
 * An option that names a location, given an empty value, is a request for a path
 * the operator did not name. The filesystem resolves it to the working directory
 * and the storage engine reads it as a temporary database, so the refusal that
 * comes back is a fact about the filesystem or about the engine — and it names a
 * path the operator never wrote. Decided HERE, it is a fact about the REQUEST,
 * and it lands before anything is opened. The rule and the set of options it
 * covers are [LOCATION_NAMING_OPTIONS] and [blankNamedValueRefusal].
 *
 * @param valueIndex the index of the argument that holds the value: the one AFTER
 *   the option.
 * @param ownedTokens the tokens the calling verb owns, beyond the ones this build knows.
 */
internal fun readOptionValue(
    token: String,
    args: Array<String>,
    valueIndex: Int,
    ownedTokens: Set<String> = emptySet(),
): OptionValueOutcome {
    val value = args.getOrNull(valueIndex)
        ?: return OptionValueOutcome.Refused(CliError.MissingOptionValue(token))
    if (knownOptionArity(value) != null || value in ownedTokens) {
        return OptionValueOutcome.Refused(CliError.OptionValueIsAnOption(token, value))
    }
    if (token in LOCATION_NAMING_OPTIONS && value.isBlank()) {
        return OptionValueOutcome.Refused(blankNamedValueRefusal(token))
    }
    return OptionValueOutcome.Present(value)
}

/** The execution-only half of each table. Split by arity because the two enums are distinct types. */
private val EXECUTION_ONLY_FLAGS: List<FlagOption> =
    listOf(
        FlagOption.RESUME,
        FlagOption.RERUN,
        FlagOption.ISOLATED,
        FlagOption.ALLOW_NETWORK,
        // The operator's demand that every plugin they brought carries a trust decision. A
        // reader loads no plugin, so the demand means nothing to it: refused by name rather
        // than accepted and dropped.
        FlagOption.REQUIRE_PLUGIN_TRUST,
    )

private val EXECUTION_ONLY_VALUED: List<ValuedOption> =
    listOf(
        ValuedOption.WORKSPACE,
        ValuedOption.SANDBOX_PROFILE,
        ValuedOption.PLUGIN_JAR,
        // The receipt is the run's OWN account of itself, so only a verb that runs can carry
        // it. A reader that accepted it would be promising a file it never writes.
        ValuedOption.RECEIPT,
    )

internal val EXECUTION_ONLY_OPTIONS: Set<String> =
    (EXECUTION_ONLY_FLAGS.map { it.token } + EXECUTION_ONLY_VALUED.map { it.token }).toSet()

/**
 * The validation-only half of each table.
 *
 * `--compile-only` asks a validation to compile the script WITHOUT running the
 * author's body. A reader cannot honour it — there is no script to compile —
 * so the reader refuses it by name rather than dropping it: an option that is
 * silently ignored is an option the operator believed in.
 *
 * It is a set of its own and not a member of [EXECUTION_ONLY_OPTIONS] because
 * that name says what it is: the options only a verb that RUNS may carry, and
 * this one belongs to the verb that VALIDATES.
 */
private val VALIDATION_ONLY_FLAGS: List<FlagOption> = listOf(FlagOption.COMPILE_ONLY)

internal val VALIDATION_ONLY_OPTIONS: Set<String> =
    VALIDATION_ONLY_FLAGS.map { it.token }.toSet()

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

    /**
     * Compile the script and do NOT run it.
     *
     * A BOOLEAN with no value, like the other flags: the only question is
     * whether this validation evaluates the author's body, and a
     * `--compile-only=<something>` spelling would invite a mode this runtime
     * does not implement.
     */
    COMPILE_ONLY("--compile-only"),

    /**
     * Every plugin the operator brought has to carry a trust decision.
     *
     * A BOOLEAN with no value, like the other flags: the question is whether an
     * unverified plugin may be loaded at all, and a spelling that took a value would
     * invite a per-plugin policy this runtime does not implement.
     */
    REQUIRE_PLUGIN_TRUST("--require-plugin-trust"),
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
    // Where the run writes its receipt: what it concluded and what it wrote, in one document.
    // Only a run may carry it; the readers refuse it by naming the verb that owns it.
    RECEIPT("--receipt"),
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

/** Look the token up. `null` means this build has no such option, and that is an error, not a skip. */
private fun recognise(option: String): RecognisedOption? =
    FlagOption.BY_TOKEN[option]?.let { RecognisedOption.Flag(it) }
        ?: ValuedOption.BY_TOKEN[option]?.let { RecognisedOption.Valued(it) }

/**
 * Whether [token] is an option this build knows, and whether it carries a value.
 *
 * `null` when this build has no such option. The answer comes from the one
 * table of the CLI's option surface, so a verb that owns a token and the
 * shared parser that also knows it cannot disagree about what that token is.
 */
internal fun knownOptionArity(token: String): Boolean? = when (recognise(token)) {
    is RecognisedOption.Flag -> false
    is RecognisedOption.Valued -> true
    null -> if (token in VERB_OWNED_TOKENS) true else null
}

/**
 * The tokens this build KNOWS that the shared table does not admit.
 *
 * `--endpoint` belongs to `inspect --state`, and the shared parser does not
 * carry it: a reader that admitted it would be promising a service it does
 * not talk to.
 *
 * It is known all the same, and that is the whole point of the set: a VALUE
 * spelled like it is refused by [readOptionValue] in every verb, instead of
 * being swallowed by the option written before it. A token nobody can spell
 * as a value is a token that cannot be passed off as a path.
 */
private val VERB_OWNED_TOKENS: Set<String> = setOf("--endpoint")

/** Options with no value. Their outcome never depends on what follows them. */
private object FlagOptions {
    fun apply(option: FlagOption, index: Int, state: ParseState): ApplyOutcome = when (option) {
        FlagOption.RESUME -> setDurablePolicy(DurableRunPolicy.ResumePriorRun, index, state)
        FlagOption.RERUN -> setDurablePolicy(DurableRunPolicy.StartFreshRun, index, state)
        FlagOption.ISOLATED -> flag(index) { state.isolated = true }
        FlagOption.ALLOW_NETWORK -> flag(index) { state.allowNetwork = true }
        FlagOption.GREP_INVERT -> flag(index) { state.grepInvert = true }
        FlagOption.FOLLOW -> flag(index) { state.follow = true }
        FlagOption.COMPILE_ONLY -> flag(index) { state.compileOnly = true }
        FlagOption.REQUIRE_PLUGIN_TRUST -> flag(index) { state.requirePluginTrust = true }
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
        // A path. Existence is NOT checked here: the path a receipt goes to is a
        // legitimate thing to name before it exists, and the effect boundary
        // reports it when the write fails.
        ValuedOption.RECEIPT -> {
            if (value.isBlank()) {
                ApplyOutcome.Rejected(CliError.MissingOptionValue("--receipt"))
            } else {
                state.receiptPath = value
                ApplyOutcome.Applied(index + 2)
            }
        }

        // Collect every supplied
        // --output-protocol token in the order the operator passed them.
        // The list is the client's priority for the negotiation. Resolution
        // (intersection with OutputProtocol.SUPPORTED_IDS, fail-closed on
        // no intersection) lives in OutputProtocolDecision.kt#negotiateOutputProtocol.
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
    /**
     * The sandbox profile the operator named, and whether this build can serve it.
     *
     * The word is not the decision. The word says what the operator ASKS for,
     * and the pure core says what can be done with it: the request for a real
     * boundary is refused because this build ships no isolation provider, and
     * the refusal names the capabilities that request would have required.
     *
     * The decision is asked HERE, while the request is being read, so a refusal
     * has read no script, evaluated no Kotlin and started no child.
     */
    fun applySandboxProfile(value: String, index: Int, state: ParseState): ApplyOutcome {
        val asked = ExecutionAdmission.profileRequestFor(value)
            ?: return ApplyOutcome.Rejected(CliError.InvalidSandboxProfile(value))
        // The provider is asked BEFORE the verdict, and it is asked only about a
        // request this build could run and that demands a boundary: the legacy words
        // demand nothing beyond the behaviour this product has always had, and the
        // word 'os' has nowhere to run, so neither of them measures the host.
        val admission = ExecutionAdmission.decide(
            asked.request,
            ExecutionAdmission.attestationFor(asked, state.provider),
        )
        return when (admission) {
            is Admission.Allowed -> {
                // A request this build admits carries a runtime profile. A word
                // without one has no way to run, and it is refused by name here
                // instead of falling back to the profile that confines nothing:
                // the request does not run unconfined.
                val profile = asked.profile
                    ?: return ApplyOutcome.Rejected(CliError.UnsupportedSandboxProfile(value))
                state.sandboxProfile = profile
                ApplyOutcome.Applied(index + 2)
            }
            is Admission.Denied -> ApplyOutcome.Rejected(
                ExecutionAdmission.refusalFor(admission.reason, asked.request),
            )
        }
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
