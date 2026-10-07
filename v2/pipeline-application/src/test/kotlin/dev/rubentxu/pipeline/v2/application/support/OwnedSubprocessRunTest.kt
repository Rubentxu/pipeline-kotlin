package dev.rubentxu.pipeline.v2.application.support

import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * S6-PRE — does [OwnedSubprocess] actually hold the properties it claims?
 *
 * ## Why this class is a falsification test and not a usage test
 *
 * A primitive that every corpus harness will call is infrastructure nobody re-reads. The only thing
 * that keeps it honest is that each claim here has a child engineered to BREAK it, so the test goes
 * RED if the guarantee weakens. Both children are real JVMs from the same JDK running this test, so
 * the evidence is about the process model and not about a shell's availability.
 *
 * ## The claims, and the child that kills each
 *
 * | claim | child that falsifies it |
 * |---|---|
 * | both pipes drain, so back-pressure cannot deadlock | `saturate` — writes 4 MiB to EACH pipe |
 * | the deadline is the child's, not JUnit's | `hang` — never exits |
 * | nothing outlives the call | `grandchild` — leaves a detached JVM behind |
 *
 * The saturation row is the one that used to be missed. Draining stdout and ignoring stderr still
 * deadlocks, because they are two independent pipes and the child blocks on whichever it fills
 * first.
 */
@DisplayName("S6-PRE — la primitiva de subproceso drena, caduca y no deja huerfanos")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class OwnedSubprocessRunTest {

    private val javaBin: String =
        File(System.getProperty("java.home"), "bin").resolve("java").absolutePath

    private fun child(vararg mode: String): List<String> = listOf(
        javaBin,
        "-cp",
        System.getProperty("java.class.path"),
        SubprocessFixtureProgram::class.java.name,
        *mode,
    )

    @Test
    @DisplayName("un hijo que desborda la capacidad del pipe en stdout Y stderr no bloquea")
    fun aChildThatSaturatesBothPipesStillCompletes() {
        val result = OwnedSubprocess.run(child("saturate"), timeout = Duration.ofMinutes(2))

        assertTrue(
            result is CliRun.Completed,
            "4 MiB written to EACH pipe must not deadlock the child; the harness has to drain both " +
                "concurrently. Observed: ${describe(result)}",
        )
        val completed = result as CliRun.Completed
        assertEquals(0, completed.exitCode)
        // A discrete observation about SIZE, never about how long it took. The byte count is what
        // proves the pipes were drained all the way to EOF instead of being truncated.
        assertTrue(
            completed.stdout.length > 4L * 1024 * 1024 - 64 * 1024,
            "the whole of stdout must have been drained, not just the first buffer; got " +
                "${completed.stdout.length} chars",
        )
        assertTrue(
            completed.stderr.length > 4L * 1024 * 1024 - 64 * 1024,
            "and the same for stderr, which is the pipe a half-fix leaves unread; got " +
                "${completed.stderr.length} chars",
        )
    }

    @Test
    @DisplayName("un hijo que nunca sale produce TimedOut y queda muerto, con su salida capturada")
    fun aChildThatNeverExitsTimesOutAndDies() {
        val result = OwnedSubprocess.run(child("hang"), timeout = Duration.ofSeconds(20))

        assertTrue(result is CliRun.TimedOut, "expected TimedOut, observed: ${describe(result)}")
        val timedOut = result as CliRun.TimedOut
        val pid = timedOut.diagnostics.pid

        assertFalse(
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false),
            "the child must be dead when run() returns; a timed-out test that leaves a JVM behind " +
                "degrades every measurement that follows it",
        )
        // The dump is what classifies a hang AFTER the fact, which is why it is captured before
        // the kill rather than reconstructed from the assertion.
        assertTrue(
            timedOut.diagnostics.threadDump != null,
            "a hung JVM must yield a thread dump; without it the hang can only be guessed at",
        )
    }

    @Test
    @DisplayName("un hijo que deja un nieto colgando no deja supervivientes")
    fun aChildThatLeavesADescendantLeavesNoSurvivors() {
        val result = OwnedSubprocess.run(child("grandchild"), timeout = Duration.ofSeconds(20))

        assertTrue(result is CliRun.TimedOut, "expected TimedOut, observed: ${describe(result)}")
        val descendants = (result as CliRun.TimedOut).diagnostics.descendantPids
        assertTrue(
            descendants.isNotEmpty(),
            "the fixture must really have left a descendant behind, otherwise this row is a green " +
                "produced by the absence of its subject",
        )
        TimeUnit.SECONDS.sleep(2)
        val alive = descendants.filter { ProcessHandle.of(it).map { h -> h.isAlive }.orElse(false) }
        assertTrue(
            alive.isEmpty(),
            "every descendant must be dead once run() returns; still alive: $alive",
        )
    }

    @Test
    @DisplayName("un comando que no existe es LaunchFailed, no una excepcion ni un codigo de salida")
    fun aCommandThatCannotLaunchIsTypedAsSuch() {
        val result = OwnedSubprocess.run(
            listOf("/nonexistent/definitely-not-a-binary"),
            timeout = Duration.ofSeconds(10),
        )

        assertTrue(
            result is CliRun.LaunchFailed,
            "a child that never became a process is a different fact from one that started and " +
                "failed; collapsing them would force callers to re-derive it. Observed: " +
                describe(result),
        )
    }

    private fun describe(result: CliRun): String = when (result) {
        is CliRun.Completed -> "Completed(exit=${result.exitCode}, ${result.stdout.length}+${result.stderr.length} chars)"
        is CliRun.TimedOut -> "TimedOut(pid=${result.diagnostics.pid})"
        is CliRun.LaunchFailed -> "LaunchFailed(${result.cause})"
    }
}
