package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.isolation.Admission
import dev.rubentxu.pipeline.v2.domain.isolation.AdmissionDecider
import dev.rubentxu.pipeline.v2.domain.isolation.AdmissionQuery
import dev.rubentxu.pipeline.v2.domain.isolation.DenialReason
import dev.rubentxu.pipeline.v2.domain.isolation.ExecutionRequirements
import dev.rubentxu.pipeline.v2.domain.isolation.IsolationAttestation
import dev.rubentxu.pipeline.v2.domain.isolation.PluginAdmissionFact
import dev.rubentxu.pipeline.v2.domain.isolation.TrustIntent
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile

/**
 * The admission of an execution request: the seam between the word the operator
 * typed and the pure core that decides it.
 *
 * ## What this owns
 *
 * The CLI knows three sandbox profile words. Two of them ask for the behaviour
 * this product has always had; the third asks for a boundary this product does
 * not have. This object says what each word REQUESTS, asks the core what to do
 * with that request, and turns the verdict into the refusal the operator reads.
 *
 * ## Why the core decides and not the name of the word
 *
 * The refusal used to be a comparison of strings: the word `os` was refused by
 * name, with a message that said the value was not supported in this build.
 * That message was true and it was useless. It said WHICH WORD was refused and
 * nothing about what the word asks for, so an operator could not learn whether
 * another word, or another question, would be answerable.
 *
 * The core already knows how to answer that question, and it answers it in the
 * vocabulary of what a host can PROVE: the refusal names the capabilities the
 * request would have required, and the reason the host cannot prove them.
 *
 * ## Why the decision is asked HERE
 *
 * The request is decided while the request is being read, before the flags are
 * built and before anything is opened: a refusal at this point has read no
 * script, evaluated no Kotlin and started no child. The order IS the contract
 * (RSO-04): the request is resolved and decided without executing anything.
 */
internal object ExecutionAdmission {

    /**
     * What a profile word of this build asks for.
     *
     * @property request the request the domain states for that word.
     * @property profile the runtime profile that carries the request, or null
     *   when this build has no runtime profile for it. A word without a profile
     *   is not a typo: it is a request for a boundary the runtime cannot host,
     *   and the core is the one that refuses it.
     */
    internal data class ProfileRequest(
        val request: ExecutionRequirements,
        val profile: SandboxProfile?,
    )

    /**
     * What the operator's profile word asks for, or null when the word names no
     * profile of this build.
     *
     * The two legacy words state the SAME request, and that is the point: a
     * request that demands nothing beyond the legacy behaviour is admitted by
     * the core, whatever the word was. The word `local` does not become `os`:
     * it asks for the workspace and the environment deny-list, which confine
     * nothing, and the core says so by admitting it.
     *
     * The word `os` asks for a boundary: the run is code this product does not
     * trust with the machine. It has no runtime profile in this build, and the
     * request that states it is refused by the core rather than by the name.
     */
    fun profileRequestFor(word: String): ProfileRequest? = when (word) {
        "none" -> ProfileRequest(LEGACY_REQUEST, SandboxProfile.NONE)
        "local" -> ProfileRequest(LEGACY_REQUEST, SandboxProfile.LOCAL)
        "os" -> ProfileRequest(ISOLATED_REQUEST, null)
        else -> null
    }

    /**
     * The verdict the core gives for [request] on this build.
     *
     * The attestation is this build's own: it ships no isolation provider, so
     * the only environment it can attest is the legacy trusted posture. The
     * artifacts are out of scope of this decision: they are admitted by the
     * product's own admission, which owns that question and answers it later.
     */
    fun decide(request: ExecutionRequirements): Admission = AdmissionDecider.decide(
        AdmissionQuery(
            requirements = request,
            attestation = THIS_BUILD_ATTESTATION,
            plugins = PluginAdmissionFact.OutOfScope,
        ),
    )

    /**
     * The refusal a denial becomes.
     *
     * The refusal carries the core's reason and the capabilities the request
     * would have required, so the sentence the operator reads can name both:
     * what went wrong, and what would have to be true for the request to run.
     *
     * @param reason why the core refused.
     * @param request the request that was refused, whose demanded capabilities
     *   the refusal lists.
     */
    fun refusalFor(reason: DenialReason, request: ExecutionRequirements): CliError =
        CliError.IsolationRequestRefused(
            reason = reason,
            required = AdmissionDecider.requiredCapabilities(request).sortedBy { it.ordinal },
        )

    /**
     * The request the legacy words state: the behaviour this product has always
     * had, which demands no capability beyond it.
     */
    private val LEGACY_REQUEST = ExecutionRequirements()

    /**
     * The request for a real boundary: the run is code this product does not
     * trust with the machine.
     */
    private val ISOLATED_REQUEST = ExecutionRequirements(trustIntent = TrustIntent.UntrustedIsolated)

    /**
     * This build ships no isolation provider, so the only environment it can
     * attest is the legacy trusted posture, which proves no capability.
     */
    private val THIS_BUILD_ATTESTATION = IsolationAttestation.TrustedProfileOnly
}
