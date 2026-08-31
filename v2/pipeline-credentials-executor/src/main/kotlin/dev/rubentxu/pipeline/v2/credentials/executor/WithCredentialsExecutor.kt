package dev.rubentxu.pipeline.v2.credentials.executor

/**
 * H0 Slice 1: WithCredentialsExecutor - binding seam provider.
 *
 * This executor is PASSIVE - it only opens [CredentialSession] objects.
 * All execution control remains in [dev.rubentxu.pipeline.v2.application.PipelineRun]:
 * - ShOptions construction
 * - outer StepStarted emission
 * - inner recursive durable loop
 * - CredentialUsed timing
 * - session close in finally
 *
 * ## Architecture (H0 Slice 1)
 *
 * No executor callback to application. No executor dependency/import on
 * application, local adapter, or step-sdk runtime. This module depends only on:
 * - pipeline-domain (for SecretHandle, BoundPurpose, CredentialsId, Clock)
 * - pipeline-events (for EventSink)
 * - pipeline-credentials-api (for SecretStore)
 * - pipeline-credentials-multipart (for CredentialMaterializer)
 *
 * @see CredentialSession for the binding seam interface
 */
class WithCredentialsExecutor(
    private val secretStore: dev.rubentxu.pipeline.v2.credentials.api.SecretStore,
    private val eventSink: dev.rubentxu.pipeline.v2.events.EventSink,
) {
    /**
     * Opens a [CredentialSession] for the given bindings.
     *
     * Resolution happens eagerly on session open. The session provides:
     * - [CredentialSession.credentialEnv]: env map for ShOptions injection
     * - [CredentialSession.boundaries]: resolution outcome per binding
     * - [CredentialSession.activeHandles] + [CredentialSession.materializer]: cleanup
     *
     * PipelineRun is responsible for:
     * - Emitting CredentialBound events (done by session on open)
     * - Constructing effectiveShOptions with credentialEnv
     * - Emitting StepStarted for the outer WithCredentialsBlock
     * - Executing inner steps
     * - Emitting CredentialUsed after successful inner steps
     * - Emitting CredentialUnbound in finally
     * - Closing the session in finally
     *
     * @param bindings The credentials bindings to resolve
     * @param runId The run ID for event context
     * @param clock Clock for event timestamps
     * @return A [CredentialSession] with resolved credentials
     */
    fun openSession(
        bindings: List<CredentialsBinding>,
        runId: String,
        clock: dev.rubentxu.pipeline.v2.domain.durable.Clock
    ): CredentialSession {
        return CredentialSessionImpl(
            bindings = bindings,
            secretStore = secretStore,
            eventSink = eventSink,
            runId = runId,
            clock = clock
        )
    }
}