package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.credentials.api.BasicCredentialResolution
import dev.rubentxu.pipeline.v2.credentials.api.BasicCredentialSource
import dev.rubentxu.pipeline.v2.credentials.api.BASIC_CREDENTIALS_CAPABILITY
import dev.rubentxu.pipeline.v2.credentials.api.CredentialStoreUnavailability
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Certificate
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.SecretFile
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * H5-B — the translation from a credential STORE to the narrow seam a plugin sees.
 *
 * The property that matters is not "a username/password comes out". It is:
 *
 * > No credential of any other kind can come out at all.
 *
 * A `CredentialProvider` will happily resolve a private key, a certificate or a
 * file. If this adapter ever grew a method that returned one, the narrowness the
 * whole design rests on would be a convention rather than a type.
 */
class BasicCredentialsCapabilityContributorTest {

    private val id = CredentialsId("deploy-token")

    /**
     * A store holding whatever the test puts in it.
     *
     * A missing name throws `SecretStoreTamperException("Credential not found: X")`
     * because that is EXACTLY what `LocalSecretStore.get` does — the same type it
     * throws for real tampering. A fake that threw something tidier would make the
     * adapter look better than it is and hide the exact ambiguity this block is
     * about.
     */
    private class FakeProvider(private val byId: Map<String, Credential>) : CredentialProvider {
        override val providerId: String = "fake"
        override fun resolve(id: CredentialsId): SecretHandle =
            throw UnsupportedOperationException("not used by this seam")

        override fun resolveToCredential(id: CredentialsId): Credential =
            byId[id.value] ?: throw dev.rubentxu.pipeline.v2.credentials.api
                .SecretStoreTamperException("Credential not found: ${id.value}")

        override fun close() = Unit
    }

    private fun sourceFrom(provider: CredentialProvider?): BasicCredentialSource =
        BasicCredentialsCapabilityContributor(provider)
            .capabilities().getValue(BASIC_CREDENTIALS_CAPABILITY)
            as BasicCredentialSource

    private fun sourceFor(vararg credentials: Credential): BasicCredentialSource =
        sourceFrom(FakeProvider(credentials.associateBy { it.id.value }))

    // ── the happy translation ──────────────────────────────────────────────

    @Test
    fun `a UsernamePassword resolves to a username and its bytes`() {
        val source = sourceFor(UsernamePassword(id, scope = CredentialScope.GLOBAL, username = "alice", password = "s3cr3t".toByteArray()))

        val resolution = source.resolve(id)

        assertTrue(resolution is BasicCredentialResolution.Resolved, "got $resolution")
        resolution as BasicCredentialResolution.Resolved
        assertEquals("alice", resolution.username)
        assertArrayEquals("s3cr3t".toByteArray(), resolution.password)
    }

    // ── every other kind is refused, and says which one it was ─────────────

    @Test
    fun `no other credential kind can come out of this seam`() {
        val others = listOf(
            SecretText(id, scope = CredentialScope.GLOBAL, bytes = "text".toByteArray()),
            SecretFile(id, scope = CredentialScope.GLOBAL, bytes = "file".toByteArray()),
            SshPrivateKey(id, scope = CredentialScope.GLOBAL, username = "git", privateKey = "pem".toByteArray()),
            Certificate(id, scope = CredentialScope.GLOBAL, keystore = "cert".toByteArray()),
        )

        for (credential in others) {
            val source = sourceFor(credential)
            val resolution = source.resolve(id)

            assertTrue(
                resolution is BasicCredentialResolution.KindUnsupported,
                "${credential::class.simpleName} must not come out of a username/password seam; " +
                    "got $resolution",
            )
            resolution as BasicCredentialResolution.KindUnsupported
            assertEquals(
                credential::class.simpleName,
                resolution.found,
                "the refusal must name what arrived so the operator can find it",
            )
            assertEquals(
                listOf("UsernamePassword"),
                resolution.supported,
                "and what would have worked",
            )
        }
    }

    // ── absence is not the same as no store ────────────────────────────────

    @Test
    fun `a name the store does not hold is StoreUnavailable, and NOT a guessed NotFound`() {
        val source = sourceFor(UsernamePassword(id, scope = CredentialScope.GLOBAL, username = "alice", password = "x".toByteArray()))

        val resolution = source.resolve(CredentialsId("something-else"))

        // The store throws SecretStoreTamperException("Credential not found: X") —
        // the same type it throws for actual tampering. Reporting NotFound would
        // mean reading that sentence, and one day the wording changes and a tampered
        // store reports itself as a typo. StoreUnavailable is true either way.
        assertTrue(
            resolution is BasicCredentialResolution.StoreUnavailable,
            "a missing name must not be reported as a wrong kind or a broken store; got $resolution",
        )
        assertEquals(
            CredentialStoreUnavailability.Unavailable,
            (resolution as BasicCredentialResolution.StoreUnavailable).reason,
        )
    }

    @Test
    fun `the adapter does not recover a fact by parsing the store's message`() {
        // The regression this pins: a store that says "Credential not found" in its
        // exception must NOT produce a NotFound, because the same exception type is
        // what a TAMPERED store produces.
        val lying = object : CredentialProvider {
            override val providerId: String = "lying"
            override fun resolve(id: CredentialsId): SecretHandle = throw StoreSaysNotFound()
            override fun resolveToCredential(id: CredentialsId): Credential = throw StoreSaysNotFound()
            override fun close() = Unit
        }
        val source = sourceFrom(lying)

        val resolution = source.resolve(id)

        assertTrue(
            resolution !is BasicCredentialResolution.NotFound,
            "a message that says 'not found' must not become a NotFound; got $resolution",
        )
    }

    private class StoreSaysNotFound :
        dev.rubentxu.pipeline.v2.credentials.api.SecretStoreTamperException(
            "Credential not found: deploy-token",
        )

    // ── no store: contributed anyway, refuses everything ───────────────────

    @Test
    fun `a run with no store still receives the seam and refuses with NotConfigured`() {
        val source = sourceFrom(null)

        val resolution = source.resolve(id)
        assertTrue(resolution is BasicCredentialResolution.StoreUnavailable, "got $resolution")
        assertEquals(
            CredentialStoreUnavailability.NotConfigured,
            (resolution as BasicCredentialResolution.StoreUnavailable).reason,
        )
    }

    @Test
    fun `the seam is contributed unconditionally, so a Step declaring it is admitted`() {
        // Withholding it would refuse admission for every Step that declares the
        // capability — including the ones that declare no credential and would never
        // call it — and the operator would be told their plugin was not wired.
        for (provider in listOf<CredentialProvider?>(null, FakeProvider(emptyMap()))) {
            val keys = BasicCredentialsCapabilityContributor(provider).capabilities().keys
            assertTrue(
                BASIC_CREDENTIALS_CAPABILITY in keys,
                "the seam must always be present; got $keys",
            )
        }
    }

    // ── nothing about the store leaks ──────────────────────────────────────

    @Test
    fun `a refusal carries no free text and no secret`() {
        val source = sourceFor(SecretText(id, scope = CredentialScope.GLOBAL, bytes = "super-secret-token".toByteArray()))

        val resolution = source.resolve(id)

        val rendered = resolution.toString()
        assertFalse(
            rendered.contains("super-secret-token"),
            "the credential's material leaked into the refusal: $rendered",
        )
        assertTrue(
            rendered.contains("SecretText"),
            "but the refusal must still say what kind arrived",
        )
    }

    @Test
    fun `a store-contract failure becomes Unavailable, and a provider bug still propagates`() {
        // A tampered store or a wrong passphrase is described by SecretStoreException,
        // so it becomes a typed "the store could not answer" rather than an exception
        // crossing a Step.
        val resolution = sourceFrom(TamperedStore()).resolve(id)
        assertTrue(
            resolution is BasicCredentialResolution.StoreUnavailable,
            "a store failure must not be reported as a tidy credential; got $resolution",
        )

        // Anything the store contract does NOT describe is a bug in the provider, and
        // hiding it as "unavailable" would make it unfindable.
        val thrown = runCatching { sourceFrom(BrokenProvider()).resolve(id) }.exceptionOrNull()
        assertTrue(
            thrown is ProviderBug,
            "a non-store exception must propagate rather than be reported as a tidy answer; got $thrown",
        )
    }

    private class BrokenProvider : CredentialProvider {
        override val providerId: String = "broken"
        override fun resolve(id: CredentialsId): SecretHandle = throw ProviderBug()
        override fun resolveToCredential(id: CredentialsId): Credential = throw ProviderBug()
        override fun close() = Unit
    }

    /** A store failing the way its own contract says it can: tampered, or locked. */
    private class TamperedStore : CredentialProvider {
        override val providerId: String = "tampered"
        override fun resolve(id: CredentialsId): SecretHandle = throw
            dev.rubentxu.pipeline.v2.credentials.api.SecretStoreTamperException("Invalid store magic")

        override fun resolveToCredential(id: CredentialsId): Credential = throw
            dev.rubentxu.pipeline.v2.credentials.api.SecretStoreTamperException("Invalid store magic")

        override fun close() = Unit
    }

    /** A store that fails in a way its own contract does not describe. */
    private class ProviderBug : RuntimeException("the provider has a null where a store should be")
}
