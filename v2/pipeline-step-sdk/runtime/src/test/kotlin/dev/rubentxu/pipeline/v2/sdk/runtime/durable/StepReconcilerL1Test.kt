package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Tests for StepReconcilerL1.
 *
 * Verifies the classification logic:
 * - COMPLETE: result.txt exists
 * - REATTACH: result.txt missing but heartbeat fresh
 * - LOST: result.txt missing AND heartbeat stale
 */
class StepReconcilerL1Test {

    @TempDir
    lateinit var tempDir: Path

    private val config = DurableShConfig.fromSystemProperties()

    @Test
    fun `classify complete when result txt exists`() {
        assumeLinux()
        val clock = FakeClock(Instant.now())
        val reconciler = StepReconcilerL1(clock, tempDir, config)

        val controlDir = tempDir.resolve("test-complete")
        Files.createDirectories(controlDir)

        // Create result.txt
        val resultFile = controlDir.resolve("result.txt")
        Files.writeString(resultFile, "0")

        val classification = reconciler.classifyControlDir(controlDir)

        assertTrue(classification is StepReconcilerL1.Classification.Complete)
        assertEquals(0, (classification as StepReconcilerL1.Classification.Complete).exitCode)
    }

    @Test
    fun `classify reattach when result txt missing but heartbeat fresh`() {
        assumeLinux()
        val now = Instant.now()
        val clock = FakeClock(now)
        val reconciler = StepReconcilerL1(clock, tempDir, config)

        val controlDir = tempDir.resolve("test-reattach")
        Files.createDirectories(controlDir)

        // Create log file with recent modification time
        val logFile = controlDir.resolve("jenkins-log.txt")
        Files.createFile(logFile)
        Files.setLastModifiedTime(logFile, java.nio.file.attribute.FileTime.from(now))

        val classification = reconciler.classifyControlDir(controlDir)

        assertTrue(classification is StepReconcilerL1.Classification.Reattach)
    }

    @Test
    fun `classify lost when result txt missing and heartbeat stale`() {
        assumeLinux()
        // Clock set to now, but log file is old
        val now = Instant.now()
        val staleTime = now.minusSeconds(config.heartbeatCheckInterval + config.heartbeatMinimumDelta + 10)
        val clock = FakeClock(now)
        val reconciler = StepReconcilerL1(clock, tempDir, config)

        val controlDir = tempDir.resolve("test-lost")
        Files.createDirectories(controlDir)

        // Create log file with old modification time
        val logFile = controlDir.resolve("jenkins-log.txt")
        Files.createFile(logFile)
        Files.setLastModifiedTime(logFile, java.nio.file.attribute.FileTime.from(staleTime))

        val classification = reconciler.classifyControlDir(controlDir)

        assertTrue(classification is StepReconcilerL1.Classification.Lost)
    }

    @Test
    fun `classify lost when neither result nor log exists`() {
        assumeLinux()
        val clock = FakeClock(Instant.now())
        val reconciler = StepReconcilerL1(clock, tempDir, config)

        val controlDir = tempDir.resolve("test-neither")
        Files.createDirectories(controlDir)

        val classification = reconciler.classifyControlDir(controlDir)

        assertTrue(classification is StepReconcilerL1.Classification.Lost)
    }

    @Test
    fun `shouldRerun returns true for LOST status`() {
        val clock = FakeClock(Instant.now())
        val reconciler = StepReconcilerL1(clock, tempDir, config)

        assertTrue(reconciler.shouldRerun(dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.LOST))
    }

    @Test
    fun `shouldRerun returns false for SUCCEEDED status`() {
        val clock = FakeClock(Instant.now())
        val reconciler = StepReconcilerL1(clock, tempDir, config)

        assertFalse(reconciler.shouldRerun(dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED))
    }

    @Test
    fun `classifyRunning requires RUNNING status`() {
        val clock = FakeClock(Instant.now())
        val reconciler = StepReconcilerL1(clock, tempDir, config)

        assertThrows(IllegalArgumentException::class.java) {
            reconciler.classifyRunning(dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED, "op1")
        }
    }

    @Test
    fun `classify by opId uses correct control dir`() {
        assumeLinux()
        val now = Instant.now()
        val clock = FakeClock(now)
        val opId = "test-runId-s0-0"
        val reconciler = StepReconcilerL1(clock, tempDir, config)

        val controlDir = tempDir.resolve(opId)
        Files.createDirectories(controlDir)

        // Create result.txt
        val resultFile = controlDir.resolve("result.txt")
        Files.writeString(resultFile, "42")

        val classification = reconciler.classify(opId)

        assertTrue(classification is StepReconcilerL1.Classification.Complete)
        assertEquals(42, (classification as StepReconcilerL1.Classification.Complete).exitCode)
    }

    /**
     * Fake clock for testing - allows controlling time.
     */
    private class FakeClock(private var currentInstant: Instant) : Clock {
        override fun now(): Instant = currentInstant
    }

    private fun assumeLinux() {
        assumeTrue(System.getProperty("os.name", "").lowercase().contains("linux"),
            "Durable shell is Linux-only")
    }
}
