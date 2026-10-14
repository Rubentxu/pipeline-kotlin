package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/**
 * WU-B — the transcript pump's completion claim, and the two ways it used to be false silently.
 *
 * ## The two defects
 *
 * **1. Exceptions thrown inside the pump were discarded.** The pump caught everything and kept a
 * comment about the bytes already persisted, which is true of the bytes that arrived and silent
 * about the ones that did not. Three concrete losses rode through that `catch`:
 *
 * - a sink whose `write` threw (the control-dir file composition had *no* refusal channel at all —
 *   its contract said "the pump's own handling covers that", which was the bug in a sentence);
 * - a sink whose `close` threw, which is where a store-backed sink commits its **final**
 *   reservation, so the transcript's tail is lost permanently and without a trace;
 * - the redaction seam's own `read` throwing.
 *
 * **2. `transcriptPumps.forEach { it.join(10_000) }` was not a bound.** `join(timeout)` returns
 * identically whether the thread finished or the timeout elapsed, so the runtime projected a
 * transcript it had no evidence was complete and then classified the step — the same
 * "short, ordered, plausible" outcome defect 1 produces, reached by the opposite route.
 *
 * ## What these rows pin
 *
 * ```text
 * PUMP-1  a throwing sink.write  -> typed step failure, and no process byte in the diagnostic
 * PUMP-2  a throwing sink.close  -> typed step failure  (the permanent tail loss)
 * PUMP-3  a throwing seam.read   -> typed step failure  (the pump's own catch)
 * PUMP-4  a pump still alive     -> typed step failure naming the stuck pump
 * PUMP-5  CONTROL: a healthy sink -> Exited(0) with the whole transcript
 * ```
 *
 * PUMP-5 is not a formality. Without it a broken harness and a broken product produce the same
 * `LaunchFailed`, and "the rows go red" would prove nothing about which one moved.
 *
 * ## Harness fidelity
 *
 * **HF1, and the entry point is named: `DurableShellExecutor.executeTerminal`.** These rows drive
 * the real launch, the real `setsid` wrapper, real pipes, the real pump threads and the real
 * terminal projection. A sink that throws is a *composed* destination, which is exactly the
 * extension point production uses (`channelSinks`), so the failure path is the production path and
 * not a reimplementation of it.
 *
 * What is composed rather than real: the sink. It has to be, or the row cannot inject a failure at
 * all. It receives real bytes from the real pipe, so nothing downstream of the composition is
 * faked — and the control row (PUMP-5) uses the same sink class with the failure switched off, so
 * the composition itself is proven able to observe a full transcript.
 *
 * **Nothing here recomputes a value the production authority owns.** The rows assert on the
 * terminal `executeTerminal` returns and on the bytes the sink was handed, never on a re-derived
 * transcript.
 *
 * ## Mutations
 *
 * - `M-PUMP-catch` → restore `catch (_: Exception) {}` with the comment. Kills PUMP-1/2/3.
 * - `M-PUMP-join` → `transcriptPumps.forEach { it.join(BUDGET) }` with no `isAlive` check. Kills
 *   PUMP-4, and only PUMP-4: the other rows have pumps that genuinely finish.
 * - `M-PUMP-noop-close` → guard `close()` on an existing refusal. Kills PUMP-2 specifically,
 *   because PUMP-1 already set a failure before the close ran.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class TranscriptPumpCompletionTest {

    @TempDir
    lateinit var tempDir: Path

    private val config = DurableShConfig.fromSystemProperties()

    /** What the child prints. Fixed, so a diagnostic that quotes it is provably leaking it. */
    private val script = "echo 'hello-transcript-canary'; echo 'to-stderr-canary' >&2"

    // ------------------------------------------------------------------ fixtures

    /**
     * A sink that records what it was handed and can be made to fail at exactly one seam.
     *
     * The failure is a constructor parameter rather than a mutable flag the rows flip mid-run: a
     * sink that fails "when the test says so" would let a row assert on a race instead of on the
     * contract.
     */
    private class RecordingSink(
        private val failOn: String? = null,
    ) : ProcessOutputSink {

        val received = java.io.ByteArrayOutputStream()
        val writeCount = AtomicInteger()
        val closeCount = AtomicInteger()

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (failOn == "write") throw java.io.IOException("disk went away")
            received.write(bytes, offset, length)
            writeCount.incrementAndGet()
        }

        /**
         * Always `null` on purpose.
         *
         * This sink THROWS at the failing seam rather than recording a refusal of its own, which is
         * the composition the guard exists to cover — the real control-dir file sink behaves exactly
         * this way. A sink that refused itself would test the sink, not the guard.
         */
        override fun refusal(): ProcessOutputRefusal? = null

        override fun close() {
            closeCount.incrementAndGet()
            if (failOn == "close") throw java.io.IOException("flush failed on close")
        }
    }

    /**
     * An InputStream whose `read` blocks until [release] is counted down.
     *
     * `available()` is delegated so the pump's read-sizing heuristic is unaffected and the ONLY
     * thing this class changes is when the bytes are allowed to arrive — which is the variable under
     * study in PUMP-4.
     */
    private class BlockingReadStream(
        private val delegate: InputStream,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : InputStream() {

        private var gateArmed = true

        override fun read(): Int {
            arm()
            return delegate.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            arm()
            return delegate.read(b, off, len)
        }

        override fun available(): Int = delegate.available()

        private fun arm() {
            if (!gateArmed) return
            gateArmed = false
            entered.countDown()
            check(release.await(60, TimeUnit.SECONDS)) { "the test never released the pump" }
        }
    }

    /**
     * A seam that CONSTRUCTS and then cannot read.
     *
     * Deliberately not a lambda that throws: a `transcriptRedactor` that fails to build fails in
     * `launch`, which is a different defect with a different owner. This row is about the pump's
     * own `catch`, and that code path is only reached once a stream exists.
     */
    private class ThrowingReadStream(
        private val delegate: InputStream,
    ) : InputStream() {
        override fun read(): Int = throw java.io.IOException("redaction seam broke mid-flight")

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            throw java.io.IOException("redaction seam broke mid-flight")

        // `available` is answered truthfully so the pump's read-sizing path runs normally and the
        // row varies only the failure, not the shape of the loop that hits it.
        override fun available(): Int = delegate.available()
    }

    private fun controlDir(name: String): Path =
        Files.createDirectories(tempDir.resolve(name))

    private fun run(
        controlDir: Path,
        sinks: Map<ProcessOutputChannel, ProcessOutputSink>,
        transcriptRedactor: ((InputStream) -> InputStream)? = null,
        script: String = this.script,
    ): DurableTaskTerminal = DurableShellExecutor().executeTerminal(
        controlDir = controlDir,
        scriptContent = script,
        opId = "wub-${controlDir.fileName}",
        shOptions = ShOptions(
            workspaceRoot = tempDir,
            captureStdout = false,
            timeoutMs = 60_000,
            env = emptyMap(),
        ),
        config = config,
        transcriptRedactor = transcriptRedactor,
        channelSinks = sinks,
    )

    private fun failed(terminal: DurableTaskTerminal) =
        assertInstanceOf(
            DurableTaskTerminal.LaunchFailed::class.java, terminal,
            "expected a typed step failure, not $terminal",
        ).failure

    // ------------------------------------------------------------------ control

    /**
     * PUMP-5 — the control. A healthy sink receives the whole transcript and the step exits 0.
     *
     * Without this row the four failure rows below would be satisfied by a harness that cannot see
     * anything at all, which is the exact false-green the redactor block walked into once.
     */
    @Test
    fun `PUMP-5 a healthy sink receives the whole transcript and the step exits`() {
        val stdout = RecordingSink()
        val stderr = RecordingSink()

        val terminal = run(
            controlDir("control"),
            sinks = mapOf(
                ProcessOutputChannel.STDOUT to stdout,
                ProcessOutputChannel.STDERR to stderr,
            ),
        )

        assertInstanceOf(DurableTaskTerminal.Exited::class.java, terminal, "got $terminal")
        assertEquals(0, (terminal as DurableTaskTerminal.Exited).exitCode)
        assertTrue(stdout.received.toString(Charsets.UTF_8).contains("hello-transcript-canary"),
            "stdout must reach the sink. got '${stdout.received.toString(Charsets.UTF_8)}'")
        assertTrue(stderr.received.toString(Charsets.UTF_8).contains("to-stderr-canary"),
            "stderr must reach the sink. got '${stderr.received.toString(Charsets.UTF_8)}'")
        assertEquals(1, stdout.closeCount.get(), "the sink must be closed exactly once, on every path")
    }

    // ------------------------------------------------------------------ defect 1

    /** PUMP-1 — a sink that cannot accept bytes must fail the step, not swallow the loss. */
    @Test
    fun `PUMP-1 a throwing sink write fails the step and leaks no process byte`() {
        val stdout = RecordingSink(failOn = "write")

        val failure = failed(
            run(
                controlDir("write"),
                sinks = mapOf(ProcessOutputChannel.STDOUT to stdout, ProcessOutputChannel.STDERR to RecordingSink()),
            ),
        )

        assertEquals("OUTPUT_PERSISTENCE_REFUSED", failure.code)
        assertEquals(FailureKind.INFRASTRUCTURE, failure.kind)
        assertFalse(failure.retryable, "the bytes left the pipe; a retry cannot recover them")
        assertTrue(failure.message.contains("write"), "the record must say WHERE it was lost. got '${failure.message}'")
        assertFalse(
            failure.message.contains("hello-transcript-canary") || failure.details.values.any { it.contains("canary") },
            "the diagnostic reaches a journal record and an event; quoting the transcript here would " +
                "publish through the diagnostic channel the exact bytes the redaction seam withholds",
        )
    }

    /**
     * PUMP-2 — a `close` that throws is the tail loss, and it is the row that pins the guard's
     * decision not to short-circuit `close` on an earlier failure.
     */
    @Test
    fun `PUMP-2 a throwing sink close fails the step`() {
        val stdout = RecordingSink(failOn = "close")

        val failure = failed(
            run(
                controlDir("close"),
                sinks = mapOf(ProcessOutputChannel.STDOUT to stdout, ProcessOutputChannel.STDERR to RecordingSink()),
            ),
        )

        assertEquals("OUTPUT_PERSISTENCE_REFUSED", failure.code)
        assertTrue(
            failure.message.contains("close"),
            "`close` is where a store-backed sink publishes its final reservation, so a failure there " +
                "is a permanent silent loss of the tail. got '${failure.message}'",
        )
    }

    /** PUMP-3 — the pump's OWN catch: a redaction seam that cannot read loses the remaining bytes. */
    @Test
    fun `PUMP-3 a throwing redaction seam read fails the step`() {
        val stdout = RecordingSink()

        val failure = failed(
            run(
                controlDir("read"),
                sinks = mapOf(ProcessOutputChannel.STDOUT to stdout, ProcessOutputChannel.STDERR to RecordingSink()),
                transcriptRedactor = { ThrowingReadStream(it) },
            ),
        )

        assertEquals("OUTPUT_PERSISTENCE_REFUSED", failure.code)
        assertTrue(failure.message.contains("read"), "got '${failure.message}'")
    }

    // ------------------------------------------------------------------ defect 2

    /**
     * PUMP-4 — a pump that has not finished at the budget must not be reported as a finished step.
     *
     * The gate blocks the pump inside the redaction seam, so the child has exited and every byte it
     * wrote is sitting in the pipe unread. `join(budget)` returns on the timeout exactly as it
     * returns on completion, which is why the row asserts on the terminal rather than on timing.
     */
    @Test
    fun `PUMP-4 a pump still alive at the budget fails the step`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stdout = RecordingSink()

        val terminal = try {
            run(
                controlDir("drain"),
                sinks = mapOf(ProcessOutputChannel.STDOUT to stdout, ProcessOutputChannel.STDERR to RecordingSink()),
                transcriptRedactor = { BlockingReadStream(it, entered, release) },
            )
        } finally {
            // The pump is a daemon holding a live pipe; leaving it blocked would leak a thread and
            // a descriptor per run. Released only AFTER the terminal has been decided.
            release.countDown()
        }

        assertEquals(
            0,
            stdout.writeCount.get(),
            "the pump must not have delivered anything: it is still holding the read end, so the " +
                "transcript is complete-by-luck or not at all, and the runtime may not claim it",
        )

        val failure = failed(terminal)
        assertEquals("OUTPUT_PERSISTENCE_REFUSED", failure.code)
        assertEquals(
            "transcript-pump-still-alive",
            failure.details["cause"],
            "the record must distinguish 'a write threw' from 'the drain never finished' — they are " +
                "different facts and a reader diagnosing the run needs to know which one happened",
        )
        assertTrue(
            failure.message.contains("stdout"),
            "the refusal must name the stuck pump, whose name carries its channel. got '${failure.message}'",
        )
    }
}