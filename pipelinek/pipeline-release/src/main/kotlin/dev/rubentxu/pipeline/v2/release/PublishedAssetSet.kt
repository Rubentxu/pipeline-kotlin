package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One file a release publishes: the name it is uploaded under and the digest
 * of the bytes it holds.
 *
 * The digest is what makes a receipt a receipt. The gate measured these
 * bytes; the publisher re-measures them and refuses any file whose digest
 * differs. A release carrying a file nobody measured is not a release with a
 * weaker guarantee, it is a release with no guarantee.
 */
@Serializable
data class PublishedAsset(
    val name: String,
    val sha256: String,
)

/**
 * The candidate's files a release publishes.
 *
 * The candidate's own digest authority is deliberately not one of them. It
 * digests the payload assets as the gate measured them; the release's digest
 * authority digests everything the release carries, which is those same assets
 * plus the provenance files. Publishing both would put two files with the same
 * name and different coverage on one release, and the one a consumer opens
 * first might be the one that does not cover the provenance. The candidate's
 * authority stays where it is: evidence about the candidate.
 */
@Serializable
data class PublishedAssetSet(
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    /** The distribution itself. */
    val distribution: PublishedAsset,
    /** The assets that travel with the distribution. */
    val assets: List<PublishedAsset>,
) {
    companion object {
        const val SCHEMA_VERSION: String = "pipelinek-published-assets-1"
    }
}

/**
 * What the published asset set may be, or why it may not be published.
 */
sealed interface PublishedAssetSetVerdict {

    /**
     * The set is publishable: [set] names the candidate's files and the digest
     * each one must have.
     */
    data class Publishable(val set: PublishedAssetSet) : PublishedAssetSetVerdict

    /** The set may not be published, and [reason] says why. */
    data class Refused(val reason: String) : PublishedAssetSetVerdict
}

/**
 * Decide which of a candidate's files a release publishes.
 *
 * Pure: it reads the manifest and returns a verdict, touching nothing. The
 * publisher asks it instead of assembling a set of its own, because the two
 * once disagreed — the publisher staged the candidate's digest authority and
 * then wrote its own over it, and the release died at the last step with two
 * files claiming the same name and different coverage.
 *
 * The rule that resolves it: a release has ONE digest authority, the one the
 * publisher generates over everything the release carries. The candidate's
 * authority covers a strict subset of that, so publishing it would add nothing
 * a consumer could check and would make "the" authority ambiguous.
 */
fun evaluatePublishedAssetSet(manifest: DistributionManifest): PublishedAssetSetVerdict {
    val slots = listOf(
        "SBOM" to manifest.sbom,
        "SDK" to manifest.sdk,
        "BOM" to manifest.bom,
    )

    val absent = slots.filter { (_, ref) -> ref == null }.map { (role, _) -> role }
    if (absent.isNotEmpty()) {
        return PublishedAssetSetVerdict.Refused(
            "the candidate carries no ${absent.joinToString(" and ")}, and the release asset " +
                "set is fixed: a release published without one of its assets is not a smaller " +
                "release, it is a release a consumer cannot check.",
        )
    }

    val distribution = PublishedAsset(manifest.asset.name, manifest.asset.sha256)
    val assets = slots.mapNotNull { (_, ref) -> ref }.map { PublishedAsset(it.name, it.sha256) }

    val names = listOf(distribution.name) + assets.map { it.name }
    val repeated = names.groupBy { it }.filterValues { it.size > 1 }.keys
    if (repeated.isNotEmpty()) {
        return PublishedAssetSetVerdict.Refused(
            "two published assets would carry the same name (${repeated.joinToString(", ")}), and " +
                "a release cannot carry two files under one name: a consumer that downloads " +
                "them has no way to tell which one the digest belongs to.",
        )
    }

    val reserved = names.firstOrNull { it == PublishedAssets.DIGEST_AUTHORITY_NAME }
    if (reserved != null) {
        return PublishedAssetSetVerdict.Refused(
            "a published asset would carry the name ${PublishedAssets.DIGEST_AUTHORITY_NAME}, " +
                "which is the release's own digest authority: the publisher writes that file " +
                "over everything the release carries but itself, so an asset with that name " +
                "would be replaced by the digest of the release.",
        )
    }

    return PublishedAssetSetVerdict.Publishable(
        PublishedAssetSet(distribution = distribution, assets = assets),
    )
}
