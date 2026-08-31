package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.dsl.StepSpec

/**
 * H0 Slice 1: Binding seam abstraction.
 *
 * Represents the outcome of credential resolution for a single binding.
 */
sealed class CredentialResolution {
    /**
     * Successfully resolved credential binding.
     *
     * @property credentialsId The credential ID
     * @property purpose How the credential is injected (ENV / FILE / SSH_KEY / etc.)
     * @property envVar The primary environment variable name
     * @property handle The secret handle for the resolved credential
     */
    data class Resolved(
        val credentialsId: CredentialsId,
        val purpose: BoundPurpose,
        val envVar: String,
        val handle: SecretHandle
    ) : CredentialResolution()

    /**
     * Failed credential resolution.
     *
     * @property credentialsId The credential ID that failed
     * @property reason Human-readable reason for the failure
     */
    data class Failed(
        val credentialsId: CredentialsId,
        val reason: String
    ) : CredentialResolution()
}

/**
 * Resolution boundaries - tracks which bindings resolved and which failed.
 */
data class ResolutionBoundaries(
    val resolved: List<CredentialResolution.Resolved>,
    val failed: List<CredentialResolution.Failed>,
    val success: Boolean  // true only if ALL resolved (no failures)
)

/**
 * CredentialSession - opened by executor, consumed by PipelineRun.
 *
 * This is the binding seam for H0 Slice 1. The executor provides the session
 * but PipelineRun controls execution (StepStarted, inner loop, CredentialUsed, close).
 *
 * ## Architecture (H0 Slice 1)
 *
 * - Executor is PASSIVE: opens session, returns credentialEnv + boundaries + cleanup
 * - PipelineRun RETAINS: ShOptions construction, outer StepStarted, inner recursive
 *   durable loop, CredentialUsed timing, closes session in finally
 * - No executor callback to application; no executor dependency on application,
 *   local adapter, or step-sdk runtime
 *
 * ## Cleanup semantics
 *
 * - [close] is idempotent and MUST be called exactly once per session
 * - The session is the SINGLE owner of cleanup; public callers MUST NOT
 *   independently emit CredentialUnbound events
 * - Calling close() multiple times is safe (idempotent)
 *
 * @see dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
 */
interface CredentialSession : AutoCloseable {

    /**
     * The resolved credential environment map.
     * Keys are environment variable names, values are SecretHandle.
     *
     * @return Immutable map of environment variable names to secret handles
     */
    fun credentialEnv(): Map<String, SecretHandle>

    /**
     * Active handles that must be closed for cleanup.
     * These are handles returned by secretStore.getAsSecretHandle() for non-file kinds.
     *
     * @return List of active secret handles
     */
    fun activeHandles(): List<SecretHandle>

    /**
     * The materializer for file-based credential cleanup.
     *
     * @return The credential materializer instance
     */
    fun materializer(): dev.rubentxu.pipeline.v2.credentials.multipart.CredentialMaterializer

    /**
     * Resolution outcome and boundaries.
     *
     * @return ResolutionBoundaries tracking success/failure per binding
     */
    fun boundaries(): ResolutionBoundaries

    /**
     * Closes the session, wiping all tracked handles and materializer paths.
     *
     * Idempotent: safe to call multiple times.
     * This is the SINGLE cleanup owner - public callers must NOT independently
     * emit CredentialUnbound events.
     */
    override fun close()
}