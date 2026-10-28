package dev.rubentxu.pipeline.v2.application.durable.credentials

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec

/**
 * Whether a credential lease was ADMITTED, and what the refusal says when it was not.
 *
 * Closed because "the lease was admitted" and "the lease was refused" are opposite outcomes,
 * and because a refusal that only carried a message would lose the KIND of the refusal — and
 * the kind is what the run's failure taxonomy is written in. A refusal therefore carries both
 * the [FailureKind] and the text, and the text is computed ONCE, by the owner of the decision.
 */
internal sealed interface LeaseAdmission {
    /** The lease was admitted; the scope carries the env overlay and the cleanup handle. */
    data class Acquired(val scope: AcquiredCredentialScope) : LeaseAdmission

    /**
     * The lease was refused. The body MUST NOT run.
     *
     * @property failureKind the kind of the refusal, in the run's taxonomy.
     * @property message the text of the refusal. It never contains a secret value.
     */
    data class Refused(
        val failureKind: FailureKind,
        val message: String,
    ) : LeaseAdmission
}

/**
 * The OWNER of the credential-lease admission decision: it asks the scope port for a lease and
 * it says, in the run's taxonomy, whether the lease was admitted.
 *
 * The decision is closed and total: an unavailable store is an [FailureKind.INFRASTRUCTURE]
 * refusal, invalid bindings are a [FailureKind.SCHEMA] refusal, and nothing else can come out
 * of the port. The body of a refused lease is never dispatched — that is the caller's job to
 * honour, and the only thing this class owes it is a refusal it can report.
 */
internal class CredentialLeaseAdmission(
    private val credentialScopePort: CredentialScopePort,
) {
    /**
     * Asks for the lease of these bindings and says whether it was admitted.
     *
     * The refusal text is computed once, here: a caller that reports the refusal twice — once
     * as an event and once as the outcome — must not read the failure twice to build two
     * copies of the same sentence.
     */
    suspend fun admit(
        bindings: List<CredentialBindingSpec>,
        runId: RunId,
    ): LeaseAdmission =
        when (val acquisition = credentialScopePort.acquire(bindings, runId)) {
            is CredentialScopeOutcome.Unavailable -> LeaseAdmission.Refused(
                failureKind = FailureKind.INFRASTRUCTURE,
                message = acquisition.failure.describe(),
            )
            is CredentialScopeOutcome.Invalid -> LeaseAdmission.Refused(
                failureKind = FailureKind.SCHEMA,
                message = acquisition.failure.describe(),
            )
            is CredentialScopeOutcome.Acquired -> LeaseAdmission.Acquired(acquisition.scope)
        }
}

/**
 * The sentence a refusal carries.
 *
 * It names the credential that is missing when that is what happened, and never names a
 * secret value: a refusal is observable, and an observable refusal must not leak.
 */
private fun CredentialScopeFailure.describe(): String = when (this) {
    is CredentialScopeFailure.StoreUnavailable -> message
    is CredentialScopeFailure.CredentialMissing -> "Credential '${credentialsId.value}' is not present in the store"
    is CredentialScopeFailure.BindingMismatch -> message
    is CredentialScopeFailure.AcquisitionFailed -> message
    CredentialScopeFailure.ReplayUnsupported -> "Replay of an in-flight credential scope is not supported"
}
