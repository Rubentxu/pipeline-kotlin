package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText

/**
 * The connected controller did not hand the credential out.
 *
 * [reason] is one of the fixed words of the sessions (`provider-denied`, `provider-gone`,
 * `provider-timeout`, ...). The message carries nothing but that word: the exception travels
 * to places that print it, and the detail of the sessions is for the service, not for the run.
 */
internal class ConnectedCredentialDenied(val reason: String) :
    Exception("the connected provider did not hand the credential out: $reason")

/**
 * The credential provider of ONE run whose credentials are held by a connected controller.
 *
 * ## What it is bound to
 *
 * Every ask names the identity that started the run ([owner]), the provider session of that
 * identity ([provider]) and the run itself ([execution]). The credential asked is the one the
 * broker already judged granted to the client that started the run, so the controller is never
 * asked for a name the run could not have received.
 *
 * ## What it never does
 *
 * It never falls back: a controller that denies, vanishes or is late is a
 * [ConnectedCredentialDenied], which the broker turns into its own word. It serves only the
 * credential channel of the broker; the typed handle channel is refused, because nothing else
 * reads a connected credential.
 *
 * Closing it fails the asks that are still waiting for the run, so a run that ends does not
 * leave a request on the screen of the controller.
 */
internal class ConnectedCredentialProvider(
    private val asker: CredentialAsker,
    private val owner: String,
    private val provider: String,
    private val execution: String,
) : CredentialProvider {

    override val providerId: String = PROVIDER_ID

    override fun resolve(id: CredentialsId): SecretHandle =
        throw UnsupportedOperationException("a connected credential is read through the broker only")

    override fun resolveToCredential(id: CredentialsId): Credential =
        when (val answer = asker.ask(owner, provider, execution, id.value, BrokeredCredentialKind.STRING.word)) {
            is CredentialResolution.Granted -> SecretText(id = id, bytes = answer.value)
            is CredentialResolution.Refused -> throw ConnectedCredentialDenied(answer.reason)
        }

    override fun close() {
        asker.release(owner, execution)
    }

    override fun toString(): String = "ConnectedCredentialProvider(provider=$provider, execution=$execution)"

    private companion object {
        const val PROVIDER_ID = "connected"
    }
}
