package dev.rubentxu.pipeline.v2.domain.step

/**
 * Trust metadata for a plugin provider (PLUGIN_IDENTITY_MODEL, evolved by)
 * ## The seam closes
 * Before this ADT was deliberately a single case — [Unverified] — because the
 * cycle had no verified-trust capability and adding new states without their evidence path
 * Was a flag-bag defect. is the evidence path: the runtime can now measure the artifact
 * bytes, look the digest up in the operator trust database, and produce one of three typed
 * outcomes. Each carries the payload needed to name the artifact, the digests involved, and
 * the reason the runtime could not promote the state to [Verified].
 * ## Why a closed sealed type, not a `trust: Boolean`
 * Three real states exist, with three different operational meanings, and conflating any two
 * of them makes the gate either fail-open (a missing measurement that read as "verified") or
 * fail-misleading (a measured mismatch that read as "the operator has not pinned it"). A
 * boolean discards the third value; a nullable string discards the typed reason. Both make
 * the gate a function of how the caller chose to spell the failure.
 * ## Mapping refusal → trust case
 * | AdmissionRefusal case                  | TrustMetadata case                         |
 * | -------------------------------------- | ------------------------------------------ |
 * | `RefusedDigestMismatch`                | `Mismatch(declared, measured, origin)`     |
 * | `RefusedUntrustedIdentity(reason)`     | `Unverified(reason, origin)`               |
 * | `RefusedAlreadyAdmittedDifferentArtifact` | n/a — already-admitted layer, not identity |
 * | `RefusedNoManifest`                    | `Unverified(ArtifactNotMeasurable, origin)`|
 * | `RefusedIncompatibleVersion`           | n/a — version gate, not trust              |
 * The first three rows are the trust cases; the last two rows sit outside the trust
 * ADT because they describe manifest-absence and API-range refusals, not identity.
*/
sealed interface TrustMetadata {

    /**
     * The runtime measured the artifact bytes and the digest matches the claim.
     * This is a positive end-state: the gate may admit on it, and a refused plugin at
     * admission time cannot have produced a [Verified] value (the only way a refused plugin
     * ends up Verified is if the gate's measurement was wrong, and the harness proves that
     * Not happening by in the mutation test plan)
*/
    data class Verified(val digest: Digest, val origin: ArtifactOrigin) : TrustMetadata

    /**
     * The runtime has not been able to confirm the artifact's identity.
     * Carries [reason] and [origin] so a refusal can say WHY a plugin is unverified (no
     * operator trust row, the bytes could not be read, or the pinned digest disagrees with
     * the claim) and WHERE it came from. The default values model the manifest-decode-time
     * state: the codec has parsed the document and the gate has not yet measured. The gate
     * replaces this with a fully-typed [Unverified] (or promotes it) before any admission
     * decision is made, so a default-bearing [Unverified] never escapes past the gate.
*/
    data class Unverified(
        val reason: UnverifiedReason = UnverifiedReason.NoTrustedDigestAvailable,
        val origin: ArtifactOrigin = ArtifactOrigin.Unknown,
    ) : TrustMetadata

    /**
     * The runtime measured the artifact and the digest disagrees with the manifest's claim.
     * A plugin that lies about its own digest. Both digests are kept so a refused plugin can
     * be diagnosed from the journal row alone, without re-reading the artifact. A `Mismatch`
     * is also a fail-closed state: [Verified] is the only case that may be admitted under
     * the trust policy.
*/
    data class Mismatch(
        val declared: Digest,
        val measured: Digest,
        val origin: ArtifactOrigin,
    ) : TrustMetadata

    /**
     * Why the runtime could not produce a [Verified] state.
     * Closed, not a `String`: a refusal that names the reason as a free-form string lets
     * "no row in operator trust db" read the same as "we never tried", and the gate would
     * lose the ability to refuse one and admit the other. Each case below is a single
     * distinct operational meaning.
*/
    sealed interface UnverifiedReason {

        /**
         * The operator has not pinned this plugin's digest in the operator trust database.
         * Default fail-closed state: a plugin the operator has never authorised is refused
         * with this reason, never admitted on the manifest's word. The same `Unverified` was
         * previously the only legal value of [TrustMetadata]; that is no longer true, and
         * the codec's decoding of the `unverified` token now produces THIS reason explicitly.
*/
        data object NoTrustedDigestAvailable : UnverifiedReason

        /**
         * The runtime could not read the artifact's bytes.
         * The [origin] field carries whatever the runtime knows about where the artifact
         * lives; the failure is that the bytes were not reachable. This is distinct from
         * [NoTrustedDigestAvailable] because the operator may have pinned the digest but
         * the runtime cannot confirm it — and an admit on "we could not measure" is the
         * same fail-open defect [Mismatch] was added to prevent.
*/
        data object ArtifactNotMeasurable : UnverifiedReason

        /**
         * The operator pinned a digest, the plugin's manifest claims a different one, and
         * the runtime caught the lie before any effect.
         * Distinct from [Mismatch] at the level of policy: [Mismatch] is the byte-level
         * "we measured and disagreed" verdict, while [OutsideOperatorTrustPolicy] is the
         * policy-level "the operator's allow-list says one thing and the manifest says
         * another" verdict. Both are fail-closed; both are refused.
*/
        data object OutsideOperatorTrustPolicy : UnverifiedReason
    }
}
