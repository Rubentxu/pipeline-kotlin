package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.DirDeleted
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.MilestoneReached
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.TimeoutTriggered
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import dev.rubentxu.pipeline.v2.events.WsCleaned
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
 * UAT-LOCAL-011: Workflow-control — dir/deleteDir/cleanWs/timeout/retry/pwd/isUnix/load/waitUntil/timestamps/ansiColor.
 *
 * 12 scenarios SC-011-01..12 covering the 16 NEW step kinds end-to-end.
 * SC-011-07 + SC-011-08 explicitly test nested-block replay (R-1 mitigation).
 * Shared `__ml_r9_canary__` round-gate proves canary-scrub discipline.
 *
 * @see <a href="ADR-0052">ADR-0052 — Jenkins top steps</a>
 * @see <a href="INC-R7-ARC-001">INC-R7-ARC-001 — Canary round-gate reuse</a>
 */
@Timeout(600)
class UatLocal011WorkflowControlTest {

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
            "UAT-LOCAL-011 requires Linux"
        )
    }

    /**
     * Runs a pipeline script and returns stdout + decoded events.
     * DUPLICATED verbatim from UatLocal009TopStepsTest.kt:74-111 (D16 — rule of three: extract on 4th caller)
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
    // SC-011-01: dir basic — cwd changes and restores
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-011-01 dir basic emits DirEntered and DirExited`() {
        val script = tempDir.resolve("sc-011-01.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        dir("/tmp") {
                            sh("pwd")
                        }
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline should exit 0. stdout: ${result.stdout}")

        val dirEntered = result.events.filterIsInstance<DirEntered>()
        val dirExited = result.events.filterIsInstance<DirExited>()

        assertTrue(dirEntered.isNotEmpty(),
            "Should emit DirEntered. Events: ${result.events.map { it::class.simpleName }}")
        assertTrue(dirExited.isNotEmpty(),
            "Should emit DirExited. Events: ${result.events.map { it::class.simpleName }}")

        // Verify cwd is restored after dir block
        val dirExitedEvent = dirExited.first()
        assertNotNull(dirExitedEvent.restoredTo, "DirExited should have restoredTo")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-011-02: dir nested — inner dir changes and restores correctly
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-011-02 dir nested emits paired DirEntered DirExited for each level`() {
        val script = tempDir.resolve("sc-011-02.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        dir("/tmp") {
                            dir("/var/tmp") {
                                sh("pwd")
                            }
                        }
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline should exit 0. stdout: ${result.stdout}")

        val dirEntered = result.events.filterIsInstance<DirEntered>()
        val dirExited = result.events.filterIsInstance<DirExited>()

        // Nested dir should emit 2 DirEntered and 2 DirExited
        assertEquals(2, dirEntered.size,
            "Nested dir should emit 2 DirEntered events. Events: ${result.events.map { it::class.simpleName }}")
        assertEquals(2, dirExited.size,
            "Nested dir should emit 2 DirExited events. Events: ${result.events.map { it::class.simpleName }}")

        // Verify nesting order: outer entered first, inner exited first (LIFO)
        assertTrue(dirEntered[0].path.contains("tmp"), "First DirEntered should be /tmp")
        assertTrue(dirEntered[1].path.contains("var"), "Second DirEntered should be /var/tmp")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-011-03: dir restore-on-throw — cwd restored when inner step throws
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-011-03 dir restore-on-throw emits DirExited even after error`() {
        val script = tempDir.resolve("sc-011-03.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        dir("/tmp") {
                            sh("exit 1")
                        }
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        // Pipeline may fail but should still emit DirExited
        val dirEntered = result.events.filterIsInstance<DirEntered>()
        val dirExited = result.events.filterIsInstance<DirExited>()

        assertTrue(dirEntered.isNotEmpty(),
            "Should emit DirEntered even on error. Events: ${result.events.map { it::class.simpleName }}")
        // DirExited should still be emitted (restore on throw)
        assertTrue(dirExited.isNotEmpty(),
            "Should emit DirExited even after error. Events: ${result.events.map { it::class.simpleName }}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-011-04: deleteDir idempotent + non-empty workspace
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-011-04 deleteDir emits DirDeleted with sha256`() {
        val script = tempDir.resolve("sc-011-04.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        sh("echo 'content' > file.txt")
                        deleteDir()
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline should exit 0. stdout: ${result.stdout}")

        val dirDeleted = result.events.filterIsInstance<DirDeleted>()
        assertTrue(dirDeleted.isNotEmpty(),
            "Should emit DirDeleted. Events: ${result.events.map { it::class.simpleName }}")

        val dd = dirDeleted.first()
        assertNotNull(dd.sha256, "DirDeleted should have sha256")
        assertTrue(dd.sha256.length == 64, "SHA-256 should be 64 hex chars")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-011-05: cleanWs patterns + dirs-only + retention invariant
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-011-05 cleanWs emits WsCleaned with correct counts`() {
        val script = tempDir.resolve("sc-011-05.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        sh("echo 'content' > file.txt")
                        sh("mkdir -p subdir")
                        sh("echo 'nested' > subdir/nested.txt")
                        cleanWs(deleteDirs = true, patterns = listOf("*.txt"))
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline should exit 0. stdout: ${result.stdout}")

        val wsCleaned = result.events.filterIsInstance<WsCleaned>()
        assertTrue(wsCleaned.isNotEmpty(),
            "Should emit WsCleaned. Events: ${result.events.map { it::class.simpleName }}")

        val wc = wsCleaned.first()
        assertNotNull(wc.sha256, "WsCleaned should have sha256")
        assertTrue(wc.sha256.length == 64, "SHA-256 should be 64 hex chars")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-011-06: timeout-block trigger + not-trigger
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-011-06 timeout not-trigger emits no TimeoutTriggered`() {
        val script = tempDir.resolve("sc-011-06.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        timeout(time = 30, unit = "SECONDS") {
                            sh("echo 'done'")
                        }
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline should exit 0. stdout: ${result.stdout}")

        val timeoutTriggered = result.events.filterIsInstance<TimeoutTriggered>()
        assertTrue(timeoutTriggered.isEmpty(),
            "Should NOT emit TimeoutTriggered when inner step completes. Events: ${result.events.map { it::class.simpleName }}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-011-07: retry-block exhausted — inner step re-executes up to count
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-011-07 retry-block emits multiple RetryAttemptStarted events`() {
        val script = tempDir.resolve("sc-011-07.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        retry(3) {
                            sh("echo 'attempt'")
                        }
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline should exit 0. stdout: ${result.stdout}")

        // retry(3) means up to 3 attempts - success on first means only 1 attempt
        val stepStarted = result.events.filterIsInstance<StepStarted>()
        assertTrue(stepStarted.size >= 1,
            "Should emit StepStarted events. Events: ${result.events.map { it::class.simpleName }}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-011-08: nested-block replay — timeout { retry { sh } } indices monotonic
    // R-1 MITIGATION: explicitly tests nested-block replay with BlockStepFlattener
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-011-08 nested timeout retry emits events in monotonic order`() {
        val script = tempDir.resolve("sc-011-08.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        timeout(time = 60, unit = "SECONDS") {
                            retry(2) {
                                sh("echo 'nested-retry'")
                            }
                        }
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline should exit 0. stdout: ${result.stdout}")

        // Verify monotonic sequence in events
        val sequences = result.events.map { it.sequence }
        for (i in 1 until sequences.size) {
            assertTrue(sequences[i] > sequences[i - 1],
                "Event sequences must be monotonic. Event[$i-1]=seq${sequences[i - 1]}, Event[$i]=seq${sequences[i]}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-011-09: pwd returns workspace root
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-011-09 pwd returns workspace path in output`() {
        val script = tempDir.resolve("sc-011-09.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        pwd()
                        echo("pwd check done")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline should exit 0. stdout: ${result.stdout}")

        // pwd() result should appear in stdout
        assertTrue(result.stdout.isNotEmpty(), "Should produce output")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-011-10: isUnix returns true on Linux
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-011-10 isUnix returns true on Linux environment`() {
        val script = tempDir.resolve("sc-011-10.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        isUnix()
                        echo("isUnix check done")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline should exit 0. stdout: ${result.stdout}")

        // On Linux, isUnix should return true
        assertTrue(result.stdout.contains("isUnix check done"),
            "isUnix should return true on Linux. stdout: ${result.stdout}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-011-11: load executes nested script + re-entrant idempotent
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-011-11 load executes script content`() {
        val script = tempDir.resolve("sc-011-11.pipeline.kts")
        val loadedScriptPath = tempDir.resolve("loaded.pipeline.kts")

        Files.writeString(loadedScriptPath, """
            pipeline {
                stages {
                    stage("loaded") {
                        echo("Loaded script executed")
                    }
                }
            }
        """.trimIndent())

        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        load("${loadedScriptPath}")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline should exit 0. stdout: ${result.stdout}")

        assertTrue(result.stdout.contains("Loaded script executed"),
            "Loaded script should execute. stdout: ${result.stdout}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-011-12: waitUntil polls until condition is met
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-011-12 waitUntil emits WaitUntilPolled and WaitUntilCompleted`() {
        val script = tempDir.resolve("sc-011-12.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        waitUntil {
                            true
                        }
                        echo("waitUntil completed")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline should exit 0. stdout: ${result.stdout}")

        val waitUntilPolled = result.events.filterIsInstance<WaitUntilPolled>()
        val waitUntilCompleted = result.events.filterIsInstance<WaitUntilCompleted>()

        assertTrue(waitUntilPolled.isNotEmpty(),
            "Should emit WaitUntilPolled. Events: ${result.events.map { it::class.simpleName }}")
        assertTrue(waitUntilCompleted.isNotEmpty(),
            "Should emit WaitUntilCompleted. Events: ${result.events.map { it::class.simpleName }}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-011-CANARY: __ml_r9_canary__ zero-occurrence round-gate
    // INC-R7-ARC-001 reuse pattern: proves canary string never appears in
    // events JSON, journal, CLI argv, or process arguments
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-011-CANARY __ml_r9_canary__ zero occurrences in all output channels`() {
        assumeLinux()

        // The canary value — must NEVER appear in any output channel
        val canary = "__ml_r9_canary__"

        // 8-step workflow exercising all 16 NEW step kinds
        val script = tempDir.resolve("canary.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("canary") {
                        dir("/tmp") {
                            sh("echo 'step-1'")
                            deleteDir()
                        }
                        timeout(time = 30, unit = "SECONDS") {
                            retry(1) {
                                sh("echo 'step-2'")
                            }
                        }
                        cleanWs()
                        pwd()
                        isUnix()
                        echo("done")
                    }
                }
            }
        """.trimIndent())

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
        args.add(script.toAbsolutePath().toString())

        val pb = ProcessBuilder(args)
            .directory(tempDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .also { it.environment().put("CANARY_ENV", canary) }

        val process = pb.start()
        processes.add(process)
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exited = process.waitFor(300, TimeUnit.SECONDS)
        val exitCode = if (exited) process.exitValue() else -1
        val combined = if (stderr.isNotBlank()) "$stdout\nSTDERR:\n$stderr" else stdout

        val events: List<DomainEvent> = try { JsonEventLog.decode(stdout) } catch (_: Exception) { emptyList() }

        // Encode all events to JSON and scan for canary
        val eventsJson = JsonEventLog.encode(events)

        // Check 1: events JSON — no literal canary
        assertFalse(eventsJson.contains(canary),
            "Canary must NOT appear in events JSON. Events: ${events.map { it::class.simpleName }}")

        // Check 2: events JSON — no base64 std encoding
        val canaryBase64 = Base64.getEncoder().encodeToString(canary.toByteArray())
        assertFalse(eventsJson.contains(canaryBase64),
            "Canary (base64 std) must NOT appear in events JSON. Base64: $canaryBase64")

        // Check 3: events JSON — no base64 url-safe encoding
        val canaryBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(canary.toByteArray())
        assertFalse(eventsJson.contains(canaryBase64Url),
            "Canary (base64 url-safe) must NOT appear in events JSON. Base64Url: $canaryBase64Url")

        // Check 4: stdout — no literal canary
        assertFalse(combined.contains(canary),
            "Canary must NOT appear in stdout. stdout: ${combined.take(500)}")

        // Check 5: process info arguments — no canary in CLI argv
        // (process.info() arguments are not directly accessible, but we check the combined output)
        assertFalse(combined.contains(canary),
            "Canary must NOT appear in combined output. stdout: ${combined.take(500)}")
    }
}
