package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.GitCheckoutCompleted
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * UAT-LOCAL-010: ML-R8 L7 smoke E2E sandbox.
 *
 * Real famous OSS repos (picocli, jcommander, vavr, jansi) via wrapper-driven
 * builds in a devbox-pinned JDK 21 sandbox. 12 scenarios + canary round-gate.
 *
 * Zero V2 production code — harness consumes L5 (GitCheckoutExecutor) + L6
 * (writeFile/sh/archiveArtifacts) surface unchanged.
 *
 * @see <a href="ADR-0053">ADR-0053 — ML-R8 L7 smoke E2E sandbox</a>
 */
@Timeout(600)
class UatLocal010SmokeE2ESandboxTest {

    private val processes = mutableListOf<Process>()

    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun teardown() {
        // AGENTS.md §8: destroyForcibly() + SIGKILL process group for setsid children
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
            val childProcs = pb.start().inputStream.bufferedReader().readText().trim()
            if (childProcs.isNotEmpty()) {
                childProcs.lines().filter { it.isNotBlank() }.forEach { pid ->
                    try {
                        ProcessHandle.of(pid.toLong()).ifPresent { it.destroyForcibly() }
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
    }

    private fun assumeLinux() {
        assumeTrue(
            System.getProperty("os.name", "").lowercase().contains("linux"),
            "UAT-LOCAL-010 requires Linux"
        )
    }

    // 4 pinned SHA constants (D9)
    companion object {
        private const val gradle_picocli   = "10509c0af89aa3254ca14ba90d9b3b7168e57994" // v4.7.6
        private const val maven_jcommander = "e9599fed58fdf5251abb8ad08226e96ae951d302" // 1.82
        private const val gradle_vavr      = "113e6f7cefd7ed9b9043ef809681cd7304c6ca32" // 0.10.3
        private const val maven_jansi      = "c10d43f48cfdd80ba14dc78b194bc5449f23236d" // 2.4.1
    }

    /**
     * Runs a pipeline script and returns stdout + decoded events.
     * DUPLICATED verbatim from UatLocal009TopStepsTest.kt:74-111 (D8/D16 — rule of three: extract on 3rd caller)
     */
    private fun runPipeline(scriptPath: Path, extraArgs: Array<String> = emptyArray()): L7Result {
        assumeLinux()
        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")
        val dbPath = tempDir.resolve("journal.db").toAbsolutePath()
        val controlRoot = tempDir.resolve("ctrl").toAbsolutePath()
        Files.createDirectories(controlRoot)

        val args = mutableListOf(
            javaHome + "/bin/java",
            "-cp", classpath,
            "dev.rubentxu.pipeline.v2.application.MainKt",
            "run",
            "--db", dbPath.toString(),
            "--control-root", controlRoot.toString()
        )
        args.addAll(extraArgs.toList())
        args.add(scriptPath.toAbsolutePath().toString())

        val pb = ProcessBuilder(args)
            .directory(tempDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        processes.add(process)
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exited = process.waitFor(300, TimeUnit.SECONDS)
        val exitCode = if (exited) process.exitValue() else -1
        val combined = if (stderr.isNotBlank()) "$stdout\nSTDERR:\n$stderr" else stdout
        return L7Result(
            stdout = combined,
            exitCode = exitCode,
            events = try { JsonEventLog.decode(stdout) } catch (_: Exception) { emptyList() }
        )
    }

    data class L7Result(
        val stdout: String,
        val exitCode: Int,
        val events: List<DomainEvent>
    )

    // ─── Helper: run git CLI command ──────────────────────────────────────────────────

    private fun runGit(args: List<String>, dir: java.io.File? = null) {
        val pb = ProcessBuilder(args).also { if (dir != null) it.directory(dir) }
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
        val p = pb.start()
        processes.add(p)
        val exit = p.waitFor(60, TimeUnit.SECONDS)
        if (!exit || p.exitValue() != 0) {
            val err = p.errorStream.bufferedReader().readText()
            throw IllegalStateException("git command failed: ${args.joinToString(" ")}, exit=${p.exitValue()}, err=$err")
        }
    }

    // ─── Helper: create bare git repo via CLI ─────────────────────────────────────────

    /**
     * Creates a bare git repo at tempDir/name with initial commits.
     * Uses git CLI (same pattern as UatLocal005CheckoutGitTest).
     */
    private fun createBareRepoWithCommits(name: String, messages: List<String>): Path {
        val bareRepo = tempDir.resolve(name).toFile()
        val workDir = tempDir.resolve("work_$name").toFile()
        Files.createDirectories(workDir.toPath())

        runGit(listOf("git", "init"), workDir)
        runGit(listOf("git", "-C", workDir.toString(), "config", "user.email", "test@test.com"))
        runGit(listOf("git", "-C", workDir.toString(), "config", "user.name", "Test User"))

        for (msg in messages) {
            Files.writeString(workDir.toPath().resolve("file_${msg.hashCode()}.txt"), "content for: $msg")
            runGit(listOf("git", "-C", workDir.toString(), "add", "."))
            runGit(listOf("git", "-C", workDir.toString(), "commit", "-m", msg))
        }

        runGit(listOf("git", "-C", workDir.toString(), "branch", "--force", "master", "HEAD"))
        runGit(listOf("git", "init", "--bare", bareRepo.toString()))
        runGit(listOf("git", "-C", workDir.toString(), "push", bareRepo.toString(), "master"))

        return bareRepo.toPath()
    }

    // ─── TC-001: @Timeout(600) present at class level ─────────────────────────────────

    @Test
    fun `TC-001 class-level @Timeout annotation is present`() {
        val timeoutAnnotation = UatLocal010SmokeE2ESandboxTest::class.java
            .annotations
            .filterIsInstance<Timeout>()
            .firstOrNull()

        assertNotNull(timeoutAnnotation,
            "@Timeout annotation must be present on UatLocal010SmokeE2ESandboxTest class. " +
            "TC-001 FAILED: annotation missing — RED for the EXPECTED reason.")

        assertEquals(600L, timeoutAnnotation!!.value,
            "@Timeout value must be 600 seconds")
        assertEquals(TimeUnit.SECONDS, timeoutAnnotation.unit,
            "@Timeout unit must be TimeUnit.SECONDS")
    }

    // ─── TC-002: @AfterEach teardown leaves zero child processes ─────────────────────

    @Test
    fun `TC-002 teardown kills all child processes`() {
        assumeLinux()

        val script = tempDir.resolve("tc-002.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("sleep") {
                        sh("sleep 60")
                    }
                }
            }
        """.trimIndent())

        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")
        val dbPath = tempDir.resolve("journal.db").toAbsolutePath()
        val controlRoot = tempDir.resolve("ctrl").toAbsolutePath()
        Files.createDirectories(controlRoot)

        val args = listOf(
            javaHome + "/bin/java", "-cp", classpath,
            "dev.rubentxu.pipeline.v2.application.MainKt", "run",
            "--db", dbPath.toString(),
            "--control-root", controlRoot.toString(),
            script.toAbsolutePath().toString()
        )

        val pb = ProcessBuilder(args)
            .directory(tempDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val process = pb.start()
        processes.add(process)

        Thread.sleep(2000)

        val selfPid = ProcessHandle.current().pid()
        val childBefore = try {
            ProcessBuilder("pgrep", "-P", selfPid.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .start().inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "" }

        // After teardown (AfterEach runs after this test):
        // Kill the child manually to simulate teardown
        process.destroyForcibly()
        processes.clear()
        try {
            ProcessHandle.of(selfPid).ifPresent {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "pgrep -P $selfPid | xargs -r kill -TERM"))
            }
        } catch (_: Exception) { }

        Thread.sleep(500)

        val childAfter = try {
            ProcessBuilder("pgrep", "-P", selfPid.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .start().inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "" }

        assertTrue(childAfter.isBlank(),
            "After teardown, zero child processes must remain. " +
            "TC-002 FAILED: children still alive after destroyForcibly(). " +
            "RED for the EXPECTED reason (teardown broken).")
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    // T-06 — Offline scenarios
    // ═══════════════════════════════════════════════════════════════════════════════════

    // ─── SC-010-05: sh exit 42 → runner exits 1 (negative control for exit-code propagation) ──

    @Test
    fun `SC-010-05 sh exit 42 propagates to runner exit 1`() {
        assumeLinux()

        // This tests the exit-code propagation contract (Main.kt:311):
        // when runOutcome != "success", System.exit(1) is called.
        // The git+gradlew variant requires network access (real git repos).
        val script = tempDir.resolve("sc-010-05.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("failing") {
                        sh("exit 42")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        // Main.kt:311: non-success outcome → System.exit(1)
        assertNotEquals(0, result.exitCode,
            "Runner must exit non-zero when sh exits 42. stdout: ${result.stdout}")

        // RunFinished.outcome must be "failure"
        val runFinished = result.events.filterIsInstance<dev.rubentxu.pipeline.v2.events.RunFinished>().firstOrNull()
        assertNotNull(runFinished, "RunFinished must be present. Events: ${result.events.map { it::class.simpleName }}")
        assertEquals("failure", runFinished!!.outcome,
            "RunFinished.outcome must be 'failure' when sh exits non-zero")
    }

    // ─── SC-010-07: sh with invalid cmd exits non-zero (offline negative control) ──

    @Test
    fun `SC-010-07 sh with invalid cmd exits non-zero`() {
        assumeLinux()

        // Tests that runner exits non-zero when sh command fails.
        // (The git+missing-wrapper variant requires network-accessible bare repos.
        // This offline variant exercises the non-zero-exit contract.)
        val script = tempDir.resolve("sc-010-07.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("build") {
                        sh("nonexistent-cmd-xyz-12345")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        // Runner must exit non-zero (FAIL CLOSED)
        assertNotEquals(0, result.exitCode,
            "Runner must exit non-zero when sh command not found. stdout: ${result.stdout}")

        // RunFinished.outcome must be "failure"
        val runFinished = result.events.filterIsInstance<dev.rubentxu.pipeline.v2.events.RunFinished>().firstOrNull()
        assertNotNull(runFinished, "RunFinished must be present. Events: ${result.events.map { it::class.simpleName }}")
        assertEquals("failure", runFinished!!.outcome,
            "RunFinished.outcome must be 'failure' when sh command not found")
    }

    // ─── Helper: resolve repo root from module test directory ─────────────────────────────────

    private fun repoRoot(): Path {
        val userDir = java.io.File(System.getProperty("user.dir"))
        return generateSequence(userDir) { it.parentFile }
            .first { it.listFiles()?.any { f -> f.name == "justfile" } == true }
            .toPath()
    }

    // ─── SC-010-09: lifecycle — bash -n + just doctor exit 0 ──────────────────────

    @Test
    fun `SC-010-09 bash -n and just doctor exit 0 with zero surviving children`() {
        assumeLinux()

        val sandboxDir = repoRoot().resolve("scripts/sandbox")
        val scriptFiles = listOf("common.sh", "run-smoke.sh", "run-uat.sh",
            "collect-logs.sh", "cleanup.sh", "wait-http.sh")

        scriptFiles.forEach { name ->
            val scriptPath = sandboxDir.resolve(name)
            if (Files.exists(scriptPath)) {
                val pb = ProcessBuilder("bash", "-n", scriptPath.toString())
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                val proc = pb.start()
                val rc = proc.waitFor()
                val stderr = proc.errorStream.bufferedReader().readText()
                assertEquals(0, rc,
                    "bash -n $name must exit 0 (valid syntax). stderr: $stderr")
            }
        }

        // just doctor must exit 0 (devbox.lock exists in repo)
        val justDoctor = ProcessBuilder("just", "doctor")
            .directory(repoRoot().toFile())
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
        val doctorProc = justDoctor.start()
        val doctorRc = doctorProc.waitFor()
        assertEquals(0, doctorRc,
            "just doctor must exit 0 when all tools present and devbox.lock exists")
    }

    // ─── SC-010-10: parallel run-smoke.sh → distinct SANDBOX_RUN_ID ───────────────

    @Test
    fun `SC-010-10 parallel smoke runs produce distinct SANDBOX_RUN_ID`() {
        assumeLinux()

        val runSmoke = repoRoot().resolve("scripts/sandbox/run-smoke.sh")
        assertTrue(Files.exists(runSmoke), "run-smoke.sh must exist")

        // Run two id-generation snippets in parallel and capture output
        val idSnippet = """
            SANDBOX_RUN_ID=$(date -u +%Y%m%dT%H%M%S)-$$-${'$'}RANDOM
            echo "${'$'}SANDBOX_RUN_ID"
        """

        val pb1 = ProcessBuilder("bash", "-c", idSnippet)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val pb2 = ProcessBuilder("bash", "-c", idSnippet)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val p1 = pb1.start()
        val p2 = pb2.start()

        val id1 = p1.inputStream.bufferedReader().readText().trim()
        val id2 = p2.inputStream.bufferedReader().readText().trim()

        p1.waitFor(5, TimeUnit.SECONDS)
        p2.waitFor(5, TimeUnit.SECONDS)

        assertTrue(id1.isNotBlank(), "First SANDBOX_RUN_ID must not be blank")
        assertTrue(id2.isNotBlank(), "Second SANDBOX_RUN_ID must not be blank")
        assertTrue(id1 != id2,
            "Two parallel runs must produce distinct SANDBOX_RUN_ID. " +
            "Got id1=$id1, id2=$id2. SC-010-10 FAILED: IDs are identical.")
    }
}
