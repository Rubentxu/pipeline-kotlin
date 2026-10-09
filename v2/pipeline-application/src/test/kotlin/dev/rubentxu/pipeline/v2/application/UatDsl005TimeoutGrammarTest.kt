package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.TimeoutScheduled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.support.CliRun
import dev.rubentxu.pipeline.v2.application.support.OwnedSubprocess

/**
 * UAT-DSL-005: Timeout Grammar — retry and timeout configuration test.
 *
 * Exercises the retry(count, delaySeconds) and timeout(seconds) DSL constructs
 * and validates that RetryAttemptStarted/RetryAttemptFinished and
 * TimeoutScheduled events are emitted.
 */
@Timeout(120)
class UatDsl005TimeoutGrammarTest {

    /**
     * The subprocess's own contract, and it sits BELOW the class watchdog on purpose.
     *
     * `@Timeout(120)` is a watchdog for "this test is broken"; it cannot own a process, and when it
     * fires the child survives. The child gets its own deadline so a hang is classified and reaped.
     */
    private val cliDeadline: Duration = Duration.ofSeconds(90)

    // WU-LPR-072: shared AppBinSupport handles the pipelinek (post-WU-LPR-070)
    // and pipeline-application (legacy) install locations.
    private val appBin: Path by lazy { AppBinSupport.discover() }

    private val timeoutRetryScript: Path by lazy {
        Paths.get(javaClass.getResource("/timeout-retry.pipeline.kts")!!.toURI())
    }

    @Test
    fun `timeout-retry script compiles and emits parseable JSON`() {
        val (_, rawOutput) = runBinary("run", "--format", "json", timeoutRetryScript.toString())
        val output = rawOutput.trim()

        assertTrue(output.isNotEmpty(), "stdout must not be empty")
        assertTrue(output.startsWith("["), "stdout must start with '['")
        assertTrue(output.endsWith("]"), "stdout must end with ']'")

        val events = JsonEventLog.decode(output)
        assertNotNull(events)
    }

    @Test
    fun `timeout-retry script emits retry attempt events`() {
        val (_, events) = runAndDecode()

        // Verify retry attempt events are emitted
        val retryStartedEvents = events.filter { it is RetryAttemptStarted }
        val retryFinishedEvents = events.filter { it is RetryAttemptFinished }

        assertTrue(retryStartedEvents.isNotEmpty(), "Must have RetryAttemptStarted events: $events")
        assertTrue(retryFinishedEvents.isNotEmpty(), "Must have RetryAttemptFinished events: $events")

        // Verify retry event structure
        val ras = retryStartedEvents.first() as RetryAttemptStarted
        assertTrue(ras.attemptNumber >= 1, "attemptNumber must be >= 1")
        assertTrue(ras.maxAttempts >= 1, "maxAttempts must be >= 1")
        assertEquals(ras.maxAttempts, (retryFinishedEvents.first() as RetryAttemptFinished).maxAttempts)
    }

    @Test
    fun `T21 retry terminal transitions project exactly one RetryAttemptFinished per attempt`(
        @TempDir tempDir: Path,
    ) {
        // The marker is the state T21 exists to observe: attempt 1 must fail because the marker is
        // absent, attempt 2 must succeed because attempt 1 created it. It used to be a hardcoded
        // `/tmp/t21-marker`, which made a fixed global name part of the fixture's meaning.
        val marker = tempDir.resolve("t21-marker").toAbsolutePath().toString()
        val fixture = tempDir.resolve("t21.pipeline.kts")
        Files.writeString(
            fixture,
            """
            pipeline {
                stages {
                    stage("t21") {
                        retry(2) {
                            sh("test -f '$marker' && exit 0 || { touch '$marker'; exit 1; }")
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        val events = decodeOrThrow(runBinary("run", "--format", "json", fixture.toString()))

        val started = events.filterIsInstance<RetryAttemptStarted>()
        val finished = events.filterIsInstance<RetryAttemptFinished>()

        // Exactly one Started + one Finished per terminal attempt transition.
        assertEquals(2, started.size, "Started: $started")
        assertEquals(2, finished.size, "Finished: $finished")

        val byAttempt = finished.associateBy { it.attemptNumber }
        assertEquals(2, byAttempt.size, "one Finished per attempt: $finished")
        assertEquals("failed", byAttempt.getValue(1).outcome, "attempt 1 must project FAILED")
        assertEquals("succeeded", byAttempt.getValue(2).outcome, "attempt 2 must project SUCCEEDED")

        // Ordering when the substrate preserves it: Started(1) < Finished(1) < Started(2) < Finished(2).
        val retryIdx = events
            .filter { it is RetryAttemptStarted || it is RetryAttemptFinished }
            .map { e ->
                when (e) {
                    is RetryAttemptStarted -> "RetryAttemptStarted" to e.attemptNumber
                    is RetryAttemptFinished -> "RetryAttemptFinished" to e.attemptNumber
                    else -> error("unreachable")
                }
            }
        assertEquals(
            listOf(
                "RetryAttemptStarted" to 1,
                "RetryAttemptFinished" to 1,
                "RetryAttemptStarted" to 2,
                "RetryAttemptFinished" to 2,
            ),
            retryIdx,
            "terminal transitions must interleave with attempt starts in order",
        )
    }

    @Test
    fun `timeout-retry script emits timeout scheduled events`() {
        val (_, events) = runAndDecode()

        val timeoutEvents = events.filter { it is TimeoutScheduled }
        assertTrue(timeoutEvents.isNotEmpty(), "Must have TimeoutScheduled events: $events")

        val ts = timeoutEvents.first() as TimeoutScheduled
        assertTrue(ts.timeoutSeconds > 0, "timeoutSeconds must be positive")
        assertTrue(ts.timeoutAction.isNotEmpty(), "timeoutAction must not be empty")
    }

    @Test
    fun `T22 valid timeout schedules exactly once before child`(@TempDir tempDir: Path) {
        val fixture = tempDir.resolve("t22.pipeline.kts")
        Files.writeString(
            fixture,
            """
            pipeline {
                stages {
                    stage("t22") {
                        timeout(30, "SECONDS") {
                            sh("echo t22-ok")
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        val events = decodeOrThrow(runBinary("run", "--format", "json", fixture.toString()))

        val scheduled = events.filterIsInstance<TimeoutScheduled>()
        assertEquals(1, scheduled.size, "TimeoutScheduled must be emitted exactly once: $scheduled")
        assertTrue(scheduled.first().timeoutSeconds > 0, "timeoutSeconds must be positive")
        assertEquals("success", events.lastOrNull().let { (it as? RunFinished)?.outcome }, "run must succeed")

        // Ordering: TimeoutScheduled precedes the child StepStarted it governs.
        val schedIdx = events.indexOfFirst { it is TimeoutScheduled }
        val childStartIdx = events.indexOfFirst { it is StepStarted }
        assertTrue(schedIdx in 0 until childStartIdx, "TimeoutScheduled must precede child StepStarted")
    }

    @Test
    fun `timeout-retry script produces complete event timeline`() {
        val (_, events) = runAndDecode()

        // Verify RunStarted
        assertTrue(events.first() is CompilationStarted, "First event must be CompilationStarted (durable spine: script compiles before the run starts)")
        // Verify RunFinished
        assertTrue(events.last() is RunFinished, "Last event must be RunFinished")

        // Verify we have stage events (3 stages: RetryTest, TimeoutTest, ErrorHandling)
        val stageStartedEvents = events.filter { it is StageStarted }
        assertTrue(stageStartedEvents.size >= 3, "Must have at least 3 StageStarted events: ${stageStartedEvents.size}")

        // Verify we have step events
        assertTrue(events.any { it is StepStarted }, "Must have StepStarted event")
        assertTrue(events.any { it is StepFinished }, "Must have StepFinished event")

        // Verify compilation events
        assertTrue(events.any { it is CompilationStarted }, "Must have CompilationStarted event")
        assertTrue(events.any { it is CompilationFinished }, "Must have CompilationFinished event")
    }

    @Test
    fun `error step type is emitted`() {
        val (_, events) = runAndDecode()

        val stepStartedEvents = events.filter { it is StepStarted }
        val stepTypes = stepStartedEvents.map { (it as StepStarted).stepType }.distinct()

        // catchError wraps error() in timeout-retry fixture — confirms error-handling semantics
        assertTrue(stepTypes.contains("catchError") || stepTypes.contains("error"),
            "Must have error-handling step type (catchError or error): $stepTypes")
    }

    /**
     * S6-PRE. The ONE way this class runs the installed binary.
     *
     * Four launch sites, and all four had the same three defects:
     *
     * - `waitFor()` with no deadline. A child that hangs hangs the TEST, and the class `@Timeout(120)`
     *   is what finally cuts it, leaving a `pipelinek` JVM alive behind it and never saying why.
     * - `.redirectError(ProcessBuilder.Redirect.PIPE)` with the stream read only AFTER `waitFor()`.
     *   That is the pipe-buffer hazard. **Measured, not assumed:** on this host a fresh pipe is
     *   8192 bytes (`F_GETPIPE_SZ` on 20 of 20 pipes; `fs.pipe-max-size = 1048576` is the ceiling a
     *   process may request, not the default), and this fixture's child writes 653 bytes of stderr.
     *   So the hazard is LATENT today with 12x headroom — the class is not currently hanging, and
     *   "it has not hung yet" is not the property that protects it. [OwnedSubprocess] drains both
     *   pipes from the instant the child starts, so the question stops mattering.
     * - `Files.createTempFile` with no parent, six sites, landing in `java.io.tmpdir`. Measured on
     *   the BLOCK 3.3 gate: 8 files per run from this class alone, and 142 more accumulated by
     *   earlier runs that nobody had inventoried.
     *
     * A fourth defect was not about the process at all: `/tmp/t21-marker` was a hardcoded global
     * path, and it is the state the T21 row exists to observe. A fixed name outside the test's own
     * directory is shared mutable state, which Harness Fidelity 4 forbids; it is now inside the
     * row's `@TempDir` and interpolated into the script.
     *
     * stderr used to be read only on the failure path, so a green run discarded it and a red run
     * got it for the first time. Both streams are now always in hand, and both go into the message.
     */
    private fun runBinary(vararg args: String): Triple<Int, String, String> {
        val outcome = OwnedSubprocess.run(
            command = listOf(appBin.toString()) + args,
            timeout = cliDeadline,
        )
        return when (outcome) {
            is CliRun.Completed -> Triple(outcome.exitCode, outcome.stdout, outcome.stderr)
            is CliRun.TimedOut -> error(
                "the installed binary hung on ${args.toList()} after ${cliDeadline.seconds}s; " +
                    "pid=${outcome.diagnostics.pid} descendants=${outcome.diagnostics.descendantPids}. " +
                    "This is an ENVIRONMENT signal, and it is what previously left a JVM alive: the " +
                    "old waitFor() had no deadline, so the class @Timeout cut the test instead and " +
                    "the failure never said why. Partial output: " +
                    (outcome.stdout + outcome.stderr).takeLast(800),
            )
            is CliRun.LaunchFailed -> error(
                "the installed binary could not be launched on ${args.toList()}: ${outcome.cause}",
            )
        }
    }

    private fun runAndDecode(): Pair<String, List<DomainEvent>> {
        val (exitCode, stdout, stderr) = runBinary("run", "--format", "json", timeoutRetryScript.toString())
        if (exitCode != 0) {
            throw IllegalStateException("CLI exited with $exitCode. stdout: $stdout. stderr: $stderr")
        }
        return stdout.trim() to JsonEventLog.decode(stdout)
    }

    private fun decodeOrThrow(result: Triple<Int, String, String>): List<DomainEvent> {
        val (exitCode, stdout, stderr) = result
        if (exitCode != 0) {
            throw IllegalStateException("CLI exited with $exitCode. stdout: $stdout. stderr: $stderr")
        }
        return JsonEventLog.decode(stdout)
    }
}
