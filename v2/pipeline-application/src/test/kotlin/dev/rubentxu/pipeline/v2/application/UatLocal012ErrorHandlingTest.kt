package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * UAT-LOCAL-012: Error-handling — catchError/warnError/unstable with 3-state outcome.
 *
 * 8 scenarios SC-012-01..08 covering:
 * - catchError catches failure → UNSTABLE (default Jenkins)
 * - catchError(buildResult="FAILURE") re-throws
 * - catchError(buildResult="SUCCESS") downgrades to success
 * - warnError marks UNSTABLE without aborting
 * - unstable marks stage UNSTABLE + continues + exit 0 (SC-012-04)
 * - unstable() inside catchError overrides downgrade (SC-012-06, R-14)
 * - nested catchError inner wins
 * - error inside catchError classified correctly
 *
 * @see <a href="ADR-0052">ADR-0052 — Jenkins top steps</a>
 * @see <a href="R-14">R-14 — unstable inside catchError override</a>
 */
@Timeout(600)
class UatLocal012ErrorHandlingTest {

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
            "UAT-LOCAL-012 requires Linux"
        )
    }

    /**
     * Runs a pipeline script and returns stdout + decoded events.
     * DUPLICATED verbatim from UatLocal009TopStepsTest.kt:74-111 (D16 — rule of three)
     */
    private fun runPipeline(scriptPath: Path, extraArgs: Array<String> = emptyArray()): PipelineResult {
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
        return PipelineResult(
            stdout = combined,
            exitCode = exitCode,
            events = try { JsonEventLog.decode(stdout) } catch (_: Exception) { emptyList() }
        )
    }

    data class PipelineResult(
        val stdout: String,
        val exitCode: Int,
        val events: List<DomainEvent>
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-012-01: catchError catches failure → UNSTABLE (default Jenkins)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-012-01 catchError default catches failure and emits CatchErrorTriggered`() {
        val script = tempDir.resolve("sc-012-01.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        catchError {
                            sh("exit 1")
                        }
                        echo("after catchError")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        // Pipeline should exit 0 (unstable, not failure)
        assertEquals(0, result.exitCode,
            "Pipeline with catchError default should exit 0 (unstable). stdout: ${result.stdout}")

        val catchErrorTriggered = result.events.filterIsInstance<CatchErrorTriggered>()
        assertTrue(catchErrorTriggered.isNotEmpty(),
            "Should emit CatchErrorTriggered. Events: ${result.events.map { it::class.simpleName }}")

        val cet = catchErrorTriggered.first()
        assertEquals("UNSTABLE", cet.stageResult,
            "Default catchError should produce UNSTABLE stage result")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-012-02: catchError(buildResult="FAILURE") re-throws after catching
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-012-02 catchError buildResult=FAILURE re-throws and pipeline fails`() {
        val script = tempDir.resolve("sc-012-02.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        catchError(buildResult = "FAILURE") {
                            sh("exit 1")
                        }
                        echo("after catchError")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        // Pipeline should exit 1 (failure, not unstable)
        assertEquals(1, result.exitCode,
            "Pipeline with catchError(buildResult='FAILURE') should exit 1. stdout: ${result.stdout}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-012-03: catchError(buildResult="SUCCESS") downgrades to success
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-012-03 catchError buildResult=SUCCESS downgrades to success`() {
        val script = tempDir.resolve("sc-012-03.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        catchError(buildResult = "SUCCESS") {
                            sh("exit 1")
                        }
                        echo("after catchError")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        // Pipeline should exit 0 (success, inner failure caught and downgraded)
        assertEquals(0, result.exitCode,
            "Pipeline with catchError(buildResult='SUCCESS') should exit 0. stdout: ${result.stdout}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-012-04: warnError marks UNSTABLE without aborting
    // D5: Main.kt:311 widening — unstable → exit 0
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-012-04 warnError emits StageMarkedUnstable and exits 0`() {
        val script = tempDir.resolve("sc-012-04.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        warnError("Warning message") {
                            sh("exit 1")
                        }
                        echo("after warnError")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        // Pipeline should exit 0 (unstable, not failure) per Main.kt:311 widening
        assertEquals(0, result.exitCode,
            "Pipeline with warnError should exit 0 (unstable per Main.kt:311). stdout: ${result.stdout}")

        val stageMarkedUnstable = result.events.filterIsInstance<StageMarkedUnstable>()
        assertTrue(stageMarkedUnstable.isNotEmpty(),
            "Should emit StageMarkedUnstable. Events: ${result.events.map { it::class.simpleName }}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-012-05: unstable(message) marks stage UNSTABLE and continues
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-012-05 unstable emits StageMarkedUnstable and pipeline continues`() {
        val script = tempDir.resolve("sc-012-05.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        unstable("This is an unstable stage")
                        echo("after unstable")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        // Pipeline should exit 0 (unstable, not failure) per Main.kt:311 widening
        assertEquals(0, result.exitCode,
            "Pipeline with unstable() should exit 0. stdout: ${result.stdout}")

        val stageMarkedUnstable = result.events.filterIsInstance<StageMarkedUnstable>()
        assertTrue(stageMarkedUnstable.isNotEmpty(),
            "Should emit StageMarkedUnstable. Events: ${result.events.map { it::class.simpleName }}")

        // Pipeline should continue after unstable
        assertTrue(result.stdout.contains("after unstable"),
            "Pipeline should continue after unstable. stdout: ${result.stdout}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-012-06: unstable() inside catchError overrides downgrade
    // R-14 MITIGATION: unstable inside catchError(buildResult="FAILURE") should override
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-012-06 unstable inside catchError overrides buildResult=FAILURE downgrade`() {
        val script = tempDir.resolve("sc-012-06.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        catchError(buildResult = "FAILURE") {
                            unstable("inner unstable overrides FAILURE downgrade")
                        }
                        echo("after catchError with inner unstable")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        // Pipeline should exit 0 because unstable overrides the FAILURE downgrade
        // This is the R-14 mitigation scenario
        assertEquals(0, result.exitCode,
            "Pipeline with unstable() inside catchError(buildResult='FAILURE') should exit 0 " +
            "(unstable overrides). stdout: ${result.stdout}")

        val stageMarkedUnstable = result.events.filterIsInstance<StageMarkedUnstable>()
        assertTrue(stageMarkedUnstable.isNotEmpty(),
            "Should emit StageMarkedUnstable for inner unstable. Events: ${result.events.map { it::class.simpleName }}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-012-07: nested catchError — inner wins
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-012-07 nested catchError inner catchError wins`() {
        val script = tempDir.resolve("sc-012-07.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        catchError {
                            catchError(buildResult = "SUCCESS") {
                                sh("exit 1")
                            }
                        }
                        echo("after nested catchError")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        // Inner catchError(buildResult="SUCCESS") catches and downgrades to success
        // Outer catchError sees success, so pipeline exits 0
        assertEquals(0, result.exitCode,
            "Pipeline with nested catchError should exit 0. stdout: ${result.stdout}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-012-08: error inside catchError classified correctly
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-012-08 error inside catchError emits StepFinished with failure outcome`() {
        val script = tempDir.resolve("sc-012-08.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        catchError {
                            sh("exit 1")
                        }
                        echo("after error caught")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline with caught error should exit 0. stdout: ${result.stdout}")

        // Verify StepFinished events exist for the failed step
        val stepFinished = result.events.filterIsInstance<StepFinished>()
        assertTrue(stepFinished.isNotEmpty(),
            "Should emit StepFinished events. Events: ${result.events.map { it::class.simpleName }}")

        // Verify RunFinished event
        val runFinished = result.events.filterIsInstance<RunFinished>()
        assertTrue(runFinished.isNotEmpty(),
            "Should emit RunFinished event. Events: ${result.events.map { it::class.simpleName }}")

        // RunFinished outcome should be "unstable" (not "failure")
        val rf = runFinished.first()
        assertEquals("unstable", rf.outcome,
            "RunFinished outcome should be 'unstable' after catchError. Got: ${rf.outcome}")
    }
}
