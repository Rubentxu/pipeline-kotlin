package dev.rubentxu.pipeline.v2.release

import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: a candidate's published assets all live in the directory that
 * holds the distribution ZIP, and the digest authority lives with the assets it
 * digests.
 *
 * The capacity this serves: a candidate that can actually be published. The
 * gate assembles the asset set and the publisher uploads it, and they have to
 * agree on where the set lives — agreement by convention is agreement until
 * somebody moves a file, and when they disagreed the gate said PASS and the
 * publication failed at its last step, with an asset the publisher was not
 * going to find.
 *
 * These rows are pure: paths in, a verdict out. No build, no filesystem, no
 * clock. What the paths are is not the point; WHERE they are is.
 */
class PublishedAssetsTest {

    private val distributions = Paths.get("/build/pipeline-application/build/distributions")
    private val zip = distributions.resolve("pipelinek-0.76.0.zip")

    @Test
    fun `the digest authority lives with the assets it digests`() {
        assertEquals(
            distributions.resolve("SHA256SUMS"),
            PublishedAssets.digestAuthorityFor(zip),
            "the digest authority is a published asset: the publisher resolves it from the " +
                "distribution's directory, so that is where it has to be written",
        )
    }

    @Test
    fun `a candidate whose assets are all in the distribution's directory is accepted`() {
        val assets = listOf(
            distributions.resolve("pipelinek-0.76.0.sbom.json"),
            distributions.resolve("pipelinek-0.76.0.sdk.zip"),
            distributions.resolve("pipelinek-bom-0.76.0.json"),
            distributions.resolve("SHA256SUMS"),
        )

        val placement = evaluateAssetPlacement(zip, assets)

        assertEquals(AssetPlacement.CoLocated, placement)
    }

    @Test
    fun `an asset outside the distribution's directory is refused and named`() {
        val stranded = Paths.get("/build/pipeline-release/build/candidate/SHA256SUMS")

        val placement = evaluateAssetPlacement(
            zip,
            listOf(distributions.resolve("pipelinek-0.76.0.sbom.json"), stranded),
        )

        val refusal = placement as AssetPlacement.Stranded
        assertEquals(stranded, refusal.asset, "the refusal names the asset that is stranded")
        assertEquals(
            distributions,
            refusal.expectedDirectory,
            "the refusal names the directory the asset belongs in",
        )
        assertTrue(
            refusal.render().contains(stranded.toString()),
            "the reason an operator reads names the asset: a refusal that does not say " +
                "which file is wrong is a refusal that has to be investigated twice",
        )
    }
}
