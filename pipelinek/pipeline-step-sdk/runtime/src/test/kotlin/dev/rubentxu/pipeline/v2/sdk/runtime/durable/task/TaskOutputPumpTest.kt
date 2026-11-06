package dev.rubentxu.pipeline.v2.sdk.runtime.durable.task

import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pump's three promises, checked against a stream the test owns.
 *
 * **The guarantee.** The bytes of a child's stream reach the sink exactly as the child wrote
 * them and in the order it wrote them, carrying the channel they came from; the pump holds ONE
 * chunk while it waits, so a sink slower than the child throttles the child instead of filling
 * the runtime's memory; and a cancelled pump stops and returns instead of holding the run.
 *
 * **Why the instrument is a stream and not a process.** What can go wrong here is a chunk
 * boundary, an order, a byte that never arrives, a window that grows without bound, and a
 * cancellation that does not reach the pump. None of those is a fact about a process: they are
 * facts about a pump reading a stream of bytes, and a stream the test owns is the cheapest
 * instrument that can fail them. A child would add a filesystem, a tree of processes and a
 * clock to every assertion about a chunk boundary, and it would still not say WHICH promise
 * broke.
 *
 * **What this does not freeze.** That the child's pipe delivers what the child wrote, that the
 * process tree dies when the run is cancelled, and that the terminal record reaches the disk.
 * Those are the imperative half, and they are observed where they run.
 */
class TaskOutputPumpTest {

    @Test
    fun `the sink receives the bytes the child wrote, in the order it wrote them`() = runBlocking {
        val payload = ByteArray(20_000) { (it % 251).toByte() }
        val received = mutableListOf<OutputChunk>()

        TaskOutputPump(TaskStream.STDOUT, ExecutionOutputSink { received.add(it) })
            .drain(ByteArrayInputStream(payload))

        val bytes = received.fold(ByteArray(0)) { carried, chunk -> carried + chunk.data }
        assertArrayEquals(
            payload,
            bytes,
            "the sink did not receive the bytes the child wrote, in the order it wrote them",
        )
        assertTrue(
            received.size > 1,
            "the payload fitted in one chunk, so the order this test checks says nothing",
        )
    }

    @Test
    fun `the chunk carries the channel it came from`() = runBlocking {
        val received = mutableListOf<OutputChunk>()

        TaskOutputPump(TaskStream.STDERR, ExecutionOutputSink { received.add(it) })
            .drain(ByteArrayInputStream(ByteArray(20_000) { 7 }))

        assertTrue(received.isNotEmpty(), "the sink received nothing, so the channel says nothing")
        received.forEach { chunk ->
            assertEquals(
                TaskStream.STDERR,
                chunk.stream,
                "the chunk was delivered on a channel it did not come from",
            )
        }
    }

    @Test
    fun `the pump reads no further than the chunk it is handing over`() = runBlocking {
        val total = 1 shl 20
        val source = CountingInputStream(total)
        val sinkEntered = CompletableDeferred<Unit>()
        val releaseSink = CompletableDeferred<Unit>()
        val pump = TaskOutputPump(
            TaskStream.STDOUT,
            ExecutionOutputSink {
                sinkEntered.complete(Unit)
                releaseSink.await()
            },
        )

        val job = launch { pump.drain(source) }
        sinkEntered.await()
        // The sink is holding the first chunk. The pump must be holding THAT chunk and nothing
        // else: a pump that reads ahead fills the runtime's memory with bytes nobody asked for.
        assertTrue(
            source.bytesRead() <= CHUNK_BYTES,
            "the pump read ${source.bytesRead()} bytes while the sink was holding one chunk",
        )

        releaseSink.complete(Unit)
        withTimeout(10_000) { job.join() }
        assertEquals(
            total,
            source.bytesRead(),
            "the pump did not read the whole stream once the sink took the chunk",
        )
    }

    @Test
    fun `a cancelled pump stops and returns`() = runBlocking {
        val source = CountingInputStream(1 shl 20)
        val sinkEntered = CompletableDeferred<Unit>()
        val pump = TaskOutputPump(
            TaskStream.STDOUT,
            ExecutionOutputSink {
                sinkEntered.complete(Unit)
                awaitCancellation()
            },
        )

        // The pump runs in a scope of its own, so a pump that does not honour the cancellation
        // makes this test FAIL instead of hanging the suite.
        val scope = CoroutineScope(SupervisorJob())
        val job = scope.launch { pump.drain(source) }
        sinkEntered.await()
        job.cancel()

        withTimeout(10_000) { job.join() }
        assertTrue(job.isCancelled, "the pump was not cancelled")
    }

    /**
     * A stream of [total] bytes that counts what it produced.
     */
    private class CountingInputStream(private val total: Int) : InputStream() {
        private val produced = AtomicInteger()

        override fun read(): Int = read(ByteArray(1), 0, 1)

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val already = produced.get()
            if (already >= total) return -1
            val count = minOf(len, total - already)
            produced.addAndGet(count)
            return count
        }

        fun bytesRead(): Int = produced.get()
    }

    private companion object {
        /**
         * How many bytes the pump reads at a time.
         *
         * The test states it because the promise it checks is "one chunk in flight": the bound is
         * the chunk the pump is holding, not the stream it could have read.
         */
        const val CHUNK_BYTES = 8 * 1024
    }
}
