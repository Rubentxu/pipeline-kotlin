package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P0.3b — the probe must read REAL bytes correctly, not just agree with
 * itself on synthetic input.
 *
 * The synthetic decision tests in [DistributionIdentityVerdictTest] prove the
 * logic. These prove the observation half: given an actual distribution ZIP,
 * the probe recovers the version each surface genuinely carries. A probe that
 * hallucinated a version would make the gate a rubber stamp, so the fixtures
 * here are built as real ZIPs containing real JARs with real manifests.
 */
class DistributionIdentityProbeTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * Build a genuine distribution ZIP: one top-level `pipelinek-<V>/` root
     * containing a genuine application JAR whose manifest carries
     * [innerVersion]. This lets a test declare an outer identity and an inner
     * identity independently, which is how the laundering defect is shaped.
     */
    private fun buildDistributionZip(
        assetVersion: String,
        innerVersion: String,
        rootVersion: String = assetVersion,
    ): Path {
        val zip = tempDir.resolve("pipelinek-$assetVersion.zip")
        val jar = tempDir.resolve("app-$innerVersion.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zos.write(
                (
                    "Manifest-Version: 1.0\r\n" +
                        "Implementation-Title: pipeline-application\r\n" +
                        "Implementation-Version: $innerVersion\r\n" +
                        "\r\n"
                    ).toByteArray(),
            )
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("dev/rubentxu/Marker.class"))
            zos.closeEntry()
        }
        ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("pipelinek-$rootVersion/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("pipelinek-$rootVersion/lib/"))
            zos.closeEntry()
            zos.putNextEntry(
                ZipEntry(
                    "pipelinek-$rootVersion/lib/pipeline-application-$innerVersion.jar",
                ),
            )
            zos.write(Files.readAllBytes(jar))
            zos.closeEntry()
        }
        return zip
    }

    @Nested
    inner class ReadingRealBytes {

        @Test
        fun `reads the asset version from the filename`() {
            val zip = buildDistributionZip("0.44.0", "0.44.0")
            assertEquals("0.44.0", DistributionIdentityProbe.assetVersion(zip))
        }

        @Test
        fun `reads the archive root version from inside the archive`() {
            val zip = buildDistributionZip("0.44.0", "0.44.0")
            assertEquals("0.44.0", DistributionIdentityProbe.archiveRootVersion(zip))
        }

        @Test
        fun `reads Implementation-Version from the nested JAR manifest`() {
            val zip = buildDistributionZip("0.44.0", "0.44.0")
            assertEquals("0.44.0", DistributionIdentityProbe.jarImplementationVersion(zip))
        }

        @Test
        fun `probeZip reports all three ZIP-derived surfaces`() {
            val zip = buildDistributionZip("0.44.0", "0.44.0")
            val bySurface = DistributionIdentityProbe.probeZip(zip).associate {
                it.surface to it.reported
            }
            assertEquals("0.44.0", bySurface[IdentitySurface.ASSET])
            assertEquals("0.44.0", bySurface[IdentitySurface.ARCHIVE_ROOT])
            assertEquals("0.44.0", bySurface[IdentitySurface.IMPLEMENTATION_VERSION])
        }
    }

    /**
     * The laundering shape, constructed for real: the ZIP is NAMED with a final
     * GA version while the JAR inside declares a candidate suffix. This is the
     * artifact shape that must never be handed off.
     */
    @Nested
    inner class LaunderedArtifact {

        @Test
        fun `probe DETECTS the outer versus inner split in a real ZIP`() {
            val zip = buildDistributionZip(assetVersion = "0.43.0", innerVersion = "0.43.0-rc1")
            val bySurface = DistributionIdentityProbe.probeZip(zip).associate {
                it.surface to it.reported
            }
            assertEquals("0.43.0", bySurface[IdentitySurface.ASSET], "outer identity")
            assertEquals(
                "0.43.0-rc1",
                bySurface[IdentitySurface.IMPLEMENTATION_VERSION],
                "inner identity: the probe must report what the JAR really says",
            )
        }

        @Test
        fun `probe facts feed the pure decision into Divergent`() {
            val zip = buildDistributionZip(assetVersion = "0.43.0", innerVersion = "0.43.0-rc1")
            val facts = DistributionIdentityFacts(
                // RUNTIME_VERSION and MANIFEST_VERSION are not ZIP-derived and
                // are not produced by this probe. They stand in here as the
                // consistent values a correct build would report, so this test
                // isolates ZIP-surface laundering and nothing else. The real
                // manifest is generated by later release work; until then the
                // honest end-to-end result is Incomplete, which is also a
                // rejection — see the precedence test below.
                buildList {
                    add(IdentityObservation(IdentitySurface.PRODUCT_VERSION, "0.43.0"))
                    add(IdentityObservation(IdentitySurface.RUNTIME_VERSION, "0.43.0"))
                    add(IdentityObservation(IdentitySurface.MANIFEST_VERSION, "0.43.0"))
                    addAll(DistributionIdentityProbe.probeZip(zip))
                },
            )
            val verdict = evaluateDistributionIdentity(facts)
            assertTrue(
                verdict is DistributionIdentityVerdict.Divergent,
                "end to end, the gate must reject a laundered artifact; got ${verdict.render()}",
            )
        }

        @Test
        fun `a missing surface outranks a conflict, so the operator is told what to build first`() {
            val zip = buildDistributionZip(assetVersion = "0.43.0", innerVersion = "0.43.0-rc1")
            // Only the ZIP surfaces are supplied: the archive is simultaneously
            // laundered (ASSET disagrees) and incomplete (RUNTIME/MANIFEST
            // unobservable). Incomplete is returned deliberately — you cannot
            // compare a surface that does not exist, and naming what is missing
            // is the actionable message for the build that has no distribution
            // manifest at all.
            val facts = DistributionIdentityFacts(
                listOf(IdentityObservation(IdentitySurface.PRODUCT_VERSION, "0.43.0")) +
                    DistributionIdentityProbe.probeZip(zip),
            )
            val verdict = evaluateDistributionIdentity(facts)
            assertTrue(
                verdict is DistributionIdentityVerdict.Incomplete,
                "expected Incomplete to take precedence; got ${verdict.render()}",
            )
            assertEquals(
                listOf(IdentitySurface.RUNTIME_VERSION, IdentitySurface.MANIFEST_VERSION),
                (verdict as DistributionIdentityVerdict.Incomplete).missing,
            )
        }
    }

    /**
     * An unobservable surface must come back null, never a default. Defaulting
     * is how a missing manifest turns into a passing gate.
     */
    @Nested
    inner class UnobservableSurfaces {

        @Test
        fun `a non-distribution filename yields a null asset version`() {
            val other = tempDir.resolve("some-other-artifact.zip")
            Files.writeString(other, "not a distribution")
            assertNull(DistributionIdentityProbe.assetVersion(other))
        }

        @Test
        fun `a missing file yields null, not an exception`() {
            val missing = tempDir.resolve("pipelinek-9.9.9.zip")
            assertNull(DistributionIdentityProbe.jarImplementationVersion(missing))
        }

        @Test
        fun `a ZIP with no application JAR yields a null implementation version`() {
            val zip = tempDir.resolve("pipelinek-0.44.0.zip")
            ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
                zos.putNextEntry(ZipEntry("pipelinek-0.44.0/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("pipelinek-0.44.0/README.txt"))
                zos.write("hello".toByteArray())
                zos.closeEntry()
            }
            assertNull(DistributionIdentityProbe.jarImplementationVersion(zip))
        }

        @Test
        fun `a ZIP with two top-level roots yields a null archive root`() {
            val zip = tempDir.resolve("pipelinek-0.44.0.zip")
            ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
                zos.putNextEntry(ZipEntry("pipelinek-0.44.0/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("unexpected-root/"))
                zos.closeEntry()
            }
            assertNull(
                DistributionIdentityProbe.archiveRootVersion(zip),
                "a malformed archive must not be guessed at",
            )
        }
    }

    /**
     * Probe the artifacts that actually exist in this checkout, when present.
     * These are skipped rather than failed when the build has not produced a
     * distribution, because a fitness test must not require a 92 MB build.
     */
    @Nested
    inner class AgainstRealCheckedOutArtifacts {

        private fun distributionsDir(): Path =
            ReleaseTestPaths.v2Root().resolve("pipeline-application/build/distributions")

        @Test
        fun `every checked-out distribution ZIP has internally agreeing ZIP surfaces`() {
            val dir = distributionsDir()
            assumeTrue(Files.isDirectory(dir), "no distributions built in this checkout")
            val zips = Files.list(dir).use { s ->
                s.filter { it.fileName.toString().endsWith(".zip") }.toList()
            }
            assumeTrue(zips.isNotEmpty(), "no distribution ZIP present")

            zips.forEach { zip ->
                val bySurface = DistributionIdentityProbe.probeZip(zip).associate {
                    it.surface to it.reported
                }
                assertEquals(
                    bySurface[IdentitySurface.ASSET],
                    bySurface[IdentitySurface.ARCHIVE_ROOT],
                    "${zip.fileName}: filename and archive root disagree",
                )
                assertEquals(
                    bySurface[IdentitySurface.ASSET],
                    bySurface[IdentitySurface.IMPLEMENTATION_VERSION],
                    "${zip.fileName}: filename and the embedded JAR manifest disagree",
                )
            }
        }

        @Test
        fun `the 0_43_0 artifact is internally consistent, so the incident was downstream`() {
            val zip = distributionsDir().resolve("pipelinek-0.43.0-rc1.zip")
            assumeTrue(Files.isRegularFile(zip), "0.43.0 artifact not present in this checkout")
            val bySurface = DistributionIdentityProbe.probeZip(zip).associate {
                it.surface to it.reported
            }
            // Recorded as measured fact, not assumption: this is the evidence
            // behind the "divergence was introduced at promotion" claim.
            assertEquals("0.43.0-rc1", bySurface[IdentitySurface.ASSET])
            assertEquals("0.43.0-rc1", bySurface[IdentitySurface.IMPLEMENTATION_VERSION])
        }
    }
}
