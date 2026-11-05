package dev.rubentxu.pipeline.v2.domain.isolation

/**
 * Why a request was refused.
 *
 * The four reasons are the four questions an operator can act on, and they are
 * kept apart because they have different answers: a capability the host lacks is
 * answered by a different build, an incoherent request is answered by the
 * operator, an untrusted plugin is answered by the publisher, and a missing
 * attestation is answered by whoever installed the provider.
 *
 * Nothing is silenced. [UnsupportedCapability] carries the SET of capabilities
 * that were missing, because "not supported" without the set leaves the operator
 * to guess which half of the request was the problem.
 */
sealed interface DenialReason {
    /** The host cannot prove a capability the request needs. [missing] names every one of them. */
    data class UnsupportedCapability(val missing: Set<IsolationCapability>) : DenialReason

    /** The request contradicts itself, so no host could honour it. */
    data class PolicyConflict(val detail: String) : DenialReason

    /** An artifact the run would load has no attested provenance. */
    data class UntrustedPlugin(val artifacts: List<String>) : DenialReason

    /** The thing that could attest the request's capabilities could not be asked. */
    data class MissingAttestation(val detail: String) : DenialReason
}

/**
 * The proof that a request may run.
 *
 * A grant is only readable together with the evidence behind it: [proven] is the
 * capability set the host proved, each with its own evidence, and [rationale]
 * says in one sentence why the request was admitted. A grant with an empty
 * [proven] is not a grant that forgot to prove anything — it is the trusted
 * posture, which asks for nothing and therefore proves nothing, and says so.
 */
data class ExecutionGrant(
    val requirements: ExecutionRequirements,
    val proven: List<ProvenCapability>,
    val rationale: String,
)

/** The verdict of the admission core: a grant, or a reason. */
sealed interface Admission {
    /**
     * The request may run.
     *
     * The grant states what the host proved and why it admitted the request,
     * so the caller can report the evidence beside the run it lets happen.
     * The grant carries no condition: a request that was admitted is a
     * request that runs.
     */
    data class Allowed(val grant: ExecutionGrant) : Admission

    /**
     * The request may not run.
     *
     * The reason names the question an operator has to answer, and nothing
     * runs: a refusal is not a grant with conditions attached.
     */
    data class Denied(val reason: DenialReason) : Admission
}

/**
 * What the artifacts the run would load look like, provenance-wise.
 *
 * The product already admits plugin artifacts before it loads them; this value
 * is that decision's PROVENANCE, reduced to the one question the admission core
 * has to ask. It is a fact about the artifacts, not a second admission: the
 * product's own admission remains the only thing that admits or refuses an
 * artifact, and this value only says whether the refusal already happened.
 */
sealed interface PluginAdmissionFact {
    /** The run loads no external artifact. */
    data object NoPlugins : PluginAdmissionFact

    /** Every artifact was admitted under an attested publisher trust policy. */
    data class Attested(val artifacts: List<String>) : PluginAdmissionFact

    /** At least one artifact was admitted on its manifest alone, with no attested publisher. */
    data class Unattested(val artifacts: List<String>) : PluginAdmissionFact

    /**
     * The artifacts' provenance is NOT part of this decision.
     *
     * The caller is deciding the run's POSTURE — whether this host can honour
     * the request — and the artifacts are admitted by the product's own
     * admission, which owns that question and answers it on its own terms. A
     * caller that HAS the provenance says [Attested] or [Unattested], and the
     * core refuses the untrusted combination. A caller that does not have it
     * says this, and the core answers the posture.
     *
     * ## Why the case exists
     *
     * The admission of an execution is decided BEFORE the artifacts are
     * admitted: the request has to be answered before any untrusted code is
     * loaded, and an artifact's provenance is only known once the product's own
     * admission has run. A caller in that position has no fact to report, and
     * the two facts it could report instead would both be false: [NoPlugins]
     * would claim that the run loads nothing, and [Unattested] would report a
     * verdict the caller never took.
     */
    data object OutOfScope : PluginAdmissionFact
}

/**
 * Everything the admission core needs, and nothing it has to go looking for.
 *
 * The core is pure: it reads these three values and returns a verdict. It never
 * touches the filesystem, never probes a host and never loads an artifact, which
 * is what lets it be decided BEFORE the script is evaluated — the ordering the
 * whole design exists to guarantee.
 */
data class AdmissionQuery(
    val requirements: ExecutionRequirements,
    val attestation: IsolationAttestation,
    val plugins: PluginAdmissionFact = PluginAdmissionFact.NoPlugins,
)

/**
 * The admission core: a request, an attestation and the artifacts' provenance in,
 * a verdict out.
 *
 * ## The order of the checks is the contract
 *
 *  1. **Is the request coherent?** A request that names an empty entry asks for
 *     something that cannot exist, and no host can answer it. Refused as a
 *     [DenialReason.PolicyConflict], because it is the operator's request that
 *     is broken, not the host's capability.
 *  2. **Are the artifacts attested?** An artifact with no attested provenance
 *     cannot be loaded into a boundary the operator believes is closed. This
 *     comes before the capability proof because the artifact is a fact already in
 *     hand, while the proof is a question about the host. A caller whose
 *     decision is NOT about the artifacts says [PluginAdmissionFact.OutOfScope],
 *     and the core answers the posture.
 *  3. **What does the request demand?** Nothing beyond the legacy behaviour
 *     means the legacy posture is enough, and the run is admitted on the
 *     posture's own identity — which is the path every existing run takes, and
 *     the reason the legacy behaviour is unchanged.
 *  4. **Can the host prove it?** A provider that proves the demanded set admits
 *     the run; a provider that proves less is refused with the SET that is
 *     missing; a host that could not be asked is refused as a missing
 *     attestation.
 *
 * ## Why the trusted posture is refused and not merely unhelpful
 *
 * A request that demands isolation, met by a host that offers only the trusted
 * posture, is refused as [DenialReason.MissingAttestation]: the thing that could
 * attest those capabilities is not installed. A request that demands isolation
 * from a host that DOES offer a provider, but proves less than the request needs,
 * is refused as [DenialReason.UnsupportedCapability]. The two are different
 * problems with different fixes, and collapsing them would tell an operator with
 * a working provider that they have no provider.
 */
object AdmissionDecider {
    /**
     * The capabilities a request has to have proven before it may run.
     *
     * In the trusted posture the demanded set is exactly what the request asks
     * BEYOND the legacy behaviour: the workspace-scoped step, the egress gate,
     * the unbounded budget and the credential store are what a run already gets,
     * so asking for them is asking for nothing new.
     *
     * In the untrusted posture the demanded set is EVERY capability: the point of
     * that posture is that the code is not trusted, and a boundary that leaves
     * any of these unproven is not a boundary. Which limits the request names is
     * a separate question — a provider that proves it can bound resources is
     * asked to bound them, and the request says how far.
     */
    fun requiredCapabilities(requirements: ExecutionRequirements): Set<IsolationCapability> =
        when (requirements.trustIntent) {
            TrustIntent.TrustedSingleTenant -> buildSet {
                if (requirements.filesystem is FilesystemAccess.ApprovedReadonly) {
                    add(IsolationCapability.FilesystemConfinement)
                }
                if (requirements.network is NetworkAccess.Allowlisted) {
                    add(IsolationCapability.NetworkConfinement)
                }
                if (requirements.resources is ResourceBudget.Bounded) {
                    add(IsolationCapability.ResourceLimits)
                }
                if (requirements.secrets is SecretGrant.Scoped) {
                    add(IsolationCapability.CredentialIsolation)
                }
            }

            TrustIntent.UntrustedIsolated -> IsolationCapability.entries.toSet()
        }

    /**
     * Decide one request.
     *
     * Pure: the same query always yields the same verdict, and the verdict
     * depends on nothing but the three values in the query.
     */
    fun decide(query: AdmissionQuery): Admission {
        incoherence(query.requirements)?.let { detail ->
            return Admission.Denied(DenialReason.PolicyConflict(detail))
        }

        val plugins = query.plugins
        if (plugins is PluginAdmissionFact.Unattested &&
            query.requirements.trustIntent == TrustIntent.UntrustedIsolated
        ) {
            return Admission.Denied(DenialReason.UntrustedPlugin(plugins.artifacts))
        }

        val required = requiredCapabilities(query.requirements)
        if (required.isEmpty()) {
            return Admission.Allowed(
                ExecutionGrant(
                    requirements = query.requirements,
                    proven = emptyList(),
                    rationale = TRUSTED_POSTURE_RATIONALE,
                ),
            )
        }

        val verdict = when (val attestation = query.attestation) {
            is IsolationAttestation.Provider -> {
                val proven = attestation.proven.map { it.capability }.toSet()
                val missing = required - proven
                if (missing.isEmpty()) {
                    Admission.Allowed(
                        ExecutionGrant(
                            requirements = query.requirements,
                            proven = attestation.proven,
                            rationale = "a provider proved every capability this request demands",
                        ),
                    )
                } else {
                    Admission.Denied(DenialReason.UnsupportedCapability(missing))
                }
            }

            is IsolationAttestation.Unprobeable ->
                Admission.Denied(DenialReason.MissingAttestation(attestation.reason))

            IsolationAttestation.TrustedProfileOnly ->
                if (query.requirements.trustIntent == TrustIntent.UntrustedIsolated) {
                    Admission.Denied(DenialReason.MissingAttestation(NO_PROVIDER_DETAIL))
                } else {
                    Admission.Denied(DenialReason.UnsupportedCapability(required))
                }
        }

        return verdict
    }

    /**
     * The incoherence of a request, if it has one.
     *
     * A scope that names an empty entry asks for a boundary around nothing, and
     * a host that honoured it would be reporting a confinement it did not
     * apply. The request is refused rather than tidied: tidying it would mean
     * the product guesses what the operator meant, and a guess about a boundary
     * is the one guess that must not be made.
     */
    private fun incoherence(requirements: ExecutionRequirements): String? = when {
        requirements.filesystem is FilesystemAccess.ApprovedReadonly &&
            requirements.filesystem.paths.any { it.isBlank() } ->
            "the read-only filesystem request names an empty path"

        requirements.network is NetworkAccess.Allowlisted &&
            requirements.network.destinations.any { it.isBlank() } ->
            "the network allowlist names an empty destination"

        requirements.secrets is SecretGrant.Scoped &&
            requirements.secrets.ids.any { it.isBlank() } ->
            "the secret grant names an empty identifier"

        else -> null
    }

    private const val TRUSTED_POSTURE_RATIONALE =
        "the trusted single-tenant posture hosts this run; it confines nothing and " +
            "the request demands nothing beyond it"

    private const val NO_PROVIDER_DETAIL =
        "this build ships no isolation provider, so nothing can attest the " +
            "capabilities an untrusted run needs"
}
