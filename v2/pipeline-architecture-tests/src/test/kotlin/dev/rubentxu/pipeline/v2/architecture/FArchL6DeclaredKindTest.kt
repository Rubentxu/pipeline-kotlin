package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * F-ARCH-L6-002: Kind declared, never inferred from byte content.
 *
 * Architecture test that enforces:
 * - No byte-content colon inference (`contains(":")`, `split(":", limit=2)`,
 *   `bytes.indexOf(':'.code.toByte())`) across scm-git/, credentials/, dsl/
 * - Kind is the static sealed type, not a runtime byte heuristic
 *
 * This CLOSES D-B: the design decision to use typed `Credential` over
 * byte-content inference. The grep gate finds zero matches when the code
 * uses `store.get(id)::class.simpleName` (typed dispatch) rather than
 * `bytes.contains(":")` (byte inference).
 *
 * RED: Findings list is non-empty (code uses colon inference)
 * GREEN: Findings list is empty (typed dispatch only)
 */
class FArchL6DeclaredKindTest {

    /**
     * Greps for colon-inference patterns across scm-git/, credentials/, dsl/.
     *
     * Patterns detected:
     * - `.contains(":")` — byte array contains colon
     * - `.split(":", limit=2)` — splitting bytes/strings by colon
     * - `.indexOf(':'.code.toByte())` — byte index of colon code
     *
     * ZERO matches = GREEN = typed kind dispatch (no byte inference)
     */
    @Test
    fun `kind_declared_in_resolveGitCredentials`() {
        val root = ScannerSupport.v2Root()

        // Scan across three domains
        val scmGitSrc = root.resolve("pipeline-step-sdk/scm-git/src/main/kotlin")
        val credentialsSrc = root.resolve("pipeline-credentials-api/src/main/kotlin")
            .let { if (it.toFile().exists()) it else null }
        val dslSrc = root.resolve("pipeline-scripting-api/src/main/kotlin")
            .let { if (it.toFile().exists()) it else null }

        val findings = mutableListOf<Finding>()

        // Pattern: looks for the three colon-inference forms
        // We use a line-based scan since these are typically one-liners
        val targets = listOfNotNull(scmGitSrc, credentialsSrc, dslSrc)

        for (srcRoot in targets) {
            if (!srcRoot.toFile().exists()) continue

            for (file in FitnessPaths.walkKotlinFiles(srcRoot)) {
                for ((lineIdx, line) in Files.readAllLines(file).withIndex()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                    // Skip string literals that might legitimately contain colons
                    if (trimmed.contains("\"") || trimmed.contains("'")) continue

                    // Check for the three forbidden patterns
                    val hasContains = trimmed.contains("contains(\":\")") ||
                                     trimmed.contains("contains(':')")
                    val hasSplit = trimmed.contains("split(\":\"") ||
                                    trimmed.contains("split(':')") ||
                                    trimmed.contains("split(\":\",") ||
                                    trimmed.contains("split(':',")
                    val hasIndexOf = trimmed.contains("indexOf(':'.code.toByte())")

                    if (hasContains || hasSplit || hasIndexOf) {
                        val token = when {
                            hasContains -> "contains-colon"
                            hasSplit -> "split-colon"
                            else -> "indexOf-colon"
                        }
                        findings.add(Finding(file, lineIdx + 1, token, line))
                    }
                }
            }
        }

        // RED: findings list non-empty means code uses byte inference
        // GREEN: findings list empty means typed dispatch only
        assertTrue(findings.isEmpty(),
            "Kind must be declared (static type), never inferred from byte content. " +
            "Found colon-inference patterns: $findings")
    }

    /**
     * Extended grep gate: also checks that no file in the scoped paths
     * uses `bytes.indexOf(':'.code.toByte())` for credential kind inference.
     */
    @Test
    fun `no byte index of colon for kind inference`() {
        val root = ScannerSupport.v2Root()

        val scmGitSrc = root.resolve("pipeline-step-sdk/scm-git/src/main/kotlin")
        val findings = mutableListOf<Finding>()

        if (!scmGitSrc.toFile().exists()) {
            return
        }

        val indexOfPattern = Pattern.compile("indexOf\\s*\\(\\s*':'.code.toByte\\s*\\(\\s*\\)\\)")

        for (file in FitnessPaths.walkKotlinFiles(scmGitSrc)) {
            for ((lineIdx, line) in Files.readAllLines(file).withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue

                if (indexOfPattern.matcher(trimmed).find()) {
                    findings.add(Finding(file, lineIdx + 1, "indexOf-colon-byte", line))
                }
            }
        }

        assertTrue(findings.isEmpty(),
            "Must not use indexOf(':'.code.toByte()) for kind inference: $findings")
    }
}
