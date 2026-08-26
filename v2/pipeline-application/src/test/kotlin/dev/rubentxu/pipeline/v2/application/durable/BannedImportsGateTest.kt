package dev.rubentxu.pipeline.v2.application.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * BannedImportsGateTest — UAT-L7-IMP-001 / INV-2
 *
 * Scans v2/pipeline-step-sdk/runtime/src/ and v2/pipeline-application/src/
 * (NOT docs/) for banned dangerous imports that indicate OS-level sandbox attempts.
 *
 * These imports indicate scope creep toward M5/M9 container-level sandboxing
 * which is explicitly OUT of scope per ADR-0016 and the ML-R3 L3 firewall.
 *
 * ## Banned Patterns
 *
 * | Pattern | Why banned | ADR reference |
 * |---------|-----------|----------------|
 * | `linux.unshare` | syscall-level namespace isolation (M5/M9) | ADR-0016 |
 * | `java.security.Policy` | SecurityManager-based sandbox (deprecated, weak) | ADR-0016 |
 * | `sun.misc.Unsafe` | Arbitrary memory access; M5/M9-adjacent | ADR-0016 |
 *
 * ## Scope
 *
 * - Scans `v2/pipeline-step-sdk/runtime/src/main/` and `v2/pipeline-application/src/main/`
 * - Does NOT scan `docs/` (ADR mentions are not code)
 * - Does NOT scan `test/` (test code may reference banned APIs without using them)
 * - Does NOT scan `v2/compatibility/` (corpus fixtures)
 *
 * Runs on every `v2 check` — this is a permanent gate, not a one-off scan.
 *
 * @see <a href="ADR-0016">ADR-0016 — M5/M9 scope firewall</a>
 * @see <a href="ADR-0048">ADR-0048 — SandboxProfile.LOCAL</a>
 */
@Timeout(30)
class BannedImportsGateTest {

    /**
     * Scans all .kt source files under v2/pipeline-step-sdk/runtime/src/main/ and
     * v2/pipeline-application/src/main/ for banned import patterns.
     *
     * Fails the build if any banned pattern is found.
     */
    @Test
    fun `BannedImportsGate - no banned dangerous imports in main source`(@TempDir tempDir: Path) {
        val root = tempDir.resolve("scan_root")
        Files.createDirectories(root)

        val results = mutableListOf<String>()

        // Scan v2/pipeline-step-sdk/runtime/src/main/
        val sdkMain = Path.of("v2/pipeline-step-sdk/runtime/src/main")
        if (Files.exists(sdkMain)) {
            scanDirectory(sdkMain, results)
        }

        // Scan v2/pipeline-application/src/main/
        val appMain = Path.of("v2/pipeline-application/src/main")
        if (Files.exists(appMain)) {
            scanDirectory(appMain, results)
        }

        if (results.isNotEmpty()) {
            val message = buildString {
                appendLine("BANNED IMPORTS DETECTED — ML-R3 L3 firewall violated:")
                appendLine()
                results.forEach { appendLine("  $it") }
                appendLine()
                appendLine("These imports indicate M5/M9 container-level sandboxing which is")
                appendLine("OUT of scope per ADR-0016. Use SandboxProfile.LOCAL instead.")
            }
            throw AssertionError(message)
        }
    }

    private fun scanDirectory(dir: Path, results: MutableList<String>) {
        Files.walk(dir)
            .filter { it.toString().endsWith(".kt") }
            .forEach { file ->
                val content = Files.readString(file)
                checkFileForBannedImports(file, content, results)
            }
    }

    private fun checkFileForBannedImports(file: Path, content: String, results: MutableList<String>) {
        val relPath = file.toString()
        val bannedPatterns = listOf(
            "linux.unshare" to "linux.unshare (namespace syscall — M5/M9)",
            "java.security.Policy" to "java.security.Policy (SecurityManager — deprecated)",
            "sun.misc.Unsafe" to "sun.misc.Unsafe (arbitrary memory — M5/M9-adjacent)"
        )

        bannedPatterns.forEach { (pattern, description) ->
            if (content.contains(pattern)) {
                results.add("[$relPath] $description")
            }
        }
    }
}
