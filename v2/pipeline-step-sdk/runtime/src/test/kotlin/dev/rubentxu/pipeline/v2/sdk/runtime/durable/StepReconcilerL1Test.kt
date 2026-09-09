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
        val logFile = DurableShellFiles.consoleLog(controlDir)
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
        val logFile = DurableShellFiles.consoleLog(controlDir)
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

    @Test
    fun `classify timedOut when timeout flag present beats heartbeat staleness`() {
        assumeLinux()
        // Clock set to now, log file is old, but timeout.flag exists
        // Per TMO-S-005: timeout.flag written BEFORE kill, so it takes precedence
        val now = Instant.now()
        val staleTime = now.minusSeconds(config.heartbeatCheckInterval + config.heartbeatMinimumDelta + 10)
        val clock = FakeClock(now)
        val reconciler = StepReconcilerL1(clock, tempDir, config)

        val controlDir = tempDir.resolve("test-timeout")
        Files.createDirectories(controlDir)

        // Create timeout.flag
        val timeoutFlag = controlDir.resolve("timeout.flag")
        Files.writeString(timeoutFlag, System.currentTimeMillis().toString())

        // Create old log file
        val logFile = DurableShellFiles.consoleLog(controlDir)
        Files.createFile(logFile)
        Files.setLastModifiedTime(logFile, java.nio.file.attribute.FileTime.from(staleTime))

        val classification = reconciler.classifyControlDir(controlDir)

        // timeout.flag takes precedence over heartbeat staleness
        assertTrue(classification is StepReconcilerL1.Classification.TimedOut,
            "Expected TimedOut when timeout.flag present, got: $classification")
        val timedOut = classification as StepReconcilerL1.Classification.TimedOut
        assertEquals(controlDir, timedOut.controlDir)
        assertEquals(logFile, timedOut.logPath)
    }

    @Test
    fun `classify timedOut when timeout flag present beats result missing`() {
        assumeLinux()
        // Clock set to now, result.txt doesn't exist, but timeout.flag exists
        // This is the scenario where watchdog killed the process before result was written
        val now = Instant.now()
        val clock = FakeClock(now)
        val reconciler = StepReconcilerL1(clock, tempDir, config)

        val controlDir = tempDir.resolve("test-timeout-no-result")
        Files.createDirectories(controlDir)

        // Create timeout.flag but no result.txt
        val timeoutFlag = controlDir.resolve("timeout.flag")
        Files.writeString(timeoutFlag, System.currentTimeMillis().toString())

        val classification = reconciler.classifyControlDir(controlDir)

        assertTrue(classification is StepReconcilerL1.Classification.TimedOut,
            "Expected TimedOut when timeout.flag present, got: $classification")
    }

    @Test
    fun `shouldRerun returns false for FAILED_TIMEOUT status`() {
        val clock = FakeClock(Instant.now())
        val reconciler = StepReconcilerL1(clock, tempDir, config)

        // FAILED_TIMEOUT is terminal - should not re-run
        assertFalse(reconciler.shouldRerun(dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.FAILED_TIMEOUT))
    }

    @Test
    fun `classify reattach when legacy jenkins log present but console log absent (compat)`() {
        assumeLinux()
        val now = Instant.now()
        val clock = FakeClock(now)
        val reconciler = StepReconcilerL1(clock, tempDir, config)

        val controlDir = tempDir.resolve("test-legacy-reattach")
        Files.createDirectories(controlDir)

        // Only the legacy jenkins-log.txt exists (pre-rename durable operation); no console.log.
        // resolveConsoleLog must fall back to it so heartbeat freshness is still observed.
        val legacyLog = controlDir.resolve("jenkins-log.txt")
        Files.createFile(legacyLog)
        Files.setLastModifiedTime(legacyLog, java.nio.file.attribute.FileTime.from(now))

        val classification = reconciler.classifyControlDir(controlDir)

        assertTrue(classification is StepReconcilerL1.Classification.Reattach,
            "legacy jenkins-log.txt must still be recoverable via the read-compatibility fallback, got: $classification")
    }

    @Test
    fun `heartbeat freshness prefers console log over legacy when both exist (precedence)`() {
        assumeLinux()
        val now = Instant.now()
        val clock = FakeClock(now)
        val reconciler = StepReconcilerL1(clock, tempDir, config)

        val controlDir = tempDir.resolve("test-precedence")
        Files.createDirectories(controlDir)

        // console.log is fresh; legacy jenkins-log.txt is stale. resolveConsoleLog must pick
        // console.log, so heartbeat reads as fresh (Reattach). If the legacy stale file won, it
        // would classify as Lost.
        val consoleLog = DurableShellFiles.consoleLog(controlDir)
        Files.createFile(consoleLog)
        Files.setLastModifiedTime(consoleLog, java.nio.file.attribute.FileTime.from(now))
        val staleTime = now.minusSeconds(config.heartbeatCheckInterval + config.heartbeatMinimumDelta + 10)
        val legacyLog = controlDir.resolve("jenkins-log.txt")
        Files.createFile(legacyLog)
        Files.setLastModifiedTime(legacyLog, java.nio.file.attribute.FileTime.from(staleTime))

        val classification = reconciler.classifyControlDir(controlDir)

        assertTrue(classification is StepReconcilerL1.Classification.Reattach,
            "console.log must take precedence over a stale legacy jenkins-log.txt, got: $classification")
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
