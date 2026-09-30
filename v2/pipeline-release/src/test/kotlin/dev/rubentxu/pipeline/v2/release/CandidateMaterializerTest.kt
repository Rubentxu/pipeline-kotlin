package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P0.2 / P0.4 end-to-end against real bytes: the materializer must measure an
 * actual ZIP, emit a manifest and a handoff descriptor that agree with it, and
 * refuse — writing nothing — when the bytes lie.
 *
 * No mocks: the fixtures are genuine ZIPs containing genuine JARs. A
 * materializer tested against invented inputs would be exactly the kind of
 * rubber stamp this whole gate exists to prevent.
 */
class CandidateMaterializerTest {

    @TempDir
    lateinit var tempDir: Path

    /** Build a real distribution ZIP whose filename, root and JAR all carry [version]. */
    private fun buildZip(
        version: String,
        assetVersion: String = version,
        rootVersion: String = version,
    ): Path {
        val zip = tempDir.resolve("pipelinek-$assetVersion.zip")
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
        ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("pipelinek-$rootVersion/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("pipelinek-$rootVersion/lib/pipeline-application-$version.jar"))
            zos.write(Files.readAllBytes(jar))
            zos.closeEntry()
        }
        return zip
    }

    private fun materialize(zip: Path, version: String, outName: String = "out"): MaterializationResult =
        CandidateMaterializer.materialize(
            zip = zip,
            productVersion = ProductVersion.parseOrThrow(version),
            gitCommit = "deadbeef",
            candidateRef = "v$version",
            toolchain = "gradle-test",
            sbom = null,
            sha256sums = null,
            candidateSequence = 1,
            outDir = tempDir.resolve(outName),
        )

    @Nested
    inner class Materializing {

        @Test
        fun `a self-consistent ZIP produces a manifest and a handoff that agree with its bytes`() {
            val zip = buildZip("0.44.0")
            val result = assertInstanceOf(
                MaterializationResult.Materialized::class.java,
                materialize(zip, "0.44.0"),
            )

            // The manifest describes the real file, not a claim about it.
            val realDigest = CandidateMaterializer.sha256Of(zip)
            assertEquals(realDigest, result.manifest.asset.sha256, "manifest must carry the real ZIP digest")
            assertEquals(Files.size(zip), result.manifest.asset.size)
            assertEquals("pipelinek-0.44.0", result.manifest.asset.archiveRoot)
            assertEquals("0.44.0", result.manifest.asset.implementationVersion)

            // The files exist and re-read to the same values.
            assertTrue(Files.isRegularFile(result.manifestPath))
            assertTrue(Files.isRegularFile(result.handoffPath))
            val manifest = DistributionManifestCodec.decode(Files.readString(result.manifestPath))
            assertEquals(result.manifest, manifest)
            val handoff = CandidateHandoffCodec.decode(Files.readString(result.handoffPath))
            assertEquals(realDigest, handoff.candidateId.digest, "candidate_id must be the ZIP digest")
            assertEquals(realDigest, handoff.artifact.sha256)
        }

        @Test
        fun `the handoff's manifest digest matches the manifest actually written`() {
            val zip = buildZip("0.44.0")
            val result = assertInstanceOf(
                MaterializationResult.Materialized::class.java,
                materialize(zip, "0.44.0"),
            )
            val handoff = CandidateHandoffCodec.decode(Files.readString(result.handoffPath))
            assertEquals(
                CandidateMaterializer.sha256Of(result.manifestPath),
                handoff.distributionManifest.sha256,
                "the handoff must reference the manifest by its real digest",
            )
        }

        @Test
        fun `materializing the same bytes twice produces byte-identical documents`() {
            val zip = buildZip("0.44.0")
            val first = assertInstanceOf(
                MaterializationResult.Materialized::class.java,
                materialize(zip, "0.44.0", "outA"),
            )
            val second = assertInstanceOf(
                MaterializationResult.Materialized::class.java,
                materialize(zip, "0.44.0", "outB"),
            )
            assertEquals(
                Files.readString(first.manifestPath),
                Files.readString(second.manifestPath),
                "the manifest must be a deterministic function of the bytes",
            )
            assertEquals(
                Files.readString(first.handoffPath),
                Files.readString(second.handoffPath),
                "the handoff must be a deterministic function of the bytes",
            )
        }

        @Test
        fun `SHA256SUMS is written in sha256sum format and verifies`() {
            val zip = buildZip("0.44.0")
            val sums = tempDir.resolve("SHA256SUMS")
            CandidateMaterializer.writeSha256Sums(sums, listOf(zip))
            val line = Files.readString(sums).trim()
            assertEquals("${CandidateMaterializer.sha256Of(zip)}  ${zip.fileName}", line)
        }
    }

    @Nested
    inner class Refusing {

        @Test
        fun `a laundered ZIP is refused and NOTHING is written`() {
            // Filename and root say 0.44.0; the embedded JAR says 0.43.0-rc1.
            val zip = buildZip(version = "0.43.0-rc1", assetVersion = "0.44.0", rootVersion = "0.44.0")
            val out = tempDir.resolve("refused")
            val result = assertInstanceOf(
                MaterializationResult.Refused::class.java,
                materialize(zip, "0.44.0", "refused"),
            )
            assertTrue(
                result.reason.contains("0.43.0-rc1"),
                "the refusal must name the lying surface, got: ${result.reason}",
            )
            assertFalse(Files.exists(out), "a refused candidate must leave no partial material behind")
        }

        @Test
        fun `a candidate-suffixed product version cannot be materialized at all`() {
            // A build still declaring 0.44.0-rc1 must not be able to produce
            // candidate material under protocol v2. This is refused by the
            // TYPE, before any byte is read: ProductVersion has no value for a
            // candidate suffix, so there is nothing to pass in. The
            // materializer therefore cannot even be called.
            assertNull(
                ProductVersion.parseOrNull("0.44.0-rc1"),
                "a candidate suffix must not be a ProductVersion at all",
            )
            val error = assertThrows(IllegalArgumentException::class.java) {
                ProductVersion.parseOrThrow("0.44.0-rc1")
            }
            assertTrue(
                error.message.orEmpty().contains("candidate"),
                "the diagnostic must explain the candidate rule, got: ${error.message}",
            )
        }

        @Test
        fun `different bytes produce different candidate ids`() {
            val a = buildZip("0.44.0")
            val first = assertInstanceOf(
                MaterializationResult.Materialized::class.java,
                materialize(a, "0.44.0", "a"),
            )
            val b = buildZip("0.45.0")
            val second = assertInstanceOf(
                MaterializationResult.Materialized::class.java,
                materialize(b, "0.45.0", "b"),
            )
            assertNotEquals(
                first.manifest.asset.sha256,
                second.manifest.asset.sha256,
                "different bytes are different candidates, whatever they are called",
            )
        }
    }
}
