package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * H5 — the seam that turns a NAME into an `Authorization` header, and nothing more.
 *
 * ## Why a narrow capability and not the credential provider
 *
 * The obvious thing to hand a Step is a `CredentialProvider`. It is also the wrong
 * thing: it resolves any kind of credential to any consumer, so a Step that can
 * read a name out of its own input could read a private key, a certificate or an
 * arbitrary secret file. `http.request` needs exactly one shape of answer — a
 * username and a password — and a port that can only return that is a port whose
 * misuse is unrepresentable rather than merely discouraged.
 *
 * The plugin owns the protocol, so the plugin owns this port too. The runtime
 * supplies a resolver backed by whatever store the operator configured; neither
 * names the other's types.
 *
 * ## The law on where the secret may exist
 *
 * ```text
 * HttpRequestInput      a CredentialsId — a public reference, never a secret
 * codec / fingerprint   the CredentialsId, and nothing else
 * journal / events      the CredentialsId, and nothing else
 * HttpCredentialResolution.Basic   secret MATERIAL, in memory, inside the plugin
 * Authorization header  the last place it exists, built at the transport boundary
 * ```
 *
 * The resolution value carries `password` as `ByteArray` rather than `String` on
 * purpose. A `String` is immutable and lingers in the heap until GC happens to
 * collect it, and it is what ends up in exception messages, log formatters and
 * `toString()` by accident. Bytes can be wiped the moment the header is built.
 */
val HTTP_CREDENTIALS_CAPABILITY: StepCapability = StepCapability("http.credentials")

/**
 * Resolves a named credential to the one shape `http.request` can use.
 *
 * Synchronous and total: every outcome is a case, including the ones that mean
 * "this run has no credential source at all". A resolver that threw would put
 * the classification of an ordinary operational fact inside an exception, and
 * the caller would have to catch a class hierarchy to find out whether a
 * credential was merely absent or the run was never configured for one.
 *
 * ## Why no free text anywhere in this hierarchy
 *
 * A resolver's "reason" is text the runtime produced, and text the runtime
 * produced has a habit of containing whatever the runtime was holding when it
 * failed. K6 in `HttpCredentialContainmentTest` caught exactly that: a
 * `NoSource("store error near <the secret>")` travelled from the resolution
 * through [HttpRejection.diagnostic] into the step outcome, the event log and the
 * journal — a secret exfiltration path with no attacker required.
 *
 * So the reasons are CLOSED VALUES, not strings. Whoever wants the human sentence
 * — "passphrase mismatch", "file is locked" — logs it through whatever redacting
 * sink it already holds, where it belongs. The durable Step record gets the case,
 * which is the part a reader can act on anyway.
 */
fun interface HttpCredentialResolver {
    fun resolve(id: CredentialsId): HttpCredentialResolution
}

/** Why a credential could not be looked up. A closed set, because these reach the journal. */
enum class StoreUnavailability {
    /** The run was given no store to look in. */
    NotConfigured,

    /** A store exists but could not be opened — wrong passphrase, tampered, locked. */
    Unreadable,

    /** The store was reachable but the lookup itself failed. */
    Unavailable,
}

/**
 * Everything a credential lookup can produce, as a closed set.
 *
 * [NoSource] exists because "there is no store configured" and "that name is not
 * in the store" are different facts with different fixes — one is an operator
 * action, the other is usually an author typo. Collapsing them would send people
 * to rename a credential that does not exist yet.
 */
sealed interface HttpCredentialResolution {
    data class Basic(
        val username: String,
        /** Secret material. Never rendered, never encoded, never journalled. */
        val password: ByteArray,
    ) : HttpCredentialResolution

    data class NotFound(val id: String) : HttpCredentialResolution

    data class KindUnsupported(
        val id: String,
        val found: String,
        val supported: List<String>,
    ) : HttpCredentialResolution

    /** No credential source is usable for this run, so no name can be resolved. */
    data class NoSource(val reason: StoreUnavailability) : HttpCredentialResolution
}

/**
 * The resolver a run gets when the operator configured no credential store.
 *
 * Contributed UNCONDITIONALLY, exactly like the transport and for the same
 * reason: withholding it would make "this run may not use credentials" and "the
 * plugin was never wired" the same admission failure, and the operator would be
 * told the wrong thing. A Step that declares `authentication` then fails with a
 * diagnosis that names the actual cause.
 */
object NoCredentialSource : HttpCredentialResolver {
    override fun resolve(id: CredentialsId): HttpCredentialResolution =
        HttpCredentialResolution.NoSource(StoreUnavailability.NotConfigured)
}

/**
 * What a declared credential name turned into.
 *
 * Three shapes because there are three genuinely different situations, and the
 * no-credential case is NOT the absent-credential case: a run with no store will
 * never resolve any name, and telling an author their credential is missing when
 * the run simply has nowhere to look sends them to edit a credential that is
 * perfectly fine.
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
 * Separate from the handler on purpose. This is where the policy lives, it has no
 * socket and no clock, and it can be exercised without constructing a coordinator,
 * a capability context or a transport — which is the only reason the decision can
 * be tested at all before the UAT exists.
 *
 * Note what is NOT decided here: whether the request is acceptable afterwards, and
 * whether the network may be reached at all. Those are the runtime's.
 */
internal fun credentialDecisionOf(
    id: CredentialsId?,
    resolver: HttpCredentialResolver,
): CredentialDecision = when (id) {
    null -> CredentialDecision.Undeclared
    else -> when (val resolution = resolver.resolve(id)) {
        is HttpCredentialResolution.Basic -> CredentialDecision.Authorized(
            HttpAuthorization.Basic(resolution.username, resolution.password),
        )
        is HttpCredentialResolution.NotFound ->
            CredentialDecision.Refused(HttpRejection.CredentialRefused.Absent(id.value))
        is HttpCredentialResolution.KindUnsupported -> CredentialDecision.Refused(
            HttpRejection.CredentialRefused.WrongKind(
                id = id.value,
                found = resolution.found,
                supported = resolution.supported,
            ),
        )
        is HttpCredentialResolution.NoSource -> CredentialDecision.Refused(
            HttpRejection.CredentialRefused.StoreUnavailable(id.value, resolution.reason),
        )
    }
}
