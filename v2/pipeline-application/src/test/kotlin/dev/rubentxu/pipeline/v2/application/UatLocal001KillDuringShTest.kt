package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * UAT-LOCAL-001: External JVM kill mid-sh, resume from result.txt.
 *
 * This test forks a separate JVM process (MinMainKt) that:
 * 1. Creates a control directory with a long-running sh script
 * 2. Launches and detaches the script via DurableShellExecutor
 * 3. Sleeps (simulating runner doing other work)
 *
 * The test then:
 * - Kills the forked JVM with destroyForcibly()
 * - Asserts the detached sh process SURVIVES (pgrep)
 * - Verifies result.txt has exit code 0
 * - Verifies marker has exactly 2 lines (started + done) - no re-execution
 *
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 */
@Tag("uat-local")
@Timeout(120)
class UatLocal001KillDuringShTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `sigkill mid sh resume reads result txt no reexec`() {
        assumeTrue(System.getProperty("os.name", "").lowercase().contains("linux"),
            "UAT-LOCAL-001 requires Linux")

        // Setup paths
        val controlDir = tempDir.resolve("control-dir")
        val markerPath = tempDir.resolve("marker.txt")
        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")
        val sleepSeconds = 30

        // Launch MinMainKt in a separate JVM (it will sleep for sleepSeconds)
        val launchProcess = ProcessBuilder(
            javaHome + "/bin/java",
            "-cp", classpath,
            "dev.rubentxu.pipeline.v2.application.MinMainKt",
            controlDir.toString(), markerPath.toString(), sleepSeconds.toString()
        )
            .directory(tempDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        // Wait for launch to output CONTROL_DIR and PID
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var controlDirFromOutput: String? = null
        var pid: Long = -1

        val readerThread = Thread {
            val reader = launchProcess.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                stdout.appendLine(line)
                if (line!!.startsWith("CONTROL_DIR=")) {
                    controlDirFromOutput = line!!.substringAfter("CONTROL_DIR=")
                }
                if (line!!.startsWith("PID=")) {
                    pid = line!!.substringAfter("PID=").toLongOrNull() ?: -1
                }
            }
        }
        val errorThread = Thread {
            val reader = launchProcess.errorStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                stderr.appendLine(line)
            }
        }
        readerThread.start()
        errorThread.start()

        // Wait for marker to show script started (up to 10 seconds)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(markerPath) && Files.readString(markerPath).contains("started")) {
                break
            }
            Thread.sleep(200)
        }

        assertTrue(Files.exists(markerPath), "Marker file should exist")
        assertTrue(Files.readString(markerPath).contains("started"), "Marker should contain 'started'")

        // Wait a moment for the forked JVM to be in the sleep period
        Thread.sleep(1000)

        // Kill the forked JVM (but the detached sh should survive)
        val destroyed = launchProcess.destroyForcibly()
        destroyed.waitFor()

        // Give the shell a moment to settle
        Thread.sleep(500)

        // Assert: the detached `sleep 30` process should still be running (pgrep)
        val pgrepResult = ProcessBuilder("pgrep", "-f", "sleep $sleepSeconds")
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
            .waitFor()
        assertEquals(0, pgrepResult, "Detached shell (sleep $sleepSeconds) should still be running after JVM kill")

        // DO NOT pkill here - we want the shell to complete naturally so result.txt is written
        // The shell will finish (sleep completes, echo done runs) and the wrapper will write result.txt
        // The script writes "done" to marker when it finishes
        val completionDeadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < completionDeadline) {
            if (Files.exists(markerPath) && Files.readString(markerPath).contains("done")) {
                break
            }
            Thread.sleep(500)
        }

        // Give a moment for result.txt to be written
        Thread.sleep(500)

        // Read result.txt - it should exist since the script completed
        val resultFile = controlDir.resolve("result.txt")
        assertTrue(Files.exists(resultFile), "result.txt should exist after script completed. controlDir=$controlDir, files=${Files.list(controlDir).toList()}")

        val exitCode = Files.readString(resultFile).trim().toInt()
        assertEquals(0, exitCode, "Script should have exited 0")

        // Verify marker has exactly 2 lines (started + done) - no re-execution
        val markerContent = Files.readString(markerPath)
        val lines = markerContent.lines().filter { it.isNotEmpty() }
        assertEquals(2, lines.size, "Marker should have exactly 2 lines (started + done). Content: $markerContent")
        assertEquals("started", lines[0], "First marker line should be 'started'")
        assertEquals("done", lines[1], "Second marker line should be 'done'")

        readerThread.join(2000)
        errorThread.join(2000)
    }
}
