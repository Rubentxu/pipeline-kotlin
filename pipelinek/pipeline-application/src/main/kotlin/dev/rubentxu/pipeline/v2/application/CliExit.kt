package dev.rubentxu.pipeline.v2.application

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Closed taxonomy of CLI exit codes.
 * A consumer that branches on a process exit code SHOULD map the [code]
 * to one of the cases below. The [name] is stable across releases and
 * is emitted in the [SCHEMA] JSON envelope on stderr before the
 * process terminates.
 * The 8 cases are exhaustive: any new exit reason must extend this
 * sealed interface, not return an ad-hoc `Int`.
 * @see for the design rationale
*/
sealed interface CliExit {
    /** Exit code returned to the OS. Stable. */
    val code: Int

    /** Stable name of the case. Emitted in the JSON envelope. */
    val name: String

    /** Human-readable message. Free text; machine consumers MUST NOT parse. */
    val message: String

    /** Optional hint for the operator. Free text; machine consumers MUST NOT parse. */
    val hint: String? get() = null

    /** Encode to a single-line JSON envelope. Schema-versioned. */
    fun encodeEnvelope(): String {
        val obj: JsonObject = buildJsonObject {
            put("schema", SCHEMA)
            put("exit", name)
            put("code", code)
            put("message", message)
            hint?.let { put("hint", it) }
        }
        return Json { prettyPrint = false }.encodeToString(JsonObject.serializer(), obj) + "\n"
    }

    /** Pipeline ran without producing a failure outcome. */
    data class Success(override val message: String = "pipeline finished successfully") : CliExit {
        override val code: Int get() = 0
        override val name: String get() = "Success"
    }

    /** Pipeline executed and produced a [dev.rubentxu.pipeline.v2.domain.RunOutcome.Failure]. */
    data class PipelineFailed(
        override val message: String,
        override val hint: String? = null,
    ) : CliExit {
        override val code: Int get() = 1
        override val name: String get() = "PipelineFailed"
    }

    /** Argument not understood, value missing, or conflicting flags. */
    data class InvalidArgument(
        override val message: String,
        override val hint: String? = null,
    ) : CliExit {
        override val code: Int get() = 2
        override val name: String get() = "InvalidArgument"
    }

    /** Version resource missing or consumer SDK version mismatch. */
    data class IncompatibleVersion(
        override val message: String,
        override val hint: String? = null,
    ) : CliExit {
        override val code: Int get() = 3
        override val name: String get() = "IncompatibleVersion"
    }

    /** Control root, db, or journal not creatable / accessible. */
    data class RuntimeNotAvailable(
        override val message: String,
        override val hint: String? = null,
    ) : CliExit {
        override val code: Int get() = 4
        override val name: String get() = "RuntimeNotAvailable"
    }

    /** `runId` not found in durable store or lease held by another process. */
    data class UnknownExecution(
        override val message: String,
        override val hint: String? = null,
    ) : CliExit {
        override val code: Int get() = 5
        override val name: String get() = "UnknownExecution"
    }

    /** I/O error reading from durable store. */
    data class ReadFailure(
        override val message: String,
        override val hint: String? = null,
    ) : CliExit {
        override val code: Int get() = 6
        override val name: String get() = "ReadFailure"
    }

    /** Credentials tampering, journal corruption, digest mismatch. */
    data class IntegrityViolation(
        override val message: String,
        override val hint: String? = null,
    ) : CliExit {
        override val code: Int get() = 7
        override val name: String get() = "IntegrityViolation"
    }

    companion object {
        const val SCHEMA: String = "pipelinek.cli/1"
    }
}
