package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * B14 / RP6-A WU-091 lock laws, against the REAL distribution (HF2 forked CLI),
 * shared `--db` + `--control-root` so both processes share the lock namespace
 * (`<controlDirRoot>/locks`, see the capability bridge).
 *
 * Laws under test (the operator's G4 hard list):
 *  1. two runs serialize on one resource (cross-process mutual exclusion);
 *  2. `skipIfLocked` runs nothing and succeeds under contention;
 *  3. a nested `lock` on the same run RE-ENTERS (no deadlock);
 *  4. a body failure RELEASES the hold (release at the failure terminal);
 *  5. a contended waiter with `timeoutSeconds` fails with timeout semantics;
 *  6. cancellation (outer `timeout`) cancels an indefinite waiter;
 *  7. sibling `parallel` branches contend (different execution lanes);
 *  8. rerun of a COMPLETED lock block does not duplicate the body (memoized);
 *  9. §4 resume row: after a failed body, the rerun RE-ACQUIRES before fresh
 *     effects (fresh LockRequested/LockAcquired events) while journalized
 *     children return memoized results (no duplicate markers).
 *
 * Coordinator-level laws (hold registry, cancellation inside `acquire`, POSIX
 * semantics) are certified by `FileLockCoordinatorTest` and are not repeated
 * here; this file certifies the PRODUCTION ROUTING of those laws.
 */
@Timeout(300)
class UatLockBlockDurableTest {
    private val processes = mutableListOf<Process>()

    @AfterEach
    fun terminateProcesses() {
        processes.forEach { process ->
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        processes.clear()
    }

    private fun runCli(workdir: Path, args: List<String>): CliResult {
        val output = Files.createTempFile(workdir, "pipeline-cli-", ".log")
        val process = startCli(workdir, args, output)
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        val text = Files.readString(output)
        if (!finished) error("CLI did not finish: $text")
        return CliResult(process.exitValue(), text)
    }

    /** Starts a CLI run and returns immediately: for the holder side of a contention. */
    private fun runCliAsync(workdir: Path, args: List<String>): Process =
        startCli(workdir, args, Files.createTempFile(workdir, "pipeline-cli-", ".log"))

    private fun startCli(workdir: Path, args: List<String>, output: Path): Process =
        ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.MainKt",
            *args.toTypedArray(),
        )
            .directory(workdir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start()
            .also { processes.add(it) }

    private data class CliResult(val exitCode: Int, val output: String)

    private fun markerLines(marker: Path): List<String> =
        if (Files.exists(marker)) Files.readAllLines(marker).filter { it.isNotBlank() } else emptyList()

    private fun awaitMarkerLine(marker: Path, expected: String, timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (markerLines(marker).contains(expected)) return
            Thread.sleep(50)
        }
        error("marker line '$expected' never appeared in $marker; contents=${markerLines(marker)}")
    }

    private fun invocation(tempDir: Path, script: Path) = listOf(
        "run",
        "--db", tempDir.resolve("journal.db").toString(),
        "--control-root", tempDir.resolve("control").toString(),
        script.toString(),
    )

    /**
     * Holder-side pattern: the holder holds the resource until the TEST releases it
     * through a sentinel file. A timed sleep is a FALSE-GREEN generator: the
     * waiter's JVM start + pipeline compilation can outlast it, so the waiter
     * arrives at an idle resource and contention is never exercised. With the
     * handshake the hold provably spans the waiter's whole critical section, and
     * the test does not pay the sleep.
     */
    private fun holderScript(resource: String, marker: Path, release: Path): Path {
        val script = marker.resolveSibling("lock-holder.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("hold") {
                        lock("$resource") {
                            sh("echo A-enter >> '$marker' && while [ ! -f '$release' ]; do sleep 0.2; done && echo A-exit >> '$marker'")
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        return script
    }

    @Test
    fun `WL-L1 two runs serialize on one resource`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val release = tempDir.resolve("release.txt")
        val scriptA = holderScript("deploy", marker, release)
        // Phase 1: a bounded waiter, so the RUN ITSELF decides the ordering.
        // (An unbounded waiter would have to be released before it can finish, and
        // the release would then race the holder's exit line — a timing artefact,
        // not a law.)
        val scriptB = tempDir.resolve("lock-waiter.kts")
        Files.writeString(
            scriptB,
            """
            pipeline {
                stages {
                    stage("wait") {
                        lock("deploy", timeoutSeconds = 5) {
                            sh("echo B-enter >> '$marker'")
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val holder = runCliAsync(tempDir, invocation(tempDir, scriptA))
        awaitMarkerLine(marker, "A-enter")

        // Phase 1 — cross-process exclusion: the waiter's own deadline expires
        // while the holder is provably still inside (A-exit absent), so the two
        // JVMs really contend through the OS lock.
        val denied = runCli(tempDir, invocation(tempDir, scriptB))
        assertTrue(denied.exitCode != 0, "the contended waiter must fail; output:\n${denied.output}")
        assertTrue(
            denied.output.contains("\"kind\":\"LockAcquireFailed\""),
            "the failed wait must be observable; output:\n${denied.output}",
        )
        assertEquals(listOf("A-enter"), markerLines(marker), "the denied waiter must not run its body")
        assertTrue(holder.isAlive, "the holder must still hold the resource")

        // Phase 2 — after the holder releases, the SAME resource is acquirable by a
        // fresh run: the hold was an OS lock, not process-local bookkeeping.
        Files.writeString(release, "go")
        assertTrue(holder.waitFor(60, TimeUnit.SECONDS), "holder must finish after release")
        val next = tempDir.resolve("lock-after.kts")
        Files.writeString(
            next,
            """
            pipeline {
                stages {
                    stage("after") {
                        lock("deploy") {
                            sh("echo C-enter >> '$marker'")
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val after = runCli(tempDir, invocation(tempDir, next))
        assertEquals(0, after.exitCode, "output:\n${after.output}")
        assertEquals(
            listOf("A-enter", "A-exit", "C-enter"),
            markerLines(marker),
            "the follow-up run must run after the holder exited",
        )
        assertTrue(
            after.output.contains("\"kind\":\"LockAcquired\""),
            "the follow-up run must observe its own acquisition; output:\n${after.output}",
        )
    }

    @Test
    fun `WL-L2 skipIfLocked runs no body and succeeds under contention`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val release = tempDir.resolve("release.txt")
        val skipped = tempDir.resolve("skipped.txt")
        val scriptA = holderScript("db", marker, release)
        val scriptB = tempDir.resolve("lock-skip.kts")
        Files.writeString(
            scriptB,
            """
            pipeline {
                stages {
                    stage("maybe") {
                        lock("db", skipIfLocked = true) {
                            sh("echo B-ran >> '$skipped'")
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val holder = runCliAsync(tempDir, invocation(tempDir, scriptA))
        awaitMarkerLine(marker, "A-enter")

        val skipper = runCli(tempDir, invocation(tempDir, scriptB))
        assertEquals(0, skipper.exitCode, "skipIfLocked is the success-without-body contract; output:\n${skipper.output}")
        assertEquals(emptyList<String>(), markerLines(skipped), "the skipped body must never run")
        assertTrue(
            skipper.output.contains("\"kind\":\"LockSkipped\""),
            "the skip decision must be observable; output:\n${skipper.output}",
        )
        // The holder is still inside its critical section while the skip was decided.
        assertEquals(listOf("A-enter"), markerLines(marker))
        holder.destroyForcibly()
    }

    @Test
    fun `WL-L3 nested lock on the same run re-enters instead of deadlocking`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val script = tempDir.resolve("lock-nested.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("nested") {
                        lock("outer") {
                            sh("echo n1 >> '$marker'")
                            lock("outer") {
                                sh("echo n2 >> '$marker'")
                            }
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val result = runCli(tempDir, invocation(tempDir, script))
        assertEquals(0, result.exitCode, "same-lane re-entrancy must not deadlock; output:\n${result.output}")
        assertEquals(listOf("n1", "n2"), markerLines(marker))
    }

    @Test
    fun `WL-L4 body failure releases the hold and the next run acquires`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val failing = tempDir.resolve("lock-fail.kts")
        Files.writeString(
            failing,
            """
            pipeline {
                stages {
                    stage("boom") {
                        lock("res") {
                            sh("echo entering >> '$marker' && exit 3")
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val next = tempDir.resolve("lock-next.kts")
        Files.writeString(
            next,
            """
            pipeline {
                stages {
                    stage("after") {
                        lock("res") {
                            sh("echo released-ok >> '$marker'")
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val first = runCli(tempDir, invocation(tempDir, failing))
        assertTrue(first.exitCode != 0, "a failed body must fail the run; output:\n${first.output}")
        assertTrue(
            first.output.contains("\"kind\":\"LockReleased\""),
            "release must be observable at the failure terminal; output:\n${first.output}",
        )
        // If the hold leaked, this run would hang until the test timeout instead
        // of finishing green.
        val second = runCli(tempDir, invocation(tempDir, next))
        assertEquals(0, second.exitCode, "output:\n${second.output}")
        assertEquals(listOf("entering", "released-ok"), markerLines(marker))
    }

    @Test
    fun `WL-L5 contended waiter with timeout fails with timeout semantics`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val release = tempDir.resolve("release.txt")
        val scriptA = holderScript("db", marker, release)
        val scriptB = tempDir.resolve("lock-timeout.kts")
        Files.writeString(
            scriptB,
            """
            pipeline {
                stages {
                    stage("impatient") {
                        lock("db", timeoutSeconds = 1) {
                            sh("echo B-ran >> '$marker'")
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val holder = runCliAsync(tempDir, invocation(tempDir, scriptA))
        awaitMarkerLine(marker, "A-enter")

        val waiter = runCli(tempDir, invocation(tempDir, scriptB))
        assertTrue(waiter.exitCode != 0, "an expired lock wait must fail the run; output:\n${waiter.output}")
        assertTrue(
            waiter.output.contains("\"kind\":\"LockAcquireFailed\""),
            "the failed wait must be observable; output:\n${waiter.output}",
        )
        assertTrue(
            !markerLines(marker).contains("B-ran"),
            "a denied waiter must never run its body",
        )
        holder.destroyForcibly()
    }

    @Test
    fun `WL-L6 an enclosing timeout bounds an indefinite waiter`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val release = tempDir.resolve("release.txt")
        val scriptA = holderScript("db", marker, release)
        val scriptB = tempDir.resolve("lock-cancel.kts")
        Files.writeString(
            scriptB,
            """
            pipeline {
                stages {
                    stage("cancellable") {
                        timeout(2, "SECONDS") {
                            lock("db") {
                                sh("echo B-ran >> '$marker'")
                            }
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val holder = runCliAsync(tempDir, invocation(tempDir, scriptA))
        awaitMarkerLine(marker, "A-enter")

        val waiter = runCli(tempDir, invocation(tempDir, scriptB))
        assertTrue(waiter.exitCode != 0, "the expired budget must fail the run; output:\n${waiter.output}")
        assertTrue(
            !markerLines(marker).contains("B-ran"),
            "a waiter bounded out by the block deadline must never run its body",
        )
        assertTrue(
            waiter.output.contains("\"kind\":\"LockAcquireFailed\""),
            "the bounded-out wait must be observable and typed; output:\n${waiter.output}",
        )
        // The wait stopped at the DEADLINE, not when the holder happened to release:
        // the holder is still inside its critical section at this point.
        assertEquals(listOf("A-enter"), markerLines(marker))
        holder.destroyForcibly()
    }

    @Test
    fun `WL-L7 sibling parallel branches contend on one resource`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val script = tempDir.resolve("lock-parallel.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("matrix") {
                        parallel {
                            branch("left") {
                                lock("shared") {
                                    sh("echo L-enter >> '$marker' && sleep 1 && echo L-exit >> '$marker'")
                                }
                            }
                            branch("right") {
                                lock("shared") {
                                    sh("echo R-enter >> '$marker'")
                                }
                            }
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val result = runCli(tempDir, invocation(tempDir, script))
        assertEquals(0, result.exitCode, "output:\n${result.output}")
        // Different lanes (runId + parallelLineage) => contention, not re-entrancy.
        // WHICH branch wins is not a contract (scheduling is not deterministic), so
        // the law is DISJOINT critical sections, not a fixed ordering.
        val lines = markerLines(marker)
        assertEquals(3, lines.size, "each branch entered and the left one exited: $lines")
        assertTrue(lines.contains("R-enter"), "the right branch must run: $lines")
        val leftIndex = lines.indexOf("L-enter")
        assertTrue(leftIndex >= 0 && lines[leftIndex + 1] == "L-exit", "left section is whole: $lines")
        val rightIndex = lines.indexOf("R-enter")
        val overlaps = leftIndex < rightIndex && rightIndex < leftIndex + 1
        assertTrue(!overlaps, "the two critical sections must not overlap: $lines")
    }

    @Test
    fun `WL-L8 rerun of a completed lock block does not duplicate the body`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val script = tempDir.resolve("lock-once.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("once") {
                        lock("res") {
                            sh("echo once >> '$marker'")
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val first = runCli(tempDir, invocation(tempDir, script))
        assertEquals(0, first.exitCode, "output:\n${first.output}")
        val second = runCli(tempDir, invocation(tempDir, script))
        assertEquals(0, second.exitCode, "output:\n${second.output}")
        assertEquals(listOf("once"), markerLines(marker), "MEMOIZED rerun must not re-run the body")
    }

    @Test
    fun `WL-L9 resume after a failed body re-acquires before fresh effects`(@TempDir tempDir: Path) {
        val marker = tempDir.resolve("marker.txt")
        val script = tempDir.resolve("lock-partial.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("partial") {
                        lock("res") {
                            sh("echo one >> '$marker'")
                            sh("exit 1")
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val first = runCli(tempDir, invocation(tempDir, script))
        assertTrue(first.exitCode != 0, "the failing body must fail the first run; output:\n${first.output}")

        val second = runCli(tempDir, invocation(tempDir, script))
        assertTrue(second.exitCode != 0, "the resume re-runs the failing tail; output:\n${second.output}")
        // SPEC_WU091_LOCK.md §4: the handler RE-RUNS on resume and RE-ACQUIRES
        // before any fresh effect — observable as fresh lock lifecycle events in
        // the SECOND run's output (a memoized-skip design would emit none).
        assertTrue(
            second.output.contains("\"kind\":\"LockRequested\"") &&
                second.output.contains("\"kind\":\"LockAcquired\"") &&
                second.output.contains("\"kind\":\"LockReleased\""),
            "resume must re-acquire the lock observably; output:\n${second.output}",
        )
        // The already-SUCCEEDED child is NOT duplicated across the two attempts:
        // the engine returns its journalized result instead of re-running the sh
        // (measured, see the resume row in the WU-091 receipt). What MUST re-run is
        // the lock itself — the hold does not survive the process.
        assertEquals(listOf("one"), markerLines(marker))
    }
}
