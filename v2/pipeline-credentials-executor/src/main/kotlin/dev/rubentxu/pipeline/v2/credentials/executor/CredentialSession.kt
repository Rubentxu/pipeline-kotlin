package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle

/**
 * H0 Slice 1: Binding seam abstraction.
 *
 * Represents the outcome of credential resolution for a single binding.
 */
sealed class CredentialResolution {
    data class Resolved(
        val credentialsId: CredentialsId,
        val purpose: BoundPurpose,
        val envVar: String,
        val handle: SecretHandle
    ) : CredentialResolution()

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
 * @see dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
 */
interface CredentialSession : AutoCloseable {

    /**
     * The resolved credential environment map.
     * Keys are environment variable names, values are SecretHandle.
     */
    fun credentialEnv(): Map<String, SecretHandle>

    /**
     * Active handles that must be closed for cleanup.
     * These are handles returned by secretStore.getAsSecretHandle() for non-file kinds.
     */
    fun activeHandles(): List<SecretHandle>

    /**
     * The materializer for file-based credential cleanup.
     */
    fun materializer(): dev.rubentxu.pipeline.v2.credentials.multipart.CredentialMaterializer

    /**
     * Resolution outcome and boundaries.
     */
    fun boundaries(): ResolutionBoundaries

    /**
     * Closes the session, wiping all tracked handles and materializer paths.
     * Safe to call multiple times (idempotent).
     */
    override fun close()
}