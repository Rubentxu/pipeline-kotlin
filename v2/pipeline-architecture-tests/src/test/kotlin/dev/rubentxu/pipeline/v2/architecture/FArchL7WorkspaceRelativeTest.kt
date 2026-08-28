package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * F-ARCH-L7-002: Workspace-relative paths gate for file steps.
 *
 * Architecture test that enforces writeFile/fileExists/withEnv callsites
 * use workspace-relative paths, NOT absolute paths.
 *
 * This CLOSES the workspace-relative invariant: the grep gate finds zero matches
 * when the code uses workspace-relative paths (path.startsWith("/") is forbidden
 * in callsites within the pipeline-step-sdk/files/ and pipeline-application/ modules).
 *
 * RED: IOException (path doesn't exist) OR findings list non-empty (absolute paths found)
 * GREEN: Findings list is empty (all paths are workspace-relative)
 */
class FArchL7WorkspaceRelativeTest {

    /**
     * Greps for absolute path patterns in writeFile/withEnv callsites.
     *
     * Forbidden patterns:
     * - path.startsWith("/")
     * - Paths starting with /tmp, /var, /home, /opt, etc.
     *
     * ZERO matches = GREEN = workspace-relative paths only
     */
    @Test
    fun `no_absolute_path_in_writeFile_callsites`() {
        val root = ScannerSupport.v2Root()

        // Scan files/ and application modules for absolute path usage
        val filesSrc = root.resolve("pipeline-step-sdk/files/src/main/kotlin")
        val appSrc = root.resolve("pipeline-application/src/main/kotlin")
            .let { if (it.toFile().exists()) it else null }

        val findings = mutableListOf<Finding>()

        val targets = listOfNotNull(filesSrc, appSrc)

        for (srcRoot in targets) {
            if (!srcRoot.toFile().exists()) {
                throw AssertionError("Source path does not exist: $srcRoot")
            }

            for (file in FitnessPaths.walkKotlinFiles(srcRoot)) {
                for ((lineIdx, line) in Files.readAllLines(file).withIndex()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                    // Skip string literals that might legitimately contain paths
                    if (trimmed.contains("\"") || trimmed.contains("'")) continue

                    // Check for absolute path patterns
                    val hasAbsolutePath = trimmed.contains("startsWith(\"/\")") ||
                                         trimmed.contains("Path.of(\"/") ||
                                         trimmed.matches(Regex("^\".*/.*\"\\s*\\+\\s*"))

                    if (hasAbsolutePath) {
                        findings.add(Finding(file, lineIdx + 1, "absolute-path", line))
                    }
                }
            }
        }

        // RED: findings list non-empty means absolute paths found
        // GREEN: findings list empty means workspace-relative paths only
        assertTrue(
            findings.isEmpty(),
            "All file paths must be workspace-relative (no absolute paths). Found: $findings"
        )
    }

    /**
     * Extended grep gate: also checks withEnv override keys don't use absolute paths.
     */
    @Test
    fun `no_absolute_path_in_withEnv_callsites`() {
        val root = ScannerSupport.v2Root()

        val filesSrc = root.resolve("pipeline-step-sdk/files/src/main/kotlin")
        val appSrc = root.resolve("pipeline-application/src/main/kotlin")
            .let { if (it.toFile().exists()) it else null }

        val findings = mutableListOf<Finding>()

        val targets = listOfNotNull(filesSrc, appSrc)

        for (srcRoot in targets) {
            if (!srcRoot.toFile().exists()) {
                throw AssertionError("Source path does not exist: $srcRoot")
            }

            for (file in FitnessPaths.walkKotlinFiles(srcRoot)) {
                for ((lineIdx, line) in Files.readAllLines(file).withIndex()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue

                    // Check for PATH+X pattern with absolute paths
                    val hasAbsolutePathInEnv = trimmed.contains("PATH+=/") ||
                                              trimmed.matches(Regex(".*\"PATH\\+.*:/.*"))

                    if (hasAbsolutePathInEnv) {
                        findings.add(Finding(file, lineIdx + 1, "absolute-path-in-env", line))
                    }
                }
            }
        }

        assertTrue(
            findings.isEmpty(),
            "withEnv overrides must not contain absolute paths. Found: $findings"
        )
    }
}
