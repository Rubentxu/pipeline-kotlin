package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.credentials.api.BasicCredentialResolution
import dev.rubentxu.pipeline.v2.credentials.api.BasicCredentialSource
import dev.rubentxu.pipeline.v2.credentials.api.BASIC_CREDENTIALS_CAPABILITY
import dev.rubentxu.pipeline.v2.credentials.api.CredentialStoreUnavailability
import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreException
import dev.rubentxu.pipeline.v2.credentials.api.NoBasicCredentialSource
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * H5-B — how the runtime presents a credential store to a plugin as a NARROW seam.
 *
 * This is the adapter the H5-A commit had to leave open. It sits here, in the module
 * that already owns [CredentialProvider], for the same reason the port sits in
 * `pipeline-credentials-api`: the plugin must not depend on the runtime and the
 * runtime must not import the plugin. The composition root instantiates it; nothing
 * else needs to know it exists.
 *
 * ## What it translates, and what it refuses to
 *
 * ```text
 * CredentialProvider            BasicCredentialSource
 * ─────────────────────         ─────────────────────
 * UsernamePassword          ->   Resolved(username, password)
 * SecretText / SecretFile   ->   KindUnsupported(found = "SecretText", ...)
 * SshPrivateKey / Zip / ... ->   KindUnsupported(...)
 * anything else             ->   KindUnsupported(found = <class name>)
 * absent from the store     ->   NotFound
 * no store at all           ->   StoreUnavailable(NotConfigured)
 * ```
 *
 * It exposes nothing else. There is no method that returns a path, a handle, a key
 * or an arbitrary credential, so a consumer cannot reach one by accident.
 *
 * ## The store is opened, or it is not
 *
 * A run with no store still receives the capability, backed by
 * [NoBasicCredentialSource]. Withholding it would make every Step that declares the
 * seam fail admission — including the ones that declare no credential and would
 * never call it — and the operator would be told their plugin was not wired instead
 * of being told they have no credentials.
 */
class BasicCredentialsCapabilityContributor(
    private val provider: CredentialProvider?,
) : RuntimeCapabilityContributor {

    private val source: BasicCredentialSource = provider?.let { ProviderBackedSource(it) }
        ?: NoBasicCredentialSource

    override fun capabilities(): Map<StepCapability, Any> =
        mapOf(BASIC_CREDENTIALS_CAPABILITY to source)

    /**
     * Translates the store's vocabulary into the narrow one.
     *
     * ## Why it catches, and what it refuses to do
     *
     * `SecretStore.get` throws `SecretStoreException` — and for a credential that
     * does not exist, `LocalSecretStore` throws `SecretStoreTamperException` with
     * the message `"Credential not found: X"`, which is the SAME exception type it
     * throws when the file has been tampered with. The two are distinguishable only
     * by reading the sentence.
     *
     * It does not read it. Parsing a message to recover a fact is how a rename
     * becomes a security incident: one day the wording changes, or a translated
     * build says something else, and a tampered store starts reporting itself as a
     * missing credential. So every `SecretStoreException` becomes
     * [BasicCredentialResolution.StoreUnavailable] — "the store could not answer" —
     * which is true in both cases and actionable in neither.
     *
     * The cost is that [BasicCredentialResolution.NotFound] is currently
     * UNREACHABLE from a real store. Making it reachable means giving the store SPI
     * a typed not-found, which is a change to a published contract that other
     * consumers (`withCredentials`, the materializers) depend on. That is a
     * decision for the contract owner, not something an adapter should smuggle in
     * by pattern-matching a string.
     */
    private class ProviderBackedSource(
        private val provider: CredentialProvider,
    ) : BasicCredentialSource {

        override fun resolve(id: CredentialsId): BasicCredentialResolution =
            try {
                when (val credential = provider.resolveToCredential(id)) {
                    is UsernamePassword -> BasicCredentialResolution.Resolved(
                        username = credential.username,
                        password = credential.password,
                    )

                    else -> BasicCredentialResolution.KindUnsupported(
                        id = id.value,
                        found = describe(credential),
                        supported = SUPPORTED_KINDS,
                    )
                }
            } catch (e: SecretStoreException) {
                BasicCredentialResolution.StoreUnavailable(
                    CredentialStoreUnavailability.Unavailable,
                )
            }

        /**
         * The credential's own name, not a raw class name.
         *
         * `SshPrivateKey` is a word an operator can act on; `dev.rubentxu.pipeline
         * .v2.domain.credentials.SshPrivateKey` is a classpath fragment. Neither is a
         * secret, and this text reaches a diagnostic, so it is chosen for being
         * readable rather than for being precise.
         */
        private fun describe(credential: Credential): String =
            credential::class.simpleName ?: "an unrecognised credential kind"
    }

    private companion object {
        /** What this seam can actually produce. Named so a refusal can say what would have worked. */
        val SUPPORTED_KINDS = listOf("UsernamePassword")
    }
}
