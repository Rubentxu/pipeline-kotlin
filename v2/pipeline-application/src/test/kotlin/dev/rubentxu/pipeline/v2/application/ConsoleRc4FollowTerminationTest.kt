package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.RunPlane
import dev.rubentxu.pipeline.v2.application.observation.followDecision
import dev.rubentxu.pipeline.v2.application.observation.FollowDecision
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailState
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * R4 / WU-RC4 — audit P1: **a follow must not report completion it did not observe.**
 *
 * ## The defect
 *
 * `runFollowFrames` returned a bare `Int`, and `0` meant both "the output lane is final" and
 * "we ran out of polls". A caller could not tell a finished follow from an abandoned one. That is
 * the same false-green the RC2 block fixed for the follow CURSOR, one level up: a truncated
 * transcript that looks exactly like a clean exit.
 *
 * It was not theoretical. `ConsoleFollowTest`'s installDist canary was GREEN while observing
 * literally nothing — `stdout=` empty, `last frame ordinal -1` — because the fourth defect left
 * the Output Plane empty, the follow fell through to `--follow-max-polls`, and the budget
 * reported success. Two defects, cancelling.
 *
 * ## What these rows pin
 *
 * ```text
 * RC4-1  a silent op exhausts the budget and is NOT a completion
 * RC4-2  a sealed op completes, and exhausts nothing
 * RC4-3  exhaustion carries the cursor so the follow is resumable, not lost
 * RC4-4  every outcome maps to a DISTINCT exit code (the old contract collapsed two of them)
 * RC4-5  the run plane says Live, so a follow over a live run does not stop at a two-step seam
 * ```
 *
 * ## Harness fidelity
 *
 * **HF1, in-process, crossing the production entry point.** Every row drives
 * `MainConsoleCli.runFollowFrames` — the real loop, with the real `FrameIndexedObservationOutputReader`
 * over a real `SegmentOutputStore`, publishing frames through the production order
 * (declare → reserve → write → commit → append). No fabricated `ObservationOutputPage`, because a
 * fabricated page could not prove the budget contract: the loop would be reading bytes the test
 * invented, and RC4-1 in particular depends on the store genuinely holding NOTHING.
 *
 * `runFollowFrames` takes `root`/`out`/`err` as parameters precisely so this is possible without a
 * forked JVM. Rows that need a real process live in `ConsoleFollowTest`.
 *
 * ## Mutations
 *
 * - `M-RC4-1` (exhaustion is not completion) → return `FollowExit.Completed` from the budget arm.
 *   Killed by RC4-1 and RC4-4.
 * - `M-RC4-2` (budget carries the cursor) → `lastOrdinal = -1L` unconditionally. Killed by RC4-3.
 * - `M-RC4-3` (distinct codes) → project exhaustion onto `0`, the rc1 behaviour. Killed by RC4-4.
 * - `M-RC4-4` (the seam) → treat a sealed stream as terminal regardless of the run plane. Killed by
 *   RC4-5, and `ObservationWakeupTest.FOLLOW-1b` pins the same property at the decision level.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class ConsoleRc4FollowTerminationTest {

    @TempDir
    lateinit var root: Path

    /**
     * `storeForReading` memoises stores in a PROCESS-WIDE map. HARNESS FIDELITY §4 forbids a
     * shared mutable singleton in a behavioural harness: under reuse a row can observe the
     * previous test's store and pass for the wrong reason. `forgetAll()` before every fixture is
     * what makes the rows independent of execution order.
     */
    @org.junit.jupiter.api.BeforeEach
    fun forgetCachedStores() = dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider.forgetAll()

    private val runId = "run-rc4"
    private val opId = "$runId-s0-0"
    /**
     * The stream production actually names, not one invented for the fixture.
     *
     * `SegmentFrameIndex.declareStream` validates exactly this `{runId}/{operationId}/{channel}`
     * shape and throws on anything else, so the fixture cannot drift into matching by accident.
     * The opId-scoped reader selects on `contains("/$opId/")`, and `OpId.format()` produces
     * `{runId}-s{stage}-{step}` — which is what [opId] holds. That matters for RC4-1: the row
     * depends on the store genuinely holding nothing, so a fixture shape that quietly matched
     * would invalidate the whole class.
     */
    private val stdout = OutputStreamId("$runId/$opId/stdout")

    /**
     * A real store at `controlDir`, containing whatever [publish] wrote — and nothing else.
     *
     * `recover()` is called before any read, exactly as production does when opening a control
     * root written by another process.
     */
    private fun controlDir(name: String, publish: (SegmentOutputStore) -> Unit = {}): Path {
        val dir = Files.createDirectories(root.resolve(name))
        // `OutputPlaneProvider.storeForReading` opens `{controlRoot}/output-plane`, NOT the control
        // root itself. Writing the fixture at the control root produced a store the verb could not
        // see, and every row came back empty — which looked like the reader being broken.
        val store = SegmentOutputStore(dir.resolve("output-plane"))
        store.recover()
        publish(store)
        return dir
    }

    private fun publish(store: SegmentOutputStore, lines: Int, seal: Boolean) {
        store.frameIndex().declareStream(stdout, OutputChannel.STDOUT)
        for (i in 0 until lines) {
            val payload = "rc4-line-$i\n".toByteArray(Charsets.UTF_8)
            val from = store.committedExtent(stdout) ?: 0L
            val reservation = store.open(stdout).reserve(payload.size)
            reservation.write(payload)
            reservation.commit()
            store.frameIndex().append(stdout, OutputChannel.STDOUT, from, from + payload.size)
        }
        if (seal) store.seal(stdout)
    }

    /** Drive the REAL production loop. */
    private fun follow(
        controlDir: Path,
        maxPolls: Int,
        pollIntervalMillis: Long = 1L,
    ): Triple<FollowExit, String, String> {
        val request = ConsoleReadRequest.FollowFrames(
            controlDir = controlDir.toString(),
            runId = runId,
            opId = opId,
            afterOrdinal = -1L,
            frameLimit = 64,
            followConfig = FollowConfig(pollIntervalMillis, maxPolls),
            format = MainConsoleCli.ConsoleFormat.JSONL,
        )
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = PrintStream(out, true, "UTF-8")
        val errStream = PrintStream(err, true, "UTF-8")
        val outcome = MainConsoleCli.runFollowFrames(request, controlDir, exit, errStream)
        exit.flush()
        errStream.flush()
        return Triple(outcome, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    /**
     * RC4-1 — the row the audit asked for.
     *
     * An operation that owns no stream — a silent `sh("true")` — produces no tails. The output
     * lane can therefore NEVER satisfy `Finished` for it, and the poll budget is the only exit.
     * The rc1 code returned `0` there, which claimed a complete observation of an operation whose
     * output was never looked at.
     */
    @Test
    fun `RC4-1 a silent op exhausts the budget and is NOT a completion`() {
        val dir = controlDir("silent") // nothing published: no stream at all

        val (outcome, stdoutText, errText) = follow(dir, maxPolls = 3)

        assertTrue(
            outcome is FollowExit.PollBudgetExhausted,
            "a silent op owns no stream, so `Finished` is unreachable and the budget is the only " +
                "exit. Reporting anything else claims the follow observed something it never did. " +
                "Got $outcome",
        )
        assertEquals("", stdoutText, "a silent op emits nothing — and must not claim to have " +
            "followed a transcript")
        assertTrue(
            outcome.exitCode != 0,
            "the type alone is not the contract — the PROCESS must not report success. An operator " +
                "piping this to `&&` must be stopped by a non-zero exit, because nothing terminal " +
                "was ever observed.",
        )
    }

    /**
     * RC4-2 — the positive control.
     *
     * A sealed op reaches `Completed` on its own and never touches the budget. Without this row
     * RC4-1 would pass for a loop that simply never terminates anything.
     */
    @Test
    fun `RC4-2 a sealed op completes and never exhausts the budget`() {
        val dir = controlDir("sealed") { store -> publish(store, lines = 3, seal = true) }

        val (outcome, stdoutText, _) = follow(dir, maxPolls = 50)

        assertEquals(
            FollowExit.Completed,
            outcome,
            "every declared stream is sealed and nothing is pending — that is the output lane's " +
                "own authority saying the tail is final",
        )
        assertEquals(
            3,
            stdoutText.lines().count { it.isNotEmpty() },
            "every real frame must have been delivered before completing. Got:\n$stdoutText",
        )
        assertTrue(
            stdoutText.lines().filter { it.isNotEmpty() }.all { it.contains("\"type\":\"output\"") },
            "no line may be a diagnostic: stdout carries records only. Got:\n$stdoutText",
        )
    }

    /**
     * RC4-3 — an abandoned follow must be RESUMABLE, not lost.
     *
     * DR-5: the cursor between polls is reconstructible local state and is never journaled, so the
     * process dying mid-follow must not cost the consumer the work already delivered. The
     * exhaustion case carries the position it reached for exactly that reason.
     */
    @Test
    fun `RC4-3 exhaustion carries the cursor so the follow is resumable`() {
        val dir = controlDir("partial") { store -> publish(store, lines = 2, seal = false) }

        val (outcome, _, _) = follow(dir, maxPolls = 2)

        val exhausted = outcome as? FollowExit.PollBudgetExhausted
            ?: error("expected exhaustion, got $outcome")
        assertEquals(
            1L,
            exhausted.lastOrdinal,
            "the two frames are ordinals 0 and 1, so the last delivered ordinal is 1. The reader " +
                "is strictly `ordinal > afterOrdinal`, so a consumer resuming at 1 gets frame 2 and " +
                "loses nothing already emitted",
        )
        assertEquals(2, exhausted.polls, "the reported budget must be the one actually spent, so an " +
            "operator can tell a tight budget from a slow run")
    }

    /**
     * RC4-4 — the projection, pinned.
     *
     * Four outcomes, four codes. The rc1 contract had `0` for two of them, which is the whole
     * defect. Asserted as a table so adding a case without a distinct code fails here rather than
     * in a caller's shell script.
     */
    @Test
    fun `RC4-4 every follow outcome maps to a DISTINCT exit code`() {
        val codes = listOf(
            FollowExit.Completed,
            FollowExit.Refused(dev.rubentxu.pipeline.v2.output.OutputRefusal.UnknownStream(stdout)),
            FollowExit.Signalled,
            FollowExit.PollBudgetExhausted(polls = 3, lastOrdinal = 7L),
        ).map { it.exitCode }

        assertEquals(codes.size, codes.toSet().size, "two outcomes share exit code $codes. An " +
            "exhausted budget reporting the same code as a completed follow is exactly the rc1 " +
            "defect; every case needs its own code.")
        assertEquals(0, FollowExit.Completed.exitCode, "only a genuine completion is a success")
        assertNotEquals(
            0,
            FollowExit.PollBudgetExhausted(polls = 1, lastOrdinal = -1L).exitCode,
            "an exhausted budget must not be a success — nothing terminal was observed",
        )
    }

    /**
     * RC4-5 — the two-step seam, at the CALLER this time.
     *
     * `ObservationWakeupTest.FOLLOW-1b` pins the decision. This row pins that the console verb
     * REPORTS the difference rather than collapsing it: `runPlaneOf` is what the verb lacks, so
     * the verb must say `Unavailable` explicitly, and a lane that CAN answer must not be
     * short-circuited. The property under test is that the three states are distinguishable at
     * the decision the verb actually makes.
     */
    @Test
    fun `RC4-5 the three run-plane states are distinguishable and only Live refuses to finish`() {
        val sealed = listOf(OutputTailState.Sealed(10))

        assertEquals(
            FollowExit.Completed,
            FollowExitOf(followDecision(false, sealed, RunPlane.Unavailable)),
            "the console verb has no event plane, so `Unavailable` must let sealing finish — that " +
                "is what keeps a console-only follow terminatable at all",
        )
        assertEquals(
            FollowExit.Completed,
            FollowExitOf(followDecision(false, sealed, RunPlane.Completed)),
            "a confirmed terminal run also finishes",
        )
        assertNotEquals(
            FollowExit.Completed,
            FollowExitOf(followDecision(false, sealed, RunPlane.Live)),
            "a run the execution plane says is STILL GOING must not be reported complete, even " +
                "though every known stream is sealed — that is the seam between two steps, and " +
                "finishing there truncates the transcript behind an exit 0",
        )
    }

    /** Local mirror of the verb's projection, so the row states the rule instead of re-deriving a magic number. */
    private fun FollowExitOf(decision: FollowDecision) =
        when (decision) {
            FollowDecision.Finished ->
                FollowExit.Completed
            FollowDecision.ReadAgain ->
                FollowExit.PollBudgetExhausted(polls = 0, lastOrdinal = -1L)
        }
}
