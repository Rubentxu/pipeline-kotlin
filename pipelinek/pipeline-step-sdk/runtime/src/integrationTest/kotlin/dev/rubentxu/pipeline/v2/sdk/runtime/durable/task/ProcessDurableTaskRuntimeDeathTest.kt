package dev.rubentxu.pipeline.v2.sdk.runtime.durable.task

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import kotlin.coroutines.cancellation.CancellationException

/**
 * The guarantee under trial: a cancellation returns only after the child is DEAD.
 *
 * ## Why the guarantee exists
 *
 * A child that was told to die has not died yet, and what it holds is held
 * until it is: the lease of the run it publishes, the files it has open, the
 * lock another process is waiting for. A caller that returns from a
 * cancellation before the death is a caller that believes the child is gone
 * while the child still holds what it held — and the next actor, the one
 * that writes the run's terminal, is refused by a lease the dead child has
 * not released.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * Because the runtime under trial is the module's own adapter, and what it
 * reaches outside the process is a SUBPROCESS: the child is the effect, and
 * the effect is faked here with a child the test owns. The lane still uses
 * the real filesystem for the control directory, because that directory is
 * the runtime's own protocol output and not the world outside it.
 *
 * ## Why the child is faked and not spawned
 *
 * Because a spawned child cannot show the ORDER this guarantee is about. A
 * child that a signal kills is dead before anybody looks at it, so a runtime
 * that returns without waiting and a runtime that waits for the death are
 * indistinguishable from the outside: the difference lives in a window of
 * microseconds that no assertion can see. The fake child closes that window
 * the only way it can be closed — it dies when somebody waits for its death,
 * and it stays alive when nobody does — so the order between the kill and
 * the return becomes a fact the test can read.
 *
 * ## What this does not freeze
 *
 * How long the wait lasts, and what the runtime does with a child that
 * refuses to die: the wait is bounded, and a child that outlives the bound
 * is a fact the caller is told by the lease it finds, not by this runtime.
 */
@Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
class ProcessDurableTaskRuntimeDeathTest {

    @TempDir
    lateinit var controlRoot: Path

    @Test
    fun `the cancellation returns only after the child is dead`() {
        val child = FakeChild(diesWhenWaitedFor = true)
        val started = CountDownLatch(1)
        val runtime = ProcessDurableTaskRuntime(controlRoot, FixedClock) {
            started.countDown()
            child
        }

        val job = run(runtime)
        assertTrue(
            started.await(30, TimeUnit.SECONDS),
            "the runtime started the child before it was cancelled",
        )
        runBlocking { job.cancelAndJoin() }

        assertTrue(child.wasKilled, "the runtime destroys the tree of the child it cancels")
        assertTrue(
            child.isDead,
            "the runtime returned from the cancellation while the child was alive: " +
                "the death of the child is what the caller waits for",
        )
    }

    @Test
    fun `a child that refuses to die does not hold the runtime for ever`() {
        val child = FakeChild(diesWhenWaitedFor = false)
        val started = CountDownLatch(1)
        val runtime = ProcessDurableTaskRuntime(controlRoot, FixedClock) {
            started.countDown()
            child
        }

        val job = run(runtime)
        assertTrue(
            started.await(30, TimeUnit.SECONDS),
            "the runtime started the child before it was cancelled",
        )
        runBlocking { job.cancelAndJoin() }

        assertFalse(
            child.isDead,
            "the child that refuses to die is still alive, and the runtime says so by returning",
        )
    }

    /**
     * Runs one task on [runtime] and hands back the job that runs it.
     *
     * The coroutine lives in a scope of its own, so the cancellation the row
     * asks for reaches the runtime and not the test.
     */
    @Suppress("InjectDispatcher")
    private fun run(runtime: ProcessDurableTaskRuntime): Job {
        val request = TaskExecutionRequest(
            task = TaskSpec.ExecTask(listOf("/bin/sh", "-c", "true")),
            runId = RunId("run-1"),
            opId = "op-1",
        )
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return scope.launch {
            try {
                runtime.execute(request, ExecutionOutputSink { })
            } catch (_: CancellationException) {
                // The cancellation IS the outcome this lane is about, and it
                // is not a failure of the row.
            }
        }
    }

    /**
     * A clock that does not move: the rows are about the order of two
     * events, not about the moment they happen.
     */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(1_000L)
    }

    /**
     * A child the test owns.
     *
     * It is killed the way a real child is killed — told to die — and it
     * STAYS ALIVE until somebody waits for its death. That is the whole
     * point of the fake: the difference between "the child was told to die"
     * and "the child is dead" is the difference this lane measures, and a
     * real child collapses both into the same instant.
     */
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private class FakeChild(private val diesWhenWaitedFor: Boolean) : Process() {

        private val lock = Object()
        private val died = CountDownLatch(1)
        private var alive = true
        private var killed = false

        /** True when the child was told to die. */
        val wasKilled: Boolean get() = synchronized(lock) { killed }

        /** True when the child is DEAD. */
        val isDead: Boolean get() = synchronized(lock) { !alive }

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        /**
         * Waits for the child to end on its own, and is interrupted when the
         * runtime is cancelled: a real child does not end on its own either.
         */
        override fun waitFor(): Int {
            died.await()
            return 0
        }

        /**
         * Waits for the death, up to [timeout].
         *
         * The child dies HERE when it was built to die when somebody waits
         * for it, and it stays alive when it was not: that is the fact the
         * row reads. The interrupt is honoured the way the JDK honours it,
         * because a wait that ignored the interrupt would be a wait the
         * runtime could not leave.
         */
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (Thread.interrupted()) throw InterruptedException("the wait was interrupted")
            if (!diesWhenWaitedFor) return false
            synchronized(lock) { alive = false }
            died.countDown()
            return true
        }

        override fun exitValue(): Int = synchronized(lock) {
            if (alive) throw IllegalThreadStateException("the child lives") else 0
        }

        override fun destroy() {
            markKilled()
        }

        override fun destroyForcibly(): Process {
            markKilled()
            return this
        }

        override fun isAlive(): Boolean = synchronized(lock) { alive }

        /**
         * The handle of the child, and it names NOBODY: a handle that named
         * a real process would have the runtime destroy a tree that is not
         * its own.
         */
        override fun toHandle(): ProcessHandle = EmptyHandle

        private fun markKilled() {
            synchronized(lock) { killed = true }
        }
    }

    /**
     * A handle that names no process and has no descendants.
     */
    private object EmptyHandle : ProcessHandle {
        override fun pid(): Long = 0L

        /**
         * There is no process to describe, and nothing asks: the runtime
         * reads the handle for the DESCENDANTS, and this one has none.
         */
        override fun info(): ProcessHandle.Info =
            throw UnsupportedOperationException("the fake child names no process")

        override fun onExit(): CompletableFuture<ProcessHandle> = CompletableFuture()

        override fun supportsNormalTermination(): Boolean = false

        override fun isAlive(): Boolean = false

        override fun destroy(): Boolean = false

        override fun destroyForcibly(): Boolean = false

        override fun children(): Stream<ProcessHandle> = Stream.empty()

        override fun descendants(): Stream<ProcessHandle> = Stream.empty()

        override fun parent(): Optional<ProcessHandle> = Optional.empty()

        override fun compareTo(other: ProcessHandle): Int = 0
    }
}
