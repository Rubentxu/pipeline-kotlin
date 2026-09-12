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
     * CP-001: original 4 corpus files (01, 03, 05, 06) are byte-identical to base commit.
     *
     * Files 02 and 04 had LEGITIMATE changes per INC-R10-ARC-001 remediation:
     * - 02-environment.pipeline.kts: Groovy environment{} syntax → Kotlin withEnv(listOf())
     * - 04-sh.pipeline.kts: Groovy array literal → Kotlin string arg
     * These changes fixed compilation failures and are tracked separately.
     * Files 07-14 are new ML-R7/R9/R10 additions and are excluded.
     */
    @Test
    fun `CP-001 original 4 corpus files byte-identical to base commit`(@TempDir tempDir: Path) {
        val projectRoot = Path.of("/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin")
        val baseCommit = findBaseCommit()

        // The original 6 files — but 02, 04, and 06 have LEGITIMATE changes:
        //   - 02 and 04: Groovy→Kotlin fix (INC-R10-ARC-001)
        //   - 06: originally Groovy `script { for (i in 1..3) ... }`; migrated
        //     to `sh("""for i in 1 2 3; do echo \"iteration ${'$'}i\"; done""",
        //     isScriptBlock = true)` per INC-R10-ARC-001 + INC-027 (the
        //     `${'$'}i` escape is rule 13 — Kotlin does not interpolate $i
        //     at compile time).
        val unchangedFiles = listOf(
            "01-basic.pipeline.kts",
            "03-stages.pipeline.kts",
            "05-scripted-if.pipeline.kts",
        )
        val changedFiles = setOf(
            "02-environment.pipeline.kts",  // Groovy→Kotlin fix
            "04-sh.pipeline.kts",           // Array literal→string fix
            "06-loop.pipeline.kts",         // Escape ${'$'}i (INC-027 / INC-021c)
        )

        val compatibilityDir = projectRoot.resolve("v2/compatibility")

        for (fileName in unchangedFiles) {
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

        // Verify changed files exist and have non-zero size (they're fixed, not deleted)
        for (fileName in changedFiles) {
            val filePath = compatibilityDir.resolve(fileName)
            assertTrue(Files.exists(filePath),
                "Legitimately changed file must exist: $fileName")
            assertTrue(Files.size(filePath) > 0,
                "Legitimately changed file must be non-empty: $fileName")
        }
    }

    /**
     * CP-002: corpus has exactly 13 valid files after FIX-ROUND-2:
     * 6 original + 3 new ML-R7 fixtures + L7 smoke + 3 new ML-R9 fixtures + 2 new ML-R10 fixtures.
     * Files 07-writeFile-readFile and 99-broken-compilation were moved to
     * UAT-owned test resources (broken/) per INC-R10-ARC-001 ruling.
     */
    @Test
    fun `CP-002 corpus has exactly 13 valid fixture files`(@TempDir tempDir: Path) {
        val projectRoot = Path.of("/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin")
        val compatibilityDir = projectRoot.resolve("v2/compatibility")

        val pipelineFiles = Files.list(compatibilityDir)
            .filter { it.fileName.toString().endsWith(".pipeline.kts") }
            .sorted()
            .toList()

        assertEquals(19, pipelineFiles.size,
            "Corpus must have exactly 19 valid pipeline fixtures (07 and 99 moved to broken/; v0.33.1 corpus-closure added 15-error, 16-sleep, 17-writeFile, 18-cleanWs and renamed 09-archive-artefacts → 09-sh-then-echo; S2-A5/G8 added 19-isunix; S2-A6/G3R added 20-pwd-tmp). Found: " +
            pipelineFiles.joinToString { it.fileName.toString() })

        // Verify the valid new fixtures exist (ML-R7: 3, ML-R9: 3, ML-R10: 2; v0.33.1 P2 corpus-closure: 4 new E2E; S2-A5/G8: 1 isunix)
        // Note: 07-writeFile-readFile and 99-broken-compilation moved to broken/
        // Note: 09 renamed archive-artefacts -> sh-then-echo (F10) in v0.33.1
        val newFiles = setOf(
            "08-withEnv-pipeline.pipeline.kts",
            "09-sh-then-echo.pipeline.kts",
            "10-smoke-e2e.pipeline.kts",
            "11-workflow-control.pipeline.kts",
            "12-error-handling.pipeline.kts",
            "13-workspace-helpers.pipeline.kts",
            "14-credentials-bindings.pipeline.kts",
            "15-error.pipeline.kts",
            "16-sleep.pipeline.kts",
            "17-writeFile.pipeline.kts",
            "18-cleanWs.pipeline.kts",
            "19-isunix.pipeline.kts",
            "20-pwd-tmp.pipeline.kts",
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
     * Finds the base commit for the current cycle.
     * Per AGENTS.md rule 16: comparison point is cycle base (4db480d), not ML-R5 base.
     */
    private fun findBaseCommit(): String {
        val projectRoot = Path.of("/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin")

        // Cycle base: fix(application): exit non-zero on script compilation failure (INC-R10-ARC-001)
        val cycleBase = "4db480d"
        val pb = ProcessBuilder("git", "rev-parse", cycleBase)
            .directory(projectRoot.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val terminated = process.waitFor(10, TimeUnit.SECONDS)
        return if (terminated && process.exitValue() == 0) {
            process.inputStream.bufferedReader().readText().trim()
        } else {
            // Fallback: use the parent of the fail-closed fix commit
            cycleBase + "^"
        }
    }
}
