package dev.rubentxu.pipeline.v2.application.support

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * One owner for every child process a test starts.
 *
 * ## Why this exists
 *
 * Roughly sixty `waitFor()` calls with no bound were spread across this module, and they were not
 * merely slow — a good number of them were **deadlocked**. The shape was always the same:
 *
 * ```kotlin
 * val process = ProcessBuilder(...).redirectOutput(PIPE).redirectError(PIPE).start()
 * val exitCode = process.waitFor()                       // blocks here forever
 * val stdout = process.inputStream.bufferedReader().readText()   // and only reads here
 * ```
 *
 * A pipe holds about 64 KiB. Once the child fills it, the child blocks in `write`, the test blocks
 * in `waitFor`, and neither ever moves. The test did not run long: it stopped running. That is a
 * harness defect, and it was hiding real results — a suite that cannot finish cannot report on the
 * product.
 *
 * ## The four obligations, in one place
 *
 * ```text
 * ownership     only this class starts, and always finishes, a child
 * drainage      stdout and stderr are drained on their own threads WHILE the child runs
 * timeout       bounded, always, with no "wait forever" overload
 * diagnosis     an expiry says what it saw: pid, liveness, how far each stream got
 * tree kill     the child is killed with its descendants, because a CLI's `sh` outlives its CLI
 * ```
 *
 * ## Why the timeout is not a budget
 *
 * [DEFAULT_TIMEOUT] is scaffolding, not an assertion. Nothing in this repository measures how long a
 * pipeline takes, and these tests must not start doing so: a wall-clock bound on a loaded machine
 * fails for reasons that have nothing to do with the code under test. The bound exists so that a
 * **hung** process is reported as a hang with evidence, instead of a build that silently never
 * finishes. Tests that genuinely need longer pass their own bound explicitly, and that number is
 * then part of what the test is claiming.
 *
 * Concretely: raising this constant to reach green is forbidden, and so is loosening an assertion.
 * The only legitimate responses to a [SubprocessOutcome.TimedOut] are a fix to the product, or an
 * explicit per-test bound that says what that test is willing to wait for.
 *
 * ## Why draining matters even when nothing looks wrong
 *
 * Reading after `waitFor` is only safe while output fits in the pipe. Draining concurrently is
 * what makes "the child printed a lot" and "the child finished" independent, and it also removes
 * the ordering trap where a caller reads stdout, then stderr, and finds stderr's 64 KiB already
 * blocking the child.
 */
object Subprocess {

    /**
     * Generous enough that no legitimate pipeline run reaches it, small enough that a hang is
     * reported inside a normal test run.
     *
     * Eleven minutes rather than a minute, because the module runs whole pipelines through the
     * installed distribution in a forked JVM and a loaded CI box is slow. It is a liveness bound,
     * never a performance claim.
     */
    val DEFAULT_TIMEOUT: Duration = Duration.ofMinutes(11)

    /**
     * Runs [command] and returns what happened.
     *
     * Both streams are captured unless [captureStdout] / [captureStderr] say otherwise. Capture is
     * not a pipe handed to the caller: this class owns the reading, so a caller can never be the
     * reason the child blocks.
     *
     * The result is a **closed** ADT. A caller has to decide what a [SubprocessOutcome.TimedOut]
     * means for its assertion, which is the whole point — the previous shape returned an `Int` and
     * a hang was indistinguishable from a slow success.
     *
     * @param timeout bounds the whole run, reading included; there is deliberately no way to pass
     *   "unbounded", so an unbounded wait cannot be reintroduced by accident
     */
    fun run(
        command: List<String>,
        workingDirectory: Path? = null,
        environment: Map<String, String> = emptyMap(),
        timeout: Duration = DEFAULT_TIMEOUT,
        captureStdout: Boolean = true,
        captureStderr: Boolean = true,
        stdin: InputStream? = null,
    ): SubprocessOutcome {
        require(command.isNotEmpty()) { "command must not be empty" }
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive, got $timeout" }

        val builder = ProcessBuilder(command)
        if (workingDirectory != null) builder.directory(workingDirectory.toFile())
        if (environment.isNotEmpty()) builder.environment().putAll(environment)
        // A stream that is not wanted is DISCARDED at the OS level, never left as an unread pipe.
        // Leaving it as a pipe would be the very defect this class exists to remove: the child
        // would block once the pipe filled, and the test would hang for a reason of its own making.
        builder.redirectOutput(
            if (captureStdout) ProcessBuilder.Redirect.PIPE else ProcessBuilder.Redirect.DISCARD,
        )
        builder.redirectError(
            if (captureStderr) ProcessBuilder.Redirect.PIPE else ProcessBuilder.Redirect.DISCARD,
        )

        val process = try {
            builder.start()
        } catch (t: Throwable) {
            return SubprocessOutcome.NotStarted(command, t)
        }

        // A collector over a discard redirect reads EOF immediately, so the "not captured" case
        // needs no separate path and cannot behave differently from the captured one.
        val stdout = StreamCollector(process.inputStream, "subprocess-stdout").also { it.start() }
        val stderr = StreamCollector(process.errorStream, "subprocess-stderr").also { it.start() }

        // stdin stays a pipe unless given, so a child that reads stdin sees EOF rather than hanging
        // forever on a descriptor nobody writes. Closing it is part of owning the process.
        try { process.outputStream.close() } catch (_: Throwable) { /* already closed */ }

        val exited = try {
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            val tree = destroyTree(process)
            stdout.join(DRAIN_JOIN_MILLIS)
            stderr.join(DRAIN_JOIN_MILLIS)
            return SubprocessOutcome.Interrupted(command, process.pid(), tree, e)
        }

        if (!exited) {
            val aliveBefore = process.isAlive
            val tree = destroyTree(process)
            // Draining after the kill collects whatever the child managed to emit before dying,
            // which is usually the diagnosis: a stack trace, or the last line it reached.
            stdout.join(DRAIN_JOIN_MILLIS)
            stderr.join(DRAIN_JOIN_MILLIS)
            return SubprocessOutcome.TimedOut(
                command = command,
                pid = process.pid(),
                timeout = timeout,
                stillAliveBeforeKill = aliveBefore,
                descendantsTerminated = tree.descendantsTerminated,
                stdout = stdout.text(),
                stderr = stderr.text(),
            )
        }

        // The child is gone; the collector threads finish on their own once the pipes reach EOF.
        stdout.join(DRAIN_JOIN_MILLIS)
        stderr.join(DRAIN_JOIN_MILLIS)

        return SubprocessOutcome.Exited(
            command = command,
            exitCode = process.exitValue(),
            stdout = stdout.text(),
            stderr = stderr.text(),
        )
    }

    /**
     * Kills [process] and everything it started, deepest first.
     *
     * A pipeline CLI forks the `sh` steps the pipeline contains, and those outlive a
     * `destroyForcibly()` aimed at the CLI alone. Left behind, they hold the temp directory and the
     * port the next test wants, and the failure lands on an unrelated test — which is how a harness
     * ends up making its own subject look broken.
     *
     * Descendants are collected before anything is killed, because once the parent dies the
     * parent/child links are gone and the orphans cannot be found at all.
     */
    private fun destroyTree(process: Process): DestroyedTree {
        val descendants = process.descendants().toList()
        descendants.reversed().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        descendants.forEach { handle ->
            try {
                handle.onExit().get(DESTROY_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
                handle.destroyForcibly()
            }
        }
        try {
            process.waitFor(DESTROY_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return DestroyedTree(descendants.size)
    }

    /**
     * Reads one stream to EOF on its own thread.
     *
     * A daemon thread, so a collector that cannot finish cannot keep the test JVM alive. Bytes are
     * accumulated in a bounded way: the cap is not a correctness bound on the product but a
     * protection against a runaway child turning a harness into an out-of-memory generator.
     */
    private class StreamCollector(
        private val source: InputStream,
        name: String,
    ) {
        private val sink = ByteArrayOutputStream()
        private var failure: Throwable? = null

        // A plain class holding a thread, rather than extending Thread: `Thread` already has a
        // mutable `name` property, so a constructor parameter of that name would collide with it.
        private val thread = Thread({ drain() }, name).apply { isDaemon = true }

        fun start(): StreamCollector {
            thread.start()
            return this
        }

        fun join(millis: Long) {
            try {
                thread.join(millis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun drain() {
            val chunk = ByteArray(DRAIN_CHUNK_BYTES)
            try {
                while (true) {
                    val read = source.read(chunk)
                    if (read < 0) break
                    if (read > 0 && sink.size() < CAPTURE_LIMIT_BYTES) {
                        sink.write(chunk, 0, minOf(read, CAPTURE_LIMIT_BYTES - sink.size()))
                    }
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                try { source.close() } catch (_: Throwable) { /* nothing left to do */ }
            }
        }

        fun text(): String = sink.toByteArray().toString(StandardCharsets.UTF_8)

        fun readFailure(): Throwable? = failure
    }

    /** How many descendants were found and destroyed with the child. */
    data class DestroyedTree(val descendantsTerminated: Int)

    private const val DRAIN_CHUNK_BYTES = 8 * 1024
    private const val CAPTURE_LIMIT_BYTES = 8 * 1024 * 1024
    private const val DRAIN_JOIN_MILLIS = 10_000L
    private const val DESTROY_WAIT_MILLIS = 5_000L
}