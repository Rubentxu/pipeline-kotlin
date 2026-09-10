package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Deterministic identity of a canonical retry logical invocation.
 *
 * ADR-0075 §2 — the retry control identity carries the COMPLETE inherited
 * canonical body path. Branch identity and parent bodyPath are NOT appended
 * here; they are captured by the underlying [operationId], which is the
 * canonical [dev.rubentxu.pipeline.v2.application.durable.OpId.format]
 * output for the retry's [BlockStepNode] (without the per-attempt segment).
 *
 * Same retry + same attempt ordinal + same canonical child MUST produce the
 * same durable child identity across restarts. This identity is the anchor
 * of the deterministic identity law.
 */
data class RetryControlIdentity(
    /**
     * Canonical retry operation id, formatted as the
     * [dev.rubentxu.pipeline.v2.application.durable.OpId.format] output for
     * the retry [BlockStepNode] WITHOUT any attempt-BlockSegment.
     */
    val operationId: String,

    /**
     * Schema version of the persisted retry control record. Defaults to 1
     * (ADR-0075 baseline). Future schema upgrades MUST bump this and provide
     * a back-compat path in [RetryReconciler].
     */
    val schemaVersion: Int = 1,
) {
    init {
        require(operationId.isNotBlank()) { "RetryControlIdentity.operationId must not be blank" }
        require(schemaVersion >= 1) { "RetryControlIdentity.schemaVersion must be >= 1" }
    }

    /**
     * Convenience accessor used by the reconciler to populate
     * [RetryReconciliationDecision.RejectDivergence.operationId].
     */
    fun id(): String = operationId

    /**
     * Returns the deterministic attempt identity for this retry at the
     * 1-based [attemptOrdinal]. Same retry + same ordinal = same identity.
     */
    fun attempt(attemptOrdinal: Int): RetryAttemptIdentity {
        require(attemptOrdinal >= 1) { "attemptOrdinal must be >= 1, got $attemptOrdinal" }
        return RetryAttemptIdentity(control = this, attemptOrdinal = attemptOrdinal)
    }
}

/**
 * Deterministic identity of a single retry attempt.
 *
 * ADR-0075 §2 + §3 — derived from the [RetryControlIdentity] plus the
 * 1-based attempt ordinal. Used to derive the deterministic child identity
 * triple `(RetryControlIdentity, attemptOrdinal, childIndex)`.
 */
data class RetryAttemptIdentity(
    val control: RetryControlIdentity,
    val attemptOrdinal: Int,
) {
    init {
        require(attemptOrdinal >= 1) { "RetryAttemptIdentity.attemptOrdinal must be >= 1, got $attemptOrdinal" }
    }

    /**
     * Returns the deterministic child identity for the given [childIndex]
     * (0-based position within the retry body) at this attempt. This is the
     * canonical OpId that the canonical child dispatch produces, kept as a
     * pure value here so the reconciler can compute and compare it.
     */
    fun child(childIndex: Int): RetryChildIdentity {
        require(childIndex >= 0) { "childIndex must be >= 0, got $childIndex" }
        return RetryChildIdentity(attempt = this, childIndex = childIndex)
    }
}

/**
 * Deterministic identity of a single retry child at a single attempt.
 *
 * ADR-0075 §2 — `(retry, attempt, childIndex)` is the canonical child
 * identity triple. It is recomputed deterministically across restarts and
 * is the key under which child durable facts are persisted.
 */
data class RetryChildIdentity(
    val attempt: RetryAttemptIdentity,
    val childIndex: Int,
)
