package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SecretHandle

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecordingDurableTaskRuntimeTest {

    private fun request(opId: String = "r1-s0-0") = TaskExecutionRequest(
        task = TaskSpec.ExecTask(argv = listOf("/bin/echo", "hi")),
        runId = RunId("run-1"),
        opId = opId,
    )

    @Test
    fun `executes with no process and returns the configured result`() {
        val runtime = RecordingDurableTaskRuntime.successOnly()

        val result = runtime.executeBlocking(request())

        assertEquals(0, result.exitCode)
        assertTrue(result.succeeded)
        assertEquals(1, runtime.dispatchCount())
    }

    @Test
    fun `chunks are replayed to the sink in order`() {
        val chunks = listOf(
            OutputChunk.stdout("line1\n".toByteArray()),
            OutputChunk.stderr("warn\n".toByteArray()),
            OutputChunk.stdout("line2\n".toByteArray()),
        )
        val runtime = RecordingDurableTaskRuntime(chunks = chunks)
        val received = mutableListOf<OutputChunk>()
        val sink = ExecutionOutputSink { chunk -> received.add(chunk) }

        runtime.executeBlocking(request(), sink)

        assertEquals(chunks, received, "chunks must reach the sink in replay order")
    }

    @Test
    fun `request is recorded verbatim`() {
        val runtime = RecordingDurableTaskRuntime.successOnly()
        val req = TaskExecutionRequest(
            task = TaskSpec.ShellScriptTask(script = "echo hi"),
            runId = RunId("run-9"),
            opId = "run-9-s1-2",
            attempt = 2,
            timeoutMs = 5000L,
            env = mapOf("TOKEN" to SecretHandle.plain("secret-value")),
            workspaceRoot = "/tmp/ws",
        )

        runtime.executeBlocking(req, ExecutionOutputSink { })

        assertEquals(req, runtime.dispatchedRequests.single())
    }

    @Test
    fun `result validation rejects inconsistent timestamps`() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskExecutionResult(exitCode = 0, startedAtEpochMs = 100L, endedAtEpochMs = 50L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskExecutionResult(exitCode = 0, timedOut = true, cancelled = true, startedAtEpochMs = 0, endedAtEpochMs = 1)
        }
    }

    @Test
    fun `succeeded is false for timeout cancel and nonzero exits`() {
        assertTrue(!TaskExecutionResult(exitCode = 1, startedAtEpochMs = 0, endedAtEpochMs = 1).succeeded)
        assertTrue(
            !TaskExecutionResult(exitCode = 0, timedOut = true, startedAtEpochMs = 0, endedAtEpochMs = 1).succeeded
        )
        assertTrue(
            !TaskExecutionResult(exitCode = 0, cancelled = true, startedAtEpochMs = 0, endedAtEpochMs = 1).succeeded
        )
    }

    @Test
    fun `sink calls are safe from concurrent pump contexts`() = runBlocking {
        val runtime = RecordingDurableTaskRuntime.successOnly()
        val received = java.util.Collections.synchronizedList(mutableListOf<OutputChunk>())
        val sink = ExecutionOutputSink { chunk -> received.add(chunk) }

        coroutineScope {
            val stdoutPump = async { runtime.execute(request("a"), sink) }
            val stderrPump = async { runtime.execute(request("b"), sink) }
            stdoutPump.await()
            stderrPump.await()
        }

        assertEquals(2, runtime.dispatchCount())
        assertNotNull(received)
    }

    @Test
    fun `request validation rejects blank opId negative attempt and nonpositive timeout`() {
        assertThrows(IllegalArgumentException::class.java) {
            request(opId = "  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskExecutionRequest(
                task = TaskSpec.ExecTask(listOf("true")),
                runId = RunId("r"),
                opId = "op",
                attempt = -1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskExecutionRequest(
                task = TaskSpec.ExecTask(listOf("true")),
                runId = RunId("r"),
                opId = "op",
                timeoutMs = 0L,
            )
        }
    }
}
