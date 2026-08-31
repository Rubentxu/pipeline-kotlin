package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.dsl.StepSpec

/**
 * Orchestrates withCredentials block execution.
 *
 * This is a thin delegation layer — for Slice 1 (H0) the actual execution
 * is still performed inline in PipelineRun.kt. Slice 2 will switch to
 * port-based execution via [dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider].
 *
 * Zero behavior change in Slice 1 — only module extraction skeleton.
 */
class WithCredentialsExecutor(
    private val secretStore: dev.rubentxu.pipeline.v2.credentials.api.SecretStore,
    private val materializer: dev.rubentxu.pipeline.v2.credentials.multipart.CredentialMaterializer,
    private val eventSink: dev.rubentxu.pipeline.v2.events.EventSink,
) {
    /**
     * Opens a credential session for the given bindings.
     *
     * The session is the SINGLE canonical session type that owns credential resolution
     * and cleanup. It accepts [StepSpec.CredentialsBinding] directly from the DSL.
     *
     * ## Architecture (H0 Slice 1)
     *
     * - Executor is PASSIVE: opens session, returns credentialEnv + boundaries + cleanup
     * - PipelineRun RETAINS: StepStarted, inner loop, CredentialUsed timing, closes session
     * - Session emits CredentialBound events on open()
     *
     * ## Cleanup semantics
     *
     * The returned [CredentialSession] is the SINGLE cleanup owner. Public callers
     * MUST call [CredentialSession.close] when done and MUST NOT independently
     * emit CredentialUnbound events.
     *
     * @param bindings The credentials bindings from StepSpec.WithCredentialsBlock
     * @param runId The run ID for event context
     * @param clock Clock for event timestamps
     * @return A new CredentialSession instance
     * @throws CredentialResolutionException if any credential cannot be resolved
     */
    fun openSession(
        bindings: List<StepSpec.CredentialsBinding>,
        runId: String,
        clock: Clock
    ): CredentialSession {
        return CredentialSessionImpl(
            bindings = bindings,
            secretStore = secretStore,
            eventSink = eventSink,
            runId = runId,
            clock = clock
        )
    }

    /**
     * Executes a withCredentials block.
     *
     * For Slice 1, this is a placeholder. The actual execution is still inline
     * in PipelineRun.kt. Slice 2 will implement this via port-based delegation.
     *
     * @return "success" or "failure"
     */
    suspend fun execute(): String {
        // Slice 1: placeholder - actual execution is inline in PipelineRun.kt
        // Slice 2: will delegate to port-based implementation
        return "success"
    }
}
