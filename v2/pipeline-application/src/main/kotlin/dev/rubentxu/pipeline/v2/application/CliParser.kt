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

/** Pure parser for the application CLI. */
object CliParser {
    fun parse(args: Array<String>): CliParseResult {
        if (args.isEmpty()) return CliParseResult.Rejected(CliError.MissingCommand)

        val command = when (args[0]) {
            "validate" -> CliCommand.VALIDATE
            "run" -> CliCommand.RUN
            else -> return CliParseResult.Rejected(CliError.InvalidCommand(args[0]))
        }

        var dbPath: String? = null
        var durableRunPolicy: DurableRunPolicy = DurableRunPolicy.ReusePriorRun
        var controlRoot: String? = null
        var workspace: String? = null
        var sandboxProfile = SandboxProfile.NONE
        val pluginJars = mutableListOf<String>()
        var index = 1

        while (index < args.size && args[index].startsWith("--")) {
            when (val option = args[index]) {
                "--db" -> {
                    val value = args.getOrNull(index + 1)
                        ?: return CliParseResult.Rejected(CliError.MissingOptionValue(option))
                    dbPath = value
                    index += 2
                }
                "--resume" -> {
                    if (durableRunPolicy != DurableRunPolicy.ReusePriorRun) {
                        return CliParseResult.Rejected(CliError.ConflictingDurablePolicies)
                    }
                    durableRunPolicy = DurableRunPolicy.ResumePriorRun
                    index++
                }
                "--rerun" -> {
                    if (durableRunPolicy != DurableRunPolicy.ReusePriorRun) {
                        return CliParseResult.Rejected(CliError.ConflictingDurablePolicies)
                    }
                    durableRunPolicy = DurableRunPolicy.StartFreshRun
                    index++
                }
                "--control-root", "--workspace", "--plugin-jar" -> {
                    val value = args.getOrNull(index + 1)
                        ?: return CliParseResult.Rejected(CliError.MissingOptionValue(option))
                    when (option) {
                        "--control-root" -> controlRoot = value
                        "--workspace" -> workspace = value
                        "--plugin-jar" -> pluginJars += value
                        else -> error("unreachable option: $option")
                    }
                    index += 2
                }
                "--sandbox-profile" -> {
                    val value = args.getOrNull(index + 1)
                        ?: return CliParseResult.Rejected(CliError.MissingOptionValue(option))
                    sandboxProfile = when (value) {
                        "none" -> SandboxProfile.NONE
                        "local" -> SandboxProfile.LOCAL
                        "os" -> return CliParseResult.Rejected(CliError.UnsupportedSandboxProfile(value))
                        else -> return CliParseResult.Rejected(CliError.InvalidSandboxProfile(value))
                    }
                    index += 2
                }
                else -> return CliParseResult.Rejected(CliError.UnknownOption(option))
            }
        }

        val scriptPath = args.getOrNull(index)
            ?: return CliParseResult.Rejected(CliError.MissingScriptPath)

        return CliParseResult.Parsed(
            CliFlags(
                command = command,
                dbPath = dbPath,
                durableRunPolicy = durableRunPolicy,
                scriptPath = scriptPath,
                controlRoot = controlRoot,
                workspace = workspace,
                sandboxProfile = sandboxProfile,
                pluginJars = pluginJars.toList(),
            ),
        )
    }
}
