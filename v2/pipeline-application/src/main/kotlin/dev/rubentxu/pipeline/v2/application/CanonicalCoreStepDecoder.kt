package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Metadata bundle for a canonical step — carries the durable execution contract.
 *
 * @property effects The side-effect classification of the step.
 * @property replayPolicy The replay strategy for the step.
 */
data class StepMetadata(
    val effects: Set<Effect>,
    val replayPolicy: ReplayPolicy,
)

/** Typed command decoded from the `dsl-v1` payloads owned by the canonical IR. */
sealed interface CanonicalCoreStepCommand {
    val pluginId: String
    val defaultMetadata: StepMetadata

    companion object {
        /**
         * Single source of truth for canonical core plugin IDs.
         * Derived from the pluginId overrides declared on each sealed subtype.
         * Adding a new sealed subtype with a pluginId override automatically propagates here.
         */
        val ALL_PLUGIN_IDS: Set<String> = setOf(
            "core.sh",
            "core.echo",
            "core.error",
            "core.sleep",
            "core.file.writeFile",
            "core.emit.event",
            "core.milestone",
            "core.deleteDir",
            "core.cleanWs",
            "core.load",
            "core.pwd",
            "core.isUnix",
            "core.waitUntil",
        )

        /** Derives the short type string from a pluginId (e.g. "core.sh" → "sh"). */
        fun pluginIdToShortType(pluginId: String): String =
            pluginId.removePrefix("core.").substringBefore(".")
    }

    data class Shell(
        val shell: ShellCommand,
        val isScriptBlock: Boolean,
    ) : CanonicalCoreStepCommand {
        @Deprecated("Use ShellCommand.returnMode")
        constructor(command: String, isScriptBlock: Boolean, returnStdout: Boolean) : this(
            shell = ShellCommand(
                script = command,
                returnMode = if (returnStdout) ShellReturnMode.STDOUT else ShellReturnMode.NONE,
            ),
            isScriptBlock = isScriptBlock,
        )

        override val pluginId = "core.sh"
        override val defaultMetadata = StepMetadata(setOf(Effect.EXECUTES_SUBPROCESS), ReplayPolicy.RERUN)
    }

    data class Echo(val text: String) : CanonicalCoreStepCommand {
        override val pluginId = "core.echo"
        override val defaultMetadata = StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED)
    }

    data class Error(val message: String, val failureKind: FailureKind) : CanonicalCoreStepCommand {
        override val pluginId = "core.error"
        override val defaultMetadata = StepMetadata(setOf(Effect.ABORTS_PIPELINE), ReplayPolicy.NEVER)
    }

    data class Sleep(val seconds: Long) : CanonicalCoreStepCommand {
        override val pluginId = "core.sleep"
        override val defaultMetadata = StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED)
    }

    /** LFC1-007: typed-command for atomic file writes via the canonical bridge. */
    data class WriteFile(
        val file: String,
        val text: String,
        val encoding: String,
    ) : CanonicalCoreStepCommand {
        override val pluginId = "core.file.writeFile"
        override val defaultMetadata = StepMetadata(setOf(Effect.WRITES_WORKSPACE), ReplayPolicy.MEMOIZED)
    }

    /** LFC1-007: first-class workflow-event emitter for shell-rewrite path. */
    data class EmitEvent(
        val kind: String,
        val payload: Map<String, String?>,
    ) : CanonicalCoreStepCommand {
        override val pluginId = "core.emit.event"
        override val defaultMetadata = StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED)
    }

    /**
     * ML-R9 T-09: local single-run milestone marker (ADR-0046 §ML — no cross-build abort).
     * Emits the typed MilestoneReached event; ordinal monotonicity is validated
     * within the run by the dispatcher.
     */
    data class Milestone(
        val ordinal: Int,
        val label: String?,
    ) : CanonicalCoreStepCommand {
        override val pluginId = "core.milestone"
        override val defaultMetadata = StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED)
    }

    /**
     * T-05: deleteDir step — recursively deletes workspace contents, leaves workspace intact.
     * Idempotent: re-execution on already-deleted path emits DirDeleted with deletedCount=0.
     */
    data class DeleteDir(
        val path: String = ".",
    ) : CanonicalCoreStepCommand {
        override val pluginId = "core.deleteDir"
        override val defaultMetadata = StepMetadata(setOf(Effect.WRITES_WORKSPACE), ReplayPolicy.MEMOIZED)
    }

    /**
     * T-05: cleanWs step — cleans workspace with optional Ant-style glob filtering.
     * @param deleteDirs If true, delete all subdirectories too
     * @param patterns Additional glob patterns to delete
     */
    data class CleanWs(
        val deleteDirs: Boolean = true,
        val patterns: List<String> = emptyList(),
    ) : CanonicalCoreStepCommand {
        override val pluginId = "core.cleanWs"
        override val defaultMetadata = StepMetadata(setOf(Effect.WRITES_WORKSPACE), ReplayPolicy.MEMOIZED)
    }

    /**
     * T-05: load step — reads and evaluates a pipeline script file in the workspace.
     * Re-entrant: subsequent calls with same (path, sha256) are skipped.
     */
    data class Load(
        val path: String,
    ) : CanonicalCoreStepCommand {
        override val pluginId = "core.load"
        override val defaultMetadata = StepMetadata(setOf(Effect.EXECUTES_SUBPROCESS), ReplayPolicy.MEMOIZED)
    }

    /**
     * T-07: pwd step — returns the current workspace directory as an absolute path.
     * @param tmp If true, creates and returns a temp subdirectory path instead
     */
    data class Pwd(
        val tmp: Boolean = false,
    ) : CanonicalCoreStepCommand {
        override val pluginId = "core.pwd"
        override val defaultMetadata = StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED)
    }

    /**
     * T-07: isUnix step — checks if the current OS is Unix-like (Linux/macOS/Darwin).
     */
    data class IsUnix(
        val unused: Unit = Unit, // sealed class requires at least one field; no params from DSL
    ) : CanonicalCoreStepCommand {
        override val pluginId = "core.isUnix"
        override val defaultMetadata = StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED)
    }

    /**
     * T-07: waitUntil step — polls a condition lambda until it returns true or deadline elapses.
     * @param initialRecurrencePeriod Initial poll interval in milliseconds (default 1000)
     * @param quiet If true, suppress output during polling
     */
    data class WaitUntil(
        val initialRecurrencePeriod: Long = 1000L,
        val quiet: Boolean = false,
    ) : CanonicalCoreStepCommand {
        override val pluginId = "core.waitUntil"
        override val defaultMetadata = StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED)
    }
}

/** Decodes a supported canonical core node without reconstructing the DSL model. */
object CanonicalCoreStepDecoder {
    private const val SCHEMA_VERSION = "dsl-v1"
    private const val SHELL_PLUGIN_ID = "core.sh"
    private const val ECHO_PLUGIN_ID = "core.echo"
    private const val ERROR_PLUGIN_ID = "core.error"
    private const val SLEEP_PLUGIN_ID = "core.sleep"
    private const val WRITE_FILE_PLUGIN_ID = "core.file.writeFile"
    private const val EMIT_EVENT_PLUGIN_ID = "core.emit.event"
    private const val MILESTONE_PLUGIN_ID = "core.milestone"
    private const val DELETE_DIR_PLUGIN_ID = "core.deleteDir"
    private const val CLEAN_WS_PLUGIN_ID = "core.cleanWs"
    private const val LOAD_PLUGIN_ID = "core.load"
    private const val PWD_PLUGIN_ID = "core.pwd"
    private const val IS_UNIX_PLUGIN_ID = "core.isUnix"
    private const val WAIT_UNTIL_PLUGIN_ID = "core.waitUntil"

    fun decode(node: StepNode): CanonicalCoreStepCommand {
        require(node.payload.schemaVersion == SCHEMA_VERSION) {
            "Unsupported step payload schema '${node.payload.schemaVersion}' for '${node.id.value}'"
        }
        val payload = Json.parseToJsonElement(node.payload.encoded).jsonObject
        return when (node.pluginStepId.value) {
            SHELL_PLUGIN_ID -> {
                require(payload.requiredString("kind") == "sh") {
                    "Payload kind must be 'sh' for '${node.id.value}'"
                }
                val returnStdout = payload.requiredBoolean("returnStdout")
                val returnStatus = payload["returnStatus"]?.jsonPrimitive?.booleanOrNull ?: false
                require(!(returnStdout && returnStatus)) {
                    "Shell payload cannot enable both returnStdout and returnStatus for '${node.id.value}'"
                }
                CanonicalCoreStepCommand.Shell(
                    shell = ShellCommand(
                        script = payload.requiredString("command"),
                        returnMode = when {
                            returnStatus -> ShellReturnMode.STATUS
                            returnStdout -> ShellReturnMode.STDOUT
                            else -> ShellReturnMode.NONE
                        },
                    ),
                    isScriptBlock = payload.requiredBoolean("isScriptBlock"),
                )
            }
            ECHO_PLUGIN_ID -> {
                require(payload.requiredString("kind") == "echo") {
                    "Payload kind must be 'echo' for '${node.id.value}'"
                }
                CanonicalCoreStepCommand.Echo(payload.requiredString("text"))
            }
            ERROR_PLUGIN_ID -> {
                require(payload.requiredString("kind") == "error") {
                    "Payload kind must be 'error' for '${node.id.value}'"
                }
                val failureKindName = payload.requiredString("failureKind")
                val failureKind = FailureKind.entries.firstOrNull { it.name == failureKindName }
                    ?: throw IllegalArgumentException("Unknown failure kind '$failureKindName' for '${node.id.value}'")
                CanonicalCoreStepCommand.Error(payload.requiredString("message"), failureKind)
            }
            SLEEP_PLUGIN_ID -> {
                require(payload.requiredString("kind") == "sleep") {
                    "Payload kind must be 'sleep' for '${node.id.value}'"
                }
                CanonicalCoreStepCommand.Sleep(payload.requiredLong("seconds"))
            }
            WRITE_FILE_PLUGIN_ID -> {
                require(payload.requiredString("kind") == "writeFile") {
                    "Payload kind must be 'writeFile' for '${node.id.value}'"
                }
                CanonicalCoreStepCommand.WriteFile(
                    file = payload.requiredString("file"),
                    text = payload.requiredString("text"),
                    encoding = payload.requiredString("encoding"),
                )
            }
            EMIT_EVENT_PLUGIN_ID -> {
                CanonicalCoreStepCommand.EmitEvent(
                    kind = payload.requiredString("kind"),
                    payload = payload.entries
                        .filter { it.key != "kind" }
                        .associate { it.key to it.value.jsonPrimitive.contentOrNull },
                )
            }
            MILESTONE_PLUGIN_ID -> {
                require(payload.requiredString("kind") == "milestone") {
                    "Payload kind must be 'milestone' for '${node.id.value}'"
                }
                val ordinal = payload.requiredInt("ordinal")
                require(ordinal > 0) {
                    "dsl-v1 payload requires a positive milestone ordinal for '${node.id.value}': $ordinal"
                }
                CanonicalCoreStepCommand.Milestone(
                    ordinal = ordinal,
                    label = payload["label"]?.jsonPrimitive?.contentOrNull,
                )
            }
            DELETE_DIR_PLUGIN_ID -> {
                require(payload.requiredString("kind") == "deleteDir") {
                    "Payload kind must be 'deleteDir' for '${node.id.value}'"
                }
                CanonicalCoreStepCommand.DeleteDir(
                    path = payload["path"]?.jsonPrimitive?.contentOrNull ?: ".",
                )
            }
            CLEAN_WS_PLUGIN_ID -> {
                require(payload.requiredString("kind") == "cleanWs") {
                    "Payload kind must be 'cleanWs' for '${node.id.value}'"
                }
                val deleteDirs = payload["deleteDirs"]?.jsonPrimitive?.booleanOrNull ?: true
                val patternsRaw = payload["patterns"]
                val patterns = if (patternsRaw != null) {
                    patternsRaw.jsonArray.map { it.jsonPrimitive.contentOrNull ?: "" }
                } else {
                    emptyList()
                }
                CanonicalCoreStepCommand.CleanWs(
                    deleteDirs = deleteDirs,
                    patterns = patterns,
                )
            }
            LOAD_PLUGIN_ID -> {
                require(payload.requiredString("kind") == "load") {
                    "Payload kind must be 'load' for '${node.id.value}'"
                }
                CanonicalCoreStepCommand.Load(
                    path = payload.requiredString("path"),
                )
            }
            PWD_PLUGIN_ID -> {
                require(payload.requiredString("kind") == "pwd") {
                    "Payload kind must be 'pwd' for '${node.id.value}'"
                }
                val tmp = payload["tmp"]?.jsonPrimitive?.booleanOrNull ?: false
                CanonicalCoreStepCommand.Pwd(tmp = tmp)
            }
            IS_UNIX_PLUGIN_ID -> {
                require(payload.requiredString("kind") == "isUnix") {
                    "Payload kind must be 'isUnix' for '${node.id.value}'"
                }
                CanonicalCoreStepCommand.IsUnix()
            }
            WAIT_UNTIL_PLUGIN_ID -> {
                require(payload.requiredString("kind") == "waitUntil") {
                    "Payload kind must be 'waitUntil' for '${node.id.value}'"
                }
                val initialRecurrencePeriod = payload["initialRecurrencePeriod"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1000L
                val quiet = payload["quiet"]?.jsonPrimitive?.booleanOrNull ?: false
                CanonicalCoreStepCommand.WaitUntil(
                    initialRecurrencePeriod = initialRecurrencePeriod,
                    quiet = quiet,
                )
            }
            else -> throw IllegalArgumentException(
                "Unsupported core plugin step '${node.pluginStepId.value}' for '${node.id.value}'"
            )
        }
    }

    private fun kotlinx.serialization.json.JsonObject.requiredString(name: String): String =
        requireNotNull(this[name]?.jsonPrimitive?.contentOrNull) {
            "dsl-v1 payload requires string '$name'"
        }

    private fun kotlinx.serialization.json.JsonObject.requiredBoolean(name: String): Boolean =
        requireNotNull(this[name]?.jsonPrimitive?.booleanOrNull) {
            "dsl-v1 payload requires boolean '$name'"
        }

    private fun kotlinx.serialization.json.JsonObject.requiredLong(name: String): Long =
        requireNotNull(this[name]?.jsonPrimitive?.content?.toLongOrNull()) {
            "dsl-v1 payload requires integer '$name'"
        }

    private fun kotlinx.serialization.json.JsonObject.requiredInt(name: String): Int =
        requireNotNull(this[name]?.jsonPrimitive?.content?.toIntOrNull()) {
            "dsl-v1 payload requires integer '$name'"
        }
}
