package dev.rubentxu.pipeline.v2.application.durable.credentials

import dev.rubentxu.pipeline.v2.credentials.executor.BoundCredentials
import dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.events.EventSink

/**
 * EM-7 / LFC-5.3 — Application adapter implementing [CredentialScopePort].
 *
 * Wraps the port-driven [WithCredentialsExecutor] (an adapter of the credential
 * SPI ports) so the canonical coordinator only names the application seam
 * contract. This adapter lives in `:pipeline-application` (the port's home);
 * dependency direction is inward: coordinator → port ← this adapter → executor.
 *
 * ## Acquire
 *
 * [acquire] delegates to [WithCredentialsExecutor.bindSpecs] with the typed
 * [CredentialBindingSpec] sealed specs the coordinator decoded from the
 * compiled node payload (no DSL round-trip). On success it wraps the returned
 * [WithCredentialsExecutor.BoundCredentials] in an [AcquiredCredentialScope]
 * whose [AcquiredCredentialScope.close] maps the executor's wipe outcome to the
 * typed [CredentialScopeCleanup]. On failure it returns a closed
 * [CredentialScopeOutcome.Unavailable] — the body is never dispatched
 * (fail-closed policy of the port).
 *
 * ## Cleanup
 *
 * [WithCredentialsExecutor.BoundCredentials.close] is idempotent and is the sole
 * owner of [CredentialUnbound][dev.rubentxu.pipeline.v2.events.CredentialUnbound]
 * emission; it throws a
 * [WithCredentialsExecutor.CleanupException] when a secure wipe fails. This
 * adapter converts that to [CredentialScopeCleanup.Failed] so the coordinator
 * finally can fold it into a typed operational `StepOutcome.Failure` per design §73.
 */
class WithCredentialsExecutorScopeAdapter(
    private val executor: WithCredentialsExecutor?,
    private val eventSink: EventSink,
) : CredentialScopePort {

    override suspend fun acquire(
        bindings: List<CredentialBindingSpec>,
        runId: RunId,
    ): CredentialScopeOutcome {
        val executorInstance = executor
            ?: return CredentialScopeOutcome.Unavailable(
                CredentialScopeFailure.StoreUnavailable(
                    "No WithCredentialsExecutor configured; credential scope cannot be acquired",
                ),
            )
        if (bindings.isEmpty()) {
            return CredentialScopeOutcome.Invalid(
                CredentialScopeFailure.BindingMismatch("withCredentials scope has no bindings"),
            )
        }
        return try {
            val bound = executorInstance.bindSpecs(
                specs = bindings,
                runId = runId.value,
                eventSink = eventSink,
            )
            CredentialScopeOutcome.Acquired(
                scope = BoundCredentialsScope(bound),
            )
        } catch (t: Throwable) {
            // Executor already balanced CredentialBound/Unbound events and
            // closed any partial projections; we only map to a closed outcome.
            CredentialScopeOutcome.Unavailable(
                CredentialScopeFailure.AcquisitionFailed(
                    "Credential scope acquisition failed: ${t.message}",
                ),
            )
        }
    }
}

/**
 * Adapter-produced [AcquiredCredentialScope] over an executor [WithCredentialsExecutor.BoundCredentials].
 *
 * Cleanup is delegated to the executor's idempotent [WithCredentialsExecutor.BoundCredentials.close]
 * and mapped to the typed [CredentialScopeCleanup]: wipe failure surfaces the
 * orphan paths as [CredentialScopeCleanup.Failed] instead of escaping as an
 * exception, so the coordinator's total outcome merge (design §73) never sees
 * an unhandled throw from the cleanup seam.
 */
private class BoundCredentialsScope(
    private val bound: BoundCredentials,
) : AcquiredCredentialScope {

    override val env: Map<String, SecretHandle> = bound.env()
    override val credentials: List<CredentialsId> = bound.credentials()

    override fun close(): CredentialScopeCleanup = try {
        bound.close()
        CredentialScopeCleanup.Cleaned
    } catch (e: BoundCredentials.CleanupException) {
        CredentialScopeCleanup.Failed(
            orphanPaths = e.orphanPaths,
            message = e.detail,
        )
    } catch (t: Throwable) {
        CredentialScopeCleanup.Failed(
            orphanPaths = emptyList(),
            message = "Unexpected credential cleanup failure: ${t.message}",
        )
    }
}
