package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The durable shell's control directory is a CROSS-PROCESS contract.
 *
 * **The guarantee.** The names inside a control directory are stable: the wrapper writes the
 * exit code where the executor polls for it, and where a later process — the reconciler, or an
 * operator — decides between "finished", "timed out" and "lost". If a name moves, the writer and
 * the reader stop meeting, and the symptom is a step that never finishes.
 *
 * **The observable capacity it serves.** A durable `sh` step can be executed, timed out, and
 * later reconciled from its control directory by another process. That capacity is exercised
 * end to end elsewhere; what is checked HERE is the thing that capacity rests on and that no
 * end-to-end scenario can localise: the names themselves, and the writer's binding to them.
 *
 * **Why this instrument and not another.** A process, a thread, a clock or a real filesystem are
 * not needed to observe a name. The reader side already has end-to-end coverage, and a second
 * instrument for the same guarantee would be duplication, not evidence. So this is a pure test:
 * no process, no thread, no clock, no file is touched. What it does add over the existing
 * coverage is the ability to tell WHICH name drifted.
 *
 * **Why the names are pinned at all.** Pinning a literal is normally a smell — it freezes an
 * implementation detail. These names are the exception: they are the durable format that another
 * process, and an operator with a directory in front of them, read. A change to one is therefore
 * an explicit decision, and this test is where that decision is recorded.
 */
class ShellProtocolLayoutTest {

    private val controlDir: Path = Path.of("/tmp/pipelinek-shell-protocol/op-42")

    private val protocol = ShellProtocolLayout(controlDir)

    @Test
    fun `the protocol names are the durable format`() {
        assertEquals("script.sh", ShellProtocolLayout.SCRIPT_FILE)
        assertEquals("script.sh.copy", ShellProtocolLayout.SCRIPT_COPY_FILE)
        assertEquals("wrapper.sh", ShellProtocolLayout.WRAPPER_FILE)
        assertEquals(".cookie", ShellProtocolLayout.COOKIE_FILE)
        assertEquals("result.txt", ShellProtocolLayout.RESULT_FILE)
        assertEquals("result.txt.tmp", ShellProtocolLayout.RESULT_TMP_FILE)
        assertEquals("output.txt", ShellProtocolLayout.OUTPUT_FILE)
        assertEquals("timeout.flag", ShellProtocolLayout.TIMEOUT_FLAG)
        assertEquals("agent.log", ShellProtocolLayout.AGENT_LOG)
        assertEquals("fifos", ShellProtocolLayout.FIFO_DIR)
        assertEquals("stdout.fifo", ShellProtocolLayout.STDOUT_FIFO)
        assertEquals("stderr.fifo", ShellProtocolLayout.STDERR_FIFO)
        assertEquals("agent.ready", ShellProtocolLayout.AGENT_READY_MARKER)
    }

    @Test
    fun `the exit code is published by a rename within one directory`() {
        // The wrapper writes the exit code to the temporary file and renames it onto the result.
        // A rename is atomic only WITHIN one directory, so the two files are siblings by
        // construction and not by convention.
        assertEquals(protocol.resultFile.parent, protocol.resultTmpFile.parent)
        assertNotEquals(protocol.resultFile, protocol.resultTmpFile)
    }

    @Test
    fun `no two roles of the control directory share a file name`() {
        // A shared name would make one role unreadable behind the other: a cookie that is also
        // the result would report "alive" for a step that already exited.
        val controlLevel = listOf(
            protocol.scriptFile,
            protocol.scriptCopyFile,
            protocol.wrapperFile,
            protocol.cookieFile,
            protocol.resultFile,
            protocol.resultTmpFile,
            protocol.outputFile,
            protocol.timeoutFlag,
            protocol.agentLog,
            protocol.consoleLog,
        ).map { it.fileName.toString() }

        val fifoLevel = listOf(
            protocol.stdoutFifo,
            protocol.stderrFifo,
            protocol.agentReadyMarker,
        ).map { it.fileName.toString() }

        assertEquals(controlLevel.size, controlLevel.toSet().size, "two control-dir roles share a name")
        assertEquals(fifoLevel.size, fifoLevel.toSet().size, "two FIFO roles share a name")
        assertTrue(
            controlLevel.none { it in fifoLevel },
            "a control-dir role and a FIFO role share a name",
        )
    }

    @Test
    fun `the console transcript name belongs to DurableShellFiles`() {
        // The transcript has ONE authority. The layout delegates to it instead of spelling the
        // name again, so a rename of the transcript cannot leave the layout pointing at a file
        // that nobody writes.
        assertEquals(DurableShellFiles.consoleLog(controlDir), protocol.consoleLog)
        assertEquals("console.log", protocol.consoleLog.fileName.toString())
    }

    @Test
    fun `the wrapper binds the protocol's paths and publishes the exit code through them`() {
        val wrapper = DurableShellExecutor().buildWrapperContent(
            protocol = protocol,
            scriptPath = protocol.scriptFile,
            heartbeatCheckInterval = 300L,
            captureStdout = false,
            opId = "op-42",
        )

        // The wrapper is the WRITER of the protocol, so its bindings are checked against the
        // authority rather than against a second copy of the names.
        assertTrue(
            wrapper.contains("COOKIE_FILE='${protocol.cookieFile}'"),
            "the wrapper does not bind the protocol's cookie file",
        )
        assertTrue(
            wrapper.contains("LOG_FILE='${protocol.consoleLog}'"),
            "the wrapper does not bind the protocol's console transcript",
        )
        assertTrue(
            wrapper.contains("RESULT_FILE='${protocol.resultFile}'"),
            "the wrapper does not bind the protocol's result file",
        )
        assertTrue(
            wrapper.contains("RESULT_TMP='${protocol.resultTmpFile}'"),
            "the wrapper does not bind the protocol's temporary result file",
        )
        assertTrue(
            wrapper.contains("echo \$EXIT_CODE > \"\$RESULT_TMP\""),
            "the wrapper does not write the exit code through the temporary file",
        )
        assertTrue(
            wrapper.contains("mv \"\$RESULT_TMP\" \"\$RESULT_FILE\""),
            "the wrapper does not publish the exit code by renaming onto the result file",
        )
    }
}
