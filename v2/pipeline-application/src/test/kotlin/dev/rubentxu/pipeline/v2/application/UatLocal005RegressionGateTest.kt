package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RunFinished
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * UAT-LOCAL-005: Regression Gate — ML-R1..R4 UAT families still green.
 *
 * Verifies that the ML-R5 checkout-git implementation does not break
 * any previously green UAT families. This is a cross-cutting regression
 * smoke test that runs a minimal pipeline for each prior family.
 *
 * Families covered:
 * - UatLocal001: Kill during sh → external process management
 * - UatLocal002: Resume after kill → durable replay
 * - UatLocal003: Return stdout → output capture
 * - UatLocal004: Timeout → watchdog classification
 * - UatLocal008: Credentials + secret redaction (CR-RD-008 canary)
 *
 * @see <a href="ADR-0050">ADR-0050 §Regression</a>
 */
@Timeout(180)
class UatLocal005RegressionGateTest {

    private val processes = mutableListOf<Process>()

    @BeforeEach
    fun setUp() {
        assumeTrue(
            System.getProperty("os.name", "").lowercase().contains("linux"),
            "UAT regression tests require Linux"
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
     * RG-001: UatLocal001 smoke — basic pipeline with sh still works after ML-R5.
     */
    @Test
    fun `RG-001 UatLocal001 smoke test — sh still works`(@TempDir tempDir: Path) {
        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")
        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        Files.createDirectories(controlRoot)

        val scriptContent = """
pipeline {
    stages {
        stage("Test") {
            sh("echo hello")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        val stdout = runPipeline(javaHome, classpath, dbPath, controlRoot, scriptPath)
        val runFinished = findRunFinished(stdout)
        assertEquals("success", runFinished,
            "Basic sh pipeline should still work after ML-R5. stdout: ${stdout.take(500)}")
    }

    /**
     * RG-002: UatLocal002 smoke — simple pipeline completes (proxy for resume).
     */
    @Test
    fun `RG-002 UatLocal002 smoke — simple pipeline completes`(@TempDir tempDir: Path) {
        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")
        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        Files.createDirectories(controlRoot)

        val scriptContent = """
pipeline {
    stages {
        stage("Test") {
            sh("echo resume-smoke")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        val stdout = runPipeline(javaHome, classpath, dbPath, controlRoot, scriptPath)
        val runFinished = findRunFinished(stdout)
        assertEquals("success", runFinished,
            "Simple pipeline should complete (proxy for resume smoke). stdout: ${stdout.take(500)}")
    }

    /**
     * RG-003: UatLocal003 smoke — echo output captured correctly.
     */
    @Test
    fun `RG-003 UatLocal003 smoke — echo output captured`(@TempDir tempDir: Path) {
        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")
        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        Files.createDirectories(controlRoot)

        val scriptContent = """
pipeline {
    stages {
        stage("Test") {
            echo("RG-003-OK")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        val stdout = runPipeline(javaHome, classpath, dbPath, controlRoot, scriptPath)
        val events = JsonEventLog.decode(stdout)
        assertTrue(events.any { it is dev.rubentxu.pipeline.v2.events.EchoOutputCaptured },
            "EchoOutputCaptured event must be present")
    }

    /**
     * RG-004: UatLocal004 smoke — timeout classification still works.
     */
    @Test
    fun `RG-004 UatLocal004 smoke — timeout still fires`(@TempDir tempDir: Path) {
        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")
        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        Files.createDirectories(controlRoot)

        val scriptContent = """
pipeline {
    stages {
        stage("Test") {
            options {
                timeout = 1L
            }
            sh("sleep 5; echo done")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        val stdout = runPipeline(javaHome, classpath, dbPath, controlRoot, scriptPath)
        val runFinished = findRunFinished(stdout)
        assertEquals("failure", runFinished,
            "Pipeline with 1s timeout should fail when sleeping 5s. stdout: ${stdout.take(500)}")
    }

    /**
     * RG-005: UatLocal008 CR-RD-008 canary — redaction still works after ML-R5.
     *
     * The ML-R4 canary "GHS6_CANARY_7f3a9c2e1b4d5e6f" is registered in the
     * SecretPatternRegistry at startup. This test verifies the redaction engine
     * is still active after the ML-R5 changes.
     */
    @Test
    fun `RG-005 UatLocal008 CR-RD-008 canary redaction still active`(@TempDir tempDir: Path) {
        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")
        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        Files.createDirectories(controlRoot)

        val canary = "GHS6_CANARY_7f3a9c2e1b4d5e6f"
        val scriptContent = """
pipeline {
    stages {
        stage("Test") {
            echo("$canary")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        val stdout = runPipeline(javaHome, classpath, dbPath, controlRoot, scriptPath)
        val events = JsonEventLog.decode(stdout)
        val encodedAll = JsonEventLog.encode(events)
        val canaryInEvents = encodedAll.contains(canary)

        assertFalse(canaryInEvents,
            "ML-R4 canary must NOT appear in any event surface after ML-R5. " +
            "Events: ${events.map { it::class.simpleName }}")
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun runPipeline(
        javaHome: String,
        classpath: String,
        dbPath: Path,
        controlRoot: Path,
        scriptPath: Path,
    ): String {
        val args = listOf(
            javaHome + "/bin/java",
            "-cp", classpath,
            "dev.rubentxu.pipeline.v2.application.MainKt",
            "run",
            "--db", dbPath.toString(),
            "--control-root", controlRoot.toString(),
            scriptPath.toString()
        )

        val pb = ProcessBuilder(args)
            .directory(scriptPath.parent.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        processes.add(process)
        val stdout = process.inputStream.bufferedReader().readText()
        process.waitFor(120, TimeUnit.SECONDS)
        return stdout
    }

    private fun findRunFinished(jsonText: String): String {
        val events = JsonEventLog.decode(jsonText)
        val runFinished = events.filterIsInstance<RunFinished>().firstOrNull()
            ?: throw AssertionError("No RunFinished event in output: ${jsonText.take(800)}")
        return runFinished.outcome
    }
}
