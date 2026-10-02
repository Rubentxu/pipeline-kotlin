package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlinx.coroutines.runBlocking

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

    /** The durable owner used by the same-run re-entrancy rows. */
    private val OWNER = LockOwner("run-a")

    /** A different durable owner sharing the same process. */
    private val OTHER_OWNER = LockOwner("run-b")

    /** An owner that never acquires anything. */
    private val UNRELATED_OWNER = LockOwner("run-never-seen")

    // ------------------------------------------------------------- the happy path

    @Test
    fun `a free resource is acquired and released`() = runBlocking {
        val c = coordinator()

        val admission = c.acquire(OWNER, "staging", LockIntent.Now)

        assertEquals(LockAdmission.Acquired("staging", reentrant = false), admission)
        assertTrue(c.isHeldLocally(OWNER, "staging"))
        c.release(LockHold(OWNER, "staging"))
        assertFalse(c.isHeldLocally(OWNER, "staging"))
    }

    @Test
    fun `acquiring a lock file is created under the lock root`() = runBlocking {
        coordinator().acquire(OWNER, "staging", LockIntent.Now)

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

        val outer = c.acquire(OWNER, "staging", LockIntent.Now)
        val inner = c.acquire(OWNER, "staging", LockIntent.Now)

        assertEquals(LockAdmission.Acquired("staging", reentrant = false), outer)
        assertEquals(
            LockAdmission.Acquired("staging", reentrant = true),
            inner,
            "a second take by the same holder is a re-entrant acquisition, not contention",
        )
        assertEquals(2, c.depthOf(OWNER, "staging"))
    }

    @Test
    fun `the hold survives until the outermost release`() = runBlocking {
        val c = coordinator()
        c.acquire(OWNER, "staging", LockIntent.Now)
        c.acquire(OWNER, "staging", LockIntent.Now)

        c.release(LockHold(OWNER, "staging"))
        assertTrue(
            c.isHeldLocally(OWNER, "staging"),
            "releasing one level of a re-entrant chain must not free the resource",
        )
        c.release(LockHold(OWNER, "staging"))
        assertFalse(c.isHeldLocally(OWNER, "staging"))
    }

    /**
     * The reason re-entrancy was decided in favour of re-entering rather than
     * denying: Jenkins is re-entrant per build, and a denial here would surface as
     * a SILENTLY SKIPPED BODY that still reports Success.
     */
    @Test
    fun `a nested acquire never denies, so a nested body is never silently skipped`() = runBlocking {
        val c = coordinator()
        c.acquire(OWNER, "a", LockIntent.Now)

        val nested = c.acquire(OWNER, "a", LockIntent.Now)

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
        holder.acquire(OWNER, "staging", LockIntent.Now)

        val admission = contender.acquire(OTHER_OWNER, "staging", LockIntent.Now)

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
        holder.acquire(OWNER, "staging", LockIntent.Now)

        val admission = contender.acquire(OTHER_OWNER, "staging", LockIntent.UpTo(120))

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
     * holding; re-entrant by DURABLE OWNER does not.
     */
    @Test
    fun `a different durable owner contends even in the same process`() = runBlocking {
        val runA = coordinator()
        runA.acquire(OWNER, "staging", LockIntent.Now)

        val admission = runA.acquire(LockOwner("run-b"), "staging", LockIntent.Now)

        assertEquals(
            LockAdmission.Denied(LockDenialReason.Held),
            admission,
            "the process is not the owner; two runs sharing a JVM must still exclude",
        )
        assertTrue(
            runA.isHeldLocally(OWNER, "staging"),
            "the denied attempt must not have disturbed the real holder",
        )
    }

    @Test
    fun `a release from another owner does not free the hold`() = runBlocking {
        val c = coordinator()
        c.acquire(OWNER, "staging", LockIntent.Now)

        c.release(LockHold(LockOwner("run-b"), "staging"))

        assertTrue(
            c.isHeldLocally(OWNER, "staging"),
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
        first.acquire(OWNER, "staging", LockIntent.Now)

        val admission = second.acquire(OWNER, "staging", LockIntent.Now)

        assertTrue(
            admission is LockAdmission.Acquired,
            "the same durable owner must re-enter, got $admission",
        )
    }

    @Test
    fun `contention does not stop an unrelated resource from being taken`() = runBlocking {
        val holder = coordinator()
        val contender = coordinator()
        holder.acquire(OWNER, "staging", LockIntent.Now)

        assertEquals(
            LockAdmission.Acquired("other", reentrant = false),
            contender.acquire(OWNER, "other", LockIntent.Now),
            "one held resource must not block every other resource",
        )
    }

    // ------------------------------------------------------------- release safety

    @Test
    fun `releasing a hold nobody took is a no-op`() = runBlocking {
        val holder = coordinator()
        holder.acquire(OWNER, "staging", LockIntent.Now)

        // Nothing was ever acquired for this owner, so this release must find no
        // hold and leave the real one alone.
        coordinator().release(LockHold(UNRELATED_OWNER, "staging"))

        assertTrue(
            holder.isHeldLocally(OWNER, "staging"),
            "a release for an owner that never acquired must not free the real holder",
        )
    }

    @Test
    fun `releasing twice does not disturb a later holder`() = runBlocking {
        val first = coordinator()
        first.acquire(OWNER, "staging", LockIntent.Now)
        first.release(LockHold(OWNER, "staging"))
        first.release(LockHold(OWNER, "staging"))

        val second = coordinator()
        assertEquals(
            LockAdmission.Acquired("staging", reentrant = false),
            second.acquire(OWNER, "staging", LockIntent.Now),
            "a double release must not free a hold acquired afterwards",
        )
    }

    // ------------------------------------------------------------- name safety

    @Test
    fun `distinct resource names get distinct lock files`() = runBlocking {
        val c = coordinator()

        c.acquire(OWNER, "staging", LockIntent.Now)
        c.acquire(OWNER, "staging/1", LockIntent.Now)
        c.acquire(OWNER, "../escape", LockIntent.Now)

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
        coordinator().acquire(OWNER, "../../etc/passwd", LockIntent.Now)

        val files = Files.list(lockRoot).use { it.toList() }
        assertEquals(1, files.size, "the lock file must stay under the lock root, got $files")
        assertTrue(
            files.single().startsWith(lockRoot),
            "expected ${files.single()} under $lockRoot",
        )
    }
}
