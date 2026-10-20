package dev.rubentxu.pipeline.v2.application.durable.credentials

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import java.nio.file.Path

/**
 * EM-7 / LFC-5.3 — Application seam credential scope contracts.
 *
 * These types live in `:pipeline-application` and belong to the application seam.
 * They are NOT domain types: the domain has no dependency on application, process,
 * events, or local storage.
 *
 * ## Design rationale
 *
 * The typed credential-scope outcome ADT replaces nullable sentinels, paired
 * boolean/value results, and direct adapter types at the coordinator boundary.
 * Each outcome variant is exhaustive and carries exactly the information relevant
 * to that outcome.
 *
 * ## Dependency direction
 *
 * ```
 * CanonicalDurableRunCoordinator (application)
 *   -> CredentialScopePort (application contract)
 *        <- WithCredentialsExecutorScopeAdapter (application adapter)
 *             -> WithCredentialsExecutor (credentials executor)
 *                  -> CredentialProjector and CredentialMaterializationDomain (domain contracts)
 * ```
 */

/**
 * EM-7 §Typed scope acquisition outcome.
 *
 * Sealed ADT covering the three possible acquisition outcomes:
 *  - [Acquired]: bindings resolved and env available; body may dispatch
 *  - [Unavailable]: store/provider unavailable; body MUST NOT dispatch
 *  - [Invalid]: bindings malformed or kind mismatch; body MUST NOT dispatch
 *
 * No nullable sentinels, no paired boolean/value results.
 */
sealed interface CredentialScopeOutcome {
    /**
     * Bindings acquired successfully. The scope carries the typed env overlay
     * and an idempotent close action.
     *
     * @property scope The acquired scope with env vars and cleanup handle
     */
    data class Acquired(val scope: AcquiredCredentialScope) : CredentialScopeOutcome

    /**
     * The credential store or provider is unavailable. The body MUST NOT run.
     *
     * @property failure The reason the scope is unavailable
     */
    data class Unavailable(val failure: CredentialScopeFailure) : CredentialScopeOutcome

    /**
     * Bindings are invalid (malformed, kind mismatch, missing credential).
     * The body MUST NOT run.
     *
     * @property failure The reason the scope is invalid
     */
    data class Invalid(val failure: CredentialScopeFailure) : CredentialScopeOutcome
}

/**
 * EM-7 §Typed scope failure.
 *
 * Covers all failure modes for credential scope acquisition.
 */
sealed interface CredentialScopeFailure {
    /** Credential store is absent or not configured. */
    data class StoreUnavailable(val message: String) : CredentialScopeFailure

    /** The requested credential ID is not present in the store. */
    data class CredentialMissing(val credentialsId: CredentialsId) : CredentialScopeFailure

    /** Binding spec is malformed or credential kind does not match the binding type. */
    data class BindingMismatch(val message: String) : CredentialScopeFailure

    /** Projection or materialization failed. */
    data class AcquisitionFailed(val message: String) : CredentialScopeFailure

    /** Replay of an in-flight credential scope is not supported. */
    data object ReplayUnsupported : CredentialScopeFailure
}

/**
 * EM-7 §Typed scope cleanup.
 *
 * Covers all cleanup outcomes:
 *  - [Cleaned]: all resources wiped successfully
 *  - [Failed]: one or more paths could not be wiped; originals preserved
 */
sealed interface CredentialScopeCleanup {
    /** All materialized resources were securely wiped. */
    data object Cleaned : CredentialScopeCleanup

    /**
     * Cleanup failed for some resources.
     *
     * @property orphanPaths Paths that could not be wiped
     * @property message Diagnostic message (never contains secret values)
     */
    data class Failed(
        val orphanPaths: List<Path>,
        val message: String,
    ) : CredentialScopeCleanup
}

/**
 * EM-7 §Acquired credential scope.
 *
 * The result of a successful [CredentialScopePort.acquire]. Carries the
 * typed env overlay and the cleanup handle. Cleanup is idempotent and
 * owned exclusively by this scope (reverse-LIFO, no shared resources).
 *
 * @property env Map of env var name to [SecretHandle]. May be empty but
 *               never null.
 * @property credentials The credential IDs that were acquired.
 * @property close Releases all retained materialization resources.
 *                 Idempotent: multiple calls are safe.
 */
interface AcquiredCredentialScope {
    val env: Map<String, SecretHandle>
    val credentials: List<CredentialsId>

    /**
     * Releases all retained materialization resources (files, directories).
     *
     * Idempotent — multiple calls are safe.
     *
     * @return [CredentialScopeCleanup] describing the outcome.
     *         [CredentialScopeCleanup.Cleaned] if all resources were wiped;
     *         [CredentialScopeCleanup.Failed] if any path could not be removed.
     */
    fun close(): CredentialScopeCleanup
}
