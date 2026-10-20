package dev.rubentxu.pipeline.v2.domain.durable

import kotlinx.coroutines.runBlocking

/**
 * Deterministic, test-friendly [DurableTaskRuntime]: records every request
 * (no process is ever spawned), replays a configurable sequence of output
 * chunks to the sink, and returns a configurable result.
 *
 * The moral equivalent of `RecordingStepDispatcher` / `MapPipelineCompiler`
 * — the test adapter for its port. Fully deterministic: same requests in,
 * same recorded log and results out.
 *
 * ## Thread-safety
 *
 * The call log is synchronized; the adapter safely serves concurrent
 * requests (M3 output-pump scenarios pump from two contexts).
 */
class RecordingDurableTaskRuntime(
    private val chunks: List<OutputChunk> = emptyList(),
    private val result: TaskExecutionResult =
        TaskExecutionResult(exitCode = 0, startedAtEpochMs = 0L, endedAtEpochMs = 1L),
) : DurableTaskRuntime {

    private val calls = mutableListOf<TaskExecutionRequest>()

    /** Requests dispatched so far, in dispatch order. */
    val dispatchedRequests: List<TaskExecutionRequest>
        get() = synchronized(calls) { calls.toList() }

    override suspend fun execute(request: TaskExecutionRequest, outputSink: ExecutionOutputSink): TaskExecutionResult {
        synchronized(calls) { calls += request }
        chunks.forEach { chunk -> outputSink.append(chunk) }
        return result
    }

    /** Number of executed requests (diagnostics/testing). */
    fun dispatchCount(): Int = synchronized(calls) { calls.size }

    companion object {
        /** Convenience factory: a runtime that always exits 0 with no output. */
        fun successOnly(): RecordingDurableTaskRuntime = RecordingDurableTaskRuntime()
    }
}

/**
 * Blocking convenience for tests that exercise the suspend port without a
 * coroutine test harness.
 */
fun DurableTaskRuntime.executeBlocking(
    request: TaskExecutionRequest,
    sink: ExecutionOutputSink = ExecutionOutputSink { },
): TaskExecutionResult = runBlocking { execute(request, sink) }
