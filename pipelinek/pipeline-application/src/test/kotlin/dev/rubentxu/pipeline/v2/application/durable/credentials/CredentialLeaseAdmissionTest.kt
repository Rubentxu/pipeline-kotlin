package dev.rubentxu.pipeline.v2.application.durable.credentials

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.StringBindingSpec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A refused credential lease is refused in the run's own taxonomy, and the refusal
 * says exactly one thing about itself.
 *
 * The port answers with a closed [CredentialScopeOutcome]: acquired, unavailable or
 * invalid. The admission translates that into the run's vocabulary, and the
 * translation is TOTAL: an unavailable store is an infrastructure failure, invalid
 * bindings are a schema failure, and there is no third answer.
 *
 * The refusal text is computed ONCE, by the owner of the decision, because a caller
 * that reports the refusal twice — once as an event and once as the outcome — must
 * not read the failure twice to build two copies of the same sentence. And the text
 * never names a secret: a refusal is observable, and an observable refusal must not
 * leak.
 *
 * Pure and total: the port here answers from a fixed value, and nothing touches a
 * disk, a clock or a process.
 */
class CredentialLeaseAdmissionTest {

    private val bindings = listOf(
        StringBindingSpec(credentialsId = CredentialsId("cred-1"), variable = "TOKEN"),
    )

    private val runId = RunId("run-1")

    @Test
    @DisplayName("an unavailable store refuses the lease as an infrastructure failure")
    fun anUnavailableStoreRefusesTheLeaseAsAnInfrastructureFailure() = runBlocking {
        val admission = CredentialLeaseAdmission(
            FixedScopePort(
                CredentialScopeOutcome.Unavailable(
                    CredentialScopeFailure.StoreUnavailable("no credential store is configured"),
                ),
            ),
        )

        val refused = assertInstanceOf(
            LeaseAdmission.Refused::class.java,
            admission.admit(bindings, runId),
            "an unavailable store never admits a lease",
        )

        assertEquals(
            FailureKind.INFRASTRUCTURE,
            refused.failureKind,
            "the store being absent is an infrastructure failure, not a bad request",
        )
        assertEquals(
            "no credential store is configured",
            refused.message,
            "the refusal carries the sentence the store reported",
        )
    }

    @Test
    @DisplayName("invalid bindings refuse the lease as a schema failure")
    fun invalidBindingsRefuseTheLeaseAsASchemaFailure() = runBlocking {
        val admission = CredentialLeaseAdmission(
            FixedScopePort(
                CredentialScopeOutcome.Invalid(
                    CredentialScopeFailure.BindingMismatch("the binding names an unknown kind"),
                ),
            ),
        )

        val refused = assertInstanceOf(
            LeaseAdmission.Refused::class.java,
            admission.admit(bindings, runId),
            "invalid bindings never admit a lease",
        )

        assertEquals(
            FailureKind.SCHEMA,
            refused.failureKind,
            "a malformed binding is a schema failure",
        )
        assertEquals(
            "the binding names an unknown kind",
            refused.message,
            "the refusal carries the sentence the port reported",
        )
    }

    @Test
    @DisplayName("an acquired scope is admitted with the scope it was given")
    fun anAcquiredScopeIsAdmittedWithTheScopeItWasGiven() = runBlocking {
        val scope = FakeScope()
        val admission = CredentialLeaseAdmission(
            FixedScopePort(CredentialScopeOutcome.Acquired(scope)),
        )

        val acquired = assertInstanceOf(
            LeaseAdmission.Acquired::class.java,
            admission.admit(bindings, runId),
            "an acquired scope is admitted",
        )

        assertEquals(
            scope,
            acquired.scope,
            "the admitted scope is the very scope the port handed over",
        )
    }

    @Test
    @DisplayName("a missing credential is named in the refusal")
    fun aMissingCredentialIsNamedInTheRefusal() = runBlocking {
        val admission = CredentialLeaseAdmission(
            FixedScopePort(
                CredentialScopeOutcome.Invalid(
                    CredentialScopeFailure.CredentialMissing(CredentialsId("cred-9")),
                ),
            ),
        )

        val refused = assertInstanceOf(
            LeaseAdmission.Refused::class.java,
            admission.admit(bindings, runId),
            "a missing credential never admits a lease",
        )

        assertEquals(
            FailureKind.SCHEMA,
            refused.failureKind,
            "a credential that is absent from the store is a schema failure, " +
                "not an infrastructure one: the store answered, and its answer was 'no such thing'",
        )
        assertEquals(
            "Credential 'cred-9' is not present in the store",
            refused.message,
            "the refusal names the credential that is missing",
        )
    }
}

/** A port that always answers the same outcome. */
private class FixedScopePort(private val outcome: CredentialScopeOutcome) : CredentialScopePort {
    override suspend fun acquire(
        bindings: List<CredentialBindingSpec>,
        runId: RunId,
    ): CredentialScopeOutcome = outcome
}

/** An acquired scope that materializes nothing: the env overlay is empty. */
private class FakeScope : AcquiredCredentialScope {
    override val env: Map<String, SecretHandle> = emptyMap()
    override val credentials: List<CredentialsId> = listOf(CredentialsId("cred-1"))

    override fun close(): CredentialScopeCleanup = CredentialScopeCleanup.Cleaned
}
