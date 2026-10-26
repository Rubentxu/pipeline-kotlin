package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.events.durable.FencingToken
import dev.rubentxu.pipeline.v2.events.durable.LeaseRecord
import dev.rubentxu.pipeline.v2.events.durable.RunLeaseObservationPort
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId

/**
 * The run's lease as a recovery that has ALREADY taken it sees it.
 *
 * ## Why the recovery plane needs a second view of the lease
 * A recovery TAKES the run's lease before it reads the run, because the lock
 * behind the lease is the only authority on "is a live process publishing this
 * run". The durable RECORD alone cannot answer that: a writer that was killed
 * leaves its record behind, still saying its owner is alive.
 *
 * So the recovery reads a lease that names ITSELF, alive, and the introspection
 * it asks recognises that holder as itself instead of refusing to describe the
 * run.
 *
 * The dry run cannot take the lease — taking it IS a write: it locks the run
 * and stamps a durable ownership record, and a dry run that held the lease
 * would also make a live writer look busy for as long as it lasted. So the dry
 * run needs the same VIEW without the acquisition, and this is that view.
 *
 * ## What it presents, and why it is the acquisition's own result
 * The record the acquisition leaves behind is not an approximation of it: it IS
 * it, and the three cases are the three the pure decider produces.
 *
 *  - no record yet: the acquisition issues the FIRST token to a fresh owner;
 *  - a record naming somebody else: the previous owner is stamped gone (the
 *    lock was free, so it is gone) and the token ADVANCES, which is what makes
 *    the predecessor detectable as stale;
 *  - a record naming US: the acquisition is a RE-ENTRY and does not advance the
 *    token, because an owner that fenced itself out of its own run would be
 *    nonsense.
 *
 * The owner is alive in all three: the acquisition always leaves a live owner,
 * because the owner IS the process that holds the lock.
 *
 * ## What it is NOT
 * It is not a lease, and it grants nothing. It holds no lock, writes no record
 * and cannot fence anybody: it presents facts. The authority on whether a live
 * process holds the run is the lock, and it is asked of the kernel through
 * [dev.rubentxu.pipeline.v2.events.durable.RunLeasePort.liveness], not of this
 * class.
 */
class LeaseAsAcquired(
    private val lease: RunLeaseObservationPort,
    private val owner: RunOwnerId,
) : RunLeaseObservationPort {

    /**
     * Whether the lease knows this run.
     *
     * Always true, and that is the acquisition's own answer: the acquisition
     * writes a record for EVERY run it touches, so after it every run is known
     * to the lease. A run that never had a lease is still known to it, because
     * the recovery just gave it one.
     */
    override fun isKnown(runId: String): Boolean = true

    /**
     * The record the acquisition would have left behind.
     */
    override fun observe(runId: String): LeaseRecord? {
        val previous = lease.observe(runId)
            ?: return LeaseRecord(
                runId = runId,
                ownerId = owner,
                fencingToken = FencingToken.FIRST,
                ownerAlive = true,
            )
        if (previous.ownerId == owner) {
            // A re-entry keeps the token: advancing it would fence the owner
            // against itself.
            return previous.copy(ownerAlive = true)
        }
        return previous.copy(
            ownerId = owner,
            fencingToken = FencingToken.of(previous.fencingToken.value + 1),
            ownerAlive = true,
        )
    }
}
