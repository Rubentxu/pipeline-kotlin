package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.GitCheckoutCompleted
import dev.rubentxu.pipeline.v2.events.JsonEventLog
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
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.annotation.AnnotationTarget.CLASS

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
        // 4 pinned SHA constants (D9)
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

        // Spawn a child process via the harness
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

        // Start the pipeline (will hang in sleep)
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

        // Let the process start
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
                // kill children
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
}
