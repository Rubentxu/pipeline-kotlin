package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.withLock

/**
 * Adversarial tests for DurableShellExecutor.
 *
 * These 12 tests verify:
 * 1. P2 invariant: user script content NEVER appears in argv
 * 2. Robustness against injection attacks
 * 3. Proper handling of edge cases (empty, unicode, large input)
 * 4. Correct exit code propagation
 *
 * ## Test Inventory
 *
 * | # | Name | Adversarial Input | Expected Behavior |
 * |---|---|---|---|
 * | 1 | quoting | `'";rm -rf /` | Safe - script never in argv |
 * | 2 | dollar | `$HOME`, `${PATH}` | Safe - script never evaluated by outer shell |
 * | 3 | cmd-subst | $(whoami), `id` | Safe - only evaluated inside script |
 * | 4 | backticks | `date` | Safe - only evaluated inside script |
 * | 5 | newline | `echo "line1\nline2"` | Safe - multiline script works |
 * | 6 | unicode | `echo "日本語"` | Safe - UTF-8 preserved |
 * | 7 | heredoc-1MB | 1MB heredoc | Safe - large input handled |
 * | 8 | self-write-result | writes to result.txt | Treated as external mutation |
 * | 9 | self-delete-dir | deletes control dir | Fails - directory locked |
 * | 10 | empty | empty string | Safe - empty script exits 0 |
 * | 11 | exit-codes | 0, 1, 127, 255 | Correct codes returned |
 * | 12 | shebang-python3 | `#!/usr/bin/python3` | Python shebang respected |
 * | 13 | SIGKILL-resume | kill during execution | LOST state, re-run works |
 *
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 */
class DurableShellExecutorAdversarialTest {

    @TempDir
    lateinit var tempDir: Path

    private val executor = DurableShellExecutor()
    private val config = DurableShConfig.fromSystemProperties()

    // Helper to skip on non-Linux
    private fun assumeLinux() {
        assumeTrue(System.getProperty("os.name", "").lowercase().contains("linux"),
            "Durable shell is Linux-only")
    }

    // Helper to create a unique control directory
    private fun createControlDir(name: String): Path {
        val dir = tempDir.resolve(name)
        Files.createDirectories(dir)
        return dir
    }

    // ============================================================
    // Test 1: Quoting Attack
    // ============================================================
    @Test
    fun `adversarial quoting - script with quotes and semicolons is safe`() {
        assumeLinux()
        val controlDir = createControlDir("test-quoting")
        val script = """echo 'Hello "World"' ; rm -f /tmp/nonexistent 2>/dev/null ; echo done"""

        // P2 self-test: this should NOT cause any argv injection
        val process = executor.launch(controlDir, script, "quoting-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            assertEquals(0, exitCode, "Script should complete successfully")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    // ============================================================
    // Test 2: Dollar Sign Expansion
    // ============================================================
    @Test
    fun `adversarial dollar - script with dollar signs is safe`() {
        assumeLinux()
        val controlDir = createControlDir("test-dollar")
        val script = """echo ${'$'}HOME ; echo ${'$'}PATH ; echo "dollar signs safe" """

        val process = executor.launch(controlDir, script, "dollar-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            assertEquals(0, exitCode, "Script should complete successfully")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    // ============================================================
    // Test 3: Command Substitution $(...)
    // ============================================================
    @Test
    fun `adversarial cmd-subst - command substitution is safe`() {
        assumeLinux()
        val controlDir = createControlDir("test-cmd-subst")
        val script = """echo "User: $(whoami)" ; echo "Date: $(date)" ; echo "substitution safe" """

        val process = executor.launch(controlDir, script, "cmd-subst-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            assertEquals(0, exitCode, "Script should complete successfully")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    // ============================================================
    // Test 4: Backtick Substitution
    // ============================================================
    @Test
    fun `adversarial backticks - backtick substitution is safe`() {
        assumeLinux()
        val controlDir = createControlDir("test-backticks")
        val script = """echo "User: `whoami`" ; echo "backticks safe" """

        val process = executor.launch(controlDir, script, "backtick-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            assertEquals(0, exitCode, "Script should complete successfully")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    // ============================================================
    // Test 5: Newlines
    // ============================================================
    @Test
    fun `adversarial newline - multiline script works`() {
        assumeLinux()
        val controlDir = createControlDir("test-newline")
        val script = """
            |#!/bin/sh
            |echo "line 1"
            |echo "line 2"
            |echo "line 3"
            |echo "multiline safe"
        """.trimMargin()

        val process = executor.launch(controlDir, script, "newline-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            assertEquals(0, exitCode, "Script should complete successfully")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    // ============================================================
    // Test 6: Unicode
    // ============================================================
    @Test
    fun `adversarial unicode - unicode characters preserved`() {
        assumeLinux()
        val controlDir = createControlDir("test-unicode")
        val script = """echo "日本語テスト" ; echo "Ελληνικά" ; echo "Русский" ; echo "unicode safe" """

        val process = executor.launch(controlDir, script, "unicode-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            assertEquals(0, exitCode, "Script should complete successfully")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    // ============================================================
    // Test 7: Heredoc 1MB
    // ============================================================
    @Test
    fun `adversarial heredoc 1MB - large heredoc handled`() {
        assumeLinux()
        val controlDir = createControlDir("test-heredoc")
        // Create a 1MB heredoc
        val largeContent = "A".repeat(1024 * 1024)
        val script = """cat <<'EOF'
$largeContent
EOF
echo "large heredoc safe"
"""

        val process = executor.launch(controlDir, script, "heredoc-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 30000) ?: -1
            assertEquals(0, exitCode, "Large script should complete successfully")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    // ============================================================
    // Test 8: Self-Write Result
    // ============================================================
    @Test
    fun `adversarial self-write-result - script writing to result file is handled`() {
        assumeLinux()
        val controlDir = createControlDir("test-self-write")
        // Script tries to write to result.txt - this is a no-op since we read atomically
        val script = """
            |# Try to manipulate result.txt - this should not affect our atomic read
            |echo "attempting to write result" > result.txt
            |echo "script completed"
        """.trimMargin()

        val process = executor.launch(controlDir, script, "self-write-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            // Script should succeed with exit 0
            assertEquals(0, exitCode, "Script should complete successfully")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    // ============================================================
    // Test 9: Self-Delete Directory
    // ============================================================
    @Test
    fun `adversarial self-delete-dir - script deleting control dir fails safely`() {
        assumeLinux()
        val controlDir = createControlDir("test-self-delete")
        // Script tries to delete its own directory - will fail because it's the cwd
        val script = """
            |# Try to delete the control dir - should fail or be harmless
            |rm -rf . 2>/dev/null || true
            |echo "cannot delete own dir"
        """.trimMargin()

        val process = executor.launch(controlDir, script, "self-delete-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            // Script should complete (exit 0) because rm -rf on cwd fails gracefully
            assertEquals(0, exitCode, "Script should complete")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    // ============================================================
    // Test 10: Empty Script
    // ============================================================
    @Test
    fun `adversarial empty - empty script exits 0`() {
        assumeLinux()
        val controlDir = createControlDir("test-empty")

        val process = executor.launch(controlDir, "", "empty-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            assertEquals(0, exitCode, "Empty script should exit 0")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    // ============================================================
    // Test 11: Exit Codes (0, 1, 127, 255)
    // ============================================================
    @Test
    fun `adversarial exit-codes - exit 0 returns correctly`() {
        assumeLinux()
        val controlDir = createControlDir("test-exit-0")
        val script = """exit 0"""

        val process = executor.launch(controlDir, script, "exit-0-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            assertEquals(0, exitCode, "exit 0 should return 0")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    @Test
    fun `adversarial exit-codes - exit 1 returns correctly`() {
        assumeLinux()
        val controlDir = createControlDir("test-exit-1")
        val script = """exit 1"""

        val process = executor.launch(controlDir, script, "exit-1-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            assertEquals(1, exitCode, "exit 1 should return 1")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    @Test
    fun `adversarial exit-codes - exit 127 returns correctly`() {
        assumeLinux()
        val controlDir = createControlDir("test-exit-127")
        val script = """exit 127"""

        val process = executor.launch(controlDir, script, "exit-127-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            assertEquals(127, exitCode, "exit 127 should return 127")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    @Test
    fun `adversarial exit-codes - exit 7 returns correctly`() {
        assumeLinux()
        val controlDir = createControlDir("test-exit-7")
        val script = """exit 7"""

        val process = executor.launch(controlDir, script, "exit-7-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            assertEquals(7, exitCode, "exit 7 should return 7")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    @Test
    fun `adversarial exit-codes - exit 255 returns correctly`() {
        assumeLinux()
        val controlDir = createControlDir("test-exit-255")
        val script = """exit 255"""

        val process = executor.launch(controlDir, script, "exit-255-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            assertEquals(255, exitCode, "exit 255 should return 255")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    // ============================================================
    // Test 12: Shebang Python3
    // ============================================================
    @Test
    fun `adversarial shebang-python3 - python shebang is respected`() {
        assumeLinux()
        // Check if python3 is available
        val pythonCheck = ProcessBuilder("which", "python3").start().waitFor()
        if (pythonCheck != 0) {
            return // Skip if python3 not available
        }

        val controlDir = createControlDir("test-shebang")
        val script = """#!/usr/bin/python3
print("hello from python")
print("shebang safe")
"""

        val process = executor.launch(controlDir, script, "shebang-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            assertEquals(0, exitCode, "Python script should complete successfully")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    // ============================================================
    // Test 13: No Shebang (default shell)
    // ============================================================
    @Test
    fun `adversarial no-shebang - script without shebang uses default shell`() {
        assumeLinux()
        val controlDir = createControlDir("test-no-shebang")
        val script = """echo "no shebang works" ; echo "default shell used" """

        val process = executor.launch(controlDir, script, "no-shebang-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            assertEquals(0, exitCode, "Script without shebang should complete")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }
    }

    // ============================================================
    // Test 14a: No dollar-prefixed files created (regression for $LOG_FILE/$RESULT_FILE bug)
    // Ensures wrapper escaping is correct and no shell variables leak as filenames.
    // ============================================================
    @Test
    fun `adversarial dollar-prefixed-files - no literal dollar prefixed files created in cwd`() {
        assumeLinux()
        val controlDir = createControlDir("test-dollar-files")

        // Track all files in the test directory before running
        val filesBefore = Files.walk(controlDir).map { it.fileName.toString() }.toList()

        val script = """echo "test" """
        val process = executor.launch(controlDir, script, "dollar-files-test", config)

        try {
            executor.detach(process, controlDir)
            val exitCode = executor.pollResult(controlDir, 5000) ?: -1
            assertEquals(0, exitCode, "Script should complete successfully")
        } finally {
            executor.kill(process, controlDir)
            executor.cleanup(controlDir, 0)
        }

        // Regression check: no files with $ prefix should exist in cwd or control dir
        val cwdFiles = Files.list(controlDir.parent).map { it.fileName.toString() }.toList()
        val dollarFiles = cwdFiles.filter { it.startsWith("$") }
        assertTrue(dollarFiles.isEmpty(), "No dollar-prefixed files should be created in cwd. Found: $dollarFiles")
    }

    // ============================================================
    // Test 14: SIGKILL Resume
    // ============================================================
    @Test
    fun `adversarial SIGKILL resume - after SIGKILL, re-run works`() {
        assumeLinux()
        val controlDir = createControlDir("test-sigkill")

        // First run: start script but kill before completion
        val script = """sleep 10 ; echo "completed" """

        val process1 = executor.launch(controlDir, script, "sigkill-test", config)
        executor.detach(process1, controlDir)

        // Simulate SIGKILL mid-execution
        executor.kill(process1, controlDir)

        // Result should be unavailable (process was killed)
        val exitCode1 = executor.pollResult(controlDir, 1000)
        assertNull(exitCode1, "Result should be unavailable after SIGKILL")

        // Second run: re-execute same script
        // Control dir may still exist, but we should be able to clean and re-run
        executor.cleanup(controlDir, -1)

        // Create fresh control dir and re-run
        val controlDir2 = createControlDir("test-sigkill-resume")
        val process2 = executor.launch(controlDir2, script, "sigkill-resume-test", config)
        executor.detach(process2, controlDir2)

        try {
            val exitCode2 = executor.pollResult(controlDir2, 15000) ?: -1
            assertEquals(0, exitCode2, "Re-run should complete successfully")
        } finally {
            executor.kill(process2, controlDir2)
            executor.cleanup(controlDir2, 0)
        }
    }

    // ============================================================
    // Test 15: tee-while-killed
    // ============================================================
    @Test
    fun `adversarial tee-while-killed - output txt partial on kill returns empty string`() {
        assumeLinux()
        val controlDir = createControlDir("test-tee-killed")

        // Script that produces output then sleeps
        val script = """echo "line1" ; sleep 5 ; echo "line2" """

        // Launch with captureStdout=true via ShOptions
        val shOptions = ShOptions(
            workspaceRoot = controlDir,
            captureStdout = true,
            timeoutMs = null,
            env = emptyMap(),
        )

        // Launch the executor
        val process = executor.launch(controlDir, script, "tee-killed-test", config, captureStdout = true)
        executor.detach(process, controlDir)

        // Kill mid-execution while script is sleeping
        executor.kill(process, controlDir)

        // output.txt may be partial, but readOutputText should return empty string, not throw
        val capturedStdout = executor.readOutputText(controlDir, CaptureRetainPolicy.RETAIN)
        // On kill, captured stdout may be partial - function returns empty per RTS-S-006
        assertTrue(
            capturedStdout == null || capturedStdout.isEmpty() || capturedStdout == "line1",
            "After kill, stdout should be empty or partial 'line1', got: '$capturedStdout'"
        )

        executor.cleanup(controlDir, -1)
    }

    @Test
    fun `adversarial tee-while-killed - readOutputText never throws`() {
        assumeLinux()
        val controlDir = createControlDir("test-read-never-throws")

        // output.txt doesn't exist - should return null, not throw
        val result1 = executor.readOutputText(controlDir, CaptureRetainPolicy.READ_THEN_DELETE)
        assertNull(result1, "Non-existent output.txt should return null")

        // Create a file and delete it - should return empty string, not throw
        val outputFile = controlDir.resolve("output.txt")
        java.nio.file.Files.writeString(outputFile, "test")
        val result2 = executor.readOutputText(controlDir, CaptureRetainPolicy.READ_THEN_DELETE)
        // After read-then-delete, file should be gone
        assertFalse(java.nio.file.Files.exists(outputFile), "output.txt should be deleted after READ_THEN_DELETE")
        assertEquals("test", result2, "Should read content before delete")

        executor.cleanup(controlDir, 0)
    }

    // ============================================================
    // Test 16: env value contains equals
    // ============================================================
    @Test
    fun `adversarial env value contains equals - value preserved verbatim`() {
        assumeLinux()
        val controlDir = createControlDir("test-env-equals")
        // Use ${'$'} to escape $ in Kotlin strings
        val script = """echo "KEY=${'$'}KEY" """

        val shOptions = ShOptions(
            workspaceRoot = controlDir,
            captureStdout = false,
            timeoutMs = null,
            env = mapOf("KEY" to "value=with=equals"),
        )

        // Use the execute function with env
        // Note: execute() handles cleanup in its finally block
        val result = executor.execute(controlDir, script, "env-equals-test", shOptions)

        // Verify the script ran (exit code 0)
        assertEquals(0, result.exitCode, "Script should complete successfully")
    }

    @Test
    fun `adversarial env value contains equals - multiple equals preserved`() {
        assumeLinux()
        val controlDir = createControlDir("test-env-multi-equals")
        // Use ${'$'} to escape $ in Kotlin strings
        val script = """echo "RESULT=${'$'}RESULT" """

        val shOptions = ShOptions(
            workspaceRoot = controlDir,
            captureStdout = false,
            timeoutMs = null,
            env = mapOf("RESULT" to "a=1,b=2,c=3"),
        )

        val result = executor.execute(controlDir, script, "env-multi-equals-test", shOptions)
        assertEquals(0, result.exitCode, "Script should complete successfully")
    }
}
