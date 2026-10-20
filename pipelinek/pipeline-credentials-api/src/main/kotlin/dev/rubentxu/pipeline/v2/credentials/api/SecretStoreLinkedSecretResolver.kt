package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialLinkedSecretResolver
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef

/**
 * WU-RP-050 (ADR-0098) — Production adapter of [CredentialLinkedSecretResolver]
 * for any caller that already holds a [SecretStore]. Centralises the
 * `store.getAsSecretHandle(ref.id)` operation so the rest of the codebase
 * does NOT call [SecretStore.getAsSecretHandle] directly outside of the
 * SPI implementation.
 *
 * Lives in `:pipeline-credentials-api` because that's the module that owns
 * [SecretStore]; both `:pipeline-credentials-executor` (via
 * [dev.rubentxu.pipeline.v2.credentials.executor.SpiCredentialLinkedSecretResolver])
 * and `:pipeline-step-sdk/scm-git` (via
 * [dev.rubentxu.pipeline.v2.sdk.scm.git.GitCredentialsApplier]) consume this
 * adapter instead of duplicating the pattern. The single legitimate
 * non-adapter consumer of [SecretStore.getAsSecretHandle] is the
 * [dev.rubentxu.pipeline.v2.credentials.local.LocalCredentialProvider]
 * SPI implementation.
 *
 * Errors from [SecretStore.getAsSecretHandle] (typed
 * [LinkedSecretReferenceNotFoundException] /
 * [LinkedSecretReferenceTypeMismatchException]) propagate as-is — fail-closed,
 * no swallowing, no substitution.
 */
class SecretStoreLinkedSecretResolver(
    private val secretStore: SecretStore,
) : CredentialLinkedSecretResolver {

    override fun resolve(ref: LinkedSecretRef): SecretHandle =
        secretStore.getAsSecretHandle(ref.credentialsId)
}
