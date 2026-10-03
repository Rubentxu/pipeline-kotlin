package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * G2 contract proof for [FileLockCoordinator] (RP6-A / WU-091).
 *
 * The adapter is tested on its own, with no coordinator, journal or capability
 * bridge: its whole job is to turn [LockIntent] into a real exclusive hold, and
 * every claim below is about that seam.
 *
 * Rows that matter most are the ones about the failure direction. A lock backend
 * that fails by *granting* when it should deny is a mutual-exclusion hole that no
 * downstream test would catch, so the denial and the idempotent-release rows are
 * the load-bearing ones.
 */
@Timeout(60)
class FileLockCoordinatorTest {

    private lateinit var lockRoot: Path

    @BeforeEach
    fun setUp() {
        lockRoot = Files.createTempDirectory("lock-coord-test-")
    }

    private fun coordinator() = FileLockCoordinator(lockRoot)

    /** The linear lane of run-a: the owner the re-entrancy rows use. */
    private val owner = LockOwner(ExecutionLaneId.of("run-a", emptyList()))

    /** SAME run, DIFFERENT parallel lane — a sibling `parallel` branch. */
    private val siblingLaneOwner =
        LockOwner(ExecutionLaneId.of("run-a", listOf(1)))

    /** A different run entirely. */
    private val otherOwner = LockOwner(ExecutionLaneId.of("run-b", emptyList()))

    /** An owner that never acquires anything. */
    private val unrelatedOwner = LockOwner(ExecutionLaneId.of("run-never-seen", emptyList()))

    // ------------------------------------------------------------- the happy path

    @Test
    fun `a free resource is acquired and released`() = runBlocking {
        val c = coordinator()

        val admission = c.acquire(owner, "staging", LockIntent.Now)

        assertEquals(LockAdmission.Acquired("staging", reentrant = false), admission)
        assertTrue(c.isHeldLocally(owner, "staging"))
        c.release(LockHold(owner, "staging"))
        assertFalse(c.isHeldLocally(owner, "staging"))
    }

    @Test
    fun `acquiring a lock file is created under the lock root`() = runBlocking {
        coordinator().acquire(owner, "staging", LockIntent.Now)

        val files = Files.list(lockRoot).use { it.toList() }
        assertEquals(1, files.size, "one resource means one lock file, got $files")
        assertTrue(
            files.single().fileName.toString().endsWith(".lock"),
            "the lock file must be identifiable, got ${files.single().fileName}",
        )
    }

    // -------------------------------------------------------------- re-entrancy

    @Test
    fun `a nested acquire is re-entrant and counted`() = runBlocking {
        val c = coordinator()

        val outer = c.acquire(owner, "staging", LockIntent.Now)
        val inner = c.acquire(owner, "staging", LockIntent.Now)

        assertEquals(LockAdmission.Acquired("staging", reentrant = false), outer)
        assertEquals(
            LockAdmission.Acquired("staging", reentrant = true),
            inner,
            "a second take by the same holder is a re-entrant acquisition, not contention",
        )
        assertEquals(2, c.depthOf(owner, "staging"))
    }

    @Test
    fun `the hold survives until the outermost release`() = runBlocking {
        val c = coordinator()
        c.acquire(owner, "staging", LockIntent.Now)
        c.acquire(owner, "staging", LockIntent.Now)

        c.release(LockHold(owner, "staging"))
        assertTrue(
            c.isHeldLocally(owner, "staging"),
            "releasing one level of a re-entrant chain must not free the resource",
        )
        c.release(LockHold(owner, "staging"))
        assertFalse(c.isHeldLocally(owner, "staging"))
    }

    /**
     * The reason re-entrancy was decided in favour of re-entering rather than
     * denying: Jenkins is re-entrant per build, and a denial here would surface as
     * a SILENTLY SKIPPED BODY that still reports Success.
     */
    @Test
    fun `a nested acquire never denies, so a nested body is never silently skipped`() = runBlocking {
        val c = coordinator()
        c.acquire(owner, "a", LockIntent.Now)

        val nested = c.acquire(owner, "a", LockIntent.Now)

        assertTrue(
            nested is LockAdmission.Acquired,
            "a nested take reported $nested, which the handler maps to skip-the-body; " +
                "that would be a silent skip dressed as success",
        )
    }

    // ----------------------------------------------------------------- contention

    @Test
    fun `a resource held by another owner is denied`() = runBlocking {
        val holder = coordinator()
        val contender = coordinator()
        holder.acquire(owner, "staging", LockIntent.Now)

        val admission = contender.acquire(otherOwner, "staging", LockIntent.Now)

        assertEquals(
            LockAdmission.Denied(LockDenialReason.Held),
            admission,
            "a different durable owner must see contention, not an exception, even when it " +
                "shares the process with the holder",
        )
    }

    @Test
    fun `a timeout under a waiting intent is a timeout, not a skip`() = runBlocking {
        val holder = coordinator()
        val contender = coordinator()
        holder.acquire(owner, "staging", LockIntent.Now)

        val admission = contender.acquire(otherOwner, "staging", LockIntent.UpTo(120))

        val denial = admission as? LockAdmission.Denied
        assertTrue(denial is LockAdmission.Denied, "expected a denial, got $admission")
        val reason = (admission as LockAdmission.Denied).reason
        assertTrue(
            reason is LockDenialReason.TimedOut,
            "under UpTo the caller DID wait, so this is a timeout; reporting Held would " +
                "make the handler treat a 120ms wait as an instant skip",
        )
        assertTrue(
            (reason as LockDenialReason.TimedOut).waitedMillis >= 100,
            "the recorded wait should reflect the wait, got ${reason.waitedMillis}ms",
        )
    }

    /**
     * The distinction the whole owner design exists for. Re-entrant by PROCESS
     * would let a second run in the same JVM walk into a resource the first run is
     * holding; re-entrant by DURABLE owner does not.
     */
    /**
     * THE law, stated in one row. Same run, different parallel lane, must
     * CONTEND. With run-only ownership this silently re-entered instead, and the
     * mutual exclusion the lock exists for was gone in the one case that needs it.
     */
    @Test
    fun `same run but a different parallel lane contends`() = runBlocking {
        val c = coordinator()
        c.acquire(owner, "db", LockIntent.Now)

        val admission = c.acquire(siblingLaneOwner, "db", LockIntent.Now)

        assertEquals(
            LockAdmission.Denied(LockDenialReason.Held),
            admission,
            "branch B walked into branch A's hold; run-scoped ownership made two parallel " +
                "branches look like one owner",
        )
    }

    @Test
    fun `a sibling lane does not re-enter a hold taken by another lane`() = runBlocking {
        val c = coordinator()
        c.acquire(owner, "db", LockIntent.Now)

        c.acquire(siblingLaneOwner, "db", LockIntent.Now)

        assertEquals(
            1,
            c.depthOf(owner, "db"),
            "a denied sibling must not have deepened the real holder's chain",
        )
    }

    @Test
    fun `a nested acquire in the SAME lane still re-enters`() = runBlocking {
        val c = coordinator()

        c.acquire(owner, "db", LockIntent.Now)
        val nested = c.acquire(owner, "db", LockIntent.Now)

        assertEquals(
            LockAdmission.Acquired("db", reentrant = true),
            nested,
            "a nested lock is a deeper body in the same lane, not contention; denying it " +
                "would deadlock the run against its own legitimate hold",
        )
        assertEquals(2, c.depthOf(owner, "db"))
    }

    /** Depth 2 -> 1 -> 0, with the resource actually freed only at 0. */
    @Test
    fun `release walks the depth down and frees only at zero`() = runBlocking {
        val c = coordinator()
        c.acquire(owner, "db", LockIntent.Now)
        c.acquire(owner, "db", LockIntent.Now)

        c.release(LockHold(owner, "db"))
        assertEquals(1, c.depthOf(owner, "db"))
        assertTrue(
            c.isHeldLocally(owner, "db"),
            "at depth 1 the OS lock must still be held",
        )
        assertEquals(
            LockAdmission.Denied(LockDenialReason.Held),
            c.acquire(siblingLaneOwner, "db", LockIntent.Now),
            "a sibling must still be locked out while depth is 1",
        )

        c.release(LockHold(owner, "db"))
        assertEquals(0, c.depthOf(owner, "db"))
        assertEquals(
            LockAdmission.Acquired("db", reentrant = false),
            c.acquire(siblingLaneOwner, "db", LockIntent.Now),
            "only at depth 0 is the resource actually free for another lane",
        )
    }

    /** The whole law in one executable statement. */
    @Test
    fun `the three outcomes of the ownership law`() = runBlocking {
        val c = coordinator()
        c.acquire(owner, "db", LockIntent.Now)

        assertTrue(
            c.acquire(owner, "db", LockIntent.Now) is LockAdmission.Acquired,
            "same run + same lane must re-enter",
        )
        assertTrue(
            c.acquire(siblingLaneOwner, "db", LockIntent.Now) is LockAdmission.Denied,
            "same run + different lane must contend",
        )
        assertTrue(
            c.acquire(otherOwner, "db", LockIntent.Now) is LockAdmission.Denied,
            "different run must contend",
        )
    }

    /**
     * A cancelled acquire is EXECUTION CONTROL, not a business outcome. It must
     * cancel the coroutine, not hand back a `Denied` value the caller could
     * mistake for a timeout and carry on from.
     */
    @Test
    fun `a cancelled acquire propagates cancellation instead of returning a denial`() = runBlocking {
        val holder = coordinator()
        holder.acquire(owner, "db", LockIntent.Now)
        val contender = coordinator()

        val waiter = launch(Dispatchers.Default) {
            contender.acquire(otherOwner, "db", LockIntent.UpTo(120_000))
        }
        delay(200) // let it enter the poll loop
        waiter.cancelAndJoin()

        assertTrue(
            waiter.isCancelled,
            "a cancelled acquire must cancel its coroutine; a returned denial would let " +
                "the caller treat a cancellation as a timeout and keep going",
        )
        assertTrue(
            holder.isHeldLocally(owner, "db"),
            "the cancelled waiter must not have disturbed the real holder",
        )
    }

    /**
     * The wait must SUSPEND, and the probe measures the WORST GAP between two
     * consecutive pieces of work on the waiter's own thread.
     *
     * Counting ticks is NOT enough: a `Thread.sleep` loop still yields at every
     * dispatcher hand-off, so ticks keep advancing and the test passes either way.
     * The gap is what separates the two: with `delay` the thread is free the whole
     * time (sub-millisecond gaps), with a blocking sleep of [POLL] every gap is at
     * least one sleep interval wide.
     */
    @Test
    fun `a contended acquire suspends instead of blocking its thread`() = runBlocking {
        val holder = coordinator()
        holder.acquire(owner, "db", LockIntent.Now)
        val contender = coordinator()

        val single = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val waiter = async(single) { contender.acquire(otherOwner, "db", LockIntent.UpTo(900)) }
            var worstGapMillis = 0L
            var ticks = 0
            var previous = System.nanoTime()
            while (!waiter.isCompleted && ticks < 2_000) {
                withContext(single) {
                    val now = System.nanoTime()
                    val gap = (now - previous) / 1_000_000
                    if (gap > worstGapMillis) worstGapMillis = gap
                    previous = now
                    ticks++
                }
                delay(2)
            }
            val outcome = waiter.await()

            assertTrue(ticks > 10, "the shared thread barely ran ($ticks times); the probe is not measuring anything")
            assertTrue(
                worstGapMillis < 40,
                "the waiter's thread was blocked for ${worstGapMillis}ms at its worst, so the " +
                    "acquire is BLOCKING rather than suspending (poll interval is 50ms)",
            )
            assertTrue(outcome is LockAdmission.Denied, "the waiter should have timed out, got $outcome")
        } finally {
            single.close()
        }
    }

    @Test
    fun `a different durable owner contends even in the same process`() = runBlocking {
        val runA = coordinator()
        runA.acquire(owner, "staging", LockIntent.Now)

        val admission = runA.acquire(otherOwner, "staging", LockIntent.Now)

        assertEquals(
            LockAdmission.Denied(LockDenialReason.Held),
            admission,
            "the process is not the owner; two runs sharing a JVM must still exclude",
        )
        assertTrue(
            runA.isHeldLocally(owner, "staging"),
            "the denied attempt must not have disturbed the real holder",
        )
    }

    @Test
    fun `a release from another owner does not free the hold`() = runBlocking {
        val c = coordinator()
        c.acquire(owner, "staging", LockIntent.Now)

        c.release(LockHold(otherOwner, "staging"))

        assertTrue(
            c.isHeldLocally(owner, "staging"),
            "one owner releasing a hold keyed to another owner is a hazard, not a no-op",
        )
    }

    @Test
    fun `the same owner re-enters on a second coordinator instance`() = runBlocking {
        // Two coordinator instances exist in practice: the durable spine may build
        // a fresh one per dispatch. Same owner must still re-enter, or a resume
        // would deadlock a run against its own earlier hold.
        val first = coordinator()
        val second = coordinator()
        first.acquire(owner, "staging", LockIntent.Now)

        val admission = second.acquire(owner, "staging", LockIntent.Now)

        assertTrue(
            admission is LockAdmission.Acquired,
            "the same durable owner must re-enter, got $admission",
        )
    }

    @Test
    fun `contention does not stop an unrelated resource from being taken`() = runBlocking {
        val holder = coordinator()
        val contender = coordinator()
        holder.acquire(owner, "staging", LockIntent.Now)

        assertEquals(
            LockAdmission.Acquired("other", reentrant = false),
            contender.acquire(owner, "other", LockIntent.Now),
            "one held resource must not block every other resource",
        )
    }

    // ------------------------------------------------------------- release safety

    @Test
    fun `releasing a hold nobody took is a no-op`() = runBlocking {
        val holder = coordinator()
        holder.acquire(owner, "staging", LockIntent.Now)

        // Nothing was ever acquired for this owner, so this release must find no
        // hold and leave the real one alone.
        coordinator().release(LockHold(unrelatedOwner, "staging"))

        assertTrue(
            holder.isHeldLocally(owner, "staging"),
            "a release for an owner that never acquired must not free the real holder",
        )
    }

    @Test
    fun `releasing twice does not disturb a later holder`() = runBlocking {
        val first = coordinator()
        first.acquire(owner, "staging", LockIntent.Now)
        first.release(LockHold(owner, "staging"))
        first.release(LockHold(owner, "staging"))

        val second = coordinator()
        assertEquals(
            LockAdmission.Acquired("staging", reentrant = false),
            second.acquire(owner, "staging", LockIntent.Now),
            "a double release must not free a hold acquired afterwards",
        )
    }

    // ------------------------------------------------------------- name safety

    @Test
    fun `distinct resource names get distinct lock files`() = runBlocking {
        val c = coordinator()

        c.acquire(owner, "staging", LockIntent.Now)
        c.acquire(owner, "staging/1", LockIntent.Now)
        c.acquire(owner, "../escape", LockIntent.Now)

        val files = Files.list(lockRoot).use { it.toList() }
        assertEquals(
            3,
            files.size,
            "sanitisation must not merge distinct resources; that would be a mutual " +
                "exclusion hole. Got $files",
        )
    }

    @Test
    fun `a traversal name cannot escape the lock root`() = runBlocking {
        coordinator().acquire(owner, "../../etc/passwd", LockIntent.Now)

        val files = Files.list(lockRoot).use { it.toList() }
        assertEquals(1, files.size, "the lock file must stay under the lock root, got $files")
        assertTrue(
            files.single().startsWith(lockRoot),
            "expected ${files.single()} under $lockRoot",
        )
    }
}
