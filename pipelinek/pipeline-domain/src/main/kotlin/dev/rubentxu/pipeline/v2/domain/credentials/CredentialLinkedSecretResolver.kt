package dev.rubentxu.pipeline.v2.domain.credentials

import dev.rubentxu.pipeline.v2.domain.SecretHandle

/**
 * LF-0403 (ADR-0097) — Domain port for resolving a [LinkedSecretRef] to its referenced
 * [SecretText] bytes.
 *
 * Lives in `:pipeline-domain` so [DefaultCredentialProjector] can compose against it
 * without dragging in `:pipeline-credentials-api`. Same pattern as the gemelo
 * [CredentialMaterializationDomain] (the SPI in `:pipeline-credentials-api/spi/CredentialMaterialization`
 * extends the domain port by subtyping; here, the impl lives in
 * `:pipeline-credentials-executor` and delegates to [SecretStore.getAsSecretHandle]).
 *
 * Contract (single method):
 *  - [resolve]: turn a [LinkedSecretRef] into a [SecretHandle] carrying the
 *    referenced [SecretText] bytes. The default impl in
 *    `:pipeline-credentials-executor` queries `SecretStore.getAsSecretHandle(ref.id)`.
 *
 * Errors are typed (no string exceptions):
 *  - [LinkedSecretReferenceNotFoundException] if the credential id is absent.
 *  - [LinkedSecretReferenceTypeMismatchException] if the referenced credential
 *    is not a [SecretText].
 *
 * [ThrowingCredentialLinkedSecretResolver] is the safe default for tests/call sites
 * that do not exercise [LinkedSecretRef]: it surfaces accidental unconfigured
 * dependencies as a clear error instead of silently injecting empty bytes
 * (the historical LF-0403 placeholder behaviour).
 */
fun interface CredentialLinkedSecretResolver {
    fun resolve(ref: LinkedSecretRef): SecretHandle
}

/**
 * Fail-closed default for [CredentialLinkedSecretResolver]. Surfaces any attempt
 * to resolve a [LinkedSecretRef] without explicit resolver wiring as a clear
 * [IllegalStateException] with the referenced credential id, instead of silently
 * injecting empty bytes (the LF-0403 placeholder bug).
 */
object ThrowingCredentialLinkedSecretResolver : CredentialLinkedSecretResolver {
    override fun resolve(ref: LinkedSecretRef): SecretHandle =
        throw IllegalStateException(
            "CredentialLinkedSecretResolver not configured; cannot resolve ref=${ref.credentialsId.value}"
        )
}
