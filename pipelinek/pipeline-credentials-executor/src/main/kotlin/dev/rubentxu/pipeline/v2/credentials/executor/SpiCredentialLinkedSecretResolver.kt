package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialLinkedSecretResolver
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText

/**
 * LF-0403 (ADR-0097) — Production adapter of the
 * [CredentialLinkedSecretResolver] domain port.
 *
 * Lives in `:pipeline-credentials-executor` because that's the module that
 * already owns the `CredentialProvider` SPI port (which has access to the
 * underlying [dev.rubentxu.pipeline.v2.credentials.api.SecretStore] via its
 * implementation, typically `LocalCredentialProvider`).
 *
 * Delegates [resolve] to [CredentialProvider.resolve], which returns a
 * [SecretHandle] wrapping the bytes. Errors from the provider
 * (`SecretStoreException` and its typed subclasses:
 * [dev.rubentxu.pipeline.v2.credentials.api.LinkedSecretReferenceNotFoundException]
 * / [dev.rubentxu.pipeline.v2.credentials.api.LinkedSecretReferenceTypeMismatchException])
 * propagate as-is — fail-closed, no swallowing, no substitution.
 *
 * Type guarantee: the resolver returns the bytes of a [SecretText] credential.
 * Other kinds (UsernamePassword / SshPrivateKey / Certificate / File / Zip) are
 * not appropriate to bind as a passphrase / keystore password and the SPI
 * surfaces the mismatch as a typed exception.
 */
class SpiCredentialLinkedSecretResolver(
    private val provider: CredentialProvider,
) : CredentialLinkedSecretResolver {

    override fun resolve(ref: LinkedSecretRef): SecretHandle {
        // Delegate to the SPI port. The provider's resolve(id) handles the
        // SecretStore lookup, integrity check, and typed-error mapping.
        return provider.resolve(ref.credentialsId)
    }
}
