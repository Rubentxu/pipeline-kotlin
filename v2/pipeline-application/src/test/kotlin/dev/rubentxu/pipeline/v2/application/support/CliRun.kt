package dev.rubentxu.pipeline.v2.application.support

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * S6-PRE — the ONE way a UAT or corpus harness is allowed to run a child process.
 *
 * ## What this exists to end
 *
 * Seven call sites across [dev.rubentxu.pipeline.v2.application.CompatibilityCorpusTest] and
 * [dev.rubentxu.pipeline.v2.application.UatCompat001CorpusSmokeRunTest] wrote
 *
 * ```kotlin
 * val exitCode = process.waitFor()
 * val stdout = process.inputStream.bufferedReader().readText()
 * ```
 *
 * and the KDoc of `CompatibilityCorpusTest` already names the consequence — "waiting first can
 * deadlock when a child fills the 64 KiB pipe buffer". A harness that can deadlock is not a
 * harness worth having, so the correct fix was never a seventh variant of the same shape: it is
 * one owner for the child, and nobody else touches `Process` directly.
 *
 * ## The three properties this makes structural
 *
 * 1. **Both pipes drain from the instant the child starts.** stdout and stderr are separate pipes
 *    and a child blocked on EITHER one is a dead child. Draining only the stream a test happens to
 *    read afterwards leaves the other one to fill and block.
 * 2. **The deadline belongs to the process, not to JUnit.** `@Timeout` is a watchdog for "the
 *    whole test is broken"; it cannot own a process, and when it fires the child survives. Here the
 *    subprocess has its own contract and is always reaped.
 * 3. **Nothing outlives the call.** On every path — completion, timeout, assertion, interruption —
 *    descendants are killed and the parent is destroyed, then both are verified dead. Without the
 *    `finally`, a timed-out test leaves a `pipelinek` JVM behind, and the next test's measurements
 *    are taken on a machine that has been quietly degraded by its own failures.
 *
 * ## Why the outcome is a sealed type and not a Boolean
 *
 * "it finished" and "it did not finish" are not enough to write a correct assertion, and a harness
 * that collapses them forces every caller to re-derive the distinction from a side channel. The
 * three cases carry exactly what a caller needs to say what it observed: [Completed] has an exit
 * code, [TimedOut] has the partial output captured BEFORE the kill plus the diagnostics needed to
 * classify the hang, and [LaunchFailed] has the cause. Absence of an exit code is never inferred.
 *
 * ## Harness fidelity
 *
 * HF0 for the outcome algebra, HF2 for what the callers do with it. This type itself is pure
 * process plumbing with no pipeline semantics; it asserts nothing about the product, and the tests
 * that use it are the ones that carry that claim.
 */
sealed interface CliRun {

    /** The child exited on its own. [exitCode] is the real code, never a sentinel. */
    data class Completed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) : CliRun

    /**
     * The deadline passed and the child had to be destroyed.
     *
     * [stdout] and [stderr] are what had been drained BEFORE the kill, so a hang that happened
     * after producing output still shows it. They are partial by construction and a caller must
     * not read them as a complete run.
     */
    data class TimedOut(
        val stdout: String,
        val stderr: String,
        val diagnostics: CliDiagnostics,
    ) : CliRun

    /** The child never became a process. Distinct from a child that started and then failed. */
    data class LaunchFailed(val cause: Throwable) : CliRun
}

/**
 * Everything needed to classify a hang AFTER the fact.
 *
 * [threadDump] is best-effort and nullable on purpose: a child that is not a JVM, or one that has
 * already exited, has no dump to give. A missing dump must read as "unknown", not as "nothing was
 * wrong".
 */
data class CliDiagnostics(
    val pid: Long,
    val command: String,
    val descendantPids: List<Long>,
    val threadDump: String?,
)

/**
 * Runs a child process and ALWAYS takes ownership of it.
 *
 * @param timeout the subprocess's own contract. Callers pick it from what the command is FOR, not
 *   from how long the enclosing test is allowed to take.
 * @param onStart invoked with the child's pid once the child exists AND both drainers are already
 *   running. It exists to let a caller OBSERVE the child — start a peak-RSS poller, read
 *   `/proc/<pid>/cmdline` — and it deliberately does not hand the [Process] back, so ownership
 *   stays here.
 *
 *   The ordering is load-bearing and not stylistic. `HttpInstalledUatTest` spends several seconds
 *   inside this callback polling for the JVM to appear under the launcher; if the drainers were not
 *   already running, a child that filled a 64 KiB pipe during those seconds would block with nobody
 *   reading, and this function would reintroduce the very deadlock it exists to remove. Drain
 *   first, then observe.
 */
object OwnedSubprocess {

    fun run(
        command: List<String>,
        timeout: Duration,
        workingDirectory: java.io.File? = null,
        environment: Map<String, String> = emptyMap(),
        onStart: (Long) -> Unit = {},
    ): CliRun {
        require(command.isNotEmpty()) { "a subprocess needs a command; an empty one cannot be launched" }

        val builder = ProcessBuilder(command)
        workingDirectory?.let(builder::directory)
        if (environment.isNotEmpty()) builder.environment().putAll(environment)

        val process = try {
            builder.start()
        } catch (e: IOException) {
            return CliRun.LaunchFailed(e)
        }

        // Drainers start BEFORE the wait and run for the whole life of the child. Joining them
        // after the process is gone is safe because each reaches EOF when the write end closes.
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val failure = AtomicReference<Throwable?>(null)

        val outDrainer = drain(process.inputStream, stdout, "stdout", failure)
        val errDrainer = drain(process.errorStream, stderr, "stderr", failure)

        val diagnostics = AtomicReference<CliDiagnostics?>(null)
        val finished = try {
            // Only now: both pipes have a reader, which is why this cannot come earlier — see the
            // @param onStart note.
            //
            // And it lives INSIDE this try on purpose. The ownership guarantee below is a
            // `finally`, so anything raised by the caller's observer must still reach it: a
            // `ProcessPeakRss.poll` that threw, or a `/proc` read that failed, would otherwise
            // escape this function with the child still running and turn one harness bug into a
            // leaked JVM plus whatever the next test measures on a degraded machine. The
            // falsification row `anObserverThatThrowsStillLeavesNoChild` exists for exactly this.
            onStart(process.pid())

            val exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!exited) {
                // Capture BEFORE the kill: once the process is destroyed its dump is gone, and the
                // dump is the only thing that says WHY it was still alive.
                diagnostics.set(capture(process))
            }
            exited
        } finally {
            // The ownership guarantee. Descendants first: killing the parent first can orphan a
            // grandchild into a reparented process this call can no longer see.
            reap(process)
        }

        // Join the drainers BEFORE reading their buffers, and build the result only after.
        //
        // This ordering is not stylistic. `return X` evaluates X first and runs `finally` after, so
        // returning from inside the try read the StringBuilders while the drainers were still
        // copying: the child had exited 0 with 8 MiB written and the result claimed 0 chars. The
        // falsification row `aChildThatSaturatesBothPipesStillCompletes` is what found it, which is
        // the whole reason that row exists rather than a passing smoke test.
        // `Thread.join` takes (millis, nanos); unlike `Process.waitFor` it has no TimeUnit overload.
        outDrainer.join(DRAIN_JOIN_MILLIS)
        errDrainer.join(DRAIN_JOIN_MILLIS)
        failure.get()?.let { throw CliRunException("draining a child pipe failed", it) }

        return if (finished) {
            CliRun.Completed(process.exitValue(), stdout.toString(), stderr.toString())
        } else {
            CliRun.TimedOut(stdout.toString(), stderr.toString(), diagnostics.get()!!)
        }
    }

    /**
     * Kill the whole tree, then prove it.
     *
     * Destroy is asynchronous and a JVM that ignores SIGTERM needs [ProcessHandle.destroyForcibly].
     * Order matters and the verification is the point: a reap that does not confirm death is a
     * reap that leaks, and the failure mode of leaking is a test suite that slows down until it is
     * untrustworthy.
     */
    fun reap(process: Process) {
        process.descendants().toList().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS)
        process.descendants().toList().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /** Live descendants after a reap. Used by the leak assertions to state the property directly. */
    fun survivingDescendants(process: Process): List<Long> =
        process.descendants().toList().filter { it.isAlive }.map { it.pid() }

    private fun drain(
        stream: java.io.InputStream,
        sink: StringBuilder,
        label: String,
        failure: AtomicReference<Throwable?>,
    ) = thread(name = "cli-drain-$label") {
        try {
            stream.bufferedReader(StandardCharsets.UTF_8).use { sink.append(it.readText()) }
        } catch (e: IOException) {
            // A drain broken by the kill we issued is expected and carries no information about
            // the product. Anything else is a real defect in the harness and must not be swallowed.
            if (!failure.compareAndSet(null, e)) return@thread
        }
    }

    private fun capture(process: Process): CliDiagnostics {
        val pid = process.pid()
        return CliDiagnostics(
            pid = pid,
            command = process.info().commandLine().orElse("<unavailable>"),
            descendantPids = process.descendants().toList().map { it.pid() },
            threadDump = threadDumpOf(pid),
        )
    }

    /**
     * Best-effort `jcmd Thread.print`, from the SAME JDK that is running the test.
     *
     * A child that is not a JVM, or a JDK without `jcmd`, yields null. That null is the honest
     * answer: "the dump was not obtainable" is a different fact from "the dump showed nothing".
     */
    private fun threadDumpOf(pid: Long): String? {
        val jcmd = bin("jcmd") ?: return null
        return try {
            val process = ProcessBuilder(jcmd, pid.toString(), "Thread.print", "-l").start()
            val text = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            process.waitFor(DUMP_SECONDS, TimeUnit.SECONDS)
            if (process.isAlive) {
                process.destroyForcibly()
                null
            } else {
                text
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun bin(name: String): String? {
        val javaHome = System.getProperty("java.home") ?: return null
        val candidate = java.io.File(javaHome, "bin").resolve(name)
        return if (candidate.exists()) candidate.absolutePath else null
    }

    private const val KILL_GRACE_SECONDS = 10L
        private const val DUMP_SECONDS = 30L
        private const val DRAIN_JOIN_MILLIS = 30_000L
}

/** A drain or a reap broke in a way that invalidates the result. Never thrown for a child failure. */
class CliRunException(message: String, cause: Throwable) : RuntimeException(message, cause)
