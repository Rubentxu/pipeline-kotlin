package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunId
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
}

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

        // RP034-H / ADR-0101 clause 3.4: fail closed on the incompatible pair.
        state.workspace?.let { explicit ->
            if (state.isolated) {
                return CliParseResult.Rejected(CliError.ConflictingWorkspaceModes(explicit))
            }
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
            else -> ApplyOutcome.Rejected(CliError.UnknownOption(option))
        }
    }
}
