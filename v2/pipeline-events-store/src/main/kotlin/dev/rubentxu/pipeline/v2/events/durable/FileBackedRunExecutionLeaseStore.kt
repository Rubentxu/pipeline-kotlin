package dev.rubentxu.pipeline.v2.events.durable

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path

/**
 * S2-R0 — the effectful half of [RunExecutionLease]: a cross-process lease store.
 *
 * ## Why this is not `DbLock`
 *
 * `DbLock` is a per-JVM `ConcurrentHashMap<String, ReentrantLock>`. It
 * serialises two *instances inside one JVM* and nothing else. Two OS processes
 * resuming the same run never see each other, which is precisely the case the
 * ownership law exists to govern. This store uses an **OS file lock**
 * (`FileChannel.tryLock`), which the kernel releases if the process dies — so
 * a killed owner's run becomes takeable without anyone having to clean up.
 *
 * That last property is the reason a file lock rather than a heartbeat-only
 * design: a SIGKILLed process cannot run a "release" handler, so liveness must
 * come from the kernel, not from the owner cooperating.
 *
 * ## The two-layer design
 *
 * ```text
 * OS file lock   ->  mutual exclusion (who is the ONLY writer right now)
 * fencing token  ->  recency proof (is my authority still current?)
 * ```
 *
 * The lock answers "may I write now"; the token answers "am I still the
 * authority". They are not substitutes. A lock alone cannot tell a process that
 * was paused long enough for a takeover to have happened; a token alone cannot
 * stop two processes both believing they hold token N.
 *
 * This class gathers facts and holds resources. Every *decision* is made by
 * [RunExecutionLease], which stays pure and is what the tests pin.
 */
class FileBackedRunExecutionLeaseStore(
    private val leaseDir: Path,
) : AutoCloseable {

    private var channel: FileChannel? = null
    private var held: FileLock? = null
    private var heldRunId: String? = null

    /** True when this store currently holds the OS lock for a run. */
    val isHolding: Boolean get() = held != null

    /**
     * Attempt to become the publishing authority for [request].
     *
     * Returns the pure decider's verdict. A refusal is a *normal* outcome, not
     * an exception: losing the race is how ownership is enforced, and the
     * caller must surface it as a typed rejection.
     */
    fun acquire(request: LeaseRequest): LeaseAcquisition {
        Files.createDirectories(leaseDir)
        val lockFile = leaseDir.resolve(lockFileName(request.runId))

        val channel = FileChannel.open(lockFile, java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)

        val lock: FileLock? = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            // Same JVM already holds it: a real ownership conflict, not an
            // infrastructure failure. Reported as a typed denial, never as an
            // acquisition.
            null
        }

        if (lock == null) {
            // Either the kernel refused us (another process holds it) or this
            // JVM already holds it. Both are ownership conflicts and both fail
            // closed. We deliberately do NOT consult the durable record to try
            // to "recover" the lease: an unobservable or refused lock is never
            // a free lock, and a run that looks abandoned must be recovered by
            // TAKEOVER on a later acquire, not by ignoring a live lock.
            val observed = readRecord(leaseDir.resolve(recordFileName(request.runId)))
            channel.close()
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
        //
        // Overwriting ownerAlive=false here is therefore correct, and is not
        // the same as claiming a takeover when a live owner exists: we only
        // reach this line while HOLDING the exclusive lock.
        val previous = readRecord(leaseDir.resolve(recordFileName(request.runId)))
        val decision = RunExecutionLease.acquire(
            current = previous?.copy(ownerAlive = false),
            request = request,
        )

        when (decision) {
            is LeaseAcquisition.Acquired -> commitOwnership(channel, lock, request.runId, decision.record)
            is LeaseAcquisition.TakenOver -> commitOwnership(channel, lock, request.runId, decision.record)
            is LeaseAcquisition.Reentered -> commitOwnership(channel, lock, request.runId, decision.record)
            else -> {
                // A verdict that is not ownership cannot be honoured even
                // though the OS lock granted us the turn.
                runCatching { lock.release() }
                channel.close()
            }
        }
        return decision
    }

    /**
     * Durably record ownership and take responsibility for the OS lock. Kept as
     * one place so every ownership-granting verdict commits identically — a
     * path that wrote the record but forgot the lock (or the reverse) would
     * produce a lease that claims authority without holding it.
     */
    private fun commitOwnership(
        channel: FileChannel,
        lock: FileLock,
        runId: String,
        record: LeaseRecord,
    ) {
        writeRecord(leaseDir.resolve(recordFileName(runId)), record)
        this.channel = channel
        this.held = lock
        this.heldRunId = runId
    }

    /**
     * Release the lease. Only the current owner may release; a stale owner
     * releasing must not unown the live one, which is enforced by
     * [RunExecutionLease.release] and re-checked here against the durable
     * record.
     */
    fun release(ownerId: RunOwnerId) {
        val runId = heldRunId
        val lock = held
        val channel = this.channel
        if (runId == null || lock == null || channel == null) return

        val current = readRecord(leaseDir.resolve(recordFileName(runId)))
        if (current != null) {
            writeRecord(leaseDir.resolve(recordFileName(runId)),
                RunExecutionLease.release(current, ownerId))
        }
        runCatching { lock.release() }
        runCatching { channel.close() }
        this.held = null
        this.channel = null
        this.heldRunId = null
    }

    /**
     * Whether a lease for [runId] has ever been recorded on disk (acquired,
     * taken over, reentered, or released).
     *
     * The check is row-presence on the durable record file, NOT the lock
     * table: the OS lock only knows whether THIS JVM currently holds the
     * lock for [runId], whereas the M1-A "is this run known" authority needs
     * to answer positively for a run whose lease was released earlier in
     * the same process, or owned by a process that has since died and had
     * its lock swept by the kernel. The record file is the audit trail that
     * survives both lifecycles.
     *
     * The lease authority is the primary answer for run existence: a run
     * that was deliberately declared (via [acquire]) MUST answer `true`
     * even if it has not yet produced any event. This is the wiring the
     * `EventRecordReadPortStoreAdapter.runExists` callback composes with
     * `SqliteEventStore::hasRun` so an empty-but-declared run does not
     * surface as [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal.UnknownRun]
     * on the read port.
     *
     * Returns `false` for a runId whose record file does not exist (never
     * acquired, or acquired on a previous lease-dir that has since been
     * cleaned up).
     */
    fun isKnown(runId: String): Boolean =
        Files.isRegularFile(leaseDir.resolve(recordFileName(runId)))

    /**
     * Whether [held] is still the authoritative token for [runId]. This is the
     * fencing check a publisher calls immediately before writing.
     */
    fun authorise(runId: String, held: FencingToken): PublishAuthority {
        val observed = readRecord(leaseDir.resolve(recordFileName(runId)))
            ?: return PublishAuthority.Unverifiable("no lease record for run $runId")
        return RunExecutionLease.authorisePublish(observed, held)
    }

    /**
     * M2 §4.7.1 — observe the live lease record for [runId] WITHOUT acquiring it.
     *
     * Read-only: this method does not take the OS file lock, does not advance the
     * fencing token, and does not mutate any durable state. It returns the
     * `LeaseRecord` so the [RuntimeIntrospectionPort] and [RuntimeControlPort]
     * adapters can authoritatively answer "is the lease currently held by
     * another?" without taking a fresh lease.
     *
     * The pure decider [RunExecutionLease.acquire] is what makes the
     * `LeaseHeldByAnother` answer authoritative; this read-side twin exists only
     * to fetch the durable facts the decider needs.
     *
     * @return The current `LeaseRecord`, or `null` when no lease has ever been
     *   issued for [runId].
     */
    fun observe(runId: String): LeaseRecord? =
        readRecord(leaseDir.resolve(recordFileName(runId)))

    override fun close() {
        val lock = held
        val channel = this.channel
        if (lock != null) runCatching { lock.release() }
        if (channel != null) runCatching { channel.close() }
        held = null
        this.channel = null
        heldRunId = null
    }

    // ------------------------------------------------------------ persistence

    /**
     * The durable record format. Deliberately a flat `key=value` text file:
     * it is written while the OS lock is held, read by an operator with `cat`,
     * and must survive a `SIGKILL` mid-run. A JSON or binary format would buy
     * nothing here and cost inspectability.
     */
    private fun writeRecord(lockFile: Path, record: LeaseRecord) {
        val body = buildString {
            appendLine("run_id=${record.runId}")
            appendLine("owner_id=${record.ownerId?.value ?: ""}")
            appendLine("fencing_token=${record.fencingToken.value}")
            appendLine("owner_alive=${record.ownerAlive ?: ""}")
        }
        // Write-and-rename so a crash mid-write cannot leave a half record
        // that parses as a valid but wrong ownership claim.
        val tmp = lockFile.resolveSibling(lockFile.fileName.toString() + ".tmp")
        Files.writeString(tmp, body)
        Files.move(
            tmp,
            lockFile,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun readRecord(lockFile: Path): LeaseRecord? {
        if (!Files.isRegularFile(lockFile)) return null
        val fields = Files.readAllLines(lockFile)
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }.toMap()
        val runId = fields["run_id"]?.takeIf { it.isNotBlank() } ?: return null
        val owner = fields["owner_id"]?.takeIf { it.isNotBlank() }?.let { RunOwnerId.of(it) }
        val token = fields["fencing_token"]?.toLongOrNull()?.let { FencingToken.of(it) }
            ?: FencingToken.UNISSUED
        val alive: Boolean? = when (fields["owner_alive"]?.trim()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        return LeaseRecord(runId, owner, token, alive)
    }

    private fun lockFileName(runId: String): String =
        "lease-" + runId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".lock"

    /**
     * The record lives in a SEPARATE file from the lock, and that separation is
     * load-bearing rather than cosmetic.
     *
     * Measured on this project's toolchain (Temurin 21.0.8): writing to a file
     * that this process holds an advisory `FileLock` on releases the lock. The
     * sequence "open, tryLock, then write the ownership record to the same
     * path" therefore produced a store that reported `isHolding = true` while
     * a second process could take the lock freely — a lease that guarded
     * nothing. Writing the record to a sibling file keeps the locked inode
     * untouched for the whole life of the lease.
     *
     * The lock file is created once and never written, so there is no window
     * in which its contents could disagree with the kernel's lock table.
     */
    private fun recordFileName(runId: String): String =
        "lease-" + runId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".record"

    private companion object {
        val UNKNOWN_OWNER: RunOwnerId = RunOwnerId.of("unknown-owner")!!
    }
}
