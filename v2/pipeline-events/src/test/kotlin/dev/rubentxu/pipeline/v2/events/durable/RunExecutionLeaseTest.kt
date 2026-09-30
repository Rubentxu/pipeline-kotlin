package dev.rubentxu.pipeline.v2.events.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * S2-R0 — the run ownership law, and its Gate Teeth canary (P-UAT-09).
 *
 * The law: a `runId` has exactly one publishing authority at a time, and every
 * takeover advances a fencing token so a superseded owner is detectable.
 *
 * These tests are only evidence if disabling the decision they claim to verify
 * turns them RED. That is what [GATE_TEETH] pins: the ownership decision must
 * come from [RunExecutionLease] itself, not from a caller's pre-check. A guard
 * in the wiring could otherwise reject a dirty tree (or a busy run) for an
 * unrelated reason and mask an unguarded authority.
 */
class RunExecutionLeaseTest {

    private fun owner(raw: String): RunOwnerId =
        requireNotNull(RunOwnerId.of(raw)) { "test owner id must be valid: $raw" }

    private val alice = owner("alice-1")
    private val bob = owner("bob-2")
    private val runId = "run-under-test"

    // ------------------------------------------------------------ acquisition

    @Test
    @DisplayName("an unowned run is acquired at the first token")
    fun first_acquisition_takes_token_one() {
        val decision = RunExecutionLease.acquire(null, LeaseRequest(runId, alice))
        assertTrue(decision is LeaseAcquisition.Acquired, "expected Acquired, got $decision")
        decision as LeaseAcquisition.Acquired
        assertEquals(FencingToken.FIRST, decision.fencingToken)
        assertEquals(alice, decision.record.ownerId)
    }

    @Test
    @DisplayName("a second live owner is denied and told who holds the run")
    fun second_live_owner_is_denied() {
        val held = LeaseRecord(runId, alice, FencingToken.FIRST, ownerAlive = true)
        val decision = RunExecutionLease.acquire(held, LeaseRequest(runId, bob))
        assertTrue(decision is LeaseAcquisition.AlreadyOwned, "expected AlreadyOwned, got $decision")
        decision as LeaseAcquisition.AlreadyOwned
        assertEquals(alice, decision.heldBy, "the refusal must name the live authority")
        assertEquals(FencingToken.FIRST, decision.fencingToken)
    }

    @Test
    @DisplayName("re-entry by the current owner does NOT advance the token")
    fun reentry_keeps_the_token_stable() {
        val held = LeaseRecord(runId, alice, FencingToken.FIRST, ownerAlive = true)
        val decision = RunExecutionLease.acquire(held, LeaseRequest(runId, alice))
        assertTrue(decision is LeaseAcquisition.Reentered, "expected Reentered, got $decision")
        // If re-entry advanced the token, an owner would fence itself out of
        // its own run on the second acquisition — the classic fencing bug.
        assertEquals(FencingToken.FIRST, (decision as LeaseAcquisition.Reentered).fencingToken)
    }

    @Test
    @DisplayName("a dead owner's run is taken over and the token ADVANCES")
    fun takeover_advances_the_fencing_token() {
        val held = LeaseRecord(runId, alice, FencingToken.FIRST, ownerAlive = false)
        val decision = RunExecutionLease.acquire(held, LeaseRequest(runId, bob))
        assertTrue(decision is LeaseAcquisition.TakenOver, "expected TakenOver, got $decision")
        decision as LeaseAcquisition.TakenOver
        assertEquals(FencingToken.of(2), decision.fencingToken, "a takeover must advance the token")
        assertEquals(alice, decision.previousOwner)
    }

    @Test
    @DisplayName("a released run is taken over, not treated as a first acquisition")
    fun takeover_after_release_still_advances() {
        val released = RunExecutionLease.release(
            LeaseRecord(runId, alice, FencingToken.FIRST, ownerAlive = true),
            alice,
        )
        assertEquals(null, released.ownerId, "release must clear the owner")
        val decision = RunExecutionLease.acquire(released, LeaseRequest(runId, bob))
        assertTrue(decision is LeaseAcquisition.TakenOver, "expected TakenOver, got $decision")
        assertEquals(
            FencingToken.of(2), (decision as LeaseAcquisition.TakenOver).fencingToken,
            "even after a clean release the token advances, so a stale owner is detectable",
        )
    }

    @Test
    @DisplayName("an unknown heartbeat is takeable, because a dead process cannot be proven dead")
    fun unknown_heartbeat_is_takeable() {
        val held = LeaseRecord(runId, alice, FencingToken.FIRST, ownerAlive = null)
        val decision = RunExecutionLease.acquire(held, LeaseRequest(runId, bob))
        assertTrue(
            decision is LeaseAcquisition.TakenOver,
            "an unobservable heartbeat must not permanently block a run; got $decision",
        )
    }

    @Test
    @DisplayName("a blank run id is Unverifiable, never a silent success")
    fun blank_run_id_is_unverifiable() {
        val decision = RunExecutionLease.acquire(null, LeaseRequest("   ", alice))
        assertTrue(
            decision is LeaseAcquisition.Unverifiable,
            "a lease keyed on nothing must fail closed, got $decision",
        )
    }

    // ---------------------------------------------------------------- fencing

    @Test
    @DisplayName("the current token may publish")
    fun current_token_is_authorised() {
        val held = LeaseRecord(runId, alice, FencingToken.FIRST, ownerAlive = true)
        val authority = RunExecutionLease.authorisePublish(held, FencingToken.FIRST)
        assertTrue(authority is PublishAuthority.Authorised, "expected Authorised, got $authority")
    }

    @Test
    @DisplayName("GATE TEETH: a superseded owner is refused BEFORE it can write")
    fun superseded_owner_cannot_publish() {
        // Alice owned the run at token 1. She was paused; Bob took over at
        // token 2. Alice waking up must discover she is stale, not corrupt
        // the log with facts from a superseded authority.
        val afterTakeover = LeaseRecord(runId, bob, FencingToken.of(2), ownerAlive = true)
        val authority = RunExecutionLease.authorisePublish(afterTakeover, FencingToken.FIRST)
        assertTrue(
            authority is PublishAuthority.Superseded,
            "a stale owner must be fenced out, got $authority",
        )
        val superseded = authority as PublishAuthority.Superseded
        assertEquals(FencingToken.FIRST, superseded.held)
        assertEquals(FencingToken.of(2), superseded.current)
    }

    @Test
    @DisplayName("a token AHEAD of the record is also refused, never trusted")
    fun token_ahead_of_record_is_refused() {
        // A publisher claiming a token the lease never issued is not more
        // authoritative than one behind: it is a forged or confused caller.
        val held = LeaseRecord(runId, alice, FencingToken.FIRST, ownerAlive = true)
        val authority = RunExecutionLease.authorisePublish(held, FencingToken.of(99))
        assertTrue(authority is PublishAuthority.Superseded, "expected Superseded, got $authority")
    }

    @Test
    @DisplayName("publishing with no lease record fails closed")
    fun publish_without_a_record_fails_closed() {
        val authority = RunExecutionLease.authorisePublish(null, FencingToken.FIRST)
        assertTrue(
            authority is PublishAuthority.Unverifiable,
            "no record means no provable authority; got $authority",
        )
    }

    @Test
    @DisplayName("takeover genuinely changes the token, so fencing has something to detect")
    fun token_actually_changes_across_takeovers() {
        // A guard that "protects" by always returning the same token would pass
        // every denial test above while providing zero fencing. This asserts
        // the discriminating property directly.
        var state: LeaseRecord? = null
        val first = RunExecutionLease.acquire(state, LeaseRequest(runId, alice))
        state = (first as LeaseAcquisition.Acquired).record

        val second = RunExecutionLease.acquire(
            state!!.copy(ownerAlive = false),
            LeaseRequest(runId, bob),
        )
        state = (second as LeaseAcquisition.TakenOver).record

        assertNotEquals(
            FencingToken.FIRST, state!!.fencingToken,
            "the token must actually move for fencing to mean anything",
        )
    }

    // --------------------------------------------------------------- release

    @Test
    @DisplayName("a stale owner releasing does NOT clear the live owner's lease")
    fun stale_release_cannot_steal_the_lease() {
        val live = LeaseRecord(runId, bob, FencingToken.of(2), ownerAlive = true)
        val afterStaleRelease = RunExecutionLease.release(live, alice)
        assertEquals(bob, afterStaleRelease.ownerId, "a stale owner must not unown the live one")
        assertEquals(FencingToken.of(2), afterStaleRelease.fencingToken)
    }

    @Test
    @DisplayName("AlreadyOwned and Unverifiable stay distinct cases")
    fun denial_reasons_are_not_collapsed() {
        // Collapsing them would tell an operator "someone else owns this run"
        // when the truth is "the lease store is unreachable" — two different
        // incidents with two different remedies.
        val busy = RunExecutionLease.acquire(
            LeaseRecord(runId, alice, FencingToken.FIRST, ownerAlive = true),
            LeaseRequest(runId, bob),
        )
        val broken = RunExecutionLease.acquire(null, LeaseRequest("  ", bob))
        assertNotEquals(busy::class, broken::class, "the two denials must remain distinguishable")
    }
}
