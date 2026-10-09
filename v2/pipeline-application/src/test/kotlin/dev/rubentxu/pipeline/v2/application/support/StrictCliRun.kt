package dev.rubentxu.pipeline.v2.application.support

import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * An ASSERTING wrapper over [OwnedSubprocess], for harnesses that migrated from a raw launch.
 *
 * ## Why this exists
 *
 * [OwnedSubprocess] already owns the child: both pipes drain from the instant it starts, the
 * deadline belongs to the subprocess, and nothing outlives the call. What it deliberately does NOT
 * do is decide what a caller should do with a child that missed its deadline. That decision was
 * previously made — badly — at every call site, and the recurring shape was:
 *
 * ```kotlin
 * process.waitFor(120, TimeUnit.SECONDS)     // boolean discarded
 * return stdout                              // a hang looks exactly like a clean run
 * ```
 *
 * Four harnesses did exactly that, and one (`UAT-L8-CR-BD-017`, via its keytool helper) started
 * keytool with BOTH pipes redirected to `PIPE`, drained neither, and threw the result away — so a
 * keystore that failed to be created surfaced later, as whatever test happened to need the file.
 *
 * This wrapper turns "the child did not finish" into a named, diagnosable failure at the call site,
 * which is the only place that knows what the child was FOR.
 *
 * ## What it deliberately does NOT do
 *
 * **It does not require `exitCode == 0`.** A harness whose rows include deliberately failing
 * pipelines cannot assert that: two rows in `UatLocal008CredentialsTest` expect
 * `RunFinished.outcome == "failure"` on purpose, and whether the CLI maps that onto a non-zero
 * process exit was never measured there. The callers judge the event stream in stdout; this type
 * only guarantees the child finished and its output is complete.
 *
 * `bytes` is the exception, and it is named for it: see that function.
 *
 * ## Why `bytes` exists at all
 *
 * [CliRun.Completed] carries `stdout` as a String. For most assertions that is the right type. For
 * a byte-identity assertion it is not: hashing a decoded String means decode-then-re-encode, which
 * changes the hash for any content that is not valid UTF-8. `CP-001` claims
 * `sha256(blob de git) == sha256(Files.readAllBytes(fichero))`, so it must read BYTES. That is what
 * this second entry point is for, and the split is the reason the two functions do not share code:
 * one drains into a String, the other drains into bytes, and unifying them would mean weakening
 * the byte claim.
 */
object StrictCliRun {

    /**
     * Run [command] and return its completed result, or fail with everything needed to classify
     * the hang.
     *
     * A child that outlives [timeout] has told us the fixture is broken, not that the product is
     * slow, so [CliRun.TimedOut] is raised rather than returned: its partial output is explicitly
     * not a complete run, and a caller that asserted on it would be asserting on a kill.
     */
    fun text(
        label: String,
        command: List<String>,
        timeout: Duration,
        workingDirectory: java.io.File? = null,
        environment: Map<String, String> = emptyMap(),
    ): CliRun.Completed {
        val outcome = OwnedSubprocess.run(command, timeout, workingDirectory, environment)
        return when (outcome) {
            is CliRun.Completed -> outcome
            is CliRun.TimedOut -> throw AssertionError(
                "$label did not finish within ${timeout.seconds}s and was destroyed.\n" +
                    "pid=${outcome.diagnostics.pid} descendants=${outcome.diagnostics.descendantPids}\n" +
                    "command=${outcome.diagnostics.command}\n" +
                    "thread dump:\n" +
                    (outcome.diagnostics.threadDump ?: "<not obtainable: not a JVM, or already gone>") +
                    "\npartial stdout:\n${outcome.stdout.take(4000)}\n" +
                    "partial stderr:\n${outcome.stderr.take(4000)}"
            )
            is CliRun.LaunchFailed -> throw AssertionError("$label never became a process", outcome.cause)
        }
    }

    /**
     * Run [command] and return its stdout AS BYTES, requiring a clean exit.
     *
     * The exit requirement is part of the name's contract and differs from [text] on purpose: the
     * caller here is comparing content hashes, and a non-zero exit means there is no content to
     * compare, which is a failure rather than a result.
     *
     * Both pipes are drained concurrently from the start. The original shape this replaces —
     * `waitFor(timeout)` and only then `inputStream.readBytes()` — is the inverted pipe with the
     * deadline in the worst possible place: a child whose output exceeds the buffer blocks while
     * the parent waits, producing a timeout with no diagnostic and no partial data.
     */
    fun bytesRequiringSuccess(
        label: String,
        command: List<String>,
        timeout: Duration,
        workingDirectory: java.io.File? = null,
    ): ByteArray {
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .also { builder -> workingDirectory?.let(builder::directory) }
            .start()
        val collected = ByteArrayOutputStream()
        val stderr = StringBuilder()
        val outDrainer = thread(name = "strict-cli-drain-stdout") {
            process.inputStream.use { it.copyTo(collected) }
        }
        val errDrainer = thread(name = "strict-cli-drain-stderr") {
            process.errorStream.bufferedReader().use { stderr.append(it.readText()) }
        }
        return try {
            val terminated = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            outDrainer.join(DRAIN_JOIN_MILLIS)
            errDrainer.join(DRAIN_JOIN_MILLIS)
            if (!terminated || process.exitValue() != 0) {
                throw AssertionError(
                    "$label did not succeed: terminated=$terminated " +
                        "exit=${if (terminated) process.exitValue() else "n/a"}\n" +
                        "stderr:\n${stderr.take(4000)}"
                )
            }
            collected.toByteArray()
        } finally {
            OwnedSubprocess.reap(process)
        }
    }

    /** Joining a drain must not outlive the deadline it belongs to. */
    private const val DRAIN_JOIN_MILLIS = 5_000L
}
