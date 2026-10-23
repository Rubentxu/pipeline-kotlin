package dev.rubentxu.pipeline.v2.release

import java.nio.file.Path

/**
 * Where a candidate's published assets live.
 *
 * The publisher assembles the asset set from ONE directory: the one that holds
 * the distribution ZIP. That directory IS the asset set, and every asset the
 * manifest names is resolved there by its name.
 *
 * The digest authority is one of those assets, so it belongs with the assets it
 * digests and not with the receipt that describes them. When the two drifted
 * apart, the gate still said PASS — it had measured and written everything it
 * was asked to — and the publication failed at its last step, after the
 * candidate had been assembled, with a file that was not where the publisher
 * was going to look for it. A disagreement about a set that is supposed to be
 * fixed has to be settled where the set is built.
 */
object PublishedAssets {

    /** The directory that holds the distribution ZIP, and therefore the asset set. */
    fun directoryOf(zip: Path): Path = zip.toAbsolutePath().parent

    /**
     * Where the digest authority lives: with the assets it digests.
     *
     * A manifest cannot contain its own digest, so this file is the one asset
     * that covers the others and covers nothing that covers itself.
     */
    fun digestAuthorityFor(zip: Path): Path = directoryOf(zip).resolve(DIGEST_AUTHORITY_NAME)

    /** The name the publisher resolves the digest authority by. */
    const val DIGEST_AUTHORITY_NAME: String = "SHA256SUMS"
}

/**
 * Whether every published asset of a candidate sits in the asset set's
 * directory.
 */
sealed interface AssetPlacement {

    /** Every asset is in the distribution's directory. */
    data object CoLocated : AssetPlacement

    /**
     * An asset is somewhere else, so the publisher cannot assemble the set it
     * is told to publish.
     */
    data class Stranded(
        val asset: Path,
        val expectedDirectory: Path,
    ) : AssetPlacement
}

/**
 * Decide whether the published assets are co-located with the distribution
 * ZIP. Pure: it compares paths and reads nothing.
 *
 * The rule exists because the two ends of the publication — the gate that
 * assembles the candidate and the publisher that uploads it — have to agree on
 * where the asset set lives, and agreement by convention is agreement until
 * somebody moves a file.
 */
fun evaluateAssetPlacement(zip: Path, assets: List<Path>): AssetPlacement {
    val directory = PublishedAssets.directoryOf(zip)
    val stranded = assets.firstOrNull { it.toAbsolutePath().parent != directory }
    return if (stranded == null) {
        AssetPlacement.CoLocated
    } else {
        AssetPlacement.Stranded(stranded, directory)
    }
}

/**
 * Render a placement verdict as the operator-facing reason a candidate was
 * refused.
 */
fun AssetPlacement.render(): String = when (this) {
    is AssetPlacement.CoLocated ->
        "every published asset is in the distribution's directory"

    is AssetPlacement.Stranded ->
        "the asset $asset is not in $expectedDirectory, which is where the distribution " +
            "lives and therefore where the publisher assembles the asset set from. " +
            "Refusing to admit a candidate the publisher cannot assemble: an asset set " +
            "with a member in another directory is not a smaller candidate, it is a " +
            "candidate nobody can publish."
}
