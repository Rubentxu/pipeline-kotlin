package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

@Timeout(30)
class DurableShellTerminalAdapterTest {
    @TempDir
    lateinit var tempDir: Path

    private val config = DurableShConfig.fromSystemProperties()

    @Test
    fun `executeTerminal cannot return a snapshot and preserves exit zero`() {
        val terminal: DurableTaskTerminal = DurableShellExecutor().executeTerminal(
            controlDir = tempDir.resolve("exit-zero"),
            scriptContent = "exit 0",
            opId = "terminal-zero",
            shOptions = ShOptions.EMPTY,
            config = config,
        )

        assertTrue(terminal is DurableTaskTerminal.Exited)
        val exited = terminal as DurableTaskTerminal.Exited
        assertEquals(0, exited.exitCode)
    }

    @Test
    fun `executeTerminal preserves nonzero exit code`() {
        val terminal: DurableTaskTerminal = DurableShellExecutor().executeTerminal(
            controlDir = tempDir.resolve("exit-nonzero"),
            scriptContent = "exit 23",
            opId = "terminal-nonzero",
            shOptions = ShOptions.EMPTY,
            config = config,
        )

        assertTrue(terminal is DurableTaskTerminal.Exited)
        val exited = terminal as DurableTaskTerminal.Exited
        assertEquals(23, exited.exitCode)
    }

    @Test
    fun `executeTerminal converts a watchdog timeout to cancellation`() {
        val terminal = DurableShellExecutor().executeTerminal(
            controlDir = tempDir.resolve("terminal-timeout"),
            scriptContent = "sleep 5",
            opId = "terminal-timeout",
            shOptions = ShOptions(
                workspaceRoot = tempDir,
                captureStdout = false,
                timeoutMs = 500,
                env = emptyMap(),
            ),
            config = config,
        )

        assertTrue(terminal is DurableTaskTerminal.Cancelled)
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind.TIMEOUT,
            (terminal as DurableTaskTerminal.Cancelled).interruption.kind,
        )
    }

    @Test
    fun `caught launch failure becomes a serializable failure record`() {
        val controlFile = tempDir.resolve("not-a-control-directory")
        Files.writeString(controlFile, "not a directory")

        val terminal = DurableShellExecutor().executeTerminal(
            controlDir = controlFile,
            scriptContent = "exit 0",
            opId = "terminal-launch-failure",
            shOptions = ShOptions.EMPTY,
            config = config,
        )

        assertTrue(terminal is DurableTaskTerminal.LaunchFailed)
        val failure = (terminal as DurableTaskTerminal.LaunchFailed).failure
        assertEquals(FailureKind.INFRASTRUCTURE, failure.kind)
        assertEquals(FailureOrigin.LAUNCHER, failure.origin)
        assertEquals("terminal-launch-failure", failure.operationId)
    }

    @Test
    fun `stale heartbeat reconciliation returns lost with a failure record`() {
        val opId = "terminal-lost"
        val controlDir = tempDir.resolve(opId)
        Files.createDirectories(controlDir)
        val now = Instant.now()
        val logFile = DurableShellFiles.consoleLog(controlDir)
        Files.createFile(logFile)
        Files.setLastModifiedTime(
            logFile,
            FileTime.from(now.minusSeconds(config.heartbeatCheckInterval + config.heartbeatMinimumDelta + 1)),
        )
        val reconciler = StepReconcilerL1(FixedClock(now), tempDir, config)

        val terminal: DurableTaskTerminal = requireNotNull(
            reconciler.terminalFromReconciliation(opId, controlDir),
        )

        assertTrue(terminal is DurableTaskTerminal.Lost)
        val failure = (terminal as DurableTaskTerminal.Lost).failure
        assertEquals(FailureKind.INFRASTRUCTURE, failure.kind)
        assertEquals(FailureOrigin.RECONCILIATION, failure.origin)
        assertEquals(opId, failure.operationId)
    }

    @Test
    fun `legacy result adapter retains complete exit behavior`() {
        val legacy = DurableTaskTerminal.Exited(
            exitCode = 23,
            output = dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput(tempDir.toString()),
        ).toLegacyShellResult(tempDir)

        assertEquals(DurableShellState.COMPLETE, legacy.state)
        assertEquals(23, legacy.exitCode)
        assertEquals(tempDir, legacy.controlDir)
    }

    @Test
    fun `legacy complete result preserves its exit through the terminal adapter`() {
        val legacy = DurableShellResult(
            state = DurableShellState.COMPLETE,
            exitCode = 23,
            controlDir = tempDir,
            capturedStdout = "captured",
        )

        assertEquals(legacy, legacy.throughTerminalAdapter("legacy-complete"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `compatibility projection preserves the canonical executor terminal semantics`() {
        val canonical = DurableShellExecutor().execute(
            controlDir = tempDir.resolve("canonical-executor"),
            scriptContent = "printf 'canonical-output'; exit 23",
            opId = "canonical-executor",
            shOptions = ShOptions.EMPTY,
        )
        val compatibility = executeDurableShell(
            controlDir = tempDir.resolve("compatibility-projection"),
            scriptContent = "printf 'canonical-output'; exit 23",
            opId = "compatibility-projection",
            config = config,
        )

        assertEquals(canonical.state, compatibility.state)
        assertEquals(canonical.exitCode, compatibility.exitCode)
        assertEquals(DurableShellState.COMPLETE, compatibility.state)
        assertEquals(23, compatibility.exitCode)
        assertEquals("canonical-output", compatibility.capturedStdout)
    }

    @Test
    fun `legacy terminal failures retain their typed terminal classification`() {
        val launchFailed = DurableShellResult(
            state = DurableShellState.LAUNCH_FAILED,
            exitCode = -1,
            controlDir = tempDir,
        ).toDurableTaskTerminal("legacy-launch-failure")
        val lost = DurableShellResult(
            state = DurableShellState.LOST,
            exitCode = -1,
            controlDir = tempDir,
        ).toDurableTaskTerminal("legacy-lost")
        val timedOut = DurableShellResult(
            state = DurableShellState.TIMED_OUT,
            exitCode = -1,
            controlDir = tempDir,
        ).toDurableTaskTerminal("legacy-timeout")

        assertTrue(launchFailed is DurableTaskTerminal.LaunchFailed)
        assertEquals(FailureOrigin.LAUNCHER, (launchFailed as DurableTaskTerminal.LaunchFailed).failure.origin)
        assertTrue(lost is DurableTaskTerminal.Lost)
        assertEquals(FailureOrigin.RECONCILIATION, (lost as DurableTaskTerminal.Lost).failure.origin)
        assertTrue(timedOut is DurableTaskTerminal.Cancelled)
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind.TIMEOUT,
            (timedOut as DurableTaskTerminal.Cancelled).interruption.kind,
        )
    }

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
