package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P-UAT-02 — a candidate is reproducible.
 *
 * The spec (`04-uat.md` P-UAT-02) requires that "two controlled builds from
 * same SHA/toolchain produce identical ZIP SHA". Nothing else in the suite
 * guards that property: a clock read, an environment lookup or a hash-map
 * iteration order introduced into either the packaging or the materializer
 * would silently make every published candidate un-reproducible, and no
 * existing test would notice.
 *
 * The packaging side of the property is verified out-of-band against the real
 * Gradle build (two forced `--rerun-tasks` builds of `:pipeline-application:distZip`
 * yielded the same ZIP SHA). What is verified HERE is the part this module
 * owns and can therefore enforce permanently: the materializer is a pure
 * function of the bytes it measures. Same inputs, same manifest, same handoff,
 * byte for byte.
 *
 * This is deliberately a hermetic test over real ZIPs rather than a mock. A
 * reproducibility test built on stubs would prove that the stubs are
 * reproducible, which is worth nothing.
 */
class CandidateReproducibilityTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * A distribution ZIP whose bytes are fully determined by [version]. The
     * entry order is fixed and no timestamps are read, so two calls produce the
     * same archive.
     */
    private fun buildZip(version: String): Path {
        val jar = tempDir.resolve("pipeline-application-$version.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zos.write(
                (
                    "Manifest-Version: 1.0\r\n" +
                        "Implementation-Version: $version\r\n" +
                        "\r\n"
                    ).toByteArray(),
            )
            zos.closeEntry()
        }
        val zip = tempDir.resolve("pipelinek-$version.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("pipelinek-$version/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("pipelinek-$version/lib/pipeline-application-$version.jar"))
            zos.write(Files.readAllBytes(jar))
            zos.closeEntry()
        }
        return zip
    }

    /**
     * Materialize and require success. Narrowing to [MaterializationResult.Materialized]
     * is what makes the paths addressable, and it doubles as an assertion: a
     * refusal fails the test by throwing rather than silently skipping the
     * reproducibility comparison.
     */
    private fun materialize(zip: Path, outName: String): MaterializationResult.Materialized {
        val result = CandidateMaterializer.materialize(
            zip = zip,
            productVersion = ProductVersion.parseOrThrow("0.44.0"),
            gitCommit = "deadbeef",
            gitTag = "v0.44.0",
            toolchain = "gradle-test",
            sbom = null,
            sha256sums = null,
            candidateSequence = 1,
            outDir = tempDir.resolve(outName),
        )
        assertInstanceOf(MaterializationResult.Materialized::class.java, result)
        return result as MaterializationResult.Materialized
    }

    private fun materializeWithPromotion(zip: Path, outName: String): MaterializationResult.Materialized {
        val result = CandidateMaterializer.materialize(
            zip = zip,
            productVersion = ProductVersion.parseOrThrow("0.44.0"),
            gitCommit = "feedface",
            gitTag = "v0.44.0-promoted",
            toolchain = "gradle-test",
            sbom = null,
            sha256sums = null,
            candidateSequence = 7,
            outDir = tempDir.resolve(outName),
        )
        assertInstanceOf(MaterializationResult.Materialized::class.java, result)
        return result as MaterializationResult.Materialized
    }

    @Test
    fun `two materializations of identical bytes produce byte-identical documents`() {
        val zip = buildZip("0.44.0")

        val first = materialize(zip, "out-a")
        val second = materialize(zip, "out-b")

        val manifestA = Files.readAllBytes(first.manifestPath)
        val manifestB = Files.readAllBytes(second.manifestPath)
        assertEquals(
            CandidateMaterializer.sha256Of(first.manifestPath),
            CandidateMaterializer.sha256Of(second.manifestPath),
            "distribution manifest must be reproducible from identical bytes",
        )
        assertTrue(manifestA.contentEquals(manifestB))

        val handoffA = Files.readAllBytes(first.handoffPath)
        val handoffB = Files.readAllBytes(second.handoffPath)
        assertEquals(
            CandidateMaterializer.sha256Of(first.handoffPath),
            CandidateMaterializer.sha256Of(second.handoffPath),
            "candidate handoff must be reproducible from identical bytes",
        )
        assertTrue(handoffA.contentEquals(handoffB))
    }

    /**
     * The converse guard: a DIFFERENT artifact must not produce the same
     * manifest. The materializer refuses a 0.44.1 ZIP when the build declares
     * 0.44.0 — that refusal IS the identity gate working, so the assertion
     * checks for a refusal rather than a differing digest.
     */
    @Test
    fun `different bytes are refused rather than described`() {
        val refused = CandidateMaterializer.materialize(
            zip = buildZip("0.44.1"),
            productVersion = ProductVersion.parseOrThrow("0.44.0"),
            gitCommit = "deadbeef",
            gitTag = "v0.44.0",
            toolchain = "gradle-test",
            sbom = null,
            sha256sums = null,
            candidateSequence = 1,
            outDir = tempDir.resolve("out-mismatch"),
        )
        assertInstanceOf(MaterializationResult.Refused::class.java, refused)
        assertFalse(
            Files.exists(tempDir.resolve("out-mismatch/distribution-manifest.json")),
            "a refused candidate must leave no manifest behind",
        )
    }

    /**
     * P-UAT-03 — promotion metadata is separate from the distribution manifest.
     *
     * The spec's wording is "changing only promotion metadata cannot alter
     * distribution manifest; promotion metadata is separate". The property is
     * enforced STRUCTURALLY, not by convention:
     *
     *  - [DistributionManifest] has no promotion field at all, so there is
     *    nothing for a promoter to write;
     *  - [DistributionManifestCodec] rejects an unknown key as a promotion
     *    attempt rather than round-tripping it.
     *
     * So the test drives the real attack: take a genuine manifest and try to
     * graft promotion state onto it. It must be refused, and the original file
     * must be left untouched.
     *
     * Note what is NOT promotion metadata: `git_commit` / `git_tag` are the
     * provenance of the build that produced these bytes and belong in the
     * manifest (P0.2). An earlier draft of this test wrongly varied them and
     * asserted the manifest stayed constant, which contradicts the spec.
     */
    @Test
    fun `promotion metadata cannot be grafted onto the distribution manifest`() {
        val zip = buildZip("0.44.0")
        val materialized = materialize(zip, "out-a")
        val before = Files.readString(materialized.manifestPath)

        val grafted = before.replaceFirst("{", """{"promotion_status": "stable",""")
        assertNotEquals(before, grafted, "fixture must actually differ before grafting")

        // The codec sets ignoreUnknownKeys = false, so an unrecognised key is a
        // hard SerializationException. Assert the refusal itself; the reason
        // string is kotlinx-serialization's, not ours, so pinning its wording
        // would make this test brittle for no gain.
        assertThrows(SerializationException::class.java) {
            DistributionManifestCodec.decode(grafted)
        }
        assertEquals(
            before,
            Files.readString(materialized.manifestPath),
            "the real manifest on disk must be untouched by a rejected promotion",
        )
    }

    /**
     * P-UAT-08 — the historical canary, end to end.
     *
     * The spec: "Feed an artifact shaped like v0.43.0 (outer GA identity,
     * embedded rc1). Local identity gate MUST reject it."
     *
     * The unit suite already classifies this shape at the probe and decision
     * level. What is verified HERE is the consequence: driven through the real
     * [CandidateMaterializer.materialize] entry point, a laundered artifact
     * produces NO candidate material at all. A gate that returns the correct
     * verdict but still writes a manifest is not a gate.
     *
     * The fixture is the real defect shape: filename and archive root claim
     * `0.43.0`, the embedded JAR manifest says `0.43.0-rc1` — exactly the
     * published v0.43.0 whose bytes were byte-identical to v0.43.0-rc1.
     */
    @Test
    fun `a v0_43_0 shaped artifact is refused by the real materializer`() {
        val jar = tempDir.resolve("pipeline-application-0.43.0-rc1.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zos.write(
                (
                    "Manifest-Version: 1.0\r\n" +
                        "Implementation-Version: 0.43.0-rc1\r\n" +
                        "\r\n"
                    ).toByteArray(),
            )
            zos.closeEntry()
        }
        // Outer identity claims GA 0.43.0; the bytes inside say rc1.
        val laundered = tempDir.resolve("pipelinek-0.43.0.zip")
        ZipOutputStream(Files.newOutputStream(laundered)).use { zos ->
            zos.putNextEntry(ZipEntry("pipelinek-0.43.0/"))
            zos.closeEntry()
            zos.putNextEntry(
                ZipEntry("pipelinek-0.43.0/lib/pipeline-application-0.43.0-rc1.jar"),
            )
            zos.write(Files.readAllBytes(jar))
            zos.closeEntry()
        }

        val outDir = tempDir.resolve("out-laundered")
        val result = CandidateMaterializer.materialize(
            zip = laundered,
            productVersion = ProductVersion.parseOrThrow("0.43.0"),
            gitCommit = "1910083e",
            gitTag = "v0.43.0",
            toolchain = "gradle-test",
            sbom = null,
            sha256sums = null,
            candidateSequence = 1,
            outDir = outDir,
        )

        assertInstanceOf(
            MaterializationResult.Refused::class.java,
            result,
            "P-UAT-08: a laundered v0.43.0-shaped artifact MUST be rejected locally",
        )
        val refused = result as MaterializationResult.Refused
        assertTrue(
            refused.reason.contains("IMPLEMENTATION_VERSION", ignoreCase = true) ||
                refused.reason.contains("rc1"),
            "the refusal must name the offending surface or value, got: ${refused.reason}",
        )
        assertFalse(
            Files.exists(outDir.resolve("distribution-manifest.json")),
            "a refused candidate must not leave a distribution manifest behind",
        )
        assertFalse(
            Files.exists(outDir.resolve("candidate-handoff.json")),
            "a refused candidate must not leave a handoff descriptor behind",
        )
    }

    /**
     * P-UAT-03, positive direction: what IS allowed to change.
     *
     * Promotion state is carried by the handoff (P0.4) and has no field in the
     * manifest at all, so a promoter has nothing to write into the manifest.
     * The handoff is where candidate sequence and provenance live, so two runs
     * differing only in those promotion-facing fields must produce DIFFERENT
     * handoffs.
     *
     * The manifest is deliberately NOT compared for equality here: it embeds
     * `git_commit`/`git_tag`/`toolchain`, which P0.2 requires as the provenance
     * of the bytes. Two builds of the same bytes from differently-labelled
     * commits legitimately describe themselves differently.
     */
    @Test
    fun `promotion facing metadata varies the handoff only`() {
        val zip = buildZip("0.44.0")
        val baseline = materialize(zip, "out-a")
        val sameArtifactRebuilt = materializeWithPromotion(zip, "out-promoted")

        assertNotEquals(
            CandidateMaterializer.sha256Of(baseline.handoffPath),
            CandidateMaterializer.sha256Of(sameArtifactRebuilt.handoffPath),
            "candidate sequence and provenance belong to the handoff, so it must differ",
        )
        assertEquals(
            baseline.manifest.asset.sha256,
            sameArtifactRebuilt.manifest.asset.sha256,
            "both runs measured the same bytes, so the recorded artifact digest is identical",
        )
    }
}
