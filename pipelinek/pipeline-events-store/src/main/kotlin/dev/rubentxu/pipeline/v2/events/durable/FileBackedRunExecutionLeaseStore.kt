package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.runtime.durable.FencingToken
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseAcquisition
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseLiveness
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseRecord
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.runtime.durable.PublishAuthority
import dev.rubentxu.pipeline.v2.runtime.durable.RunExecutionLease
import dev.rubentxu.pipeline.v2.runtime.durable.RunLeasePort
import dev.rubentxu.pipeline.v2.runtime.durable.RunOwnerId
import java.nio.file.Path

/**
 * The effectful half of [RunExecutionLease]: a cross-process lease store.
 * ## Why this is not `DbLock`
 * `DbLock` is a per-JVM `ConcurrentHashMap<String, ReentrantLock>`. It
 * serialises two *instances inside one JVM* and nothing else. Two OS processes
 * resuming the same run never see each other, which is precisely the case the
 * ownership law exists to govern. This store uses an **OS file lock**
 * (`FileChannel.tryLock`), which the kernel releases if the process dies — so
 * a killed owner's run becomes takeable without anyone having to clean up.
 * That last property is the reason a file lock rather than a heartbeat-only
 * design: a SIGKILLed process cannot run a "release" handler, so liveness must
 * come from the kernel, not from the owner cooperating.
 * ## The two-layer design
 * ```text
 * OS file lock   ->  mutual exclusion (who is the ONLY writer right now)
 * fencing token  ->  recency proof (is my authority still current?)
 * ```
 * The lock answers "may I write now"; the token answers "am I still the
 * authority". They are not substitutes. A lock alone cannot tell a process that
 * was paused long enough for a takeover to have happened; a token alone cannot
 * stop two processes both believing they hold token N.
 * This class gathers facts and holds resources. Every *decision* is made by
 * [RunExecutionLease], which stays pure and is what the tests pin.
 *
 * ## How the lease is named
 *
 * The lock file and the ownership record of a run are named after the run
 * itself, and the name IS the identity, verbatim: `lease-<runId>.lock` and
 * `lease-<runId>.record`. The run decides whether it can carry that name
 * ([RunId.fileKey]). A name that were a transformation of the identity — a
 * folded or escaped form — could give two runs the same file, and then one run
 * would take the lease of the other, advance the fencing token and fence out
 * a publisher that had done nothing wrong. An identity that cannot be named is
 * refused before a path is built and before the machine is touched.
 *
 * ## The window, and why it has exactly one exit
 *
 * An acquisition holds two resources — the open channel and the lock taken over
 * it — from the moment the lock file is opened until this store takes
 * responsibility for them. Every step in between can fail: opening the file,
 * taking the lock, reading the previous record, writing the new one.
 *
 * A failure that leaves the lock behind is NOT a failed acquisition. It is a run
 * that nobody can acquire again: the kernel still sees this process as the
 * holder, so the next acquisition of the same run is answered "somebody else
 * owns it" by a process that does not exist. A run that can never be recovered
 * is a far worse failure than a run that failed loudly.
 *
 * So the window has ONE exit, not one per failure mode: a `finally` that gives
 * back whatever this call took and did not hand over. The cleanup used to be
 * spread over four places, and each of the four could be forgotten by the next
 * change — which is exactly how a run gets locked for ever.
 *
 * The machine underneath is reached through [LeaseFileSystem], so the window can
 * be exercised without a filesystem: a double can fail at the exact step the
 * real machine would fail at, and can then be asked whether the lock was given
 * back.
*/
class FileBackedRunExecutionLeaseStore internal constructor(
    private val leaseDir: Path,
    private val files: LeaseFileSystem = FileLeaseFileSystem(),
) : AutoCloseable, RunLeasePort {

    /**
     * The lock this store owns, and the channel it lives on, as the one thing
     * the operating system gives back together.
     */
    private var held: LeaseLock? = null

    /**
     * The durable record of the run this store owns. The PATH, not the run id:
     * the path is what the release has to write back, and re-deriving it from
     * the run id would repeat the derivation that named the lease in the first
     * place.
     */
    private var heldRecordFile: Path? = null

    /** True when this store currently holds the OS lock for a run. */
    val isHolding: Boolean get() = held != null

    /**
     * Attempt to become the publishing authority for [request].
     * Returns the pure decider's verdict. A refusal is a *normal* outcome, not
     * an exception: losing the race is how ownership is enforced, and the
     * caller must surface it as a typed rejection.
    */
    override fun acquire(request: LeaseRequest): LeaseAcquisition {
        // The identity must name its own lease. A key DERIVED from it — folded,
        // escaped or truncated — can give two identities the same key, and then
        // two runs share one lease: one of them is refused as "already owned"
        // and the other takes the lease over, advancing the fencing token and
        // fencing out a publisher that did nothing wrong. So the key IS the
        // identity, and an identity that cannot name a location has no key at
        // all: it is refused here, before a path is built and before the
        // machine is touched.
        val key = RunId.fileKey(request.runId.value)
            ?: return LeaseAcquisition.Unverifiable(
                RunId.unnameableReason(request.runId.value),
            )

        files.ensureDirectory(leaseDir)
        val lockFile = leaseDir.resolve(lockFileName(key))
        val recordFile = leaseDir.resolve(recordFileName(key))

        // What this call took and did not hand over. The `finally` below is the
        // single exit of the window: while `committed` is false, whatever was
        // taken goes back.
        var channel: LeaseChannel? = null
        var lock: LeaseLock? = null
        var committed = false

        try {
            val opened = files.openLockFile(lockFile)
            channel = opened

            val taken = opened.tryLock()
            lock = taken

            if (taken == null) {
                // Either the kernel refused us (another process holds it) or this
                // JVM already holds it. Both are ownership conflicts and both fail
                // closed. We deliberately do NOT consult the durable record to try
                // to "recover" the lease: an unobservable or refused lock is never
                // a free lock, and a run that looks abandoned must be recovered by
                // TAKEOVER on a later acquire, not by ignoring a live lock.
                val observed = files.readRecord(recordFile)
                return LeaseAcquisition.AlreadyOwned(
                    record = observed ?: LeaseRecord(
                        request.runId, ownerId = null,
                        fencingToken = FencingToken.UNISSUED, ownerAlive = true,
                    ),
                    heldBy = observed?.ownerId ?: UNKNOWN_OWNER,
                    fencingToken = observed?.fencingToken ?: FencingToken.UNISSUED,
                )
            }

            // We hold the OS lock, which is itself the liveness fact: the kernel
            // grants this lock only when no other process holds it. So whatever the
            // durable record says, the PREVIOUS owner is now gone — its lock was
            // released because the process died or released it explicitly.
            // Overwriting ownerAlive=false here is therefore correct, and is not
            // the same as claiming a takeover when a live owner exists: we only
            // reach this line while HOLDING the exclusive lock.
            val previous = files.readRecord(recordFile)
            val decision = RunExecutionLease.acquire(
                current = previous?.copy(ownerAlive = false),
                request = request,
            )

            // Only a verdict that GRANTS ownership may be honoured. A verdict
            // that refuses cannot, even though the OS lock granted us the turn:
            // the lock goes back in the `finally` below.
            val granted: LeaseRecord? = when (decision) {
                is LeaseAcquisition.Acquired -> decision.record
                is LeaseAcquisition.TakenOver -> decision.record
                is LeaseAcquisition.Reentered -> decision.record
                else -> null
            }
            if (granted != null) {
                commitOwnership(taken, recordFile, granted)
                committed = true
            }
            return decision
        } finally {
            // The ONE exit of the window. Anything this call took and did not
            // hand over goes back, so the next acquisition of the same run can
            // still progress. The lock carries its channel, so handing the lock
            // back hands the channel back too; when there is no lock, the
            // channel is closed on its own.
            if (!committed) {
                runCatching { lock?.close() }
                if (lock == null) runCatching { channel?.close() }
            }
        }
    }

    /**
     * Durably record ownership and take responsibility for the OS lock. Kept as
     * one place so every ownership-granting verdict commits identically — a
     * path that wrote the record but forgot the lock (or the reverse) would
     * produce a lease that claims authority without holding it.
    */
    private fun commitOwnership(
        lock: LeaseLock,
        recordFile: Path,
        record: LeaseRecord,
    ) {
        // The order is the point: the ownership is on disk BEFORE the store
        // claims the lock. A failure while writing leaves the lock with this
        // call, which gives it back; the opposite order would leave a lease
        // that claims authority without holding it, which nobody can recover.
        files.writeRecord(recordFile, record)
        this.held = lock
        this.heldRecordFile = recordFile
    }

    /**
     * Release the lease. Only the current owner may release; a stale owner
     * releasing must not unown the live one, which is enforced by
     * [RunExecutionLease.release] and re-checked here against the durable
     * record.
     *
     * The lock is given back whatever the record said: a release that could not
     * write must not keep the run locked for everybody.
    */
    override fun release(ownerId: RunOwnerId) {
        val recordFile = heldRecordFile
        val lock = held
        if (recordFile == null || lock == null) return

        try {
            val current = files.readRecord(recordFile)
            if (current != null) {
                files.writeRecord(recordFile, RunExecutionLease.release(current, ownerId))
            }
        } finally {
            runCatching { lock.close() }
            this.held = null
            this.heldRecordFile = null
        }
    }

    /**
     * Whether a lease for [runId] has ever been recorded on disk (acquired,
     * taken over, reentered, or released).
     * The check is row-presence on the durable record file, NOT the lock
     * table: the OS lock only knows whether THIS JVM currently holds the
     * Lock for [runId], whereas the "is this run known" authority needs
     * to answer positively for a run whose lease was released earlier in
     * the same process, or owned by a process that has since died and had
     * its lock swept by the kernel. The record file is the audit trail that
     * survives both lifecycles.
     * The lease authority is the primary answer for run existence: a run
     * that was deliberately declared (via [acquire]) MUST answer `true`
     * even if it has not yet produced any event. This is the wiring the
     * `EventRecordReadPortStoreAdapter.runExists` callback composes with
     * `SqliteEventStore::hasRun` so an empty-but-declared run does not
     * surface as [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal.UnknownRun]
     * on the read port.
     * Returns `false` for a runId whose record file does not exist (never
     * acquired, or acquired on a previous lease-dir that has since been
     * cleaned up).
    */
    override fun isKnown(runId: RunId): Boolean =
        recordFileFor(runId)?.let { files.fileExists(it) } ?: false

    /**
     * Whether [held] is still the authoritative token for [runId]. This is the
     * fencing check a publisher calls immediately before writing.
    */
    fun authorise(runId: RunId, held: FencingToken): PublishAuthority {
        val recordFile = recordFileFor(runId)
            ?: return PublishAuthority.Unverifiable(
                RunId.unnameableReason(runId.value),
            )
        val observed = files.readRecord(recordFile)
            ?: return PublishAuthority.Unverifiable("no lease record for run ${runId.value}")
        return RunExecutionLease.authorisePublish(observed, held)
    }

    /**
     * Observe the live lease record for [runId] WITHOUT acquiring it.
     * Read-only: this method does not take the OS file lock, does not advance the
     * fencing token, and does not mutate any durable state. It returns the
     * `LeaseRecord` so the [RuntimeIntrospectionPort] and [RuntimeControlPort]
     * adapters can authoritatively answer "is the lease currently held by
     * another?" without taking a fresh lease.
     * The pure decider [RunExecutionLease.acquire] is what makes the
     * `LeaseHeldByAnother` answer authoritative; this read-side twin exists only
     * to fetch the durable facts the decider needs.
     * @return The current `LeaseRecord`, or `null` when no lease has ever been
     *   issued for [runId].
    */
    override fun observe(runId: RunId): LeaseRecord? =
        recordFileFor(runId)?.let { files.readRecord(it) }

    /**
     * Whether a LIVE process holds this run's lease right now.
     *
     * ## How it asks, and what it refuses to do
     * The lock is the only authority on "is somebody publishing this run", and
     * the kernel is the only authority on the lock. So the question is asked of
     * the kernel: the lock file is opened and `tryLock`ed, and the answer is
     * what the kernel said. The lock is given straight back — this probe
     * observes, it does not own.
     *
     * Three deliberate choices:
     *
     *  - **The file is opened WITHOUT `CREATE`.** A probe that creates the file
     *    it is probing has written, and a caller that promised to write nothing
     *    would have broken its promise on a run that never held a lease.
     *  - **The channel is opened for READ and WRITE**, so the lock taken is the
     *    same EXCLUSIVE kind the acquirer takes. A shared lock would answer a
     *    different question, and a read-only file makes the question
     *    unanswerable: the honest answer there is [LeaseLiveness.Unverifiable],
     *    never a free lease.
     *  - **The record is never written.** The probe reads the record to
     *    attribute the holder and touches nothing else.
     *
     * A `OverlappingFileLockException` means THIS JVM already holds the lock,
     * which is a real ownership conflict and not an infrastructure failure — the
     * same distinction [acquire] draws.
     */
    override fun liveness(runId: RunId): LeaseLiveness {
        val key = RunId.fileKey(runId.value)
            ?: return LeaseLiveness.Unverifiable(
                RunId.unnameableReason(runId.value),
            )
        val lockFile = leaseDir.resolve(lockFileName(key))
        val recordFile = leaseDir.resolve(recordFileName(key))
        if (!files.fileExists(lockFile)) {
            // No lock file: no lock was ever taken, so nobody holds it. The
            // probe must not create the file to find this out.
            return LeaseLiveness.Free
        }
        return try {
            val lock = files.probeLock(lockFile)
            if (lock == null) {
                LeaseLiveness.Held(
                    files.readRecord(recordFile)?.ownerId ?: UNKNOWN_OWNER,
                )
            } else {
                runCatching { lock.close() }
                LeaseLiveness.Free
            }
        } catch (e: Exception) {
            LeaseLiveness.Unverifiable(e.shortDiagnostic())
        }
    }

    override fun close() {
        val lock = held
        if (lock != null) runCatching { lock.close() }
        held = null
        heldRecordFile = null
    }

    // ------------------------------------------------------------ persistence

    /**
     * The durable record of [runId], or `null` when the identity cannot name
     * one. `null` is the honest answer and not a failure: an identity that
     * cannot be named has never had a record, because the store refuses to
     * create one.
     */
    private fun recordFileFor(runId: RunId): Path? =
        RunId.fileKey(runId.value)?.let { leaseDir.resolve(recordFileName(it)) }

    /**
     * The name of the lock file of a run. The KEY is the identity, verbatim.
     */
    private fun lockFileName(key: String): String = "lease-$key.lock"

    /**
     * The record lives in a SEPARATE file from the lock, and that separation is
     * load-bearing rather than cosmetic.
     * Measured on this project's toolchain (Temurin 21.0.8): writing to a file
     * that this process holds an advisory `FileLock` on releases the lock. The
     * sequence "open, tryLock, then write the ownership record to the same
     * path" therefore produced a store that reported `isHolding = true` while
     * a second process could take the lock freely — a lease that guarded
     * nothing. Writing the record to a sibling file keeps the locked inode
     * untouched for the whole life of the lease.
     * The lock file is created once and never written, so there is no window
     * in which its contents could disagree with the kernel's lock table.
    */
    private fun recordFileName(key: String): String = "lease-$key.record"

    private companion object {
        val UNKNOWN_OWNER: RunOwnerId = RunOwnerId.of("unknown-owner")!!
    }

    /** A short, one-line diagnostic for a failure that must not become a stack trace. */
    private fun Throwable.shortDiagnostic(): String {
        val cls = this::class.simpleName ?: this.javaClass.name
        val msg = message?.take(120)?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }
}
