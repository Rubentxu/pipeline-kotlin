package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * S6-COMPOSITION — the runtime version has two carriers and they must never disagree.
 *
 * ## Why two carriers exist at all
 *
 * The runtime used to read `Package.getImplementationVersion()`, which answers only when the code was
 * loaded from a jar carrying a manifest. Thirty-four UAT and corpus harnesses launch the CLI with
 * `java -cp <test classpath>`, where this module is a CLASSES DIRECTORY and the call returns `null`.
 * Admission then failed closed on every one of them — 143 test failures across 29 classes, all of
 * which had been passing before the version check was wired into the product path.
 *
 * So there are now two places the version lives:
 *
 * ```text
 * project.version  --+--> jar manifest Implementation-Version   (pipeline-release, external tools)
 *                     +--> pipelinek-version.properties          (the runtime, every run mode)
 * ```
 *
 * Two sources that can drift are worse than one, which is what this class exists to prevent.
 *
 * ## What each row proves, and what it does NOT
 *
 * - [theRuntimeReadsTheGeneratedResource] crosses the productive reader
 *   ([RuntimeApiVersion.readImplementationVersion]) and proves it is not null in a build where no jar
 *   manifest is visible to the test classpath — which is precisely the case that used to fail.
 * - [theResourceAndTheManifestCarryTheSameVersion] reads the actual jar next to the resource the
 *   runtime uses and compares them. This is the anti-drift row: a `project.version` bump that
 *   reached one carrier and not the other turns this RED.
 *
 * Neither row proves the version is CORRECT, only that it is CONSISTENT and READABLE. Whether 0.47.0
 * is the right number is a release decision, not a property of this module.
 *
 * ## Harness fidelity
 *
 * HF1/HF0. The first row crosses the production reader with no substitute; the second reads two
 * artifacts from disk. Both assert on discrete values, never on a duration, a size or an ordering.
 */
@DisplayName("S6-COMPOSITION the runtime version has one value and two carriers that must agree")
class RuntimeApiVersionDriftTest {

    @Test
    @DisplayName("the runtime reads the generated resource even with no jar manifest in sight")
    fun theRuntimeReadsTheGeneratedResource() {
        // In the test classpath pipeline-application is a classes directory, so the OLD reader
        // (Package.getImplementationVersion) would answer null here. Asserting non-null through the
        // production reader is what makes this row a regression test rather than a restatement.
        val version = requireNotNull(RuntimeApiVersion.readImplementationVersion()) {
            "RuntimeApiVersion must read a build-generated version resource. A null here is the " +
                "defect that made 143 tests fail: the old Package-based reader returns null " +
                "whenever the CLI runs from a classes directory."
        }
        assertEquals(
            3,
            version.split('.').size,
            "the version must be MAJOR.MINOR.PATCH; the runtime compares plugin apiRanges against " +
                "a three-component SemVer and nothing else. Got '$version'.",
        )
    }

    @Test
    @DisplayName("the resource the runtime uses and the jar manifest carry the SAME version")
    fun theResourceAndTheManifestCarryTheSameVersion() {
        val fromResource = requireNotNull(RuntimeApiVersion.readImplementationVersion()) {
            "the runtime version must be readable at all"
        }

        val jar = locateApplicationJar()
        val fromManifest = JarFile(jar.toFile()).use { file ->
            file.manifest?.mainAttributes?.getValue("Implementation-Version")
        }

        assertNotNull(
            fromManifest,
            "the application jar at $jar must still carry Implementation-Version: pipeline-release " +
                "certifies candidate identity from that attribute, so removing it would break the " +
                "release contract even though the runtime no longer needs it.",
        )
        assertEquals(
            fromManifest,
            fromResource,
            "the jar manifest and the generated resource disagree. Both are produced from " +
                "project.version, so this means one of the two carriers was not regenerated.",
        )
    }

    /**
     * The jar this module just built.
     *
     * Located rather than assumed, and required rather than skipped: a test that returns quietly when
     * it cannot find its subject is a green produced by the absence of a subject.
     */
    private fun locateApplicationJar(): Path {
        val libs = Path.of("build", "libs")
        assertTrue(
            Files.isDirectory(libs),
            "no build output at $libs; run :pipeline-application:jar before this test.",
        )
        val candidates = Files.list(libs).use { stream ->
            stream.filter { it.fileName.toString().startsWith("pipeline-application-") }
                .filter { it.fileName.toString().endsWith(".jar") }
                .filter { !it.fileName.toString().endsWith("-sources.jar") }
                .filter { !it.fileName.toString().endsWith("-javadoc.jar") }
                .toList()
        }
        return requireNotNull(candidates.maxByOrNull { Files.getLastModifiedTime(it) }) {
            "no pipeline-application jar under $libs. Skipping here would be a green that proves nothing."
        }
    }
}
