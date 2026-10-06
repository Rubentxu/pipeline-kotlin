package dev.rubentxu.pipeline.v2.domain.step

/**
 * S6/C — why a manifest was refused.
 *
 * A closed ADT on purpose. "Rejected" with a String reason is how a fail-closed boundary
 * degrades into a log line nobody can switch on, and the whole value of refusing a plugin
 * is that a caller can act on WHY without parsing English.
 *
 * Each case carries the evidence needed to diagnose without re-reading the artifact:
 * [UnsupportedSchema] and [UnknownCodec] say what was asked for, [DuplicateIdentity] says
 * which identity collided and with whom, so admission can name two plugins rather than
 * pointing at "a conflict".
 */
sealed interface PluginManifestRejection {

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
 * S6/C — identity of an artifact AS THE RUNTIME MEASURED IT.
 *
 * ## Why this type exists at all
 *
 * A manifest carries its own `digest` field, and ADR-EVO-003 refuses "self-declared digest
 * as proof": a plugin that can write its own digest can write any digest. So the runtime
 * keeps the measured bytes apart from the declared value, and it is the RUNTIME's digest
 * that is authoritative for identity — the declared one is provenance.
 *
 * ## What S6 does and does not do with it
 *
 * [PluginAdmission] requires this type as an input, which means a caller CANNOT admit a
 * plugin by handing over a manifest alone. That is the structural half of the guarantee and
 * it is what S6 delivers.
 *
 * The other half — actually hashing the JAR bytes and comparing them to the declared
 * digest — is a capability that does not exist yet in this runtime, because S6 has no notion
 * of where an artifact came from. That comparison is recorded as the opening condition of
 * EVO-M3b, where the generic artifact model lands. This type is the seam it will occupy:
 * it exists so the eventual verifier plugs in without changing admission's signature.
 *
 * Declaring that limit here, in the type, is what keeps the seam honest. A type called
 * "artifact identity" that silently meant "the digest the plugin said about itself" is the
 * defect this naming prevents.
 */
data class MeasuredArtifactIdentity(
    val declaredDigest: Digest,
    val measuredDigest: Digest?,
    val origin: ArtifactOrigin,
) {
    /**
     * True when the runtime could hash the artifact and the hash disagrees with the claim.
     *
     * `measuredDigest == null` means "the runtime did not measure", and that is deliberately
     * NOT a pass: it is an [Unverified] state, distinct from both agreement and disagreement.
     * Collapsing "nobody checked" into "checked and fine" is the fail-open this whole type
     * was written to prevent.
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
 *
 * Three cases, not a boolean: `Unverified` is the state S6 actually operates in, and it is
 * information a boolean would destroy. Every caller must therefore decide what to do about
 * a runtime that could not measure, rather than inheriting `true` by accident.
 */
sealed interface ArtifactIdentityVerdict {

    /** Bytes were measured and match the claim. */
    data class Verified(val digest: Digest) : ArtifactIdentityVerdict

    /** Bytes were measured and CONTRADICT the claim. A plugin that lies about itself. */
    data class Mismatch(val declared: Digest, val measured: Digest) : ArtifactIdentityVerdict

    /**
     * No measurement was possible. Not a pass.
     *
     * S6 is in this state by construction: it has no artifact-resolution capability yet.
     * It is named rather than defaulted so that "we could not check" is visible at every
     * call site instead of being absorbed into a green result.
     */
    data class Unverified(val origin: ArtifactOrigin) : ArtifactIdentityVerdict
}

/**
 * Where the runtime believes the artifact came from.
 *
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
     *
     * Included because ADR-EVO-003 requires admission to apply to compiled-artifact hits
     * too: a plugin that reaches the runtime by being reused rather than discovered must not
     * be the one path that skips admission.
     */
    data class ReusedCompiledArtifact(val cacheKey: String) : ArtifactOrigin
}
