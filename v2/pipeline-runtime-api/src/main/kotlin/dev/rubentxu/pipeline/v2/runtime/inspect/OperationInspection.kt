package dev.rubentxu.pipeline.v2.runtime.inspect

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus

/**
 * M6 — closed ADT returned by [RuntimeIntrospectionPort.inspectOperation].
 *
 * Mirrors the M2 envelope pattern: a sealed result with two cases (the
 * inspection or a refusal). The [OperationInspection] is the typed view the
 * audit's §F.2 names "what is the terminality of THIS operation?"; the
 * `IntrospectionRefusal` is the existing M2 closed ADT (no new refusal
 * variants needed).
 *
 */
sealed interface OperationInspectionResult {

    /**
     * The inspection succeeded. `inspection` is the typed view: operation id,
     * attempt, run id, stage / step ordinals, status, timestamps, deadline,
     * fingerprint, and the declared stdout / stderr streams.
     */
    data class Inspection(val inspection: OperationInspection) : OperationInspectionResult

    /**
     * The inspection could not be served. `refusal` is the closed
     * [IntrospectionRefusal] reason (UnknownRun, StorageError, etc.).
     */
    data class Refused(val refusal: IntrospectionRefusal) : OperationInspectionResult
}

/**
 * The typed view of one operation, projected from the journal.
 *
 * `status` is the journal's [OperationStatus] enum, projected verbatim.
 * `fingerprint` is the SHA-256 of the operation's input (M3 sibling) — null
 * when the journal did not record one. `streams` names the declared stdout
 * / stderr streams of the operation. `stageOrdinal` and `stepOrdinal` locate
 * the operation within the run's structure.
 *
 */
data class OperationInspection(
    val operationId: String,
    val attempt: Int,
    val runId: String,
    val stageOrdinal: Int,
    val stepOrdinal: Int,
    val status: OperationStatus,
    val startedAtMs: Long?,
    val endedAtMs: Long?,
    val deadlineMs: Long?,
    val fingerprint: String?,
    val streams: OperationOutputStreams,
) {
    init {
        require(operationId.isNotBlank()) { "operationId must be non-blank" }
        require(runId.isNotBlank()) { "runId must be non-blank" }
        require(attempt >= 1) { "attempt must be >= 1, got $attempt" }
        require(stageOrdinal >= 0) { "stageOrdinal must be >= 0, got $stageOrdinal" }
        require(stepOrdinal >= 0) { "stepOrdinal must be >= 0, got $stepOrdinal" }
        require(endedAtMs == null || startedAtMs == null || endedAtMs >= startedAtMs) {
            "endedAtMs must be null or >= startedAtMs, got $endedAtMs vs $startedAtMs"
        }
        require(fingerprint == null || Sha256.isSha256Hex(fingerprint)) {
            "fingerprint must be null or a 64-char lowercase hex SHA-256; got '$fingerprint'"
        }
    }
}
