package dev.rubentxu.pipeline.v2.application.durable.credentials

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec

/**
 * EM-7 / LFC-5.3 — Application seam credential scope port.
 *
 * A suspend function interface consumed by [dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator].
 * It is implemented by [WithCredentialsExecutorScopeAdapter] which wraps the
 * [dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor].
 *
 * ## Design rationale
 *
 * The port is a pure function from bindings + runId to a closed [CredentialScopeOutcome].
 * No nullable capability sentinels cross this boundary. When no executor is configured,
 * the adapter returns [CredentialScopeOutcome.Unavailable].
 *
 * ## Fail-closed policy
 *
 * [CredentialScopeOutcome.Unavailable] and [CredentialScopeOutcome.Invalid] must result
 * in a [CredentialScopeOutcome.Failure][dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure]
 * at the coordinator boundary — the body is never dispatched.
 *
 * [CredentialScopeOutcome.Invalid] with [CredentialScopeFailure.ReplayUnsupported]
 * also fails closed. No persisted scope row is added.
 */
fun interface CredentialScopePort {
    /**
     * Acquires credential bindings for a scope block.
     *
     * @param bindings The credential binding specifications to resolve
     * @param runId The pipeline run ID for event attribution
     * @return A closed [CredentialScopeOutcome] — never null, never throws
     */
    suspend fun acquire(
        bindings: List<CredentialBindingSpec>,
        runId: RunId,
    ): CredentialScopeOutcome
}
