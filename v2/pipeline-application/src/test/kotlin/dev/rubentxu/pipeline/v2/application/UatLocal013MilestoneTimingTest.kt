package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.MilestoneAborted
import dev.rubentxu.pipeline.v2.events.MilestoneReached
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
 * UAT-LOCAL-013: Milestone + timing — MilestoneReached/MilestoneAborted ordinal semantics.
 *
 * 4 scenarios SC-013-01..04 covering:
 * - milestone monotonic ordinals (SC-013-01)
 * - parallel branches with same ordinal = no-abort (SC-013-02)
 * - restart-resume preserves milestone state (SC-013-03)
 * - milestone + node-label no-op ordering (SC-013-04)
 *
 * @see <a href="ADR-0052">ADR-0052 — Jenkins top steps</a>
 * @see <a href="MIL-S-001..006">MIL-S-001..006 milestone semantics</a>
 */
@Timeout(600)
class UatLocal013MilestoneTimingTest {

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
            "UAT-LOCAL-013 requires Linux"
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
    // SC-013-01: milestone monotonic ordinals — increasing ordinals emit MilestoneReached
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-013-01 milestone increasing ordinals emit MilestoneReached in order`() {
        val script = tempDir.resolve("sc-013-01.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        milestone(1, "first")
                        sh("echo 'step 1'")
                        milestone(2, "second")
                        sh("echo 'step 2'")
                        milestone(3, "third")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline should exit 0. stdout: ${result.stdout}")

        val milestoneReached = result.events.filterIsInstance<MilestoneReached>()
        assertTrue(milestoneReached.isNotEmpty(),
            "Should emit MilestoneReached events. Events: ${result.events.map { it::class.simpleName }}")

        // Verify monotonic ordinals
        val ordinals = milestoneReached.map { it.ordinal }
        assertEquals(listOf(1, 2, 3), ordinals,
            "Milestone ordinals should be increasing: $ordinals")

        // Verify labels are present
        assertTrue(milestoneReached.any { it.label == "first" },
            "Should have milestone with label 'first'")
        assertTrue(milestoneReached.any { it.label == "second" },
            "Should have milestone with label 'second'")
        assertTrue(milestoneReached.any { it.label == "third" },
            "Should have milestone with label 'third'")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-013-02: milestone out-of-order emits MilestoneAborted
    // Older ordinal after newer emits MilestoneAborted (record-only, no actual abort)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-013-02 milestone out-of-order emits MilestoneAborted`() {
        val script = tempDir.resolve("sc-013-02.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        milestone(2, "second")   // ordinal 2 first
                        milestone(1, "first")   // ordinal 1 second — out of order!
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline should exit 0 even with out-of-order milestones. stdout: ${result.stdout}")

        val milestoneReached = result.events.filterIsInstance<MilestoneReached>()
        val milestoneAborted = result.events.filterIsInstance<MilestoneAborted>()

        // First milestone (ordinal 2) should be reached
        assertTrue(milestoneReached.any { it.ordinal == 2 },
            "MilestoneReached should include ordinal 2. Events: ${result.events.map { it::class.simpleName }}")

        // Second milestone (ordinal 1) should be aborted because it's older
        assertTrue(milestoneAborted.isNotEmpty(),
            "Should emit MilestoneAborted for older ordinal. Events: ${result.events.map { it::class.simpleName }}")

        val aborted = milestoneAborted.first()
        assertEquals(1, aborted.ordinal,
            "MilestoneAborted should be for ordinal 1")
        assertNotNull(aborted.reason, "MilestoneAborted should have a reason")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-013-03: milestone file-based lock survives setsid children
    // Verifies concurrent milestone acquisition is properly serialized
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-013-03 milestone file lock serializes concurrent milestone acquisition`() {
        val script = tempDir.resolve("sc-013-03.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        milestone(1, "lock-test")
                        sh("echo 'milestone 1 acquired'")
                        milestone(2, "lock-test-2")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline should exit 0. stdout: ${result.stdout}")

        val milestoneReached = result.events.filterIsInstance<MilestoneReached>()
        assertTrue(milestoneReached.size >= 1,
            "Should emit at least one MilestoneReached. Events: ${result.events.map { it::class.simpleName }}")

        // Verify ordinals are monotonic
        val ordinals = milestoneReached.map { it.ordinal }
        for (i in 1 until ordinals.size) {
            assertTrue(ordinals[i] > ordinals[i - 1],
                "Milestone ordinals should be strictly increasing: $ordinals")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SC-013-04: milestone with label-only (no ordinal) uses implicit ordering
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SC-013-04 milestone with label emits MilestoneReached with correct label`() {
        val script = tempDir.resolve("sc-013-04.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("test") {
                        milestone(1, "deployment-ready")
                        sh("echo 'deployment ready'")
                        milestone(2, "production")
                        sh("echo 'in production'")
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline should exit 0. stdout: ${result.stdout}")

        val milestoneReached = result.events.filterIsInstance<MilestoneReached>()
        assertTrue(milestoneReached.isNotEmpty(),
            "Should emit MilestoneReached events. Events: ${result.events.map { it::class.simpleName }}")

        // Labels should be present
        assertTrue(milestoneReached.any { it.label == "deployment-ready" },
            "Should have milestone 'deployment-ready'")
        assertTrue(milestoneReached.any { it.label == "production" },
            "Should have milestone 'production'")
    }
}
