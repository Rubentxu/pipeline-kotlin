package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.credentials.api.BasicCredentialResolution
import dev.rubentxu.pipeline.v2.credentials.api.BasicCredentialSource
import dev.rubentxu.pipeline.v2.domain.CredentialsId

/**
 * H5 — what a declared credential name turned into, as far as `http.request` is
 * concerned.
 *
 * The DECISION is pure and lives in [credentialDecisionOf]; the handler only
 * interprets it at the effect boundary. Keeping them apart is what lets the policy
 * be exercised without a coordinator, a capability context or a transport, and it
 * is also what stopped a security property from looking covered when nothing
 * enforced it — see `HttpCredentialApplicationTest`.
 *
 * The seam itself — [dev.rubentxu.pipeline.v2.credentials.api.BasicCredentialSource]
 * — belongs to `pipeline-credentials-api`, not here, because the runtime has to
 * SUPPLY it and must not import this plugin's vocabulary to do so.
 */
sealed interface CredentialDecision {
    /** No `authentication` was declared. The request is sent unauthenticated ON PURPOSE. */
    data object Undeclared : CredentialDecision

    data class Authorized(val authorization: HttpAuthorization) : CredentialDecision

    data class Refused(val rejection: HttpRejection.CredentialRefused) : CredentialDecision
}

/**
 * PURE and TOTAL: a declared name becomes a header, or a refusal.
 *
 * Note what is NOT decided here: whether the request is acceptable afterwards, and
 * whether the network may be reached at all. Those are the runtime's, and K13 pins
 * the ordering — a denied run must not decrypt a secret nobody was authorised to
 * send.
 */
internal fun credentialDecisionOf(
    id: CredentialsId?,
    source: BasicCredentialSource,
): CredentialDecision = when (id) {
    null -> CredentialDecision.Undeclared
    else -> when (val resolution = source.resolve(id)) {
        is BasicCredentialResolution.Resolved -> CredentialDecision.Authorized(
            HttpAuthorization.Basic(resolution.username, resolution.password),
        )
        is BasicCredentialResolution.NotFound ->
            CredentialDecision.Refused(HttpRejection.CredentialRefused.Absent(id.value))

        is BasicCredentialResolution.KindUnsupported -> CredentialDecision.Refused(
            HttpRejection.CredentialRefused.WrongKind(
                id = id.value,
                found = resolution.found,
                supported = resolution.supported,
            ),
        )
        is BasicCredentialResolution.StoreUnavailable -> CredentialDecision.Refused(
            HttpRejection.CredentialRefused.StoreUnavailable(id.value, resolution.reason),
        )
    }
}
