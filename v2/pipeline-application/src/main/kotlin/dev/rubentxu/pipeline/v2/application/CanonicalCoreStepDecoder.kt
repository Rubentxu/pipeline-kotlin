package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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

    data class Shell(
        val command: String,
        val isScriptBlock: Boolean,
        val returnStdout: Boolean,
    ) : CanonicalCoreStepCommand {
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
                CanonicalCoreStepCommand.Shell(
                    command = payload.requiredString("command"),
                    isScriptBlock = payload.requiredBoolean("isScriptBlock"),
                    returnStdout = payload.requiredBoolean("returnStdout"),
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
}
