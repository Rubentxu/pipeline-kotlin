package dev.rubentxu.pipeline.v2.application

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

        /**
         * Every case of this taxonomy, once.
         *
         * The codes are READ from the cases, never written here. A copy of the
         * number would be a second place where a code lives, and a case that
         * moved would leave the copy naming a number nobody owns.
         */
        private val CASES: List<CliExit> = listOf(
            Success(),
            PipelineFailed(message = "the command reported a failure"),
            InvalidArgument(message = "the request was not understood"),
            IncompatibleVersion(message = "the version this build needs is not available"),
            RuntimeNotAvailable(message = "this build cannot serve the request"),
            UnknownExecution(message = "the execution named is unknown"),
            ReadFailure(message = "the durable material could not be read"),
            IntegrityViolation(message = "the durable material did not match its digest"),
        )

        /**
         * The case that owns [code], or null when this taxonomy declares none.
         *
         * ## Why a number needs a way back to its case
         *
         * Ten of the read-only verbs are CLIs of their own, and their entry
         * point answers with a number, because that is the contract it has
         * always had. A number that cannot find the case that owns it leaves
         * the CLI with two ways of saying what an exit means: the case, and a
         * number spelled out somewhere else.
         *
         * A number this taxonomy does not declare has no case, and the refusal
         * that says so is the honest answer: a verb that answered with a code
         * nobody declared is a defect, and guessing a case for it would hide
         * that defect behind a plausible number.
         */
        fun of(code: Int): CliExit? = CASES.firstOrNull { it.code == code }
    }
}
