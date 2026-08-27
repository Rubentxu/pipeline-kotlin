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
import java.util.concurrent.TimeUnit

/**
 * UAT-LOCAL-005: Compatibility Corpus UNTOUCHED.
 *
 * Verifies that the ML-R5 checkout-git implementation does not modify any
 * files in the v2/compatibility/ corpus directory. The corpus is a frozen
 * fixture set that must remain byte-identical to the base commit.
 *
 * This is the CP-001 gate for ML-R5, matching the pattern from ML-R4
 * (UatLocal008CredentialsTest CP-001).
 *
 * INV-CR-7: Compatibility corpus must be byte-identical to base commit.
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
     * CP-001: compatibility corpus unchanged since base commit.
     *
     * The base commit for the ML-R5 cycle is the commit before T-01.
     * This test verifies that no files in v2/compatibility/ were modified.
     */
    @Test
    fun `CP-001 compatibility corpus unchanged since base commit`(@TempDir tempDir: Path) {
        val projectRoot = Path.of("/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin")

        // Find the base commit (before T-01)
        val baseCommit = findBaseCommit()
        assertTrue(baseCommit.isNotBlank(), "Base commit must be found")

        // Run git diff between base and HEAD for the compatibility directory
        val pb = ProcessBuilder(
            "git", "diff", baseCommit, "HEAD", "--", "v2/compatibility/"
        )
            .directory(projectRoot.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val exitCode = process.waitFor(30, TimeUnit.SECONDS)
        assertTrue(exitCode, "git diff must complete")
        val diff = process.inputStream.bufferedReader().readText()

        assertEquals("", diff.trim(),
            "Compatibility corpus should be unchanged vs base commit. " +
            "Diff:\n$diff\n\nBase commit: $baseCommit")
    }

    /**
     * CP-002: No new files added to compatibility corpus.
     */
    @Test
    fun `CP-002 no new files added to compatibility corpus`(@TempDir tempDir: Path) {
        val projectRoot = Path.of("/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin")
        val baseCommit = findBaseCommit()

        val pb = ProcessBuilder(
            "git", "diff", baseCommit, "HEAD", "--name-status", "--", "v2/compatibility/"
        )
            .directory(projectRoot.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val exitCode = process.waitFor(30, TimeUnit.SECONDS)
        assertTrue(exitCode, "git diff must complete")
        val output = process.inputStream.bufferedReader().readText()

        // Filter only A (added) entries — M (modified) is covered by CP-001
        val addedFiles = output.lines()
            .filter { it.startsWith("A\t") || it.startsWith("A") }
            .filter { it.contains("v2/compatibility/") }

        assertEquals("", addedFiles.joinToString("\n"),
            "No new files should be added to compatibility corpus. Found added:\n${addedFiles.joinToString("\n")}")
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

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
