package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.CaptureRetainPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShellProtocolLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The captured stdout has ONE rule, whichever path published it.
 *
 * **The guarantee.** A `sh` step that returns its stdout publishes the same VALUE whether it ran
 * now or was resumed after the worker died. The two paths are different code: the executor reads
 * the value when the process ends, and the observer reads it out of the control directory
 * afterwards. Before this cut they read the same file under two different rules — the executor
 * trimmed it and the observer did not — so a program that printed `hello\n` published `"hello"`
 * on a fresh run and `"hello\n"` on a resumed one, and the difference reached the consumer
 * through the typed value of a step whose behaviour had not changed.
 *
 * **The observable capacity it serves.** A durable `sh` with `returnStdout` survives the death
 * of the worker that was running it: the resumed run reports the value the program printed. That
 * capacity is exercised end to end by the E2E layer; what is checked HERE is the thing it rests
 * on and that no end-to-end scenario can localise — the two readers agreeing on the value.
 *
 * **Why this is a unit test and not a scenario.** The window between "the process published
 * `result.txt`" and "the executor deleted `output.txt`" is microseconds wide, so a scenario that
 * tried to kill a worker inside it would be flaky by construction. The seam, on the other hand,
 * is fully observable in process: the observer takes a control directory and returns a terminal,
 * and the executor's reader takes a control directory and returns a string.
 *
 * **Why the value is compared against the absolute text too.** A comparison between the two
 * readers alone would also pass if both were wrong in the same way. The rows below therefore pin
 * the text the value IS, and the equality between the paths separately.
 */
class CapturedStdoutSingleRuleTest {

    /**
     * The directory this instrumentation writes into.
     *
     * The test framework creates it before the test and removes it when the
     * test ends, so the fixture owns its own temporary instead of leaving one
     * behind in the shared temporary directory of the machine.
     */
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a resumed execution publishes the value a fresh execution publishes`() {
        val root = controlRootWith(captured = "hello\n")

        val recovered = recoveredCapturedStdout(root)
        val fresh = freshCapturedStdout(root)

        assertEquals("hello", recovered)
        assertEquals("hello", fresh)
        assertEquals(fresh, recovered)
    }

    @Test
    fun `an output file that holds nothing is an emptiness in both paths`() {
        val root = controlRootWith(captured = "")

        assertEquals("", recoveredCapturedStdout(root))
        assertEquals("", freshCapturedStdout(root))
    }

    @Test
    fun `an absent output file is an absence in both paths`() {
        val root = controlRootWith(captured = null)

        assertNull(recoveredCapturedStdout(root))
        assertNull(freshCapturedStdout(root))
    }

    @Test
    fun `the observer reads the captured stdout without disposing of it`() {
        val root = controlRootWith(captured = "hello\n")

        recoveredCapturedStdout(root)

        assertTrue(
            Files.exists(ShellProtocolLayout(root.resolve(OP_ID)).outputFile),
            "the observer read the captured stdout; it did not dispose of the evidence",
        )
    }

    /**
     * The value the RECOVERED path publishes: the observer's terminal, read through the Step's
     * own vocabulary. The observer has no policy and no notion of a step outcome, so the
     * captured stdout is the only thing this test asks it for.
     */
    private fun recoveredCapturedStdout(root: Path): String? {
        val observation = ExternalSubprocessRecovery(FixedClock, root).observe(OP_ID)
        val observed = assertInstanceOf(RunningSubprocessObservation.Observed::class.java, observation)
        val exited = assertInstanceOf(DurableTaskTerminal.Exited::class.java, observed.terminal)
        return exited.output.capturedStdout
    }

    /**
     * The value the FRESH path publishes: the executor's own reader, on the same file.
     *
     * The retention policy is RETAIN, not the default READ_THEN_DELETE, because the default
     * deletes the file it read — and this test reads the SAME file through both paths. The
     * retention is not what is under test here; the VALUE is.
     */
    private fun freshCapturedStdout(root: Path): String? =
        DurableShellExecutor().readOutputText(root.resolve(OP_ID), CaptureRetainPolicy.RETAIN)

    /**
     * A control directory holding a finished operation: `result.txt` publishes the exit code, so
     * the reconciler classifies it as COMPLETE, and `output.txt` holds the captured stdout — or
     * is absent, which is a different fact from an empty one.
     */
    private fun controlRootWith(captured: String?): Path {
        val root = tempDir.resolve("captured-stdout")
        val opDir = root.resolve(OP_ID)
        Files.createDirectories(opDir)
        val protocol = ShellProtocolLayout(opDir)
        Files.writeString(protocol.resultFile, "0")
        if (captured != null) {
            Files.writeString(protocol.outputFile, captured)
        }
        return root
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(2_000L)
    }

    private companion object {
        const val OP_ID = "op-1"
    }
}
