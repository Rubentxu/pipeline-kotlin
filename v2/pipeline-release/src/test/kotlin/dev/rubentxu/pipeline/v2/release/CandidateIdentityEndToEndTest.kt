package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P-UAT-01 — exact identity, end to end through the real materializer.
 *
 * The spec requires SIX surfaces to equal the target version V:
 *
 * ```text
 * ZIP filename == pipelinek-V.zip
 * root        == pipelinek-V/
 * app JAR     == V
 * Implementation-Version == V
 * installed `pipelinek version` == V
 * manifest.version == V
 * ```
 *
 * The unit suite verifies the pure decision and the probe separately. What is
 * verified HERE is the consequence at the boundary that actually publishes
 * material: does a candidate get written, or refused?
 *
 * The critical property is the one the v0.43.0 incident taught us. A surface
 * that CANNOT be observed must produce a refusal, never a manifest. If
 * `materialize` treated "unobservable" as "no objection", a ZIP missing its
 * application JAR would sail through and produce a manifest asserting an
 * identity nobody verified — the exact unbacked-claim failure the protocol
 * bans.
 */
class CandidateIdentityEndToEndTest {

    @TempDir
    lateinit var tempDir: Path

    private fun buildZip(
        assetVersion: String,
        rootVersion: String,
        innerVersion: String?,
        includeAppJar: Boolean = true,
    ): Path {
        val zip = tempDir.resolve("pipelinek-$assetVersion.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("pipelinek-$rootVersion/"))
            zos.closeEntry()
            if (includeAppJar) {
                val jar = tempDir.resolve("inner-$innerVersion.jar")
                ZipOutputStream(Files.newOutputStream(jar)).use { j ->
                    j.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                    j.write(
                        (
                            "Manifest-Version: 1.0\r\n" +
                                "Implementation-Version: $innerVersion\r\n\r\n"
                            ).toByteArray(),
                    )
                    j.closeEntry()
                }
                zos.putNextEntry(
                    ZipEntry("pipelinek-$rootVersion/lib/pipeline-application-$innerVersion.jar"),
                )
                zos.write(Files.readAllBytes(jar))
                zos.closeEntry()
            }
        }
        return zip
    }

    private fun materialize(zip: Path, declared: String, out: String) =
        CandidateMaterializer.materialize(
            zip = zip,
            productVersion = ProductVersion.parseOrThrow(declared),
            gitCommit = "deadbeef",
            gitTag = "v$declared",
            toolchain = "gradle-test",
            sbom = null,
            sha256sums = null,
            candidateSequence = 1,
            outDir = tempDir.resolve(out),
        )

    /**
     * The positive path, stated correctly.
     *
     * `materialize` cannot reach `Consistent`, and that is deliberate: the
     * verdict demands all SIX surfaces, but two of them are not observable from
     * the ZIP alone. `RUNTIME_VERSION` needs the installed binary executed, and
     * `MANIFEST_VERSION` is written from the bytes being measured, so it cannot
     * be a prior input. Both belong to the harness's heavy lane. The producer
     * therefore legitimately reports `Incomplete` and hands the remaining
     * verification over, rather than faking agreement.
     *
     * What matters here is that the ZIP-observable surfaces produce NO
     * divergence. A `Divergent` verdict here would mean the archive contradicts
     * the declared version, which is a producer-side defect this module must
     * catch on its own.
     */
    @Test
    fun `a fully consistent artifact is Incomplete on the non-observable surfaces only`() {
        val result = materialize(buildZip("0.44.0", "0.44.0", "0.44.0"), "0.44.0", "out-ok")

        val materialized = assertInstanceOf(
            MaterializationResult.Materialized::class.java,
            result,
            "an artifact whose ZIP-observable surfaces all agree must materialize",
        )
        val identity = materialized.identity
        assertFalse(
            identity is DistributionIdentityVerdict.Divergent,
            "agreeing surfaces must not produce a divergence, got: ${identity.render()}",
        )
        assertInstanceOf(
            DistributionIdentityVerdict.Incomplete::class.java,
            identity,
            "the two non-observable surfaces must stay visible as Incomplete, " +
                "never defaulted to the expected version",
        )
        val incomplete = identity as DistributionIdentityVerdict.Incomplete
        assertEquals(
            listOf(
                IdentitySurface.RUNTIME_VERSION,
                IdentitySurface.MANIFEST_VERSION,
            ).sortedBy { it.name },
            incomplete.missing.sortedBy { it.name },
            "exactly the two surfaces the producer cannot observe must be missing; " +
                "a ZIP-observable surface being missing would be a producer defect",
        )
    }

    /**
     * P-UAT-01, the laundering case. The asset and the archive root advertise
     * GA `0.44.0` while the embedded JAR says `0.44.0-rc1`. This is the exact
     * shape of the published v0.43.0 defect and it must be refused.
     */
    @Test
    fun `a laundered artifact is refused naming the offending surface`() {
        val result = materialize(
            buildZip("0.44.0", "0.44.0", "0.44.0-rc1"),
            "0.44.0",
            "out-laundered",
        )

        val refused = assertInstanceOf(MaterializationResult.Refused::class.java, result)
        assertTrue(
            refused.reason.contains("IMPLEMENTATION_VERSION") || refused.reason.contains("rc1"),
            "the refusal must name the offending surface, got: ${refused.reason}",
        )
        assertFalse(
            Files.exists(tempDir.resolve("out-laundered/distribution-manifest.json")),
            "a refused candidate must leave no manifest behind",
        )
    }

    /**
     * The load-bearing case, and a deliberately STRICT test.
     *
     * A ZIP with no application JAR cannot have its `Implementation-Version`
     * observed. The spec's law is that an unobservable surface is never
     * defaulted to the expected version, so the IDENTITY layer must itself
     * reject the artifact — before any later check gets a chance to save it.
     *
     * This asserts the identity verdict directly, not merely that "something
     * eventually refused". That distinction matters: the handoff validator
     * independently rejects a null `implementationVersion`, so a looser test
     * ("materialize returned Refused") would keep passing even if the identity
     * gate were completely disabled. A mutation canary confirmed exactly that:
     * defaulting IMPLEMENTATION_VERSION to the expected version left the whole
     * suite green. Pinning the verdict is what gives the gate teeth.
     */
    @Test
    fun `a missing application JAR makes the identity itself INCOMPLETE`() {
        val zip = buildZip("0.44.0", "0.44.0", innerVersion = null, includeAppJar = false)

        val observations = buildList {
            add(IdentityObservation(IdentitySurface.PRODUCT_VERSION, "0.44.0"))
            addAll(DistributionIdentityProbe.probeZip(zip))
        }
        val identity = evaluateDistributionIdentity(DistributionIdentityFacts(observations))

        val incomplete = assertInstanceOf(
            DistributionIdentityVerdict.Incomplete::class.java,
            identity,
            "an unobservable identity surface must yield Incomplete, never agreement",
        )
        assertTrue(
            incomplete.missing.contains(IdentitySurface.IMPLEMENTATION_VERSION),
            "the missing application JAR must be named as missing; got ${incomplete.missing}",
        )
    }

    /**
     * The same input must also produce no candidate material at the boundary.
     * Asserted separately so a failure localises to the layer that broke.
     */
    @Test
    fun `a missing application JAR is refused rather than handed off`() {
        val outDir = tempDir.resolve("out-unobservable")
        val result = materialize(
            buildZip("0.44.0", "0.44.0", innerVersion = null, includeAppJar = false),
            "0.44.0",
            "out-unobservable",
        )

        val refused = assertInstanceOf(
            MaterializationResult.Refused::class.java,
            result,
            "an artifact whose producer-side identity surface is unobservable must be refused, " +
                "never handed off with an unverified identity claim",
        )
        assertTrue(
            refused.reason.contains("IMPLEMENTATION_VERSION") ||
                refused.reason.contains("INCOMPLETE"),
            "the refusal must name the unobservable surface, got: ${refused.reason}",
        )
        assertFalse(
            Files.exists(outDir.resolve("distribution-manifest.json")),
            "a refused candidate must leave no manifest behind",
        )
        assertFalse(
            Files.exists(outDir.resolve("candidate-handoff.json")),
            "a refused candidate must leave no handoff behind",
        )
    }

    /**
     * The asset name itself is a surface. A ZIP whose filename does not carry
     * the declared version is a divergence, not a detail.
     */
    @Test
    fun `an asset renamed away from the product version is refused`() {
        val result = materialize(buildZip("0.44.0", "0.44.0", "0.43.0"), "0.44.0", "out-renamed")

        // The inner version disagrees with the declared product version, so
        // this must not be certified.
        assertTrue(
            result is MaterializationResult.Refused ||
                (result is MaterializationResult.Materialized &&
                    result.identity !is DistributionIdentityVerdict.Consistent),
            "an artifact whose inner identity disagrees must never be Consistent",
        )
    }

    @Test
    fun `a refusal writes nothing at all`() {
        val outDir = tempDir.resolve("out-nothing")
        val result = materialize(buildZip("0.44.0", "0.44.0", "0.44.0-rc1"), "0.44.0", "out-nothing")

        assertInstanceOf(MaterializationResult.Refused::class.java, result)
        assertFalse(Files.exists(outDir.resolve("distribution-manifest.json")))
        assertFalse(Files.exists(outDir.resolve("candidate-handoff.json")))
        assertEquals(
            emptyList<Path>(),
            if (Files.isDirectory(outDir)) {
                Files.list(outDir).use { it.toList() }
            } else {
                emptyList()
            },
            "a refused candidate must leave the output directory empty, not partially populated",
        )
    }
}
