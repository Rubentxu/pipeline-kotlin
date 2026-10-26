package dev.rubentxu.pipeline.v2.events.durable

/**
 * The whole lease: the read-only facts AND the exclusivity.
 *
 * ## Why this port exists
 * The two halves of a lease are different contracts and a caller usually
 * needs exactly one of them:
 *
 *  - a READER wants the facts and nothing else ([RunLeaseObservationPort]);
 *  - a WRITER wants exclusivity — it has to know that no live process is
 *    publishing the run, and the only authority on that is the LOCK, not the
 *    record.
 *
 * The record cannot answer the second question. A writer that was killed
 * leaves its record behind, and the record still says the owner is alive.
 * A writer that consults the record concludes that a dead run is a busy
 * run, and then refuses forever to touch it.
 *
 * So [acquire] takes the OS lock and [release] gives it back, and the
 * store keeps the lock until it does. That is the same step the cancel
 * adapter takes, and it is what makes "nobody is publishing this run" a
 * fact instead of a guess.
 */
interface RunLeasePort : RunLeaseObservationPort {

    /**
     * Take the run's lease, and hold it until [release].
     *
     * The lock is the authority: [LeaseAcquisition.AlreadyOwned] means
     * another process is holding it RIGHT NOW, which is a fact about the
     * present and not about the past.
     */
    fun acquire(request: LeaseRequest): LeaseAcquisition

    /**
     * Whether a LIVE process holds this run's lease right now.
     *
     * ## Why this is a question of its own
     * [RunLeaseObservationPort.observe] answers what the durable RECORD says, and
     * a record is not a liveness fact: a writer that was killed leaves its record
     * behind, still saying its owner is alive. [acquire] answers the live question
     * too, but only by TAKING the lease — which writes a durable ownership record
     * and locks the run. A caller that must not write cannot ask it.
     *
     * So the live question gets its own door, and it does not write: it asks the
     * kernel whether the lock is free and gives it straight back. The answer is
     * about the present, exactly like the lock [acquire] consults, and the
     * difference is that nothing was taken.
     */
    fun liveness(runId: String): LeaseLiveness

    /**
     * Give the lease back: release the lock and mark the owner as gone.
     *
     * Releasing a lease nobody holds is a no-op, not a failure.
     */
    fun release(ownerId: RunOwnerId)
}
