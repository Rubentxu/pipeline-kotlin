package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.service.CredentialMaterialCodec

/**
 * The connected controller did not hand the credential out.
 *
 * [reason] is one of the fixed words of the sessions (`provider-denied`, `provider-gone`,
 * `provider-timeout`, ...) or one of the words of this class (`credential-not-granted`,
 * `provider-answer-not-understood`). The message carries nothing but that word: the exception
 * travels to places that print it, and the detail of the sessions is for the service, not for the
 * run.
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
 * ## The FORM it asks for
 *
 * The grants of the client that started the run say in which form each credential may be handed
 * over, and the ask carries that form. The controller is asked for the credential IN THAT FORM,
 * so the service never receives a credential it is not going to hand over: a controller that
 * holds a secret text where a private key was granted has nothing to give, and says so.
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
    private val grants: Set<CredentialGrant> = emptySet(),
) : CredentialProvider {

    override val providerId: String = PROVIDER_ID

    override fun resolve(id: CredentialsId): SecretHandle =
        throw UnsupportedOperationException("a connected credential is read through the broker only")

    override fun resolveToCredential(id: CredentialsId): Credential {
        val kind = grants.firstOrNull { it.credentialId == id.value }?.kind
            ?: throw ConnectedCredentialDenied("credential-not-granted")
        val answer = asker.ask(owner, provider, execution, id.value, kind.word)
        return when (answer) {
            is CredentialResolution.Granted -> decode(id, answer.value, kind)
            is CredentialResolution.Refused -> throw ConnectedCredentialDenied(answer.reason)
        }
    }

    override fun close() {
        asker.release(owner, execution)
    }

    override fun toString(): String = "ConnectedCredentialProvider(provider=$provider, execution=$execution)"

    /**
     * The credential the answer carries, or a refusal.
     *
     * The answer is the material of the credential, and it says what it is: a form of another
     * kind is not the credential that was asked for, and the controller that sent it did not
     * answer the question it was asked.
     */
    private fun decode(id: CredentialsId, material: ByteArray, kind: BrokeredCredentialKind): Credential {
        val credential = try {
            CredentialMaterialCodec.decode(id, material)
        } finally {
            material.fill(0)
        }
        if (credential == null) {
            throw ConnectedCredentialDenied("provider-answer-not-understood")
        }
        if (CredentialMaterialCodec.kindOf(credential) != kind.word) {
            throw ConnectedCredentialDenied("provider-answer-of-another-form")
        }
        return credential
    }

    private companion object {
        const val PROVIDER_ID = "connected"
    }
}
