package dev.rubentxu.pipeline.v2.events.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A run has exactly one publishing authority at a time, and every takeover
 * advances a fencing token so a superseded owner can be detected.
 *
 * The two guarantees that `UNIQUE(run_id, sequence)` does NOT provide, and that
 * this decider does:
 *
 *  - **Ownership.** The database constraint rejects the losing writer's rows;
 *    it does not tell the loser it lost. The loser sees a raw driver error and
 *    its batch is dropped, so a probe costs the live owner facts.
 *  - **Fencing.** A stale owner that was paused (SIGSTOP, long GC) can wake up
 *    long after a legitimate owner took over. The constraint accepts its rows
 *    when the numbers do not collide, so the log ends up carrying facts from a
 *    superseded authority. This is the check that stops that.
 *
 * Every function here is pure and total, so each test states the whole law and
 * needs no store, no clock and no process. The order of the branches in
 * `acquire` IS the policy, and each rule below is one branch of it.
 */
class RunExecutionLeaseTest {

    private val runId = "run-42"
    private val ownerA = RunOwnerId.of("worker-a")!!
    private val ownerB = RunOwnerId.of("worker-b")!!

    private fun liveLease(
        owner: RunOwnerId = ownerA,
        token: FencingToken = FencingToken.FIRST,
        alive: Boolean? = true,
    ) = LeaseRecord(runId, owner, token, ownerAlive = alive)

    // --- acquire: the four branches, in policy order ----------------------------

    @Test
    fun `a run with no record is acquired at the FIRST token`() {
        val decision = RunExecutionLease.acquire(null, LeaseRequest(runId, ownerA))

        val acquired = decision as LeaseAcquisition.Acquired
        assertEquals(FencingToken.FIRST, acquired.fencingToken)
        assertEquals(ownerA, acquired.record.ownerId)
        assertEquals(true, acquired.record.ownerAlive)
    }

    @Test
    fun `a blank run id is unverifiable, never acquired`() {
        val decision = RunExecutionLease.acquire(null, LeaseRequest("   ", ownerA))

        // The specific claim: an empty key must not become a lease. Acquiring it
        // would put every unkeyed writer in the same bucket, which is precisely
        // the contention this type exists to prevent.
        assertTrue(decision is LeaseAcquisition.Unverifiable, "got ${decision.render()}")
    }

    @Test
    fun `the live owner re-entering does not advance the token`() {
        val current = liveLease(ownerA, FencingToken.of(7))

        val decision = RunExecutionLease.acquire(current, LeaseRequest(runId, ownerA))

        // Re-entry is not a takeover. Advancing here would fence the owner out
        // of its own run the second time it checked.
        val reentered = decision as LeaseAcquisition.Reentered
        assertEquals(FencingToken.of(7), reentered.fencingToken)
        assertEquals(current, reentered.record)
    }

    @Test
    fun `a different live owner is refused, and the refusal names the live authority`() {
        val current = liveLease(ownerA, FencingToken.of(7))

        val decision = RunExecutionLease.acquire(current, LeaseRequest(runId, ownerB))

        // The core law. The returned token is the HOLDER's, so the diagnostic
        // can say who owns it rather than only "busy".
        val denied = decision as LeaseAcquisition.AlreadyOwned
        assertEquals(ownerA, denied.heldBy)
        assertEquals(FencingToken.of(7), denied.fencingToken)
    }

    @Test
    fun `taking over a dead owner advances the token and names the predecessor`() {
        val current = liveLease(ownerA, FencingToken.of(7), alive = false)

        val decision = RunExecutionLease.acquire(current, LeaseRequest(runId, ownerB))

        // The advance is the whole point: it is what makes ownerA detectable as
        // stale if ownerA ever wakes up.
        val taken = decision as LeaseAcquisition.TakenOver
        assertEquals(FencingToken.of(8), taken.fencingToken)
        assertEquals(ownerA, taken.previousOwner)
        assertEquals(ownerB, taken.record.ownerId)
    }

    @Test
    fun `an unknown heartbeat is takeable, because nobody can prove a dead process is dead`() {
        val current = liveLease(ownerA, FencingToken.of(7), alive = null)

        val decision = RunExecutionLease.acquire(current, LeaseRequest(runId, ownerB))

        // `null` is deliberately NOT `false`: an unobserved heartbeat is not a
        // dead owner. Treating it as dead is safe ONLY because the token
        // advances; refusing instead would make a run unrecoverable forever.
        val taken = decision as LeaseAcquisition.TakenOver
        assertEquals(FencingToken.of(8), taken.fencingToken)
        assertEquals(ownerA, taken.previousOwner)
    }

    @Test
    fun `a released lease is taken over, not re-entered`() {
        val released = LeaseRecord(runId, ownerId = null, FencingToken.of(7), ownerAlive = false)

        val decision = RunExecutionLease.acquire(released, LeaseRequest(runId, ownerB))

        val taken = decision as LeaseAcquisition.TakenOver
        assertNull(taken.previousOwner, "a released lease has no predecessor to name")
        assertEquals(FencingToken.of(8), taken.fencingToken)
    }

    // --- authorisePublish: the fencing check -----------------------------------

    @Test
    fun `an exact token match is authorised`() {
        val current = liveLease(ownerA, FencingToken.of(7))

        val authority = RunExecutionLease.authorisePublish(current, FencingToken.of(7))

        assertEquals(PublishAuthority.Authorised(FencingToken.of(7)), authority)
    }

    @Test
    fun `a publisher behind the current token is fenced out`() {
        // THE property. Owner A was paused at token 7, owner B took over and the
        // token advanced to 8. A waking up must be refused here, BEFORE it writes
        // anything -- not discovered later as a corrupted log.
        val current = liveLease(ownerB, FencingToken.of(8))

        val authority = RunExecutionLease.authorisePublish(current, FencingToken.of(7))

        val superseded = authority as PublishAuthority.Superseded
        assertEquals(FencingToken.of(7), superseded.held)
        assertEquals(FencingToken.of(8), superseded.current)
        assertTrue(
            authority.render().contains("fenced out"),
            "the refusal must say WHY, so the diagnostic names fencing: ${authority.render()}",
        )
    }

    @Test
    fun `a publisher with no lease record fails closed`() {
        // "I could not tell" is not "nobody has it", and it is not permission.
        val authority = RunExecutionLease.authorisePublish(null, FencingToken.of(7))

        assertTrue(authority is PublishAuthority.Unverifiable, "got ${authority.render()}")
    }

    // --- release ----------------------------------------------------------------

    @Test
    fun `the live owner releasing clears the lease without advancing the token`() {
        val current = liveLease(ownerA, FencingToken.of(7))

        val released = RunExecutionLease.release(current, ownerA)

        assertNull(released.ownerId)
        assertEquals(false, released.ownerAlive)
        // The NEXT owner advances it on acquisition; a release that bumped the
        // token would fence nobody and corrupt the audit trail.
        assertEquals(FencingToken.of(7), released.fencingToken)
    }

    @Test
    fun `a stale owner releasing does not clear the live owner's lease`() {
        val current = liveLease(ownerB, FencingToken.of(8))

        val after = RunExecutionLease.release(current, ownerA)

        // The fence, from the other side: owner A waking up and tidying up must
        // not hand the run back or release B's hold on it.
        assertSame(current, after)
        assertEquals(ownerB, after.ownerId)
    }

    // --- the owner id boundary ---------------------------------------------------

    @Test
    fun `a blank owner id is refused at construction rather than normalised`() {
        assertNull(RunOwnerId.of(""))
        assertNull(RunOwnerId.of("   "))
        assertNull(RunOwnerId.of("worker a"), "an id with an inner space could collide in a diagnostic")
    }

    @Test
    fun `an owner id is trimmed once, at the boundary`() {
        assertEquals(RunOwnerId.of("worker-a"), RunOwnerId.of("  worker-a  "))
    }
}
