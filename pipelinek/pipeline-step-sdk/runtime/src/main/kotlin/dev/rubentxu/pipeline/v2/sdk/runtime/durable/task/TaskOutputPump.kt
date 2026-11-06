package dev.rubentxu.pipeline.v2.sdk.runtime.durable.task

import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import kotlinx.coroutines.runInterruptible
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * The pump that turns ONE child's output stream into chunks on the sink.
 *
 * ## What this guarantees
 * - The sink receives the bytes of the stream EXACTLY as the child wrote them, and in the order
 *   it wrote them. Nothing is dropped, nothing is reordered, and the chunk carries the channel it
 *   came from.
 * - The pump holds ONE chunk. It reads a chunk, hands it over, and reads the next one only when
 *   the sink has taken the previous one: a sink slower than the child throttles the child
 *   instead of filling the runtime's memory.
 * - A cancelled pump stops. The read that waits for the child's bytes is interruptible, and the
 *   hand-off that waits for the sink is a suspending call in the pump's own scope, so cancelling
 *   the pump reaches both.
 *
 * ## Why the pump reads a stream and not a process
 * The bytes, the order and the window are facts about the PUMP, and the pump's subject is a
 * stream of bytes. A process is one way to produce such a stream and the cheapest way to
 * observe one, but it is also the most expensive: it brings a filesystem, a tree of processes
 * and a clock into every assertion about a chunk boundary. The pump therefore takes the stream,
 * and the part that owns the process (spawning it, waiting for it, killing its tree) stays where
 * it belongs.
 *
 * ## What this does not freeze
 * That the child's pipe delivers what the child wrote, that the process tree dies when the run
 * is cancelled, and that the terminal record reaches the disk. Those are the imperative half,
 * and they are observed where they run.
 */
internal class TaskOutputPump(
    private val stream: TaskStream,
    private val sink: ExecutionOutputSink,
) {

    /**
     * Reads [input] to its end, handing one chunk at a time to the sink.
     *
     * The read is the only blocking call, and it runs under `runInterruptible` so that a
     * cancelled pump interrupts the thread that waits for the child's bytes. The hand-off is
     * OUTSIDE that call: it is a suspending call in the pump's own scope, so a pump that waits
     * for the sink suspends instead of holding the thread, and a cancelled pump cancels the
     * hand-off it is waiting on.
     */
    suspend fun drain(input: InputStream) {
        val buffer = ByteArray(CHUNK_SIZE_BYTES)
        try {
            while (true) {
                val read = runInterruptible { input.read(buffer) }
                if (read < 0) break
                if (read > 0) {
                    val chunk = when (stream) {
                        TaskStream.STDOUT -> OutputChunk.stdout(buffer.copyOf(read))
                        TaskStream.STDERR -> OutputChunk.stderr(buffer.copyOf(read))
                    }
                    sink.append(chunk)
                }
            }
        } catch (_: CancellationException) {
            // The run is terminal: the bytes that were still to come are dropped, because the
            // child's tree is being destroyed and nobody will read them.
        }
    }

    private companion object {
        /** How many bytes the pump reads at a time. */
        const val CHUNK_SIZE_BYTES = 8 * 1024
    }
}
