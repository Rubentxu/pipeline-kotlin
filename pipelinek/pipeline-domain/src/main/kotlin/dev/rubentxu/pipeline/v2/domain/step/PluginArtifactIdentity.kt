package dev.rubentxu.pipeline.v2.domain.step

/**
 * Why a manifest was refused.
 * A closed ADT on purpose. "Rejected" with a String reason is how a fail-closed boundary
 * degrades into a log line nobody can switch on, and the whole value of refusing a plugin
 * is that a caller can act on WHY without parsing English.
 * Each case carries the evidence needed to diagnose without re-reading the artifact:
 * [UnsupportedSchema] and [UnknownCodec] say what was asked for, [DuplicateIdentity] says
 * which identity collided and with whom, so admission can name two plugins rather than
 * pointing at "a conflict".
*/
sealed interface PluginManifestRejection : AdmissionRejection {

    /** The bytes are not a manifest this codec can read at all. */
    data class MalformedDocument(val detail: String) : PluginManifestRejection

    /** Well-formed JSON, wrong document: a schema version this release does not admit. */
    data class UnsupportedSchema(val found: String) : PluginManifestRejection

    /** Well-formed, but written by a codec whose spelling differs from [PluginManifestCodec.CODEC]. */
    data class UnknownCodec(val found: String) : PluginManifestRejection

    /** Two artifacts claim the same plugin identity. Carries the incumbent it collides with. */
    data class DuplicateIdentity(val identity: String, val incumbent: String) : PluginManifestRejection

    /** The declared PipelineK API range excludes the running version. */
    data class IncompatibleApiRange(
        val identity: String,
        val declared: PipelineKApiRange,
        val runtime: SemVer,
    ) : PluginManifestRejection

    /** The manifest parsed and is structurally sound, but a cross-field invariant failed. */
    data class InvalidManifest(val identity: String, val detail: String) : PluginManifestRejection

    /**
     * The artifact's real bytes could not be measured, so nothing about it can be established.
     * Distinct from [MalformedDocument] on purpose. A malformed document is a verdict about the
     * manifest TEXT; this is a verdict about the ARTIFACT — an unreadable JAR, a directory that
     * disappeared between enumeration and admission. Folding the second into the first would report
     * a packaging fault as if the plugin had written nonsense, and the operator's remedy is different
     * in each case.
     * Composition refuses on this BEFORE loading any class from the artifact. An artifact whose
     * bytes cannot be hashed is not an artifact that may be executed, and there is no degraded
     * reading in which that is safe: the digest is what tells one copy of a plugin from another.
*/
    data class ArtifactNotMeasurable(val artifact: String, val detail: String) : PluginManifestRejection

    /**
     * The artifact's bytes changed between the moment they were measured and the moment they would
     * have been loaded.
     * A time-of-check/time-of-use seam, and the reason the measurement is taken before composition
     * rather than only inside it: a digest measured once and trusted later proves nothing about the
     * file that is actually read. Carries both digests so the drift is auditable rather than merely
     * reported.
*/
    data class ArtifactChangedDuringAdmission(
        val artifact: String,
        val measuredDigest: String,
        val foundDigest: String,
    ) : PluginManifestRejection
}

/**
 * Total result of reading a manifest. There is no third state and no exception: a boundary
 * reading bytes it did not author must be able to answer "refused, and here is why"
 * without the reader's own bugs becoming indistinguishable from a bad plugin.
*/
sealed interface PluginManifestDecodeResult {

    data class Accepted(val manifest: PluginManifest) : PluginManifestDecodeResult

    data class Rejected(val rejection: PluginManifestRejection) : PluginManifestDecodeResult

    companion object {
        fun accept(manifest: PluginManifest?): PluginManifestDecodeResult =
            if (manifest == null) {
                Rejected(PluginManifestRejection.MalformedDocument("manifest document is null or unreadable"))
            } else {
                Accepted(manifest)
            }

        fun reject(rejection: PluginManifestRejection): PluginManifestDecodeResult = Rejected(rejection)
    }
}

/**
 * Identity of an artifact AS THE RUNTIME MEASURED IT.
 * ## Why this type exists at all
 * A manifest carries its own `digest` field, and refuses "self-declared digest as proof": a
 * plugin that can write its own digest can write any digest. So the runtime keeps the measured
 * bytes apart from the declared value, and it is the RUNTIME's digest that is authoritative for
 * identity — the declared one is provenance.
 * ## What does and does not do with it
 * [PluginAdmission] requires this type as an input, which means a caller CANNOT admit a plugin
 * by handing over a manifest alone. That is the structural half of the guarantee and it is what
 * delivers it.
 * The other half — actually hashing the JAR bytes and comparing them to the declared digest —
 * is a capability this runtime does not have, and the reason is worth stating in the type
 * rather than leaving it to be discovered: the manifest declares a digest of the plugin's
 * CONTENT and the runtime measures a digest of the FILE that carries that content, so the two
 * are different magnitudes and never agree. What the runtime compares is the artifact with
 * itself: the bytes read before admission have to be the bytes that are loaded.
 * This type is the seam the verifier occupies: it exists so the verifier plugs in without
 * changing admission's signature. Declaring that limit here, in the type, is what keeps the
 * seam honest. A type called "artifact identity" that silently meant "the digest the plugin
 * said about itself" is the defect this naming prevents.
*/
data class MeasuredArtifactIdentity(
    val declaredDigest: Digest,
    val measuredDigest: Digest?,
    val origin: ArtifactOrigin,
) {
    /**
     * What the runtime can say about the artifact's bytes against the manifest's claim.
     * `measuredDigest == null` means "the runtime did not measure", and that is deliberately
     * NOT a pass: it is an [Unverified] state, distinct from both agreement and disagreement.
     * Collapsing "nobody checked" into "checked and fine" is the fail-open this whole type
     * was written to prevent.
     * A [ArtifactIdentityVerdict.Mismatch] here means the artifact and the manifest disagree.
     * The two digests are different magnitudes, so this verdict is not an accusation, and
     * nothing in the runtime acts on it: the refusal that does act on the bytes lives in the
     * admission layer, which compares the artifact with itself.
*/
    val verdict: ArtifactIdentityVerdict
        get() = when {
            measuredDigest == null -> ArtifactIdentityVerdict.Unverified(origin)
            measuredDigest.value.equals(declaredDigest.value, ignoreCase = true) ->
                ArtifactIdentityVerdict.Verified(declaredDigest)

            else -> ArtifactIdentityVerdict.Mismatch(declared = declaredDigest, measured = measuredDigest)
        }
}

/**
 * Closed outcome of comparing a claim against bytes the runtime measured.
 * Three cases, not a boolean: `Unverified` is the state actually operates in, and it is
 * information a boolean would destroy. Every caller must therefore decide what to do about
 * a runtime that could not measure, rather than inheriting `true` by accident.
*/
sealed interface ArtifactIdentityVerdict {

    /** Bytes were measured and match the claim. */
    data class Verified(val digest: Digest) : ArtifactIdentityVerdict

    /**
     * Bytes were measured and they CONTRADICT the claim.
     * The two digests are different magnitudes, so this says that the artifact and the
     * manifest disagree, and nothing more: it is not, on its own, a statement about honesty.
     */
    data class Mismatch(val declared: Digest, val measured: Digest) : ArtifactIdentityVerdict

    /**
     * No measurement was possible. Not a pass.
     * Is in this state by construction: it has no artifact-resolution capability yet
     * It is named rather than defaulted so that "we could not check" is visible at every
     * call site instead of being absorbed into a green result.
*/
    data class Unverified(val origin: ArtifactOrigin) : ArtifactIdentityVerdict
}

/**
 * Where the runtime believes the artifact came from.
 * Deliberately a closed set rather than a path String: an admission decision recorded years
 * later has to be able to say whether the plugin came from a local JAR, a published
 * coordinate, or a compiled artifact the pipeline already had, and a String field would let
 * all three be spelled the same way.
*/
sealed interface ArtifactOrigin {

    /** A JAR or directory on the runtime classpath, identified by its own path. */
    data class LocalClasspathEntry(val location: String) : ArtifactOrigin

    /** Resolved from published Maven coordinates. */
    data class ResolvedCoordinate(val group: String, val artifact: String, val version: String) : ArtifactOrigin

    /**
     * An artifact the pipeline already had compiled and cached.
     * Included because requires admission to apply to compiled-artifact hits
     * too: a plugin that reaches the runtime by being reused rather than discovered must not
     * be the one path that skips admission.
*/
    data class ReusedCompiledArtifact(val cacheKey: String) : ArtifactOrigin

    /**
     * The runtime has not yet resolved where the artifact came from.
     * Placeholder used by the manifest codec: the codec parses the document but does
     * not measure the artifact, so it cannot know the real origin. The gate replaces this
     * with the actual [LocalClasspathEntry] / [ResolvedCoordinate] / [ReusedCompiledArtifact]
     * after measurement, and a refusal recorded against an [Unknown] origin is the smell of
     * a decision made before measurement completed.
*/
    data object Unknown : ArtifactOrigin
}

/**
 * First-level admission refusals that arise AFTER the manifest has decoded cleanly.
 * ## The two lifecycles
 * [PluginManifestRejection] and [AdmissionRefusal] are kept disjoint by design. Codec
 * failures (malformed document, unsupported schema, unknown codec) come from reading bytes
 * the runtime did not author and belong to the decode-time boundary. [AdmissionRefusal]
 * covers failures that depend on the runtime's own state — measured bytes, the operator
 * trust database, the admission log of who has already been admitted. The two are a
 * partition of the refusal surface, and a caller that needs to distinguish "this plugin
 * was malformed" from "this plugin was forged" switches on the parent
 * [AdmissionRejection] case, not on a String reason.
 * ## Why first-level, not derived
 * Each case below carries a different payload: a `RefusedDigestMismatch` carries two
 * digests; a `RefusedUntrustedIdentity` carries a [TrustMetadata.UnverifiedReason];
 * `RefusedAlreadyAdmittedDifferentArtifact` carries the incumbent's origin. Collapsing
 * them into `Refused(reason: String)` or into a single `RefusedTrustFailure(...)` would
 * make the refusal a function of the caller's prose, and the same defect the existing
 * `PluginManifestRejection` KDoc refuses ("a closed ADT on purpose... a String reason is
 * how a fail-closed boundary degrades into a log line nobody can switch on") would re-open
 * here.
 * ## Order of cases
 * Listed in the order the gate would discover them: no manifest first (the gate cannot
 * even measure an artifact it has no document for), then a digest mismatch, then a
 * trust-database gap, then a version conflict, then a duplicate identity whose
 * second-arrival bytes disagree with the first-arrival bytes.
*/
sealed interface AdmissionRefusal : AdmissionRejection {

    /**
     * The gate could not find a manifest for the contributor.
     * A plugin that declares nothing is refused rather than admitted on the strength of its
     * class. The contributor class name is carried so the refusal names the artifact, not
     * just "no document".
*/
    data class RefusedNoManifest(val contributor: String) : AdmissionRefusal

    /**
     * The bytes the operator approved are not the bytes the runtime measured.
     * Both [declared] and [measured] are kept so the durable control row can be diagnosed
     * from the journal alone. The [identity] is the canonical plugin text so the refusal can
     * name the same key every call.
     * The runtime does not produce this refusal today, and it does not produce the older
     * version of it either: the manifest declares a digest of the plugin's content while the
     * runtime measures a digest of the file that carries it, and comparing the two accuses
     * every plugin. It stays in the vocabulary because the refusal it names is a real one —
     * the operator pinning BYTES rather than a declaration — and that policy is a decision
     * about what the operator's file holds, not a defect to be patched here.
*/
    data class RefusedDigestMismatch(
        val identity: String,
        val declared: Digest,
        val measured: Digest,
    ) : AdmissionRefusal

    /**
     * The runtime could not produce a [TrustMetadata.Verified] state.
     * The [reason] names which kind of unverified: no operator trust row, bytes not
     * readable, or the operator's pinned digest disagrees with the manifest. The [identity]
     * is the canonical plugin text. The same plugin producing the same reason twice is
     * refused the same way, with no implicit "previous time we admitted it" recovery —
     * fail-closed is a state, not a heuristic.
*/
    data class RefusedUntrustedIdentity(
        val identity: String,
        val reason: TrustMetadata.UnverifiedReason,
    ) : AdmissionRefusal

    /**
     * The manifest is for a different PipelineK version range than the runtime enforces.
     * Distinct from [PluginManifestRejection.IncompatibleApiRange] in scope: that one is
     * the gate's primary version check; this one is the trust-layer re-assertion that the
     * declared [declared] range and the [runtime] version really do not overlap. Carrying
     * both as separate cases lets a future change in the version policy be reflected in
     * only one place without renaming the other.
*/
    data class RefusedIncompatibleVersion(
        val identity: String,
        val declared: PipelineKApiRange,
        val runtime: SemVer,
    ) : AdmissionRefusal

    /**
     * A second plugin claims the same identity as one already admitted, with bytes from a
     * different artifact.
     * Neither first-wins nor last-wins; the second one is refused, naming the [identity]
     * and the [incumbentOrigin] it collides with. The [declaredDigest] is the digest the
     * newcomer carried — useful when diagnosing a downgrade attack where the incumbent was
     * a legitimate earlier version and the newcomer is a later build substituted in place
     * of the legitimate one.
*/
    data class RefusedAlreadyAdmittedDifferentArtifact(
        val identity: String,
        val declaredDigest: Digest,
        val incumbentOrigin: ArtifactOrigin,
    ) : AdmissionRefusal
}
