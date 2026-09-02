package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import kotlinx.serialization.Serializable

/**
 * Which pipe a chunk came from.
 */
enum class TaskStream { STDOUT, STDERR }

/**
 * One bounded window of process output (spec invariant: memory O(chunk),
 * never O(total output)). Chunks are delivered to the
 * [ExecutionOutputSink] as the process produces them; nothing in the
 * runtime may buffer the whole stream.
 */
class OutputChunk(
    val stream: TaskStream,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is OutputChunk && other.stream == stream && other.data.contentEquals(data)

    override fun hashCode(): Int = 31 * stream.hashCode() + data.contentHashCode()

    override fun toString(): String = "OutputChunk(stream=$stream, bytes=${data.size})"

    companion object {
        fun stdout(data: ByteArray): OutputChunk = OutputChunk(TaskStream.STDOUT, data)
        fun stderr(data: ByteArray): OutputChunk = OutputChunk(TaskStream.STDERR, data)
    }
}

/**
 * Streaming destination for process output. Implementations MUST be
 * safe to call from two concurrent pump contexts (stdout and stderr are
 * drained concurrently per the spec).
 *
 * Prohibited on the producing side: `readText()` / `readAllBytes()` over
 * unbounded pipes — only bounded chunks cross this interface.
 */
fun interface ExecutionOutputSink {
    suspend fun append(chunk: OutputChunk)
}

/**
 * Terminal state of one durable task execution.
 *
 * Exactly one of the boolean flags is a "why it ended" marker:
 * [timedOut] (watchdog killed the process tree after [TaskExecutionRequest.timeoutMs]),
 * [cancelled] (external cancellation), or neither (the process exited on
 * its own with [exitCode]).
 */
data class TaskExecutionResult(
    val exitCode: Int,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
) {
    init {
        require(startedAtEpochMs <= endedAtEpochMs) {
            "TaskExecutionResult timestamps are inconsistent: started $startedAtEpochMs > ended $endedAtEpochMs"
        }
        require(!(timedOut && cancelled)) { "A result cannot be both timedOut and cancelled" }
    }

    val succeeded: Boolean get() = !timedOut && !cancelled && exitCode == 0
}

/**
 * Request to execute one durable task (LF-0302 port contract).
 *
 * @property task the task to run.
 * @property runId identity of the pipeline invocation (M1-001 chain).
 * @property opId durable journal key for the operation; the runtime
 *                 records its durable result under this id (atomic
 *                 result.txt-style write is an adapter concern).
 * @property attempt zero-based retry attempt (M1 chain continuity).
 * @property timeoutMs wall-clock budget for the process; the watchdog
 *                     kills the whole process tree when it fires. `null`
 *                     means no deadline.
 * @property env typed environment (values are [SecretHandle]s so redaction
 *               precedes any persistence or transmission).
 * @property workspaceRoot process working directory; `null` lets the
 *                         adapter choose (its control dir).
 */
data class TaskExecutionRequest(
    val task: TaskSpec,
    val runId: RunId,
    val opId: String,
    val attempt: Int = 0,
    val timeoutMs: Long? = null,
    val env: Map<String, SecretHandle> = emptyMap(),
    val workspaceRoot: String? = null,
) {
    init {
        require(opId.isNotBlank()) { "TaskExecutionRequest.opId must not be blank" }
        require(attempt >= 0) { "TaskExecutionRequest.attempt must be non-negative" }
        require(timeoutMs == null || timeoutMs > 0) { "timeoutMs must be positive when present" }
    }
}

/**
 * Port for durable process execution (LF-0302).
 *
 * The single authorised home for process creation in the V2 runtime
 * (M3 gate: production may contain `ProcessBuilder` only inside this
 * port's adapter). Every sh / git / tar execution must migrate onto this
 * seam (LF-0305..LF-0307) so that the spec invariants hold in ONE place:
 * concurrent stdout/stderr drains, O(chunk) memory, timeout/cancel while
 * the process lives, process-tree termination, atomic durable result,
 * and reconcile-after-restart.
 *
 * ## Contract
 *
 * - Returns exactly one [TaskExecutionResult]; step failure is data
 *   (`exitCode`/`timedOut`/`cancelled`), never an exception. Throwing is
 *   reserved for invalid requests and runtime bugs (mirrors
 *   `StepDispatcher`).
 * - Output is delivered chunk-wise to the sink WHILE the process lives;
 *   the result is returned only after both pumps drain.
 * - Adapters must be fail-closed on restart: a request whose operation
 *   was interrupted mid-flight is reconciled, never silently trusted.
 */
interface DurableTaskRuntime {
    suspend fun execute(request: TaskExecutionRequest, outputSink: ExecutionOutputSink): TaskExecutionResult
}
