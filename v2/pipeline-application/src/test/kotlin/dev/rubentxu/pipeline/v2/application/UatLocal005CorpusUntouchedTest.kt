package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * UAT-LOCAL-005: Compatibility Corpus UNTOUCHED.
 *
 * Verifies that the ML-R7 compatibility corpus does not modify any
 * of the 6 BASE files (01-06) from the base commit. The corpus is a
 * frozen fixture set whose ORIGINAL 6 files must remain byte-identical.
 * Files 07-09 are new legitimate fixtures for ML-R7 and are excluded
 * from the byte-identity check (they are verified by the corpus count
 * check below).
 *
 * This is the CP-001/CP-002 gate for ML-R7.
 *
 * INV-CR-7: Compatibility corpus original 6 files must be byte-identical
 * to base commit. Files 07-09 are new ML-R7 additions verified separately.
 *
 * @see <a href="ADR-0050">ADR-0050 §Compatibility</a>
 */
@Timeout(30)
class UatLocal005CorpusUntouchedTest {

    @BeforeEach
    fun setUp() {
        assumeTrue(
            System.getProperty("os.name", "").lowercase().contains("linux"),
            "UAT tests require Linux"
        )
    }

    /**
     * CP-001: original 6 corpus files (01-06) are byte-identical to base commit.
     *
     * The original 6 fixtures must not change. Files 07-09 are new ML-R7
     * additions and are excluded from this check (verified by count below).
     */
    @Test
    fun `CP-001 original 6 corpus files byte-identical to base commit`(@TempDir tempDir: Path) {
        val projectRoot = Path.of("/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin")
        val baseCommit = findBaseCommit()

        // The original 6 files that must not change
        val originalFiles = listOf(
            "01-basic.pipeline.kts",
            "02-environment.pipeline.kts",
            "03-stages.pipeline.kts",
            "04-sh.pipeline.kts",
            "05-scripted-if.pipeline.kts",
            "06-loop.pipeline.kts"
        )

        val compatibilityDir = projectRoot.resolve("v2/compatibility")

        for (fileName in originalFiles) {
            val filePath = compatibilityDir.resolve(fileName)
            assertTrue(Files.exists(filePath),
                "Original corpus file must exist: $fileName")

            // Compute SHA-256 of current file
            val currentHash = sha256(filePath)

            // Get SHA-256 from git at base commit
            val baseHash = gitCatFile(baseCommit, "v2/compatibility/$fileName")

            assertEquals(baseHash, currentHash,
                "Original corpus file must be byte-identical to base: $fileName. " +
                "Base SHA: $baseHash, Current SHA: $currentHash")
        }
    }

    /**
     * CP-002: corpus has exactly 15 files (6 original + 3 new ML-R7 fixtures + L7 smoke + 3 new ML-R9 fixtures + 2 new ML-R10 fixtures).
     */
    @Test
    fun `CP-002 corpus has exactly 15 fixture files`(@TempDir tempDir: Path) {
        val projectRoot = Path.of("/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin")
        val compatibilityDir = projectRoot.resolve("v2/compatibility")

        val pipelineFiles = Files.list(compatibilityDir)
            .filter { it.fileName.toString().endsWith(".pipeline.kts") }
            .sorted()
            .toList()

        assertEquals(15, pipelineFiles.size,
            "Corpus must have exactly 15 pipeline fixtures. Found: " +
            pipelineFiles.joinToString { it.fileName.toString() })

        // Verify the 9 new fixtures exist (ML-R7: 4, ML-R9: 3, ML-R10: 2)
        val newFiles = setOf(
            "07-writeFile-readFile.pipeline.kts",
            "08-withEnv-pipeline.pipeline.kts",
            "09-archive-artefacts.pipeline.kts",
            "10-smoke-e2e.pipeline.kts",
            "11-workflow-control.pipeline.kts",
            "12-error-handling.pipeline.kts",
            "13-workspace-helpers.pipeline.kts",
            "14-credentials-bindings.pipeline.kts",
            "99-broken-compilation.pipeline.kts"
        )
        val actualNames = pipelineFiles.map { it.fileName.toString() }.toSet()
        assertTrue(actualNames.containsAll(newFiles),
            "New ML-R7/L7 fixtures must exist: $newFiles. Found: $actualNames")
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val content = Files.readAllBytes(path)
        val hash = digest.digest(content)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun gitCatFile(commit: String, path: String): String {
        val projectRoot = Path.of("/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin")
        val pb = ProcessBuilder(
            "git", "show", "$commit:$path"
        )
            .directory(projectRoot.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val terminated = process.waitFor(10, TimeUnit.SECONDS)
        return if (terminated && process.exitValue() == 0) {
            // Compute SHA of the file content at base
            val content = process.inputStream.readBytes()
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(content)
            hash.joinToString("") { "%02x".format(it) }
        } else {
            throw AssertionError("Could not read $path at commit $commit from git")
        }
    }

    /**
     * Finds the base commit for the ML-R5 cycle.
     * The base is the parent of the first T-01 commit (6b39949).
     */
    private fun findBaseCommit(): String {
        val projectRoot = Path.of("/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin")

        // T-01 commit is 6b39949, base is its parent
        val pb = ProcessBuilder("git", "rev-parse", "6b39949^")
            .directory(projectRoot.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val terminated = process.waitFor(10, TimeUnit.SECONDS)
        return if (terminated && process.exitValue() == 0) {
            process.inputStream.bufferedReader().readText().trim()
        } else {
            // Fallback: use the commit before feat/domain: T-01
            "6b39949^"
        }
    }
}
