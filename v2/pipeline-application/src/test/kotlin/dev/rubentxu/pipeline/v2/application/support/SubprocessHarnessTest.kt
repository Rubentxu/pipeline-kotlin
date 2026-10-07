package dev.rubentxu.pipeline.v2.application.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The harness tests its own harness, because everything else in this module now depends on it.
 *
 * ## Why this file comes before the migration
 *
 * Sixty call sites are about to be moved onto [Subprocess]. If the replacement is wrong, sixty
 * tests change behaviour at once and the resulting failures are indistinguishable from product
 * regressions — the exact situation the migration is meant to end. So the harness is proved here,
 * against the three failures it exists to prevent, before anything depends on it.
 *
 * ## Fidelity
 *
 * Real processes, really killed. No mocks, because the properties under test are properties of
 * pipes, process trees and thread scheduling; a mock of those proves only that the mock was
 * called. `@TempDir` and no ambient state, so nothing here can affect the tests that follow.
 *
 * ## Non-vacuity, measured rather than asserted
 *
 * M10 removes the concurrent drain and reads after the wait, which is the exact shape the sixty
 * call sites used. It turns three rows red, and the numbers are the evidence:
 *
 * ```text
 *                                          drained      M10 (drain after wait)
 * a child writing past the pipe buffer     0.108 s  ->  60.497 s, timed out
 * both streams drained concurrently        0.019 s  ->  60.402 s, timed out
 * the hang's diagnosis keeps its output    passes   ->  loses "started"
 * whole class                              7.9 s    ->  128.8 s
 * ```
 *
 * Two rows stay green under M10, and one of them matters: declining stdout does not hang, because
 * a discarded stream has no pipe to fill. That is what makes the classification behind this work
 * credible — the deadlock belongs to the "pipe plus read afterwards" class and to no other, so the
 * migration can be aimed instead of sprayed.
 */
@Timeout(value = 300, unit = java.util.concurrent.TimeUnit.SECONDS)
class SubprocessHarnessTest {

    @TempDir
    lateinit var root: Path

    // ------------------------------------------------------------ the deadlock

    /**
     * The defect this whole file exists to remove: a child that writes far more than a pipe holds.
     *
     * A Unix pipe buffers about 64 KiB on Linux. A child that writes more blocks in `write` until a
     * reader appears, so the previous shape — `waitFor()` first, `readText()` after — could not
     * complete for **any** output over the buffer, regardless of how fast the product was.
     *
     * [Subprocess] drains both streams on their own threads from the moment the child starts, so
     * the child never waits for the assertion and the assertion never waits for the child.
     */
    @Test
    fun `a child writing far past the pipe buffer is drained, not blocked`() {
        // 1 MiB: sixteen times a pipe buffer, so nothing about a buffer can explain the result.
        val megabyte = 1024 * 1024
        val outcome = Subprocess.run(
            command = listOf(
                "sh", "-c",
                "head -c $megabyte /dev/zero | tr '\\0' 'x'; printf '%s' done",
            ),
            timeout = Duration.ofSeconds(60),
        )

        val exited = outcome.requireExited()
        assertEquals(0, exited.exitCode, "the child failed: $outcome")
        assertEquals(megabyte + 4, exited.stdout.length, "the drain lost or duplicated bytes")
        assertTrue(
            exited.stdout.endsWith("done"),
            "the tail after a megabyte of output did not arrive, so the drain stopped early",
        )
    }

    /**
     * Both streams at once, which is the harder case: two pipes to fill, and a child that would
     * block on whichever it wrote second.
     */
    @Test
    fun `both streams are drained concurrently`() {
        val half = 512 * 1024
        val outcome = Subprocess.run(
            command = listOf(
                "sh", "-c",
                "head -c $half /dev/zero | tr '\\0' 'o' & head -c $half /dev/zero | tr '\\0' 'e' >&2; wait",
            ),
            timeout = Duration.ofSeconds(60),
        )

        val exited = outcome.requireExited()
        assertEquals(half, exited.stdout.length, "stdout was not drained in full: $outcome")
        assertEquals(half, exited.stderr.length, "stderr was not drained in full: $outcome")
    }

    // ------------------------------------------------------------ the hang

    /**
     * A child that never finishes is reported as a hang, with evidence, instead of ending the run.
     *
     * The bound here is short on purpose. It is not measuring anything about the product — it is
     * proving that an expiry produces a diagnosis rather than an abandoned build.
     */
    @Test
    fun `a child that never exits times out with a diagnosis`() {
        val outcome = Subprocess.run(
            command = listOf("sh", "-c", "printf '%s' started; sleep 600"),
            timeout = Duration.ofSeconds(3),
        )

        val timedOut = assertInstanceOf(
            SubprocessOutcome.TimedOut::class.java,
            outcome,
            "expected a hang to be reported",
        )
        assertTrue(timedOut.stillAliveBeforeKill, "the child was already gone, so this row is not " +
            "covering the hang it was written for")
        assertTrue(
            timedOut.stdout.contains("started"),
            "the diagnosis dropped what the child had already said, which is usually the whole clue",
        )
        assertTrue(
            timedOut.description.contains("exceeded"),
            "the description does not name the failure, so a reader has to open this class to " +
                "understand a failure message",
        )
    }

    // ------------------------------------------------------------ the tree

    /**
     * Killing the parent kills the children, which is what stops one test from poisoning the next.
     *
     * A pipeline CLI forks the `sh` its pipeline describes. Those outlive a `destroyForcibly()`
     * aimed at the CLI, keep the temp directory, and make an unrelated later test fail in a way
     * that looks like a product defect.
     *
     * The child here is written to be unmistakable: it creates a sentinel file, so the row asserts
     * the process really ran rather than that a `sleep` was merely spawned.
     */
    @Test
    fun `killing the parent also kills the descendants`() {
        val sentinel = root.resolve("grandchild-ran")
        val outcome = Subprocess.run(
            command = listOf(
                "sh", "-c",
                // The grandchild outlives its parent unless something kills the tree.
                "( sh -c 'touch ${sentinel.toAbsolutePath()}; sleep 600' ) & sleep 600",
            ),
            timeout = Duration.ofSeconds(4),
        )

        assertInstanceOf(
            SubprocessOutcome.TimedOut::class.java,
            outcome,
            "expected a hang",
        )
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (!Files.exists(sentinel) && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue(Files.exists(sentinel), "premise broken: the grandchild never ran, so this row " +
            "is not covering a live descendant")

        val timedOut = outcome as SubprocessOutcome.TimedOut
        assertTrue(
            timedOut.descendantsTerminated >= 1,
            "the harness killed the parent and reported ${timedOut.descendantsTerminated} " +
                "descendants, so a grandchild was left running",
        )
    }

    // ------------------------------------------------------------ the contract

    /**
     * A command that cannot start is its own outcome, not an exception at an unrelated line.
     */
    @Test
    fun `a command that cannot start is reported as such`() {
        val outcome = Subprocess.run(
            command = listOf("this-binary-does-not-exist-${System.nanoTime()}"),
            timeout = Duration.ofSeconds(10),
        )
        assertTrue(
            outcome is SubprocessOutcome.NotStarted,
            "a missing binary surfaced as $outcome",
        )
        assertTrue(
            outcome.description.contains("never started"),
            "the description does not distinguish 'did not run' from 'ran and hung'",
        )
    }

    /**
     * A zero or negative bound is refused rather than silently treated as "no limit".
     *
     * Without this, `Duration.ZERO` would be the shortest way to reintroduce an unbounded wait,
     * which is the defect this harness was created to end.
     */
    @Test
    fun `a non-positive bound is refused`() {
        listOf(Duration.ZERO, Duration.ofSeconds(-1)).forEach { bound ->
            val thrown = runCatching {
                Subprocess.run(listOf("true"), timeout = bound)
            }.exceptionOrNull()
            assertTrue(
                thrown is IllegalArgumentException,
                "bound $bound was accepted instead of refused",
            )
        }
    }

    /**
     * stdout can be declined, and declining it must not reintroduce the deadlock.
     *
     * A declined stream is discarded at the OS level rather than left as an unread pipe, so the
     * child still runs at full speed and the caller simply gets an empty string.
     */
    @Test
    fun `declining stdout keeps the child running at full speed`() {
        val megabyte = 1024 * 1024
        val outcome = Subprocess.run(
            // The megabyte goes to the declined stream and the marker to the captured one, so the
            // row can tell "declined" from "lost": a megabyte of stdout is discarded by the OS and
            // never has to be read by anyone.
            command = listOf("sh", "-c", "head -c $megabyte /dev/zero; printf '%s' done >&2"),
            timeout = Duration.ofSeconds(60),
            captureStdout = false,
        )

        val exited = outcome.requireExited()
        assertEquals(0, exited.exitCode, "the child failed: $outcome")
        assertEquals("", exited.stdout, "stdout was declined but something was captured")
        assertEquals("done", exited.stderr, "stderr was not captured while stdout was declined")
    }
}