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
    val sandboxProfile: SandboxProfile = SandboxProfile.NONE,
    /** External plugin JARs: one list feeds compilation and runtime discovery. */
    val pluginJars: List<String> = emptyList(),
)

/** Typed failures for CLI admission. */
sealed interface CliError {
    data object MissingCommand : CliError
    data class InvalidCommand(val value: String) : CliError
    data class MissingOptionValue(val option: String) : CliError
    data object ConflictingDurablePolicies : CliError
    data class InvalidSandboxProfile(val value: String) : CliError
    data class UnsupportedSandboxProfile(val value: String) : CliError
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
    var sandboxProfile: SandboxProfile = SandboxProfile.NONE,
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

        return CliParseResult.Parsed(
            CliFlags(
                command = command,
                dbPath = state.dbPath,
                durableRunPolicy = state.durableRunPolicy,
                scriptPath = scriptPath,
                controlRoot = state.controlRoot,
                workspace = state.workspace,
                sandboxProfile = state.sandboxProfile,
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
            "--plugin-jar" -> {
                state.pluginJars += value
                ApplyOutcome.Applied(index + 2)
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
