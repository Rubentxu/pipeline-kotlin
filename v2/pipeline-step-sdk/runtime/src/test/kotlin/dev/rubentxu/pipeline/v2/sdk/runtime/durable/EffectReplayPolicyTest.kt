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
    fun `NEVER policy returns ABORT`() {
        val decision = policy.decide(
            replayPolicy = ReplayPolicy.NEVER,
            effects = emptySet(),
            hasJournalEntry = false,
            journaledOutcome = null,
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
}
