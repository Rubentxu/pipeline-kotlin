package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import kotlinx.serialization.Serializable

/**
 * Durable, transport-safe provenance for a failure observed by a task adapter.
 *
 * A [Throwable] may be retained by an in-process exception for diagnostics, but
 * it is deliberately not part of this persisted contract.
 */
@Serializable
data class FailureRecord(
    val code: String,
    val kind: FailureKind,
    val message: String,
    val origin: FailureOrigin,
    val retryable: Boolean,
    val operationId: String,
    val workerId: String? = null,
    val taskId: String? = null,
    val details: Map<String, String> = emptyMap(),
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(code.isNotBlank()) { "FailureRecord.code must not be blank" }
        require(message.isNotBlank()) { "FailureRecord.message must not be blank" }
        require(operationId.isNotBlank()) { "FailureRecord.operationId must not be blank" }
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported FailureRecord schema version: $schemaVersion"
        }
        require(details.keys.none(String::isBlank)) { "FailureRecord details keys must not be blank" }
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/** Where the durable adapter observed the failure. */
@Serializable
enum class FailureOrigin {
    LAUNCHER,
    DURABLE_TASK,
    RECONCILIATION,
    UNKNOWN,
}

/** Durable reason why a task was interrupted rather than failed. */
@Serializable
data class InterruptionRecord(
    val kind: InterruptionKind,
    val message: String,
    val operationId: String,
    val causedBy: String? = null,
    val deadlineEpochMillis: Long? = null,
    val details: Map<String, String> = emptyMap(),
) {
    init {
        require(message.isNotBlank()) { "InterruptionRecord.message must not be blank" }
        require(operationId.isNotBlank()) { "InterruptionRecord.operationId must not be blank" }
        require(deadlineEpochMillis == null || deadlineEpochMillis > 0) {
            "InterruptionRecord.deadlineEpochMillis must be positive when present"
        }
        require(details.keys.none(String::isBlank)) { "InterruptionRecord details keys must not be blank" }
    }
}

@Serializable
enum class InterruptionKind {
    TIMEOUT,
    USER_ABORT,
    PARENT_CANCELLED,
    SUPERSEDED,
    SHUTDOWN,
}

/**
 * A non-terminal observation of a durable task. It is intentionally separate
 * from [DurableTaskTerminal] so a caller awaiting completion cannot receive a
 * launching or running value through the terminal interface.
 */
@Serializable
sealed interface DurableTaskSnapshot {
    val operationId: String
    val controlDir: String
    val workerId: String?

    @Serializable
    data class Launching(
        override val operationId: String,
        override val controlDir: String,
        override val workerId: String? = null,
    ) : DurableTaskSnapshot

    @Serializable
    data class Running(
        override val operationId: String,
        override val controlDir: String,
        override val workerId: String? = null,
    ) : DurableTaskSnapshot
}

/**
 * Reference to durable output retained by the task substrate.
 *
 * [capturedStdout] is the explicit typed VALUE requested by a capture mode (returnStdout); it is NOT
 * the console transcript. [consoleTranscript] is the durable console output destined for the
 * observable event/console substrate (in plain mode it is the merged stdout+stderr transcript; in
 * capture mode it is stderr only, because stdout went to the typed value). These two channels are
 * deliberately distinct and MUST NOT be conflated. Both are carried in-memory only; neither changes
 * the persisted control-dir file protocol.
 */
@Serializable
data class DurableTaskOutput(
    val controlDir: String,
    val capturedStdout: String? = null,
    val consoleTranscript: String? = null,
) {
    init {
        require(controlDir.isNotBlank()) { "DurableTaskOutput.controlDir must not be blank" }
    }
}

/**
 * The only result shape returned by a durable await seam.
 *
 * Non-terminal [DurableTaskSnapshot] values are excluded by type.
 */
@Serializable
sealed interface DurableTaskTerminal {
    @Serializable
    data class Exited(
        val exitCode: Int,
        val output: DurableTaskOutput,
    ) : DurableTaskTerminal

    @Serializable
    data class LaunchFailed(val failure: FailureRecord) : DurableTaskTerminal

    @Serializable
    data class Lost(val failure: FailureRecord) : DurableTaskTerminal

    @Serializable
    data class Cancelled(val interruption: InterruptionRecord) : DurableTaskTerminal
}
