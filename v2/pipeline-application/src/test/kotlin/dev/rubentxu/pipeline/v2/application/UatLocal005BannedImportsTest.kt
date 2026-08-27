package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * UAT-LOCAL-005: Banned Imports Gate — no org.eclipse.jgit in v2/ source.
 *
 * F-ARCH-L5-001: The scm-git module uses the git CLI exclusively.
 * No JGit library imports are permitted in any v2/ source file.
 *
 * This grep-gate verifies the architectural constraint:
 * - INV-L5-CR-002: no JGit imports in v2/ source
 *
 * This is the IMP-001 gate for ML-R5, matching the pattern from ML-R4
 * (UatLocal008CredentialsTest IMP-001).
 *
 * Scopes:
 * - v2/pipeline-step-sdk/scm-git/src/main/ (the git CLI module itself)
 * - v2/pipeline-domain/src/main/ (domain types)
 * - v2/pipeline-application/src/main/ (application wiring)
 *
 * Does NOT scan: test/, docs/, compatibility/
 *
 * @see <a href="ADR-0050">ADR-0050 §Architecture</a>
 * @see <a href="F-ARCH-L5-001">F-ARCH-L5-001 — CLI-git not JGit</a>
 */
@Timeout(60)
class UatLocal005BannedImportsTest {

    @BeforeEach
    fun setUp() {
        assumeTrue(
            System.getProperty("os.name", "").lowercase().contains("linux"),
            "UAT tests require Linux"
        )
    }

    /**
     * IMP-001: No org.eclipse.jgit imports in v2/ main source.
     *
     * JGit is the Eclipse pure-Java Git implementation. Using it would
     * violate the CLI-git design decision (D1 in ADR-0050).
     */
    @Test
    fun `IMP-001 no jgit imports in v2 main source`(@TempDir tempDir: Path) {
        val projectRoot = Path.of("/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin")
        val results = mutableListOf<String>()

        val scanDirs = listOf(
            projectRoot.resolve("v2/pipeline-step-sdk/scm-git/src/main"),
            projectRoot.resolve("v2/pipeline-domain/src/main"),
            projectRoot.resolve("v2/pipeline-application/src/main"),
            projectRoot.resolve("v2/pipeline-events/src/main"),
            projectRoot.resolve("v2/pipeline-credentials-api/src/main"),
            projectRoot.resolve("v2/pipeline-credentials-local/src/main"),
            projectRoot.resolve("v2/pipeline-scripting-api/src/main"),
        )

        for (dir in scanDirs) {
            if (Files.exists(dir)) {
                scanDirectory(dir, results)
            }
        }

        assertEquals(0, results.size,
            "BANNED IMPORTS: org.eclipse.jgit found in v2/ main source.\n" +
            "F-ARCH-L5-001 requires CLI-git only (no JGit).\n" +
            "Violations:\n${results.joinToString("\n") { "  $it" }}\n")
    }

    /**
     * IMP-002: No git CLI binary path assumptions in v2/ main source.
     *
     * The implementation must call "git" and rely on PATH resolution,
     * not hardcode paths like "/usr/bin/git".
     */
    @Test
    fun `IMP-002 no hardcoded git binary paths in v2 main source`(@TempDir tempDir: Path) {
        val projectRoot = Path.of("/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin")
        val results = mutableListOf<String>()

        val scanDirs = listOf(
            projectRoot.resolve("v2/pipeline-step-sdk/scm-git/src/main"),
            projectRoot.resolve("v2/pipeline-domain/src/main"),
            projectRoot.resolve("v2/pipeline-application/src/main"),
        )

        val hardcodedGitPattern = Regex("(/usr/bin/git|/bin/git|/opt/git/bin/git)")

        for (dir in scanDirs) {
            if (Files.exists(dir)) {
                Files.walk(dir)
                    .filter { it.toString().endsWith(".kt") }
                    .forEach { file ->
                        val content = Files.readString(file)
                        val matches = hardcodedGitPattern.findAll(content).toList()
                        if (matches.isNotEmpty()) {
                            results.add("${file}: hardcoded git path: ${matches.joinToString { it.value }}")
                        }
                    }
            }
        }

        assertEquals(0, results.size,
            "Hardcoded git paths found — must use 'git' (PATH resolution):\n" +
            results.joinToString("\n") { "  $it" } + "\n")
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun scanDirectory(dir: Path, results: MutableList<String>) {
        Files.walk(dir)
            .filter { it.toString().endsWith(".kt") }
            .forEach { file ->
                checkFileForJgitImports(file, results)
            }
    }

    private fun checkFileForJgitImports(file: Path, results: MutableList<String>) {
        val content = Files.readString(file)
        val relPath = file.toString()

        val bannedPatterns = listOf(
            "org.eclipse.jgit" to "org.eclipse.jgit (JGit library — CLI-git only)",
            "org.eclipse.jgit." to "org.eclipse.jgit.* subpackage",
        )

        bannedPatterns.forEach { (pattern, description) ->
            if (content.contains(pattern)) {
                results.add("[$relPath] $description")
            }
        }
    }
}
