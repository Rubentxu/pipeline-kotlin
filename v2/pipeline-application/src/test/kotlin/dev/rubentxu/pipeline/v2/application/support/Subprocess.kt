package dev.rubentxu.pipeline.v2.application.support

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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
     * Both streams are captured unless [stdoutFile] or the capture flags say otherwise. Capture is
     * not a pipe handed to the caller: this class owns the reading, so a caller can never be the
     * reason the child blocks.
     *
     * The result is a **closed** ADT. A caller has to decide what a [SubprocessOutcome.TimedOut]
     * means for its assertion, which is the whole point — the previous shape returned an `Int` and
     * a hang was indistinguishable from a slow success.
     *
     * @param stdoutFile sends stdout to this file instead of a pipe, and reads it back once the
     *   child is gone. It exists for tests that genuinely want a file on disk, and **not** as a way
     *   to avoid draining: stderr is captured and drained either way, so handing stdout to a file
     *   does not make the run deadlock-proof. What made a run deadlock-proof was never "stdout goes
     *   to a file" — it was "no stream is left in a pipe nobody reads while the child runs".
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
        stdoutFile: Path? = null,
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
        when {
            stdoutFile != null -> {
                stdoutFile.parent?.let { Files.createDirectories(it) }
                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(stdoutFile.toFile()))
            }
            captureStdout -> builder.redirectOutput(ProcessBuilder.Redirect.PIPE)
            else -> builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        }
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
                stdout = stdoutFromFileOr(stdout.text(), stdoutFile),
                stderr = stderr.text(),
            )
        }

        // The child is gone; the collector threads finish on their own once the pipes reach EOF.
        stdout.join(DRAIN_JOIN_MILLIS)
        stderr.join(DRAIN_JOIN_MILLIS)

        return SubprocessOutcome.Exited(
            command = command,
            exitCode = process.exitValue(),
            stdout = stdoutFromFileOr(stdout.text(), stdoutFile),
            stderr = stderr.text(),
        )
    }

    /**
     * Kills [process] and waits for it to actually die, returning what happened.
     *
     * ## Why this exists rather than `destroyForcibly().waitFor()`
     *
     * Because killing is the *subject* of a whole family of tests — "kill the JVM mid-`sh` and
     * assert the detached process survives", "kill during a heartbeat window", "kill and resume" —
     * and in all of them the unbounded wait is exactly the wrong shape. It blocks forever if the
     * kill somehow does not land, it reports nothing about descendants, and it cannot distinguish
     * "we killed it" from "it had already finished", which is the one thing those tests need to
     * know before they can assert the wrong thing correctly.
     *
     * [SubprocessOutcome.Killed] carries all three. Its bound is a liveness bound on the *kill*,
     * not on the work the process was doing, and it is never satisfied by the process finishing on
     * its own — [SubprocessOutcome.Killed.hadAlreadyExited] says so explicitly.
     */
    fun kill(
        process: Process,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): SubprocessOutcome.Killed {
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive, got $timeout" }

        val command = process.commandLine()
        val pid = process.pid()
        // Read BEFORE the kill: afterwards the process is gone and so is the answer.
        val wasAlive = process.isAlive
        val tree = destroyTree(process)

        val died = try {
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AssertionError(
                "the thread was interrupted while waiting for pid $pid to die; it was killed " +
                    "first, so nothing is left running",
            )
        }
        check(died) {
            "pid $pid survived destroyForcibly() and $timeout. The kill did not land, and leaving " +
                "it running is what poisons whatever test runs next."
        }

        return SubprocessOutcome.Killed(
            command = command,
            pid = pid,
            descendantsTerminated = tree.descendantsTerminated,
            hadAlreadyExited = !wasAlive,
            // An exit code exists only if the process finished by itself. A killed one has none of
            // its own, and inventing one here would be the same lie as reporting it as Exited.
            exitCode = if (!wasAlive) process.exitValue() else null,
        )
    }

    /**
     * The child's stdout, from [file] when it was redirected there and from the pipe otherwise.
     *
     * Reading the file back rather than returning an empty string keeps one shape for callers: a
     * test that redirected stdout to a file to look at it on disk still gets the bytes, so it does
     * not need a second code path just to assert on the same content.
     */
    private fun stdoutFromFileOr(piped: String, file: Path?): String {
        if (file == null || !Files.isRegularFile(file)) return piped
        return runCatching { Files.readString(file) }.getOrElse { piped }
    }

    /**
     * Kills **only** [process], leaving its descendants running, and waits for it to die.
     *
     * ## Why the tree-killing default is wrong for some tests
     *
     * [kill] takes the descendants with it, because a surviving grandchild is what makes an
     * unrelated later test fail for a reason that looks like a product defect. That is right almost
     * always, and it is **wrong exactly when the orphan is the subject**.
     *
     * `UatLocal001KillDuringShTest` kills the JVM mid-`sh` and asserts the detached `sh` SURVIVES,
     * because surviving is the property durable `sh` claims. Under [kill] the assertion fails, and
     * the failure is not a bug in either: the harness did its job and removed precisely the thing
     * the test exists to observe.
     *
     * So there are two modes, both bounded, and choosing between them is the caller's claim about
     * what the test is about:
     *
     * ```text
     * kill(process)      kill the tree; use when leaking orphans would poison other tests
     * killAlone(process) kill only the parent; use when the surviving child IS the assertion
     * ```
     *
     * A caller that picks the wrong one gets a red test with a message saying so, rather than a
     * green one that quietly observed something else.
     */
    fun killAlone(
        process: Process,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): SubprocessOutcome.Killed {
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive, got $timeout" }

        val command = process.commandLine()
        val pid = process.pid()
        val wasAlive = process.isAlive
        process.destroyForcibly()

        val died = try {
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AssertionError("the thread was interrupted while waiting for pid $pid to die")
        }
        check(died) { "pid $pid survived destroyForcibly() and $timeout" }

        return SubprocessOutcome.Killed(
            command = command,
            pid = pid,
            descendantsTerminated = 0,
            hadAlreadyExited = !wasAlive,
            exitCode = if (!wasAlive) process.exitValue() else null,
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

    /**
     * The command line [process] was started with, as far as the OS still knows.
     *
     * Best-effort on purpose. A killed process can have an unreadable `ProcessHandle.Info` on some
     * platforms, and a diagnosis that cannot name the command is still worth far more than an
     * exception thrown while building it.
     */
    private fun Process.commandLine(): List<String> =
        runCatching {
            info().arguments().orElse(emptyArray()).toList()
        }.getOrElse { listOf("<command unavailable>", "pid=${this@commandLine.pid()}") }

    /** How many descendants were found and destroyed with the child. */
    data class DestroyedTree(val descendantsTerminated: Int)

    private const val DRAIN_CHUNK_BYTES = 8 * 1024
    private const val CAPTURE_LIMIT_BYTES = 8 * 1024 * 1024
    private const val DRAIN_JOIN_MILLIS = 10_000L
    private const val DESTROY_WAIT_MILLIS = 5_000L
}