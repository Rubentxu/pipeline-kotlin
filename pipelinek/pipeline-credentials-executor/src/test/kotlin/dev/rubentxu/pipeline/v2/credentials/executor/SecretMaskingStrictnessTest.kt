package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.api.SecretShorterThanMinimumMaskableLengthWarning
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What the policy does with a secret it cannot mask.
 *
 * ## The guarantee
 *
 * The threshold governs MASKING, and the strictness governs whether the run may
 * proceed with that fact:
 *
 *  - under [SecretMaskingStrictness.ADVISORY] the binding is used and the operator is
 *    told — the legacy posture, and the one a run with no durable output keeps;
 *  - under [SecretMaskingStrictness.STRICT] the binding is REFUSED before it is used,
 *    so the step never runs and the value never reaches a durable surface.
 *
 * ## Why this instrument
 *
 * The executor is the real one, over a provider and a projector that answer with a
 * three-character secret. The refusal is observed where it happens: as the
 * exception that leaves the bind, not as a flag somebody has to remember to read.
 */
class SecretMaskingStrictnessTest {

    @Test
    fun `the strict policy refuses the binding that would expose a secret it cannot mask`() {
        val executor = executorWith(strictness = SecretMaskingStrictness.STRICT, secret = "abc")

        val refusal = assertThrows(SecretMaskingRefusedException::class.java) {
            runBlocking { executor.bindSpecs(specs(), runId = "run-1", eventSink = NullEventSink) }
        }

        assertEquals(
            listOf(UnmaskableSecret(CredentialsId("cred-1"), 3)),
            refusal.unmaskable,
            "the refusal does not name the credential that cannot be masked",
        )
        assertEquals(
            SecretPatternRegistry.MIN_MASKABLE_LENGTH,
            refusal.minimumLength,
            "the refusal does not name the length a value needs to be masked",
        )
        assertTrue(
            refusal.message.orEmpty().contains("cred-1"),
            "the refusal does not name the credential: ${refusal.message}",
        )
        assertFalse(
            refusal.message.orEmpty().contains("abc"),
            "the refusal carries the secret it is refusing to expose",
        )
    }

    @Test
    fun `the strict policy refuses nothing when every secret can be masked`() {
        val executor = executorWith(strictness = SecretMaskingStrictness.STRICT, secret = "abcdefgh")

        val bound = runBlocking {
            executor.bindSpecs(specs(), runId = "run-1", eventSink = NullEventSink)
        }

        assertEquals(listOf(CredentialsId("cred-1")), bound.credentials())
        bound.close()
    }

    @Test
    fun `the advisory policy binds the value and tells the operator`() {
        val warnings = mutableListOf<SecretShorterThanMinimumMaskableLengthWarning>()
        val executor = executorWith(
            strictness = SecretMaskingStrictness.ADVISORY,
            secret = "abc",
            warnings = warnings,
        )

        val bound = runBlocking {
            executor.bindSpecs(specs(), runId = "run-1", eventSink = NullEventSink)
        }

        assertEquals(listOf(CredentialsId("cred-1")), bound.credentials())
        assertEquals(1, warnings.size, "the operator is not told that the secret is not masked")
        assertEquals("cred-1", warnings.single().credentialsId)
        assertEquals(3, warnings.single().length)
        bound.close()
    }

    @Test
    fun `the strict policy refuses the short secret even when the run masks nothing at all`() {
        val executor = executorWith(
            strictness = SecretMaskingStrictness.STRICT,
            secret = "abc",
            registry = null,
        )

        val refusal = assertThrows(SecretMaskingRefusedException::class.java) {
            runBlocking { executor.bindSpecs(specs(), runId = "run-1", eventSink = NullEventSink) }
        }

        assertEquals(
            listOf(UnmaskableSecret(CredentialsId("cred-1"), 3)),
            refusal.unmaskable,
            "the refusal depends on whether a registry is wired, and not on the value",
        )
    }

    private fun specs() = listOf(
        StringBindingSpec(CredentialsId("cred-1"), "TOKEN"),
    )

    private fun executorWith(
        strictness: SecretMaskingStrictness,
        secret: String,
        warnings: MutableList<SecretShorterThanMinimumMaskableLengthWarning> = mutableListOf(),
        registry: SecretPatternRegistry? = SecretPatternRegistry(),
    ) = WithCredentialsExecutor(
        provider = MaskingTextProvider,
        projector = CredentialProjector { _, _, _ ->
            ProjectionResult(
                bindings = mapOf("TOKEN" to SecretHandle.secret(secret.toByteArray(Charsets.UTF_8))),
            )
        },
        clock = MaskingFixedClock,
        redactionRegistry = registry,
        warning = { warnings += it },
        maskingStrictness = strictness,
    )
}

/** A fixed instant. The unit-test rule forbids a real clock; a constant is the fix. */
private object MaskingFixedClock : Clock {
    override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
}

/** Resolves every id to the same inert text. Only [CredentialProvider.resolveToCredential] is reached. */
private object MaskingTextProvider : CredentialProvider {
    override val providerId: String = "test"
    override fun resolve(id: CredentialsId): SecretHandle = SecretHandle.secret(id.value.toByteArray())
    override fun resolveToCredential(id: CredentialsId): Credential = SecretText(id, bytes = byteArrayOf(0x78))
    override fun close() = Unit
}
