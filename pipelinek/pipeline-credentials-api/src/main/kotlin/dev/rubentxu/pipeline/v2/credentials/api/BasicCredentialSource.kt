package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * H5-B — the contract a plugin uses to turn a credential NAME into a username and a
 * password, and the runtime's obligation to supply it.
 *
 * ## Why this contract lives here and not in the plugin that wants it
 *
 * `http.request` needs this. It cannot own it, for the same reason it cannot own its
 * own transport's permission: the value has to be SUPPLIED by the runtime, from
 * whatever store the operator configured, and the runtime must not import the
 * plugin's vocabulary to do it. A contract both sides can see is the only shape
 * that works without either module depending on the other.
 *
 * This is the same split `NetworkEgressPolicy` already uses in `pipeline-domain`:
 * the plugin DECLARES the capability, the runtime PRODUCES the verdict, and neither
 * names the other's types. It lives in `pipeline-credentials-api` rather than
 * `pipeline-domain` because it is a credentials contract, and the module already
 * exists for exactly that — inventing a second home for the same kind of thing is
 * how two authorities for one concept get created.
 *
 * ## Why it is narrow
 *
 * A `CredentialProvider` resolves any kind of credential to any consumer, so a Step
 * that could reach one out of its own input could read a private key, a
 * certificate or an arbitrary secret file. This port can only return a username and
 * a password. That is not a convention; it is the reason the port exists, and it
 * makes the misuse unrepresentable rather than merely discouraged.
 *
 * ## No free text, anywhere
 *
 * These values reach the durable journal through whatever a Step chooses to report.
 * A resolver's "reason" is text the runtime produced, and text the runtime produced
 * tends to contain whatever the runtime was holding when it failed. The reasons here
 * are therefore a CLOSED SET. Whoever wants the human sentence — "passphrase
 * mismatch" — logs it through the redacting sink it already holds, where it
 * belongs; the durable record gets the case.
 */
val BASIC_CREDENTIALS_CAPABILITY: StepCapability = StepCapability("credentials.basic")

/**
 * Resolves a named credential to a username and a password.
 *
 * Synchronous and total. Every outcome is a case, including "this run has no
 * credential source at all" — a resolver that threw would put the classification of
 * an ordinary operational fact inside an exception.
 */
fun interface BasicCredentialSource {
    fun resolve(id: CredentialsId): BasicCredentialResolution
}

/** Why a credential could not be looked up. Closed, because these reach the journal. */
enum class CredentialStoreUnavailability {
    /** The run was given no store to look in. */
    NotConfigured,

    /** A store exists but could not be opened — wrong passphrase, tampered, locked. */
    Unreadable,

    /** The store was reachable but the lookup itself failed. */
    Unavailable,
}

/**
 * Everything a lookup can produce.
 *
 * [StoreUnavailable] is deliberately not the same case as [NotFound]: one means the
 * run has nowhere to look, the other usually means an author typo. Telling someone
 * their credential is missing when the run simply has no store sends them to rename
 * a credential that is perfectly fine.
 */
sealed interface BasicCredentialResolution {
    data class Resolved(
        val username: String,
        /** Secret material. Never rendered, never encoded, never journalled. */
        val password: ByteArray,
    ) : BasicCredentialResolution

    data class NotFound(val id: String) : BasicCredentialResolution

    data class KindUnsupported(
        val id: String,
        val found: String,
        val supported: List<String>,
    ) : BasicCredentialResolution

    data class StoreUnavailable(
        val reason: CredentialStoreUnavailability,
    ) : BasicCredentialResolution
}

/**
 * The source a run gets when the operator configured no credential store.
 *
 * Contributed UNCONDITIONALLY rather than withheld, for the same reason the egress
 * verdict is not withheld when it is negative: "this run may not use credentials" and
 * "the credentials seam was never wired" are different facts, and only one of them
 * is something an operator can act on. A Step that declares no credential never
 * calls this, so a run that needs nothing pays nothing.
 */
object NoBasicCredentialSource : BasicCredentialSource {
    override fun resolve(id: CredentialsId): BasicCredentialResolution =
        BasicCredentialResolution.StoreUnavailable(CredentialStoreUnavailability.NotConfigured)
}
