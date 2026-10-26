package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.events.durable.FencingToken
import dev.rubentxu.pipeline.v2.events.durable.LeaseRecord
import dev.rubentxu.pipeline.v2.events.durable.RunLeaseObservationPort
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The lease as the recovery that has taken it sees it.
 *
 * ## What this pins
 * The dry run and the recovery must answer the SAME question with the SAME
 * facts, and the only thing that can guarantee that is that both read the
 * lease through one view. The view presents the record the acquisition leaves
 * behind, so the three cases the pure decider produces — a fresh run, a run
 * owned by somebody else, and a re-entry — are the three the view presents.
 *
 * A dry run that read the raw record instead would say "somebody else owns
 * this run" for a run whose owner is dead, and refuse to describe a run the
 * recovery would happily re-attach.
 */
class LeaseAsAcquiredTest {

    private val us: RunOwnerId = RunOwnerId.of("cli-recover-1")!!

    /**
     * A run nobody ever leased gets the FIRST token.
     */
    @Test
    fun `un run sin lease recibe el primer token`() {
        val view = LeaseAsAcquired(RecordedLease(null), us)

        val record = view.observe("run-1")

        assertEquals(
            LeaseRecord("run-1", us, FencingToken.FIRST, ownerAlive = true),
            record,
            "the acquisition issues the FIRST token to a fresh owner, so the view must present " +
                "exactly that record",
        )
    }

    /**
     * A run owned by somebody else is TAKEN OVER: the token advances.
     *
     * The token advancing is what makes the predecessor detectable as stale,
     * so a view that kept the old token would describe a world in which two
     * owners could both believe they hold the same authority.
     */
    @Test
    fun `un run de otro se toma con el token avanzado`() {
        val other = RunOwnerId.of("dead-writer")!!
        val view = LeaseAsAcquired(
            RecordedLease(LeaseRecord("run-1", other, FencingToken.of(7L), ownerAlive = true)),
            us,
        )

        val record = view.observe("run-1")

        assertEquals(
            LeaseRecord("run-1", us, FencingToken.of(8L), ownerAlive = true),
            record,
            "the lock was free, so the previous owner is gone and the acquisition advances the " +
                "token; a view that kept the old token would let a stale owner look current",
        )
    }

    /**
     * A re-entry keeps the token.
     *
     * An owner that fenced itself out of its own run would be nonsense, so
     * the acquisition does not advance the token when it is already the owner.
     */
    @Test
    fun `un reingreso conserva el token`() {
        val view = LeaseAsAcquired(
            RecordedLease(LeaseRecord("run-1", us, FencingToken.of(7L), ownerAlive = false)),
            us,
        )

        val record = view.observe("run-1")

        assertEquals(
            LeaseRecord("run-1", us, FencingToken.of(7L), ownerAlive = true),
            record,
            "re-entering a run you already own must not advance the token; the owner is alive " +
                "because it is the process holding the lock",
        )
    }

    /**
     * Every run is known to the lease once the recovery has touched it.
     */
    @Test
    fun `la lease conoce el run tras tomarlo`() {
        val view = LeaseAsAcquired(RecordedLease(null), us)

        assertTrue(
            view.isKnown("run-1"),
            "the acquisition writes a record for EVERY run it touches, so a run with no prior " +
                "lease is known to it afterwards",
        )
    }

    /**
     * A lease with one fixed record.
     */
    private class RecordedLease(private val record: LeaseRecord?) : RunLeaseObservationPort {
        override fun isKnown(runId: String): Boolean = record != null

        override fun observe(runId: String): LeaseRecord? = record
    }
}
