package dev.rubentxu.pipeline.v2.runtime.durable.credentials

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.executor.SecretMaskingStrictness
import dev.rubentxu.pipeline.v2.credentials.executor.UnmaskableSecret
import dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialProjector
import dev.rubentxu.pipeline.v2.domain.credentials.ProjectionResult
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.StringBindingSpec
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.NullEventSink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What the engine seam does with a secret it cannot mask.
 *
 * ## The guarantee
 *
 * The refusal of the credentials layer reaches the coordinator as its OWN typed failure,
 * and the coordinator is left with a scope it cannot use: the step body is never
 * dispatched, because the outcome is [CredentialScopeOutcome.Unavailable] and not a scope.
 *
 * ## Why this instrument
 *
 * The adapter is the real one and the executor under it is the real one. Only the
 * provider and the projector are fakes, and they answer with a three-character secret:
 * the value that the registry cannot mask. What is observed is the outcome the
 * coordinator actually receives.
 *
 * The assertion that matters is the TYPE of the outcome. A failure that arrives as a
 * generic acquisition error would still stop the body, but it would lose the fact that
 * the credential was too short to mask, and that fact is the one the operator needs.
 */
class SecretNotMaskableScopeTest {

    @Test
    fun `the refusal of a secret that cannot be masked hands over no scope`() = runBlocking {
        val adapter = WithCredentialsExecutorScopeAdapter(
            executor = strictExecutor(secret = "abc"),
            eventSink = NullEventSink,
        )

        val outcome = adapter.acquire(
            bindings = listOf(StringBindingSpec(CredentialsId("cred-1"), "TOKEN")),
            runId = RunId("run-1"),
        )

        val unavailable = assertInstanceOf(
            CredentialScopeOutcome.Unavailable::class.java,
            outcome,
            "the coordinator received a scope for a secret the product cannot mask",
        )
        val failure = assertInstanceOf(
            CredentialScopeFailure.SecretNotMaskable::class.java,
            unavailable.failure,
            "the refusal does not arrive as its own typed failure",
        )
        assertEquals(
            listOf(UnmaskableSecret(CredentialsId("cred-1"), 3)),
            failure.unmaskable,
            "the failure does not name the credential that cannot be masked",
        )
        assertEquals(
            8,
            failure.minimumLength,
            "the failure does not name the length a value needs to be masked",
        )
    }

    @Test
    fun `the refusal of a secret that cannot be masked keeps the value out of the failure`() = runBlocking {
        val adapter = WithCredentialsExecutorScopeAdapter(
            executor = strictExecutor(secret = "abc"),
            eventSink = NullEventSink,
        )

        val outcome = adapter.acquire(
            bindings = listOf(StringBindingSpec(CredentialsId("cred-1"), "TOKEN")),
            runId = RunId("run-1"),
        )

        val unavailable = outcome as CredentialScopeOutcome.Unavailable
        assertEquals(
            false,
            unavailable.failure.toString().contains("abc"),
            "the failure about a secret carries the secret",
        )
    }

    @Test
    fun `the refusal of a secret that cannot be masked is a failure the operator has to fix`() = runBlocking {
        val admission = CredentialLeaseAdmission(
            WithCredentialsExecutorScopeAdapter(
                executor = strictExecutor(secret = "abc"),
                eventSink = NullEventSink,
            ),
        )

        val refused = assertInstanceOf(
            LeaseAdmission.Refused::class.java,
            admission.admit(
                bindings = listOf(StringBindingSpec(CredentialsId("cred-1"), "TOKEN")),
                runId = RunId("run-1"),
            ),
            "a secret the product cannot mask was admitted",
        )

        assertEquals(
            FailureKind.USER,
            refused.failureKind,
            "the operator has to change the credential: no worker and no retry makes a short value longer",
        )
        assertTrue(
            refused.message.contains("cred-1"),
            "the refusal does not name the credential: ${refused.message}",
        )
        assertFalse(
            refused.message.contains("abc"),
            "the refusal carries the secret it is refusing to expose",
        )
    }

    private fun strictExecutor(secret: String) = WithCredentialsExecutor(
        provider = ScopeTextProvider,
        projector = CredentialProjector { _, _, _ ->
            ProjectionResult(
                bindings = mapOf("TOKEN" to SecretHandle.secret(secret.toByteArray(Charsets.UTF_8))),
            )
        },
        clock = ScopeFixedClock,
        redactionRegistry = SecretPatternRegistry(),
        maskingStrictness = SecretMaskingStrictness.STRICT,
    )
}

/** A fixed instant. The unit-test rule forbids a real clock; a constant is the fix. */
private object ScopeFixedClock : Clock {
    override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
}

/** Resolves every id to inert text. Only [CredentialProvider.resolve] is reached. */
private object ScopeTextProvider : CredentialProvider {
    override val providerId: String = "test"
    override fun resolve(id: CredentialsId): SecretHandle = SecretHandle.secret(id.value.toByteArray())
    override fun resolveToCredential(id: CredentialsId): Credential = SecretText(id, bytes = byteArrayOf(0x78))
    override fun close() = Unit
}
