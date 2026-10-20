package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Where the operator's trust material comes from.
 *
 * Two ports, no policy: the rows the operator wrote, and the keys they keep. The policy that
 * combines them lives in [PluginTrustPolicy].
 */
class PluginTrustSource(
    /** The operator's rows. */
    val trustDatabase: OperatorTrustDatabase,
    /** The publisher keys the operator keeps. */
    val keyRing: PublisherKeyRing,
)

/**
 * The operator's trust policy, in force for one composition.
 *
 * ## What it covers, and why
 * The policy covers the artifacts the OPERATOR brought — the ones named with `--plugin-jar`. It
 * does not cover the plugins bundled with the installation: those ship inside the distribution the
 * operator installed, and their integrity is that distribution's concern (its checksums, its
 * release signature), not a row the operator has to write for every bundled plugin before any
 * third-party plugin can be used. A policy that made the bundled set un-pinnable would be a
 * policy nobody turns on.
 *
 * The coverage is decided by BYTES, not by path: an operator may name a plugin by any path, and
 * the same bytes under two paths are one artifact. Once the bytes have been measured, the
 * question is whether those bytes were the ones the operator brought, and the answer is the same
 * either way.
 *
 * ## Why the operator's path is kept, and not only their bytes
 * The publisher's signature travels BESIDE the copy the operator downloaded. When the operator
 * names a plugin the installation also bundles, the two copies are the same bytes and the
 * composition keeps one of them — but the signature sits next to the operator's copy, not next to
 * the copy that happened to survive deduplication. So the policy remembers WHERE the operator's
 * copy is, by digest, and reads the signature from there. The signature covers bytes, not paths:
 * reading it from the operator's copy is reading it from the copy those bytes came from.
 *
 * ## What it reads
 * The artifact's manifest, the operator's row, the publisher's detached signature, and the
 * operator's public key. Everything else is left to the pure decision in
 * [PluginTrustDecider], which is where the policy actually lives.
 */
class PluginTrustPolicy(
    private val source: PluginTrustSource,
    private val operatorArtifacts: Map<String, Path>,
) {

    /**
     * Whether the policy has anything to say about the artifact with these bytes.
     */
    fun covers(measuredDigest: Digest): Boolean =
        operatorArtifacts.containsKey(measuredDigest.value.lowercase())

    /**
     * Decide whether the artifact at [artifact] is the one the operator approved.
     *
     * @param manifest the artifact's own declaration, already decoded by the admission pass.
     * @param measuredDigest the digest the runtime measured over the artifact's bytes.
     */
    fun evaluate(
        artifact: Path,
        manifest: PluginManifest,
        measuredDigest: Digest,
    ): PluginTrustDecision {
        val pluginId = manifest.plugin.canonicalText()
        val releaseVersion = manifest.release.version
        val row = source.trustDatabase.lookup(pluginId, releaseVersion)
        val operatorCopy = operatorArtifacts[measuredDigest.value.lowercase()]
        val signature = readSignature(operatorCopy ?: artifact)
        val publicKey = row?.signedBy
            ?.let { source.keyRing.publicKey(it) }
            ?.let { Base64.getEncoder().encodeToString(it) }

        return PluginTrustDecider.decide(
            PluginTrustFacts(
                pluginId = pluginId,
                releaseVersion = releaseVersion,
                declaredPublisher = manifest.publisher,
                measuredDigest = measuredDigest,
                operatorRow = row,
                signature = signature,
                publisherPublicKey = publicKey,
            ),
        )
    }

    /**
     * The publisher's detached signature beside the artifact, or `null` when there is none this
     * runtime can read. A missing file, an unreadable file and a malformed document are the same
     * fact here: there is no signature, and the decision refuses that.
     */
    private fun readSignature(artifact: Path): PluginArtifactSignature? {
        val sidecar = PluginArtifactSignatureCodec.sidecarOf(artifact)
        if (!Files.isRegularFile(sidecar)) return null
        val text = runCatching { Files.readString(sidecar, Charsets.UTF_8) }.getOrNull() ?: return null
        return PluginArtifactSignatureCodec.decode(text)
    }
}
