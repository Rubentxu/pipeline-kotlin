package dev.rubentxu.pipeline.v2.release

import java.util.HexFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: a release publishes the candidate's files and exactly one
 * digest authority.
 *
 * The capacity this serves: a publication that completes and that a consumer
 * can check. The gate and the publisher used to assemble the asset set
 * separately, and the publisher staged the candidate's digest authority and
 * then wrote its own over it — two files with the same name and different
 * coverage, and a release that died at its last step. The set is decided in
 * one place now, and the rule it follows is that the candidate's digest
 * authority is evidence about the candidate, not an asset of the release.
 *
 * These rows are pure: a manifest in, a verdict out. No build, no filesystem,
 * no clock. What the manifest says is not the point; what the release may
 * carry is.
 */
class PublishedAssetSetTest {

    private val version = requireNotNull(ProductVersion.parseOrNull("0.76.0"))

    private fun manifest(
        sbom: DistributionArtifactRef? = ref("pipelinek-0.76.0.sbom.json"),
        sdk: DistributionArtifactRef? = ref("pipelinek-0.76.0.sdk.zip"),
        bom: DistributionArtifactRef? = ref("pipelinek-bom-0.76.0.json"),
        sha256sums: DistributionArtifactRef? = ref("SHA256SUMS"),
    ) = DistributionManifest(
        version = version,
        asset = DistributionAsset(
            name = "pipelinek-0.76.0.zip",
            archiveRoot = "pipelinek-0.76.0",
            size = 1L,
            sha256 = digest("pipelinek-0.76.0.zip"),
            implementationVersion = "0.76.0",
        ),
        source = DistributionSource("commit", null, "toolchain"),
        sbom = sbom,
        sdk = sdk,
        bom = bom,
        sha256sums = sha256sums,
    )

    private fun ref(name: String) = DistributionArtifactRef(name, digest(name))

    private fun digest(seed: String): String =
        HexFormat.of().formatHex(seed.toByteArray()).padEnd(64, '0')

    private fun publishable(candidate: DistributionManifest): PublishedAssetSet {
        val verdict = evaluatePublishedAssetSet(candidate)
        assertTrue(
            verdict is PublishedAssetSetVerdict.Publishable,
            "the candidate must be publishable, but the set was refused: " +
                ((verdict as? PublishedAssetSetVerdict.Refused)?.reason ?: "no reason given"),
        )
        return (verdict as PublishedAssetSetVerdict.Publishable).set
    }

    private fun refusal(candidate: DistributionManifest): String {
        val verdict = evaluatePublishedAssetSet(candidate)
        assertTrue(
            verdict is PublishedAssetSetVerdict.Refused,
            "the candidate must be refused, but the set was publishable",
        )
        return (verdict as PublishedAssetSetVerdict.Refused).reason
    }

    @Test
    fun `the published set carries the candidate's files and not its digest authority`() {
        val set = publishable(manifest())

        assertEquals(
            "pipelinek-0.76.0.zip",
            set.distribution.name,
            "the distribution is published as the candidate's bytes",
        )
        assertEquals(
            listOf(
                "pipelinek-0.76.0.sbom.json",
                "pipelinek-0.76.0.sdk.zip",
                "pipelinek-bom-0.76.0.json",
            ),
            set.assets.map { it.name },
            "the assets that travel with the distribution are published too",
        )
        assertTrue(
            set.assets.none { it.name == PublishedAssets.DIGEST_AUTHORITY_NAME },
            "the candidate's digest authority is evidence about the candidate, not an asset of " +
                "the release: the release has ONE digest authority, the one generated over " +
                "everything the release carries but itself, and publishing the candidate's would " +
                "put two files with the same name on the release with different coverage",
        )
    }

    @Test
    fun `the published set carries the digest the gate measured for each file`() {
        val set = publishable(manifest())

        assertEquals(
            digest("pipelinek-0.76.0.sbom.json"),
            set.assets.first { it.name == "pipelinek-0.76.0.sbom.json" }.sha256,
            "the receipt names the digest the gate measured, so the publisher can refuse a file " +
                "that is not the one the candidate was admitted for",
        )
    }

    @Test
    fun `a candidate without one of its assets is refused and the refusal names it`() {
        val reason = refusal(manifest(bom = null))

        assertTrue(
            reason.contains("BOM"),
            "the reason an operator reads names what is missing: a refusal that does not say " +
                "which file is absent is a refusal that has to be investigated twice",
        )
    }

    @Test
    fun `two assets with the same name are refused`() {
        val reason = refusal(
            manifest(sbom = ref("pipelinek-0.76.0.sbom.json"), bom = ref("pipelinek-0.76.0.sbom.json")),
        )

        assertTrue(
            reason.contains("pipelinek-0.76.0.sbom.json"),
            "the refusal names the name that would have been published twice",
        )
    }

    @Test
    fun `an asset that claims the release's digest authority name is refused`() {
        val reason = refusal(manifest(bom = ref("SHA256SUMS")))

        assertTrue(
            reason.contains(PublishedAssets.DIGEST_AUTHORITY_NAME),
            "the refusal names the reserved name: the publisher writes that file over " +
                "everything the release carries but itself, so an asset with that name would be " +
                "replaced by the digest of the release",
        )
    }
}
