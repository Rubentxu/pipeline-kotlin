package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RunFinished
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * UAT-LOCAL-008: Credentials + Secret Redaction — integration + infrastructure tests.
 *
 * Store-layer (CR-ST-001..007) is fully covered by T4 unit tests
 * (CredentialsStoreTest, CredentialsStorePassphraseTest, CredentialsStoreListAtomicTest).
 * Redaction (CR-RD-001..016) is fully covered by T6 unit tests
 * (RedactingEventSinkTest, SecretPatternRegistryTest, AhoCorasickSwitchTest).
 * Credential binding (CR-BD-001..016) depends on withCredentials DSL which is T3/T5
 * and is tested at the step-executor level.
 *
 * This class provides only the tests that MUST run at UAT/integration level:
 *  - TC-001/TC-002: infrastructure (timeout + teardown)
 *  - IMP-001: banned-imports grep gate
 *  - CP-001: corpus UNTOUCHABLE
 *  - RG-001: UatLocal001 regression smoke
 *  - CR-RD-008: canary round gate (synthetic secret registered → zero in output)
 *
 * @see <a href="ADR-0049">ADR-0049 — Local Credentials + Secret Redaction</a>
 */
@Timeout(120)
class UatLocal008CredentialsTest {

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
        // AGENTS.md §8: destroyForcibly() + process group
        processes.forEach { p ->
            if (p.isAlive) {
                p.destroyForcibly()
            }
        }
        processes.clear()

        // AGENTS.md §8: kill whole process group (setsid children survive parent kill)
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

    // ─── TC-001/002 — infrastructure ───────────────────────────────────────

    @Test
    fun `UAT-L8-TC-001 class-level Timeout 120 declared`() {
        val annotation = UatLocal008CredentialsTest::class.java.getAnnotation(Timeout::class.java)
        assertNotNull(annotation, "@Timeout annotation must be present on class")
        assertEquals(120, annotation.value)
        assertEquals(TimeUnit.SECONDS, annotation.unit)
    }

    @Test
    fun `UAT-L8-TC-002 AfterEach kills surviving children`(@TempDir tempDir: Path) {
        // Start a background sleep; teardown should kill it
        val pb = ProcessBuilder("sleep", "30")
            .directory(tempDir.toFile())
            .start()
        processes.add(pb)
        assertTrue(pb.isAlive, "Background sleep should be running before teardown")
        teardown()
        assertFalse(pb.isAlive, "Sleep should be killed by teardown")
    }

    // ─── IMP-001 — banned imports gate ────────────────────────────────────

    @Test
    fun `UAT-L8-IMP-001 no experimental script imports in credentials modules`() {
        // INV-CR-CR12: No kotlin.script.experimental.* in credentials modules
        val result = ProcessBuilder()
            .command(listOf(
                "grep", "-rE", "kotlin\\.script\\.experimental\\..*",
                "v2/pipeline-credentials-api/src/main/",
                "v2/pipeline-credentials-local/src/main/"
            ))
            .directory(java.io.File("/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin"))
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        val output = result.inputStream.bufferedReader().readText()
        result.waitFor()

        // grep returns 1 when no matches found (matches our expectation)
        assertEquals(1, result.exitValue(),
            "grep should return 1 (no matches). Output: $output")
    }

    // ─── CP-001 — corpus UNTOUCHABLE ──────────────────────────────────────

    @Test
    fun `UAT-L8-CP-001 compatibility corpus unchanged since base commit`() {
        // INV-CR-7: Compatibility corpus must be byte-identical to base commit
        val result = ProcessBuilder()
            .command(listOf("git", "diff", "a3fd09040b6ac647e46ef668758c6ac756c48a7b",
                "HEAD", "--", "v2/compatibility/"))
            .directory(java.io.File("/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin"))
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        val diff = result.inputStream.bufferedReader().readText()
        result.waitFor()

        assertEquals("", diff.trim(),
            "Compatibility corpus should be unchanged vs base commit. Diff:\n$diff")
    }

    // ─── RG-001 — regression smoke ────────────────────────────────────────

    @Test
    fun `UAT-L8-RG-001 simple pipeline completes successfully`(@TempDir tempDir: Path) {
        // Smoke test: a basic pipeline that worked in UatLocal001 still works
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
            "Basic pipeline should complete. stdout: ${stdout.take(500)}")
    }

    // ─── CR-RD-008 — canary round gate ────────────────────────────────────
    // Synthetic canary registered in SecretPatternRegistry at Main.kt startup.
    // T6 unit tests (RedactingEventSinkTest) verify the redaction engine in isolation.
    @Test
    fun `CR-RD-008 canary round gate — synthetic secret zero occurrences in output`(@TempDir tempDir: Path) {
        // A synthetic canary secret (not in any real credential) is registered in
        // SecretPatternRegistry. After a full pipeline run, zero occurrences of the
        // canary value must appear in any output surface.
        //
        // This test verifies the round-trip: register canary → run pipeline →
        // grep output → zero matches.  The registry is pre-seeded with the canary
        // at Main.kt construction time (T6); here we run a pipeline and check.
        //
        // The canary value: "GHS6_CANARY_7f3a9c2e1b4d5e6f" (never appears in real creds)

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

        // Check every event surface for canary occurrence
        val events = JsonEventLog.decode(stdout)
        val encodedAll = JsonEventLog.encode(events)
        val canaryInEvents = encodedAll.contains(canary)

        assertFalse(canaryInEvents,
            "Canary secret must NOT appear in any event surface. " +
            "Events: ${events.map { it::class.simpleName }}")
    }

    // ─── CR-RD-012 — StepFailed message surface ───────────────────────────

    @Test
    fun `CR-RD-012 StepFinished carries stepName field`(@TempDir tempDir: Path) {
        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")
        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        Files.createDirectories(controlRoot)

        val scriptContent = """
pipeline {
    stages {
        stage("Test") {
            sh("exit 1")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        val stdout = runPipeline(javaHome, classpath, dbPath, controlRoot, scriptPath)
        val events = JsonEventLog.decode(stdout)

        val stepFinishedEvents = events.filterIsInstance<dev.rubentxu.pipeline.v2.events.StepFinished>()
        assertTrue(stepFinishedEvents.isNotEmpty(),
            "exit 1 should produce StepFinished. Events: ${events.map { it::class.simpleName }}")

        val stepNames = stepFinishedEvents.map { it.stepName }
        assertTrue(stepNames.any { it == "sh" || it.contains("sh") },
            "StepFinished should record sh step. stepNames: $stepNames")
    }

    // ─── CR-RD-013 — line-oriented echo capture ───────────────────────────

    @Test
    fun `CR-RD-013 echo output captured line by line`(@TempDir tempDir: Path) {
        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")
        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        Files.createDirectories(controlRoot)

        val scriptContent = """
pipeline {
    stages {
        stage("Test") {
            echo("LINE1 normal")
            echo("LINE2 also-normal")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        val stdout = runPipeline(javaHome, classpath, dbPath, controlRoot, scriptPath)
        val events = JsonEventLog.decode(stdout)

        val echoEvents = events.filterIsInstance<EchoOutputCaptured>()
        assertTrue(echoEvents.size >= 2,
            "Should have at least 2 EchoOutputCaptured events. Got: ${echoEvents.size}")

        val allContent = echoEvents.joinToString(" ") { it.content }
        assertTrue(allContent.contains("LINE1"), "LINE1 should be captured")
        assertTrue(allContent.contains("LINE2"), "LINE2 should be captured")
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun runPipeline(
        javaHome: String,
        classpath: String,
        dbPath: Path,
        controlRoot: Path,
        scriptPath: Path,
        extraArgs: Array<String> = emptyArray(),
    ): String {
        val args = mutableListOf(
            javaHome + "/bin/java",
            "-cp", classpath,
            "dev.rubentxu.pipeline.v2.application.MainKt",
            "run",
            "--db", dbPath.toString(),
            "--control-root", controlRoot.toString()
        )
        args.addAll(extraArgs)
        args.add(scriptPath.toString())

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
            ?: throw AssertionError(
                "No RunFinished event in output: ${jsonText.take(800)}"
            )
        return runFinished.outcome
    }
}
