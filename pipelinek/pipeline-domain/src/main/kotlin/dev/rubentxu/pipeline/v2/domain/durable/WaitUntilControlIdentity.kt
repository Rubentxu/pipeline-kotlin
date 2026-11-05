package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Deterministic waitUntil aggregate identity.
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

    /**
     * The schema version of the control row, and the floor a row's own
     * version is checked against.
     *
     * The row is durable, so the version travels with it: a row that carries
     * a version below this one is refused when it is read back, and a row
     * that carries a newer one is read as this shape, with the newer version
     * carried in the field.
     */
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
