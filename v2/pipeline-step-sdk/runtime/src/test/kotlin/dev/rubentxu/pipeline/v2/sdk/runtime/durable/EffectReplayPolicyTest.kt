package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class EffectReplayPolicyTest {

    private val policy: EffectReplayPolicy = DefaultEffectReplayPolicy()

    @Test
    fun `MEMOIZED plus READ_ONLY plus SUCCEEDED journal entry returns SKIP`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.READ_ONLY),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.SUCCEEDED,
        )
        assertEquals(ReplayDecision.SKIP, decision)
    }

    @Test
    fun `RERUN policy always returns RERUN`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.RERUN,
            effects = emptySet(),
            hasJournalEntry = false,
            journaledOutcome = null,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `EXECUTES_SUBPROCESS returns RERUN`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.EXECUTES_SUBPROCESS),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.SUCCEEDED,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `NEVER policy with journal entry returns ABORT`() {
        // NEVER constrains re-execution of durable history: a journaled
        // invocation may NEVER be re-executed.
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.NEVER,
            effects = emptySet(),
            hasJournalEntry = true,
            journaledOutcome = null,
        )
        assertEquals(ReplayDecision.ABORT, decision)
    }

    @Test
    fun `NEVER policy without journal entry executes fresh`() {
        // E-EM-11/NEVER fix (classification A): NEVER means "never RE-run",
        // not "never run". A fresh invocation (no durable history) executes
        // normally. RERUN is the current decision name for "execute handler
        // now" — naming debt, documented, not renamed in this slice.
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.NEVER,
            effects = emptySet(),
            hasJournalEntry = false,
            journaledOutcome = null,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `NEVER policy with journaled nonterminal entry still aborts`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.NEVER,
            effects = emptySet(),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.RUNNING,
        )
        assertEquals(ReplayDecision.ABORT, decision)
    }

    @Test
    fun `ABORTS_PIPELINE effect returns ABORT`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.ABORTS_PIPELINE),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.SUCCEEDED,
        )
        assertEquals(ReplayDecision.ABORT, decision)
    }

    @Test
    fun `MEMOIZED with no journal entry returns RERUN`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.READ_ONLY),
            hasJournalEntry = false,
            journaledOutcome = null,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `MEMOIZED with FAILED journal outcome returns RERUN`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.READ_ONLY),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.FAILED,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `WRITES_WORKSPACE with journaled SUCCEEDED returns RERUN`() {
        // WRITES_WORKSPACE always reruns (like EXECUTES_SUBPROCESS)
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.WRITES_WORKSPACE),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.SUCCEEDED,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `WRITES_WORKSPACE with no journal entry returns RERUN`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.WRITES_WORKSPACE),
            hasJournalEntry = false,
            journaledOutcome = null,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `RERUN policy with WRITES_WORKSPACE returns SKIP when SUCCEEDED`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.RERUN,
            effects = setOf(Effect.WRITES_WORKSPACE),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.SUCCEEDED,
        )
        assertEquals(ReplayDecision.SKIP, decision)
    }

    // WU-RP-040 R8 category C follow-up: exhaustive MEMOIZED decision-table
    // rows for non-SUCCEEDED journaled outcomes. The pitest survivors were the
    // MEMOIZED x (journal entry with non-SUCCEEDED status) combinations not
    // exercised by the rows above. Contract per the class KDoc table:
    // journaled entry with outcome != SUCCEEDED -> RERUN regardless of effects
    // (except ABORTS_PIPELINE, which aborts before the MEMOIZED branch).
    @Test
    fun `MEMOIZED journaled PENDING returns RERUN`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.READ_ONLY),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.PENDING,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `MEMOIZED journaled RUNNING returns RERUN`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.READ_ONLY),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.RUNNING,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `MEMOIZED journaled ABORTED returns RERUN`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.READ_ONLY),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.ABORTED,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `MEMOIZED journaled DIVERGENT returns RERUN`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.READ_ONLY),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.DIVERGENT,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `MEMOIZED journaled LOST returns RERUN`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.READ_ONLY),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.LOST,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `MEMOIZED journaled FAILED_TIMEOUT returns RERUN`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.READ_ONLY),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.FAILED_TIMEOUT,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `MEMOIZED journaled FAILED with subprocess effect returns RERUN`() {
        // Non-READ_ONLY effect set: must still RERUN, not fall through to SKIP.
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.EXECUTES_SUBPROCESS, Effect.WRITES_WORKSPACE),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.FAILED,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `MEMOIZED READ_ONLY plus WRITES_WORKSPACE mix with SUCCEEDED returns RERUN`() {
        // Mixed effect set: READ_ONLY membership alone must NOT produce SKIP
        // when a workspace-writing effect is present (survivor combination).
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.READ_ONLY, Effect.WRITES_WORKSPACE),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.SUCCEEDED,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `MEMOIZED READ_ONLY plus EXECUTES_SUBPROCESS mix with SUCCEEDED returns RERUN`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.READ_ONLY, Effect.EXECUTES_SUBPROCESS),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.SUCCEEDED,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `MEMOIZED empty effects with journaled SUCCEEDED returns RERUN`() {
        // No READ_ONLY: the SKIP branch must not fire; falls through to RERUN.
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = emptySet(),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.SUCCEEDED,
        )
        assertEquals(ReplayDecision.RERUN, decision)
    }

    @Test
    fun `MEMOIZED NEVER-policy precedence ABORTS_PIPELINE over non-SUCCEEDED journal`() {
        // ABORTS_PIPELINE dominates: returns ABORT even with a FAILED journal
        // entry, because the abort check precedes the MEMOIZED branch.
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.ABORTS_PIPELINE),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.FAILED,
        )
        assertEquals(ReplayDecision.ABORT, decision)
    }
}
