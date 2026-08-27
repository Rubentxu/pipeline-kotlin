package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * F-ARCH-L5-001: No JGit in scm-git module.
 *
 * Architecture test that enforces:
 * 1. Zero `org.eclipse.jgit.*` imports in `v2/pipeline-step-sdk/scm-git/src/main/kotlin`
 * 2. No `ProcessBuilder` argv containing `extraHeader` or `Authorization` literals
 *    (defense-in-depth for INV-L5-CR-004: credentials never enter argv)
 * 3. F-ARCH-L6-001 extension: No URL-embedded credentials `https?://[^/]*:[^/]*@` in argv
 *    (prevents credential leakage via URL auth in process args)
 *
 * This is a skeleton at T-01 — the scm-git module is populated at T-03.
 * The test passes on an empty directory (T-01 GREEN minimal scope).
 * T-03 will add real implementation; this test closes the architecture gate.
 */
class FArchL5NoJgitTest {

    private val forbiddenImportPrefixes = listOf("org.eclipse.jgit")

    /**
     * Scans the scm-git module for forbidden JGit imports.
     * Since the module doesn't exist at T-01, this passes on empty directory.
     */
    @Test
    fun `no jgit imports in scm-git module`() {
        val root = ScannerSupport.v2Root()
        val scmGitSrc = root.resolve("pipeline-step-sdk/scm-git/src/main/kotlin")

        val findings = if (scmGitSrc.toFile().exists()) {
            ScannerSupport.findForbiddenImportPrefixes(scmGitSrc, forbiddenImportPrefixes)
        } else {
            emptyList()
        }

        assertTrue(findings.isEmpty(),
            "scm-git must NOT import org.eclipse.jgit.*: $findings")
    }

    /**
     * Scans scm-git source for ProcessBuilder argv containing extraHeader/Authorization.
     * This is a grep-gate: any argv literal with these strings is a fail-closed violation.
     *
     * Pattern matches: ProcessBuilder(arrayOf(...), ...) or ProcessBuilder(listOf(...), ...)
     * where any arg contains extraHeader or Authorization.
     */
    @Test
    fun `no argv contains extraHeader or Authorization in scm-git`() {
        val root = ScannerSupport.v2Root()
        val scmGitSrc = root.resolve("pipeline-step-sdk/scm-git/src/main/kotlin")

        if (!scmGitSrc.toFile().exists()) {
            // Module doesn't exist yet - skeleton passes
            return
        }

        val forbiddenInArgv = mutableListOf<Finding>()
        val pbPattern = Pattern.compile("ProcessBuilder\\s*\\(")

        for (file in FitnessPaths.walkKotlinFiles(scmGitSrc)) {
            for ((lineIdx, line) in Files.readAllLines(file).withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue

                // Check if line contains ProcessBuilder
                if (pbPattern.matcher(trimmed).find()) {
                    // Check for forbidden substrings in the line
                    if (trimmed.contains("extraHeader") || trimmed.contains("Authorization")) {
                        forbiddenInArgv.add(Finding(file, lineIdx + 1, "argv-literal", line))
                    }
                }
            }
        }

        assertTrue(forbiddenInArgv.isEmpty(),
            "scm-git argv must NOT contain 'extraHeader' or 'Authorization' literals: $forbiddenInArgv")
    }

    /**
     * Scans build.gradle.kts files for forbidden JGit dependencies.
     */
    @Test
    fun `no jgit in scm-git build file`() {
        val root = ScannerSupport.v2Root()
        val buildFile = root.resolve("pipeline-step-sdk/scm-git/build.gradle.kts")

        if (!buildFile.toFile().exists()) {
            // Module doesn't exist yet - skeleton passes
            return
        }

        val findings = ScannerSupport.findBuildSubstring(buildFile, "jgit")
        assertTrue(findings.isEmpty(),
            "scm-git build must NOT depend on jgit: $findings")
    }

    /**
     * F-ARCH-L6-001: Scans scm-git source for URL-embedded credentials in argv.
     * Pattern: `https?://[^/]*:[^/]*@` matches user:pass@host URL fragments.
     *
     * This is a grep-gate: any argv literal with embedded credentials is a fail-closed violation.
     * Covers INV-L6-CR-011 (git credential scope decided by git — no host literals)
     * and INV-CR-CR3 (P2 argv cleanliness).
     */
    @Test
    fun `no url embedded credentials in scm-git argv`() {
        val root = ScannerSupport.v2Root()
        val scmGitSrc = root.resolve("pipeline-step-sdk/scm-git/src/main/kotlin")

        if (!scmGitSrc.toFile().exists()) {
            // Module doesn't exist yet - skeleton passes
            return
        }

        val findings = mutableListOf<Finding>()
        // Matches URLs with embedded credentials: https://user:pass@host or http://user:pass@host
        val urlCredentialPattern = Pattern.compile("https?://[^/]*:[^/]*@")

        for (file in FitnessPaths.walkKotlinFiles(scmGitSrc)) {
            for ((lineIdx, line) in Files.readAllLines(file).withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue

                if (urlCredentialPattern.matcher(trimmed).find()) {
                    findings.add(Finding(file, lineIdx + 1, "url-credential-in-argv", line))
                }
            }
        }

        assertTrue(findings.isEmpty(),
            "scm-git argv must NOT contain URL-embedded credentials (https?://[^/]*:[^/]*@): $findings")
    }
}
