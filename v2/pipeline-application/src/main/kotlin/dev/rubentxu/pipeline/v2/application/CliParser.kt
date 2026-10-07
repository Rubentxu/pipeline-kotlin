package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.SelectorCompileResult
import dev.rubentxu.pipeline.v2.application.observation.LineSelector
import dev.rubentxu.pipeline.v2.application.observation.compileQuery
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.observation.ObservationQuery
import dev.rubentxu.pipeline.v2.application.observation.TextSelector
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import dev.rubentxu.pipeline.v2.application.observation.ViewParseResult
import dev.rubentxu.pipeline.v2.application.observation.resolveView
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile

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
    /** WU-LPR-062: project workspace base directory (--workspace). */
    val workspace: String? = null,
    /**
     * RP034-H / ADR-0101: request the historical PipelineK-managed scratch
     * workspace instead of the default attached invocation directory.
     *
     * Mutually exclusive with [workspace]; the parser rejects the combination
     * rather than applying a silent precedence rule.
     */
    val isolated: Boolean = false,
    val sandboxProfile: SandboxProfile = SandboxProfile.NONE,
    /**
     * RP6-C / LFC-2E3 (`--allow-network`): whether this run may open outbound
     * connections. FALSE unless the operator asks for it.
     *
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
     * ADR-0088 / `CLI_OBSERVABILITY_SPEC.md` §1: which families of observation
     * reach the reader. Orthogonal to [format]; neither changes execution.
     */
    val view: ObservationView = ObservationView.NORMAL,
    /**
     * ADR-0088: wire encoding. `TEXT` is human and the default; JSON and JSONL
     * are opt-in, so a machine consumer must ask for them explicitly instead of
     * discovering them by accident.
     */
    val format: ObservationFormat = ObservationFormat.TEXT,
    /**
     * Normalized read-side selection (ADR-0088). Identity by default.
     *
     * ## The KDoc that used to say `channel` could not exist
     *
     * This field once carried the line "channel is deliberately absent: no durable carrier exists
     * for stdout vs stderr, so offering the dimension would be a filter that cannot filter."
     *
     * That claim became false at OBS-C2.3, when the merge was removed and two independent pumps
     * began attributing every committed range to its channel, durably. What was left behind was the
     * worse half: [ObservationQuery.channels] had been implemented and consumed all along, so the
     * dimension was REAL and UNREACHABLE — a filter nobody could type. The correction is not a new
     * field here, it is the removal of a sentence that had stopped being true.
     *
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
     * RP034-H / ADR-0101 clause 3.4: `--isolated` and `--workspace` request two
     * different workspace origins. Applying a silent precedence would let a user
     * believe they attached a workspace while running in scratch, so the
     * combination fails closed at admission.
     */
    data class ConflictingWorkspaceModes(val workspace: String) : CliError {
        override fun toString(): String =
            "ConflictingWorkspaceModes(workspace=$workspace): --isolated requests a " +
                "PipelineK-managed scratch workspace while --workspace attaches a " +
                "user directory; choose one (ADR-0101 clause 3.4)."
    }
    data class InvalidSandboxProfile(val value: String) : CliError
    data class UnsupportedSandboxProfile(val value: String) : CliError {
        // D-012: enrich the typed error message with cross-references so the
        // fail-closed CLI surface cites the contract documents the operator
        // expects to see at the boundary (ADR-0016 sandbox policy, M5 OS-level
        // isolation, M9 multi-tenant gate). The value token echoes the rejected
        // input verbatim so log scrapers can correlate.
        override fun toString(): String =
            "UnsupportedSandboxProfile(value=$value, ref=ADR-0016/M5/M9: " +
                "'os' requires OS-level isolation which is out of scope for L3; " +
                "use 'none' or 'local' instead.)"
    }
    data class UnknownOption(val value: String) : CliError
    data object MissingScriptPath : CliError

    /**
     * An option appeared AFTER the script path.
     *
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
     *
     * Refused rather than downgraded: silently substituting another view tells
     * the operator they observed something they did not observe.
     */
    data class UnavailableView(val view: ObservationView) : CliError {
        override fun toString(): String =
            "UnavailableView($view): this build cannot deliver the '$view' view. " +
                "Process transcript belongs to `pipeline console`; `full` interleaving " +
                "has no decided rule yet (CLI_OBSERVABILITY_SPEC §11)."
    }

    /** `--format` named a value that is not a format. */
    data class InvalidFormat(val value: String) : CliError {
        override fun toString(): String =
            "InvalidFormat($value): expected one of " +
                ObservationFormat.entries.joinToString(", ") { it.wire }
    }

    /**
     * `--grep` or `--grep-regex` was given an empty value.
     *
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
     *
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
     * The assembled query is not usable — most often an uncompilable
     * `--grep-regex`. Caught here so no process, store or journal is created
     * for a run that could never be filtered as asked.
     */
    data class InvalidQuery(val reason: String) : CliError {
        override fun toString(): String = "InvalidQuery: $reason"
    }
}

/**
 * Normalizes collected query values into the typed [ObservationQuery].
 *
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
): ObservationQuery = ObservationQuery(
    stageNames = stageNames.toSet(),
    stepNames = stepNames.toSet(),
    eventKinds = eventKinds.toSet(),
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

sealed interface DurableRunPolicy {
    data object ReusePriorRun : DurableRunPolicy
    data object ResumePriorRun : DurableRunPolicy
    data object StartFreshRun : DurableRunPolicy
}

sealed interface DurableRunSelection {
    val runId: RunId

    data class Reused(override val runId: RunId) : DurableRunSelection
    data class StartedFresh(override val runId: RunId) : DurableRunSelection
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
    /** RP034-H / ADR-0101: `--isolated` requests managed scratch. */
    var isolated: Boolean = false,
    var sandboxProfile: SandboxProfile = SandboxProfile.NONE,
    /**
     * RP6-C / LFC-2E3: `--allow-network` permits outbound egress for this run.
     * FALSE by default, so a pipeline that reaches for the network without the
     * flag is rejected at capability admission rather than quietly succeeding.
     */
    var allowNetwork: Boolean = false,
    val pluginJars: MutableList<String> = mutableListOf(),
    /** ADR-0088 `--view`. Resolved to a DELIVERABLE view at parse time. */
    var view: ObservationView = ObservationView.NORMAL,
    /** ADR-0088 `--format`. JSON/JSONL are opt-in; text is the default. */
    var format: ObservationFormat = ObservationFormat.TEXT,
    /**
     * ADR-0088 query dimensions. AND across dimensions, OR within each one.
     *
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
     *
     * The parser is the boundary where a human's string becomes a domain value, so this is where an
     * unknown token is refused. Storing `stdout` as a `String` and letting [ObservationQuery] decide
     * would push the vocabulary check downstream, where a miss reads as "no stderr in this run"
     * instead of "that was not a channel".
     */
    val channels: MutableSet<OutputChannel> = sortedSetOf(),
)

/** Pure parser for the application CLI. */
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

        // RP034-H / ADR-0101 clause 3.4: fail closed on the incompatible pair.
        state.workspace?.let { explicit ->
            if (state.isolated) {
                return CliParseResult.Rejected(CliError.ConflictingWorkspaceModes(explicit))
            }
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
                view = state.view,
                format = state.format,
                query = buildObservationQuery(
                    grepSelectors = state.grepSelectors,
                    grepInvert = state.grepInvert,
                    stageNames = state.stageNames,
                    stepNames = state.stepNames,
                    eventKinds = state.eventKinds,
            channels = state.channels,
                ),
            ),
        )
    }

    /**
     * Process one CLI option at [index] of [args], mutating [state] and
     * returning the next index or a typed rejection. Extracted from
     * [parse] to keep the loop driver's complexity below the detekt
     * `CyclomaticComplexMethod` threshold.
     */
    private fun applyOption(
        option: String,
        args: Array<String>,
        index: Int,
        state: ParseState,
    ): ApplyOutcome {
        val value = args.getOrNull(index + 1)
            ?: return ApplyOutcome.Rejected(CliError.MissingOptionValue(option))
        return when (option) {
            "--db" -> {
                state.dbPath = value
                ApplyOutcome.Applied(index + 2)
            }
            "--resume" -> if (state.durableRunPolicy != DurableRunPolicy.ReusePriorRun) {
                ApplyOutcome.Rejected(CliError.ConflictingDurablePolicies)
            } else {
                state.durableRunPolicy = DurableRunPolicy.ResumePriorRun
                ApplyOutcome.Applied(index + 1)
            }
            "--rerun" -> if (state.durableRunPolicy != DurableRunPolicy.ReusePriorRun) {
                ApplyOutcome.Rejected(CliError.ConflictingDurablePolicies)
            } else {
                state.durableRunPolicy = DurableRunPolicy.StartFreshRun
                ApplyOutcome.Applied(index + 1)
            }
            "--control-root" -> {
                state.controlRoot = value
                ApplyOutcome.Applied(index + 2)
            }
            "--workspace" -> {
                state.workspace = value
                ApplyOutcome.Applied(index + 2)
            }
            "--isolated" -> {
                state.isolated = true
                ApplyOutcome.Applied(index + 1)
            }
            "--plugin-jar" -> {
                state.pluginJars += value
                ApplyOutcome.Applied(index + 2)
            }
            // RP6-C / LFC-2E3. A BOOLEAN flag with no value, deliberately: the
            // only question is whether this run may egress at all, and a
            // `--allow-network=<something>` spelling would invite a per-host
            // allowlist this runtime does not implement. Until it does, an
            // all-or-nothing switch is the honest surface.
            "--allow-network" -> {
                state.allowNetwork = true
                ApplyOutcome.Applied(index + 1)
            }
            "--sandbox-profile" -> {
                state.sandboxProfile = when (value) {
                    "none" -> SandboxProfile.NONE
                    "local" -> SandboxProfile.LOCAL
                    "os" -> return ApplyOutcome.Rejected(CliError.UnsupportedSandboxProfile(value))
                    else -> return ApplyOutcome.Rejected(CliError.InvalidSandboxProfile(value))
                }
                ApplyOutcome.Applied(index + 2)
            }
            "--view" -> {
                val requested = args.getOrNull(index + 1)
                    ?: return ApplyOutcome.Rejected(CliError.MissingOptionValue(option))
                // Resolved HERE, not at the effect boundary: an unusable view is
                // rejected before a process, a store or a journal is created.
                when (val resolved = resolveView(requested)) {
                    is ViewParseResult.Parsed -> {
                        state.view = resolved.view
                        ApplyOutcome.Applied(index + 2)
                    }
                    is ViewParseResult.Invalid ->
                        ApplyOutcome.Rejected(CliError.InvalidView(resolved.value))
                    is ViewParseResult.Unavailable ->
                        ApplyOutcome.Rejected(CliError.UnavailableView(resolved.view))
                }
            }
            "--format" -> {
                val requested = args.getOrNull(index + 1)
                    ?: return ApplyOutcome.Rejected(CliError.MissingOptionValue(option))
                when (val resolved = ObservationFormat.parseFormat(requested)) {
                    is dev.rubentxu.pipeline.v2.application.observation.FormatParseResult.Parsed -> {
                        state.format = resolved.format
                        ApplyOutcome.Applied(index + 2)
                    }
                    is dev.rubentxu.pipeline.v2.application.observation.FormatParseResult.Invalid ->
                        ApplyOutcome.Rejected(CliError.InvalidFormat(resolved.value))
                }
            }
            "--grep" -> {
                val value = args.getOrNull(index + 1)
                    ?: return ApplyOutcome.Rejected(CliError.MissingOptionValue(option))
                if (value.isEmpty()) {
                    return ApplyOutcome.Rejected(CliError.EmptyTextFilter(option))
                }
                // Repeated flags OR together into one group; the group's sense
                // (whitelist or blacklist) is decided once, at normalization.
                state.grepSelectors += TextSelector.Literal(value)
                ApplyOutcome.Applied(index + 2)
            }
            "--grep-regex" -> {
                val value = args.getOrNull(index + 1)
                    ?: return ApplyOutcome.Rejected(CliError.MissingOptionValue(option))
                if (value.isEmpty()) {
                    return ApplyOutcome.Rejected(CliError.EmptyTextFilter(option))
                }
                state.grepSelectors += TextSelector.Pattern(value)
                ApplyOutcome.Applied(index + 2)
            }
            "--grep-invert" -> {
                state.grepInvert = true
                ApplyOutcome.Applied(index + 1)
            }
            "--stage" -> {
                val value = args.getOrNull(index + 1)
                    ?: return ApplyOutcome.Rejected(CliError.MissingOptionValue(option))
                if (value.isEmpty()) {
                    return ApplyOutcome.Rejected(CliError.EmptyTextFilter(option))
                }
                state.stageNames += value
                ApplyOutcome.Applied(index + 2)
            }
            "--step" -> {
                val value = args.getOrNull(index + 1)
                    ?: return ApplyOutcome.Rejected(CliError.MissingOptionValue(option))
                if (value.isEmpty()) {
                    return ApplyOutcome.Rejected(CliError.EmptyTextFilter(option))
                }
                state.stepNames += value
                ApplyOutcome.Applied(index + 2)
            }
            "--kind" -> {
                val value = args.getOrNull(index + 1)
                    ?: return ApplyOutcome.Rejected(CliError.MissingOptionValue(option))
                if (value.isEmpty()) {
                    return ApplyOutcome.Rejected(CliError.EmptyTextFilter(option))
                }
                state.eventKinds += value
                ApplyOutcome.Applied(index + 2)
            }
            "--channel" -> {
                val value = args.getOrNull(index + 1)
                    ?: return ApplyOutcome.Rejected(CliError.MissingOptionValue(option))
                // Resolved HERE, at the boundary, so nothing downstream has to decide whether a
                // token names a channel. Repeating the flag unions, per the AND-across/OR-within rule.
                val channel = OutputChannel.fromToken(value)
                    ?: return ApplyOutcome.Rejected(CliError.InvalidChannel(value))
                state.channels += channel
                ApplyOutcome.Applied(index + 2)
            }
            else -> ApplyOutcome.Rejected(CliError.UnknownOption(option))
        }
    }
}
