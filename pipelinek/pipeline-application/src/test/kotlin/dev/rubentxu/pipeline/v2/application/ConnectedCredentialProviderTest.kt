package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The provider the broker of a run asks when the credential is held by a connected controller.
 *
 * It is the seam between two things that already exist: the broker, which judges a request and
 * hands the bytes to the run, and the sessions, which ask the controller. Its whole job is to
 * bind each ask to the run, the identity and the credential, and to turn a denial into the one
 * exception the broker knows how to say.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConnectedCredentialProviderTest {

    private val asked = mutableListOf<List<String>>()
    private val released = mutableListOf<Pair<String, String>>()
    private var resolution: CredentialResolution = CredentialResolution.Granted(SECRET.copyOf())

    private val asker = object : CredentialAsker {
        override fun ask(owner: String, provider: String, execution: String, credentialId: String, kind: String): CredentialResolution {
            asked += listOf(owner, provider, execution, credentialId, kind)
            return resolution
        }

        override fun release(owner: String, execution: String) {
            released += owner to execution
        }
    }

    private val provider = ConnectedCredentialProvider(asker, owner = "owner-a", provider = "vault", execution = "run-7")

    @Test
    fun `the credential the controller hands out reaches the broker as a secret text with the name that was asked`() {
        val credential = provider.resolveToCredential(CredentialsId("deploy-token"))

        val secret = assertInstanceOf(SecretText::class.java, credential)
        assertEquals(CredentialsId("deploy-token"), secret.id)
        assertArrayEquals(SECRET, secret.bytes)
    }

    @Test
    fun `every ask is bound to the identity, the provider session, the run and the credential, as a string`() {
        provider.resolveToCredential(CredentialsId("deploy-token"))

        assertEquals(listOf(listOf("owner-a", "vault", "run-7", "deploy-token", "string")), asked)
    }

    @Test
    fun `a controller that does not hand the credential out is a denial with the word of the registry`() {
        for (reason in listOf("provider-denied", "provider-gone", "provider-timeout", "provider-not-registered", "run-ended")) {
            resolution = CredentialResolution.Refused(reason, "detail the broker must not repeat")

            val denied = assertThrows(ConnectedCredentialDenied::class.java) {
                provider.resolveToCredential(CredentialsId("deploy-token"))
            }

            assertEquals(reason, denied.reason)
            assertFalse(denied.message.orEmpty().contains("detail the broker must not repeat"), denied.message)
        }
    }

    @Test
    fun `a denial is never answered with a stand-in credential`() {
        resolution = CredentialResolution.Refused("provider-timeout", "late")

        assertThrows(ConnectedCredentialDenied::class.java) { provider.resolveToCredential(CredentialsId("deploy-token")) }
        assertEquals(1, asked.size, "the controller is asked once and nobody else is asked")
    }

    @Test
    fun `the typed handle channel is not served because nothing but the broker reads a connected credential`() {
        assertThrows(UnsupportedOperationException::class.java) { provider.resolve(CredentialsId("deploy-token")) }
        assertEquals(emptyList<List<String>>(), asked)
    }

    @Test
    fun `closing the provider releases the asks of its run and only of its run, and closing twice is harmless`() {
        provider.close()
        provider.close()

        assertEquals(listOf("owner-a" to "run-7", "owner-a" to "run-7"), released)
    }

    @Test
    fun `the provider says what it is and renders no secret`() {
        assertEquals("connected", provider.providerId)
        val rendered = provider.toString()
        assertFalse(rendered.contains(String(SECRET, Charsets.UTF_8)), rendered)
    }

    private companion object {
        val SECRET = "connected-secret-value".toByteArray(Charsets.UTF_8)
    }
}
