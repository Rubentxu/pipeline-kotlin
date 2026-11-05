package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.api.CredentialScope
import dev.rubentxu.pipeline.v2.credentials.api.CredentialsBinding
import dev.rubentxu.pipeline.v2.credentials.api.CredentialsStoreProjectionException
import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreLinkedSecretResolver
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope as CredentialUsageScope
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.NullEventSink
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * The store handing a credential over as ONE secret.
 *
 * ## The guarantee
 * A credential that IS one secret reaches the caller as its own bytes and
 * nothing else. A credential that is NOT one secret is refused, with a
 * rejection that names the kind the store holds, and its bytes never leave
 * the store.
 *
 * ## What the defect was
 * A v2 entry holds the credential in the store's canonical form: a part
 * count, a part name, a length and the payload. The read path used to hand
 * THAT over as the secret, so a caller received the envelope instead of the
 * secret it asked for. The consequence was not a crash but a wrong secret:
 * the scope that binds a login splits the bytes it receives at the first
 * null, and the envelope's null is not where the login's null is.
 *
 * ## Why the rule is not invented here
 * Two KDoc in this repository already state it: the linked-secret resolver
 * promises the bytes of a SecretText and says that the other kinds are
 * refused with a typed exception, and the store's part accessor says the same
 * about the credential a link points at. This test holds the code to what
 * they promise.
 *
 * ## Why this test is an INTEGRATION test
 * The fact under test is what a file carries: an entry written by the store
 * and read back by it. A unit test that fabricated the bytes of a v2 entry
 * would be a second authority on the format, and a second authority is how
 * the format and the reader drift apart.
 *
 * ## What is faked
 * The world outside the process and nothing else. The store file is a real
 * file in a directory the test owns. The passphrase is a constant the test
 * supplies through the resolver's environment map, so no terminal and no
 * operator is ever asked for one. The clock is a fixed instant and the audit
 * sink discards its events: neither is what this test is about.
 */
class CredentialsStoreProjectionTest {

    /** The home directory the store's path authority resolves against. */
    @TempDir
    lateinit var home: Path

    private val passphrase = "a passphrase this test owns"

    /**
     * The store file, as the one authority for the store's location names it.
     */
    private fun storeFile(): Path =
        CredentialsStorePath.resolve(envValue = null, userHome = home)

    /**
     * A store over [storeFile], opened with the passphrase the resolver
     * supplies for the environment this test owns.
     */
    private fun openStore(): LocalSecretStore =
        LocalSecretStore(
            file = storeFile(),
            passphrase = PassphraseResolver.resolve(
                env = mapOf(PASSPHRASE_ENV to passphrase),
                ttyReader = { null },
            ),
        )

    @Test
    fun `a secret read as a handle carries the secret and not the store's serialization`() {
        val id = CredentialsId("api-key")
        val secret = "s3cr3t".toByteArray()
        val store = openStore()
        store.add(id, SecretText(id, CredentialUsageScope.GLOBAL, secret))

        val carried = store.getAsSecretHandle(id).bytesView()

        assertArrayEquals(
            secret,
            carried,
            "the handle carried something other than the secret: ${carried.toList()}",
        )
    }

    @Test
    fun `a store written in v1 hands over the secret it holds`() {
        val id = CredentialsId("api-key")
        val store = openStore()
        store.put(id, "s3cr3t".toByteArray())
        store.close()

        val carried = openStore().getAsSecretHandle(id).bytesView()

        assertArrayEquals(
            "s3cr3t".toByteArray(),
            carried,
            "the v1 entry did not hand over the secret it holds: ${carried.toList()}",
        )
    }

    @Test
    fun `a credential that is not one secret is refused and its bytes never leave the store`() {
        val id = CredentialsId("login")
        val store = openStore()
        store.add(id, UsernamePassword(id, CredentialUsageScope.GLOBAL, "admin", "s3cr3t".toByteArray()))

        val failure = assertThrows<CredentialsStoreProjectionException> {
            store.getAsSecretHandle(id)
        }

        assertEquals(
            "UsernamePassword",
            failure.foundKind,
            "the refusal does not name the kind the store holds",
        )
        assertTrue(
            failure.message?.contains("not a single secret") == true,
            "the refusal does not say why the credential cannot be one secret; it said: ${failure.message}",
        )
        assertFalse(
            failure.message.orEmpty().contains("s3cr3t"),
            "the refusal carries the secret it refused to hand over",
        )
    }

    @Test
    fun `the linked secret resolver hands over the secret and not the store's serialization`() {
        val id = CredentialsId("passphrase")
        val store = openStore()
        store.add(id, SecretText(id, CredentialUsageScope.GLOBAL, "s3cr3t".toByteArray()))

        val carried = SecretStoreLinkedSecretResolver(store).resolve(LinkedSecretRef(id)).bytesView()

        assertArrayEquals(
            "s3cr3t".toByteArray(),
            carried,
            "the resolver handed over something other than the secret: ${carried.toList()}",
        )
    }

    @Test
    fun `the scope that binds a login hands over the two halves and not a blob`() {
        val id = CredentialsId("login")
        val store = openStore()
        store.add(id, SecretText(id, CredentialUsageScope.GLOBAL, "admin\u0000s3cr3t".toByteArray()))

        val scope = CredentialScope(store, NullEventSink, FixedClock, "run-1")
        val bound = scope.env(CredentialsBinding.usernamePassword(id, "DB_USER", "DB_PASS"))

        assertEquals(
            "admin",
            String(bound.getValue("DB_USER").bytesView(), Charsets.UTF_8),
            "the username variable does not carry the username",
        )
        assertEquals(
            "s3cr3t",
            String(bound.getValue("DB_PASS").bytesView(), Charsets.UTF_8),
            "the password variable does not carry the password",
        )
    }

    /** The clock the scope reads: a fixed instant, because time is not under test. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
    }

    private companion object {
        /** The environment variable the passphrase resolver reads. */
        const val PASSPHRASE_ENV = "PIPELINE_STORE_PASSPHRASE"
    }
}
