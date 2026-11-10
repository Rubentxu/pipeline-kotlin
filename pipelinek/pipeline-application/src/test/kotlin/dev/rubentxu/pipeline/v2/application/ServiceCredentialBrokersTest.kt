package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.local.CredentialsStorePath
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * How the service gets the credentials it hands to the brokers of its runs.
 *
 * ## The guarantee under trial
 *
 * That a service whose authorizations grant no credential never opens the store, so it never
 * holds the key to secrets it will not hand out; that a service that does grant one starts only
 * with a store it has opened and unlocked; and that every way in which the store cannot be used
 * is a refusal of fixed words that contains neither the passphrase nor a byte read from the store.
 *
 * ## The capacity this serves
 *
 * A running execution asks for a credential when it enters `withCredentials`. For the broker of
 * the run to have something to answer from, the service has to have opened the local store BEFORE
 * any run starts, and a service that could not must say so when it starts and not refuse every
 * request of every run for the rest of its life.
 *
 * ## Why this instrument
 *
 * The opening is tried with a real store sealed with a small passphrase, because what it decides
 * —whether this passphrase unlocks this file— is a property of the store and not of a double. The
 * composition is tried with a double provider, because what it decides —whether to open at all,
 * and what to give back when the root is too deep— needs no store.
 *
 * ## What this does not freeze
 *
 * The words of the refusals, beyond what they must not contain, and the cost of the derivation of
 * the key.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ServiceCredentialBrokersTest {

    @TempDir
    lateinit var tmp: Path

    // --- opening the store ---

    @Test
    fun `a store opened with its passphrase hands out the secret it holds`() {
        val store = seededStore()

        val access = openServiceCredentialProvider(environment(store, PASSPHRASE))

        val opened = assertInstanceOf(ServiceCredentialAccess.Opened::class.java, access)
        try {
            val credential = opened.provider.resolveToCredential(CredentialsId("deploy-key"))
            val text = assertInstanceOf(SecretText::class.java, credential)
            assertArrayEquals(SECRET, text.bytes)
        } finally {
            opened.provider.close()
        }
    }

    @Test
    fun `a store that is not there refuses and says how to create it`() {
        val missing = tmp.resolve("absent").resolve("credentials.bin")

        val access = openServiceCredentialProvider(environment(missing, PASSPHRASE))

        val refused = assertInstanceOf(ServiceCredentialAccess.Refused::class.java, access)
        assertTrue(refused.detail.contains("credentials add"), "the refusal names the command that creates the store")
        assertFalse(Files.exists(missing), "looking for the store never creates it")
    }

    @Test
    fun `no passphrase in the environment refuses and asks nobody`() {
        val store = seededStore()

        val access = openServiceCredentialProvider(mapOf(CredentialsStorePath.ENV_VAR to store.toString()))

        val refused = assertInstanceOf(ServiceCredentialAccess.Refused::class.java, access)
        assertTrue(refused.detail.contains("no passphrase"), "the refusal says what is missing: ${refused.detail}")
    }

    @Test
    fun `a wrong passphrase refuses at the start and the refusal holds neither passphrase nor secret`() {
        val store = seededStore()

        val access = openServiceCredentialProvider(environment(store, "not-the-passphrase"))

        val refused = assertInstanceOf(ServiceCredentialAccess.Refused::class.java, access)
        assertTrue(refused.detail.contains("passphrase did not match"), "the refusal says why: ${refused.detail}")
        assertFalse(refused.detail.contains("not-the-passphrase"), "the passphrase the operator typed is not echoed")
        assertFalse(refused.detail.contains(String(SECRET)), "no byte of the store is in a refusal")
    }

    @Test
    fun `a store that is open to others is closed to them by the service that opens it`() {
        val store = seededStore()
        Files.setPosixFilePermissions(store, PosixFilePermissions.fromString("rw-r--r--"))

        val access = openServiceCredentialProvider(environment(store, PASSPHRASE))

        val opened = assertInstanceOf(ServiceCredentialAccess.Opened::class.java, access)
        opened.provider.close()
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(store),
            "the store the service holds is private to its owner, as the guard of the store leaves it",
        )
    }

    @Test
    fun `a file that is not a store refuses and the refusal holds none of its content`() {
        val garbage = tmp.resolve("garbage.bin")
        Files.write(
            garbage,
            "this is not a credential store ".repeat(8).toByteArray(),
        )
        Files.setPosixFilePermissions(garbage, PosixFilePermissions.fromString("rw-------"))

        val access = openServiceCredentialProvider(environment(garbage, PASSPHRASE))

        val refused = assertInstanceOf(ServiceCredentialAccess.Refused::class.java, access)
        assertEquals("the credential store did not pass its integrity check", refused.detail)
        assertFalse(refused.detail.contains("this is not"), "nothing read from the file is in the refusal")
    }

    // --- composing the brokers ---

    @Test
    fun `a service that grants no credential never opens the store`() {
        val opened = AtomicInteger(0)

        val composed = composeServiceBrokers(
            authorizations = authorizations(grants = emptySet()),
            brokersRoot = tmp.resolve("brokers"),
            environment = mapOf(CredentialsStorePath.ENV_VAR to tmp.resolve("absent").toString()),
            opening = {
                opened.incrementAndGet()
                ServiceCredentialAccess.Refused("the store would have been opened")
            },
        )

        val result = assertInstanceOf(ServiceBrokers.Composed::class.java, composed)
        assertNull(result.factory, "nobody was granted a credential, so there is no broker to open")
        assertNull(result.provider, "and no provider to close")
        assertEquals(0, opened.get(), "the passphrase of the operator is not asked for")
    }

    @Test
    fun `a service that grants a credential opens the store once and keeps the provider it got`() {
        val provider = ProviderDouble()
        val asked = mutableListOf<Map<String, String>>()
        val environment = mapOf("MARKER" to "the-environment-of-the-service")

        val composed = composeServiceBrokers(
            authorizations = authorizations(grants = GRANTS),
            brokersRoot = tmp.resolve("brokers"),
            environment = environment,
            opening = {
                asked += it
                ServiceCredentialAccess.Opened(provider)
            },
        )

        val result = assertInstanceOf(ServiceBrokers.Composed::class.java, composed)
        assertNotNull(result.factory, "a granted credential needs a broker per run")
        assertSame(provider, result.provider, "the service owns the provider it was given")
        assertEquals(listOf(environment), asked, "the store is opened once, from the environment of the service")
        assertFalse(provider.closed.get(), "the provider stays open while the service runs")
    }

    @Test
    fun `a service that serves connected controllers does not open the store at all`() {
        val opened = AtomicInteger(0)

        val composed = composeServiceBrokers(
            authorizations = authorizations(grants = GRANTS),
            brokersRoot = tmp.resolve("brokers"),
            environment = mapOf(CredentialsStorePath.ENV_VAR to tmp.resolve("store.bin").toString()),
            opening = {
                opened.incrementAndGet()
                ServiceCredentialAccess.Opened(ProviderDouble())
            },
            connectedOnly = true,
        )

        val result = assertInstanceOf(ServiceBrokers.Composed::class.java, composed)
        assertNotNull(result.factory, "a granted credential needs a broker per run")
        assertNull(result.provider, "and no provider behind it: the controller is the only source")
        assertEquals(
            0,
            opened.get(),
            "the store is not opened, so the passphrase of the operator is not even asked for",
        )
    }

    @Test
    fun `a store that cannot be opened is a service that does not start`() {
        val composed = composeServiceBrokers(
            authorizations = authorizations(grants = GRANTS),
            brokersRoot = tmp.resolve("brokers"),
            environment = emptyMap(),
            opening = { ServiceCredentialAccess.Refused("the credential store could not be unlocked") },
        )

        val refused = assertInstanceOf(ServiceBrokers.Refused::class.java, composed)
        assertEquals("the credential store could not be unlocked", refused.detail)
    }

    @Test
    fun `a root too deep for a socket is a service that does not start and gives the provider back`() {
        val provider = ProviderDouble()
        val tooDeep = tmp.resolve("d".repeat(MAX_SEGMENT)).resolve("d".repeat(MAX_SEGMENT)).resolve("brokers")

        val composed = composeServiceBrokers(
            authorizations = authorizations(grants = GRANTS),
            brokersRoot = tooDeep,
            environment = emptyMap(),
            opening = { ServiceCredentialAccess.Opened(provider) },
        )

        val refused = assertInstanceOf(ServiceBrokers.Refused::class.java, composed)
        assertTrue(refused.detail.contains("too long"), "the refusal names the cause: ${refused.detail}")
        assertTrue(provider.closed.get(), "a service that does not start leaves nothing open behind it")
        assertFalse(Files.exists(tooDeep), "judging the root creates nothing")
    }

    @Test
    fun `giving the credentials back ignores what the provider throws`() {
        val provider = ProviderDouble(failOnClose = true)

        closeServiceCredentials(provider)
        closeServiceCredentials(null)

        assertTrue(provider.closed.get(), "the provider was asked to close")
    }

    // --- fixtures ---

    private fun seededStore(): Path {
        val file = tmp.resolve("credentials.bin")
        val store = LocalSecretStore(file, PASSPHRASE.toCharArray())
        try {
            store.put(CredentialsId("deploy-key"), SECRET.copyOf())
        } finally {
            store.close()
        }
        return file
    }

    private fun environment(store: Path, passphrase: String): Map<String, String> = mapOf(
        CredentialsStorePath.ENV_VAR to store.toString(),
        PASSPHRASE_VARIABLE to passphrase,
    )

    private fun authorizations(grants: Set<CredentialGrant>): ServiceAuthorizations =
        ServiceAuthorizations.of(listOf(ServiceAuthorization(TOKEN, "operator", ServiceScope.All, grants)))

    /** A provider that records whether it was closed. */
    private class ProviderDouble(private val failOnClose: Boolean = false) : CredentialProvider {
        val closed = AtomicBoolean(false)

        override val providerId: String = "provider-double"

        override fun resolve(id: CredentialsId): SecretHandle = error("not asked in these rows")

        override fun resolveToCredential(id: CredentialsId): Credential = error("not asked in these rows")

        override fun close() {
            closed.set(true)
            check(!failOnClose) { "the provider failed while closing" }
        }
    }

    private companion object {
        const val TOKEN: String = "token-1"
        const val PASSPHRASE: String = "the-passphrase-of-the-operator"
        const val PASSPHRASE_VARIABLE: String = "PIPELINE_STORE_PASSPHRASE"
        const val MAX_SEGMENT: Int = 60
        val SECRET: ByteArray = "s3cr3t-of-the-store".toByteArray()
        val GRANTS: Set<CredentialGrant> = setOf(CredentialGrant("deploy-key", BrokeredCredentialKind.STRING))
    }
}
