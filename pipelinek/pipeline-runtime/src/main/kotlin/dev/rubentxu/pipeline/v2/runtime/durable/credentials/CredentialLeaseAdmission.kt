package dev.rubentxu.pipeline.v2.runtime.durable.credentials

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
 * The decision is closed and total: nothing else can come out of the port, and every refusal
 * carries a [FailureKind] and a sentence. The body of a refused lease is never dispatched —
 * that is the caller's job to honour, and the only thing this class owes it is a refusal it
 * can report.
 *
 * The KIND of a refusal is decided by the FAILURE, not by the envelope that carried it. The
 * envelope says what the caller must do (the body does not run either way); the failure says
 * what happened, and a dashboard that sends the operator to the wrong place is a defect. A
 * store that is not there is [FailureKind.INFRASTRUCTURE] because the machine failed; a
 * credential whose value is too short to mask is [FailureKind.USER] because the operator has
 * to change the credential — no retry and no new worker will make the value longer.
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
                failureKind = acquisition.failure.failureKind(),
                message = acquisition.failure.describe(),
            )
            is CredentialScopeOutcome.Invalid -> LeaseAdmission.Refused(
                failureKind = acquisition.failure.failureKind(),
                message = acquisition.failure.describe(),
            )
            is CredentialScopeOutcome.Acquired -> LeaseAdmission.Acquired(acquisition.scope)
        }
}

/**
 * The kind of the refusal, in the run's taxonomy.
 *
 * The FAILURE decides the kind, not the envelope that carried it. The envelope says
 * whether a scope could be handed over at all; the kind says what the operator has to
 * change, and the two are not the same question. A store that is not there and a
 * projection that broke are machinery problems ([FailureKind.INFRASTRUCTURE]); a binding
 * that does not conform is a declaration problem ([FailureKind.SCHEMA]); a secret whose
 * value is too short to mask is a problem with the VALUE ([FailureKind.USER]) — no
 * worker and no retry makes a three-character secret longer.
 */
private fun CredentialScopeFailure.failureKind(): FailureKind = when (this) {
    is CredentialScopeFailure.StoreUnavailable -> FailureKind.INFRASTRUCTURE
    is CredentialScopeFailure.AcquisitionFailed -> FailureKind.INFRASTRUCTURE
    is CredentialScopeFailure.BindingMismatch -> FailureKind.SCHEMA
    is CredentialScopeFailure.CredentialMissing -> FailureKind.SCHEMA
    is CredentialScopeFailure.SecretNotMaskable -> FailureKind.USER
    CredentialScopeFailure.ReplayUnsupported -> FailureKind.SCHEMA
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
    is CredentialScopeFailure.SecretNotMaskable ->
        "Credential cannot be masked: " +
            unmaskable.joinToString(", ") { "'${it.credentialsId}' is ${it.length} characters" } +
            "; a secret needs at least $minimumLength characters to be masked"
    CredentialScopeFailure.ReplayUnsupported -> "Replay of an in-flight credential scope is not supported"
}
