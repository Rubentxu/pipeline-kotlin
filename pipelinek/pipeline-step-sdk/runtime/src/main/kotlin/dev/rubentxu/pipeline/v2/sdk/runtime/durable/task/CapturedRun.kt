package dev.rubentxu.pipeline.v2.sdk.runtime.durable.task

import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import dev.rubentxu.pipeline.v2.domain.durable.decideTaskTerminal
import java.util.Collections

/**
 * Result of a captured run: the task executed through the runtime with its
 * output accumulated as decoded text.
 *
 * The accumulation happens at the CALLER level, after the runtime has
 * already delivered the output as bounded chunks — the O(chunk) invariant
 * is a runtime guarantee; captured runs additionally opt into holding the
 * (small) whole output for programmatic consumption.
 *
 * ## Why both `timedOut` and `cancelled`
 *
 * `CapturedRun` exists as a coarse-grained data carrier for callers (git,
 * tar listing) that want a single struct rather than a sealed ADT. The
 * ADT-first rule for [dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult]
 * still applies at the construction site: [runCaptured] routes through
 * [decideTaskTerminal] so the boolean pair is read in exactly one place
 * (the decider), and the boolean fields here are translated from that
 * single decision. This is the same architectural rule that
 * `TaskTerminalDecisionFitnessTest` enforces, just for a parallel domain.
 */
data class CapturedRun(
    val exitCode: Int,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val stdout: String,
    val stderr: String,
) {
    val succeeded: Boolean get() = exitCode == 0 && !timedOut && !cancelled
    val combinedOutput: String get() = stdout + stderr
}

/**
 * Runs [request] through the runtime and captures stdout/stderr as decoded
 * text. Convenience for small-output operations (git, tar listing) that
 * need programmatic access to what the process printed.
 *
 * Thread safety: the runtime delivers chunks from two concurrent pump
 * contexts; collection is synchronized.
 *
 * Construction routes through [decideTaskTerminal] (the same ADT-first
 * seam that the rest of the codebase uses) so the boolean pair on
 * [dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult] is
 * consumed in exactly one place. Adding a future fourth case to the
 * decider is a compile error here, not a silent gap.
 */
suspend fun DurableTaskRuntime.runCaptured(request: TaskExecutionRequest): CapturedRun {
    val chunks: MutableList<OutputChunk> = Collections.synchronizedList(mutableListOf())
    val sink = ExecutionOutputSink { chunk -> chunks.add(chunk) }
    val result = execute(request, sink)
    val stdout = chunks.filter { it.stream == TaskStream.STDOUT }
        .joinToString("") { it.data.toString(Charsets.UTF_8) }
    val stderr = chunks.filter { it.stream == TaskStream.STDERR }
        .joinToString("") { it.data.toString(Charsets.UTF_8) }
    return when (decideTaskTerminal(result)) {
        is dev.rubentxu.pipeline.v2.domain.durable.TaskTerminalDecision.TimedOut ->
            CapturedRun(exitCode = result.exitCode, timedOut = true, cancelled = false, stdout = stdout, stderr = stderr)
        is dev.rubentxu.pipeline.v2.domain.durable.TaskTerminalDecision.Cancelled ->
            CapturedRun(exitCode = result.exitCode, timedOut = false, cancelled = true, stdout = stdout, stderr = stderr)
        is dev.rubentxu.pipeline.v2.domain.durable.TaskTerminalDecision.Exited ->
            CapturedRun(exitCode = result.exitCode, timedOut = false, cancelled = false, stdout = stdout, stderr = stderr)
    }
}
