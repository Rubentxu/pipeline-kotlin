package dev.rubentxu.pipeline.v2.sdk.runtime.durable.task

import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import java.util.Collections

/**
 * Result of a captured run: the task executed through the runtime with its
 * output accumulated as decoded text.
 *
 * The accumulation happens at the CALLER level, after the runtime has
 * already delivered the output as bounded chunks — the O(chunk) invariant
 * is a runtime guarantee; captured runs additionally opt into holding the
 * (small) whole output for programmatic consumption.
 */
data class CapturedRun(
    val exitCode: Int,
    val timedOut: Boolean,
    val stdout: String,
    val stderr: String,
) {
    val succeeded: Boolean get() = exitCode == 0 && !timedOut
    val combinedOutput: String get() = stdout + stderr
}

/**
 * Runs [request] through the runtime and captures stdout/stderr as decoded
 * text. Convenience for small-output operations (git, tar listing) that
 * need programmatic access to what the process printed.
 *
 * Thread safety: the runtime delivers chunks from two concurrent pump
 * contexts; collection is synchronized.
 */
suspend fun DurableTaskRuntime.runCaptured(request: TaskExecutionRequest): CapturedRun {
    val chunks: MutableList<OutputChunk> = Collections.synchronizedList(mutableListOf())
    val sink = ExecutionOutputSink { chunk -> chunks.add(chunk) }
    val result = execute(request, sink)
    return CapturedRun(
        exitCode = result.exitCode,
        timedOut = result.timedOut,
        stdout = chunks.filter { it.stream == TaskStream.STDOUT }
            .joinToString("") { it.data.toString(Charsets.UTF_8) },
        stderr = chunks.filter { it.stream == TaskStream.STDERR }
            .joinToString("") { it.data.toString(Charsets.UTF_8) },
    )
}
