package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
 * UAT-LOCAL-005: Requires Git on PATH.
 *
 * Verifies that:
 * 1. git --version returns >=2.30 (AGENTS.md requirement)
 * 2. file:// remote fixture works when V2_NETWORK_OK != "true"
 *
 * This test is the pre-flight check for the entire UAT-LOCAL-005 family.
 * It MUST pass before any other test in the family runs.
 *
 * @see <a href="AGENTS.md">AGENTS.md §Git version requirement</a>
 */
@Timeout(30)
class UatLocal005RequiresGitOnPathTest {

    private val processes = mutableListOf<Process>()

    @BeforeEach
    fun setUp() {
        assumeTrue(
            System.getProperty("os.name", "").lowercase().contains("linux"),
            "UAT integration tests require Linux"
        )
    }

    @AfterEach
    fun teardown() {
        processes.forEach { p ->
            if (p.isAlive) {
                p.destroyForcibly()
            }
        }
        processes.clear()
        val selfPid = ProcessHandle.current().pid()
        try {
            val pb = ProcessBuilder("pgrep", "-P", selfPid.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
            val childProcs = pb.start().inputStream.bufferedReader().readText()
            if (childProcs.isNotBlank()) {
                childProcs.lines().filter { it.isNotBlank() }.forEach { pid ->
                    try {
                        ProcessHandle.of(pid.toLong()).ifPresent { it.destroyForcibly() }
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * REQ-001: git --version returns >=2.30 on PATH.
     */
    @Test
    fun `REQ-001 git version is at least 2-30`() {
        val output = runCommand(listOf("git", "--version"))
        assertTrue(output.startsWith("git version"),
            "git --version must return version string, got: $output")

        val versionStr = output.removePrefix("git version ")
        val major = versionStr.substringBefore(".").toIntOrNull()
        assertTrue(major != null && major >= 2, "git major version must be >= 2, got: $versionStr")

        if (major == 2) {
            val minor = versionStr.substringAfter(".").substringBefore(".").toIntOrNull()
            assertTrue(minor != null && minor >= 30,
                "git minor version must be >= 30 for 2.x series, got: $versionStr")
        }
    }

    /**
     * REQ-002: git is on PATH (not hardcoded path).
     */
    @Test
    fun `REQ-002 git is resolvable via PATH`() {
        // which git should find it
        val whichOutput = runCommand(listOf("which", "git"))
        assertTrue(whichOutput.isNotBlank(), "git must be on PATH")
        assertFalse(whichOutput.contains("not found"), "git not found on PATH")

        // git (without path) should work
        val pb = ProcessBuilder("git", "--version")
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val p = pb.start()
        processes.add(p)
        val exit = p.waitFor(10, TimeUnit.SECONDS)
        assertTrue(exit, "git --version must succeed without explicit path")
        val output = p.inputStream.bufferedReader().readText()
        assertTrue(output.startsWith("git version"), "git --version must work: $output")
    }

    /**
     * REQ-003: file:// local bare repo works for testing.
     *
     * This verifies the fixture strategy used by all UatLocal005CheckoutGitTest
     * scenarios — creating a bare repo at file:// URL and using it as the remote.
     */
    @Test
    fun `REQ-003 file bare repo fixture works`(@TempDir tempDir: Path) {
        // Create a bare repo using git init --bare
        val bareRepo = tempDir.resolve("fixture.git")

        val initPb = ProcessBuilder("git", "init", "--bare", bareRepo.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        var p = initPb.start()
        processes.add(p)
        assertTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0,
            "git init --bare must succeed, err: ${p.errorStream.bufferedReader().readText()}")

        // Verify bare repo exists
        assertTrue(Files.exists(bareRepo), "Bare repo must exist at $bareRepo")

        // git ls-remote on empty bare repo returns exit 0 with empty output
        // (no refs yet, but the command itself works)
        val lsRemotePb = ProcessBuilder("git", "ls-remote", bareRepo.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        p = lsRemotePb.start()
        processes.add(p)
        assertTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0,
            "git ls-remote must succeed on empty bare repo, err: ${p.errorStream.bufferedReader().readText()}")
    }

    /**
     * REQ-004: V2_NETWORK_OK env var is respected (tests may skip when not set).
     *
     * Network-dependent tests should be skipped when V2_NETWORK_OK != "true".
     * This test documents that the environment variable is available.
     */
    @Test
    fun `REQ-004 V2_NETWORK_OK env var is readable`() {
        val networkOk = System.getenv("V2_NETWORK_OK")
        // The env var may or may not be set — we just verify it's readable
        // Network tests use @EnabledIfEnvironmentVariable to conditionally skip
        assertTrue(networkOk == null || networkOk == "true" || networkOk == "false",
            "V2_NETWORK_OK must be null, 'true', or 'false'")
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun runCommand(args: List<String>): String {
        val pb = ProcessBuilder(args)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val p = pb.start()
        processes.add(p)
        val ok = p.waitFor(30, TimeUnit.SECONDS)
        return if (ok) p.inputStream.bufferedReader().readText().trim() else ""
    }
}
