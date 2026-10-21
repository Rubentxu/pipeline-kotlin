package dev.rubentxu.pipeline.v2.events.durable

/**
 * The READ-ONLY half of the run lease: the two facts a reader needs and
 * nothing else.
 *
 * ## Why this port exists
 * [FileBackedRunExecutionLeaseStore] is a cross-process lease store: it opens
 * an OS file lock, it writes a lease record, it hands out fencing tokens. A
 * reader that only wants to LOOK — does this run exist, and who held it — has
 * no business depending on a class whose whole purpose is to MUTATE that
 * lease, and whose storage is a directory of lock files.
 *
 * That dependence had a concrete cost. The introspection adapter took the
 * concrete store as a constructor argument, so the only way to exercise it
 * was to give it a directory of lock files on disk. That is not a unit test,
 * it is a second harness, and this repository does not buy those: `AGENTS.md`
 * keeps unit tests free of filesystem persistence and puts that behaviour in
 * `examples/` instead.
 *
 * So the two read-only facts became a port, and the adapter depends on the
 * port. The store implements it; a test supplies an in-memory answer.
 *
 * ## What this port is NOT
 * It is not the lease. It cannot be acquired, released or fenced: the
 * authority for those is [RunExecutionLease] (pure) plus the store's OS lock.
 * A reader that asks this port whether the lease is held learns what the
 * RECORD says, and a record is not a liveness fact — a process that was
 * killed leaves its record behind saying the owner is alive. That is why the
 * control adapter, which WRITES, takes the lease instead of consulting it.
 */
interface RunLeaseObservationPort {

    /**
     * Whether a lease has ever been issued for [runId].
     *
     * True when the lease RECORD exists, which includes a record left behind
     * by a writer that died. It says nothing about whether that writer is
     * still alive.
     */
    fun isKnown(runId: String): Boolean

    /**
     * The current lease record for [runId], or `null` when no lease has ever
     * been issued for it.
     *
     * Read-only: it does not take the lock, does not advance the fencing
     * token and does not mutate anything.
     */
    fun observe(runId: String): LeaseRecord?
}
