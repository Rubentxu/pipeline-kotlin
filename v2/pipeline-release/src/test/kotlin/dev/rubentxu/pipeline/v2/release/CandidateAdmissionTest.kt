package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P0.3 build wiring — the admission boundary is the LAST producer-side gate.
 * Whatever it admits is a candidate; whatever it refuses never becomes one.
 *
 * The critical case is the operator decision still pending in this repo: the
 * build currently declares `0.44.0-rc1`. Under the release-evolution protocol
 * that is a candidate state, not a product identity, so admission must refuse
 * it with a diagnostic that tells the operator exactly what to do — without
 * throwing, and without silently proceeding.
 */
class CandidateAdmissionTest {

    @TempDir
    lateinit var tempDir: Path

    private fun buildZip(assetVersion: String, embeddedVersion: String): Path {
        val zip = tempDir.resolve("pipelinek-$assetVersion.zip")
        val jar = tempDir.resolve("pipeline-application-$embeddedVersion.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zos.write(
                (
                    "Manifest-Version: 1.0\r\nImplementation-Version: $embeddedVersion\r\n\r\n"
                    ).toByteArray(),
            )
            zos.closeEntry()
        }
        ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("pipelinek-$assetVersion/")); zos.closeEntry()
            zos.putNextEntry(ZipEntry("pipelinek-$assetVersion/lib/pipeline-application-$embeddedVersion.jar"))
            zos.write(Files.readAllBytes(jar)); zos.closeEntry()
        }
        return zip
    }

    private fun admit(zip: Path, declared: String, outName: String = "out") = CandidateAdmission.admit(
        zip = zip,
        productVersion = declared,
        gitCommit = "abc123",
        gitTag = null,
        toolchain = "test",
        sbom = null,
        sha256sums = null,
        candidateSequence = 1,
        outDir = tempDir.resolve(outName),
    )

    @Test
    fun `a self-consistent target-versioned ZIP is admitted`() {
        val zip = buildZip("0.44.0", "0.44.0")
        val outcome = assertInstanceOf(AdmissionOutcome.Admitted::class.java, admit(zip, "0.44.0"))
        assertTrue(outcome.candidateId.startsWith("sha256:"))
        assertTrue(Files.isRegularFile(outcome.manifestPath))
        assertTrue(Files.isRegularFile(outcome.handoffPath))
    }

    @Test
    fun `the pending 0_44_0-rc1 decision is REFUSED with an actionable diagnostic`() {
        // This is the operator decision this repo is currently sitting on. The
        // gate's job is to make it impossible to publish a candidate-suffixed
        // product identity, not to quietly allow the old behaviour.
        val zip = buildZip("0.44.0-rc1", "0.44.0-rc1")
        val outcome = assertInstanceOf(AdmissionOutcome.Refused::class.java, admit(zip, "0.44.0-rc1"))
        assertTrue(
            outcome.reason.contains("0.44.0-rc1") && outcome.reason.contains("TARGET version"),
            "the diagnostic must name the offending version and the rule, got: ${outcome.reason}",
        )
        assertTrue(
            Files.notExists(tempDir.resolve("out")),
            "a refused candidate must leave no material behind",
        )
    }

    @Test
    fun `a laundered ZIP is refused`() {
        val zip = buildZip("0.44.0", "0.43.0-rc1")
        val outcome = assertInstanceOf(AdmissionOutcome.Refused::class.java, admit(zip, "0.44.0"))
        assertTrue(outcome.reason.contains("0.43.0-rc1"), "got: ${outcome.reason}")
    }

    @Test
    fun `the identity verdict is carried through verbatim for the build log`() {
        val zip = buildZip("0.44.0", "0.44.0")
        val outcome = assertInstanceOf(AdmissionOutcome.Admitted::class.java, admit(zip, "0.44.0"))
        // The build log must show what the gate concluded, not a flattened OK.
        // On a real build RUNTIME_VERSION is unobservable (that is the
        // harness's heavy-lane job), so the honest verdict is INCOMPLETE and
        // that string must reach the operator verbatim.
        assertTrue(
            outcome.identityVerdict.contains("INCOMPLETE") || outcome.identityVerdict.contains("consistent"),
            "the carried verdict must be the real one, got: ${outcome.identityVerdict}",
        )
    }

    @Test
    fun `the SBOM locator returns null when no SBOM was produced`() {
        assertEquals(
            null,
            CandidateAdmission.locateSbom(tempDir, "0.44.0"),
            "an absent SBOM must be reported as absent, not defaulted",
        )
    }
}
