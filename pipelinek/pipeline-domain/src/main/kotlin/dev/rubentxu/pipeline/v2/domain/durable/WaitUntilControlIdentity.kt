package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Deterministic waitUntil aggregate identity.
 *
 * Mirrors [RetryControlIdentity]. Anchors the [WaitUntilReconciliationDecision.RejectDivergence]
 * diagnostics and the control file key.
 */
@kotlinx.serialization.Serializable
data class WaitUntilControlIdentity(
    val operationId: String,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    init {
        require(operationId.isNotBlank()) { "operationId must not be blank" }
        require(schemaVersion >= CURRENT_SCHEMA_VERSION) {
            "schemaVersion must be >= $CURRENT_SCHEMA_VERSION, got $schemaVersion"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
