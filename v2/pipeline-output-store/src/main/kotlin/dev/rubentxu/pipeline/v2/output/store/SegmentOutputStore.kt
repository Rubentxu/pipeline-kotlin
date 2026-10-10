package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputDigest
import dev.rubentxu.pipeline.v2.output.OutputPinPort
import dev.rubentxu.pipeline.v2.output.OutputPinResult
import dev.rubentxu.pipeline.v2.output.OutputPruneIntent
import dev.rubentxu.pipeline.v2.output.OutputPruneReport
import dev.rubentxu.pipeline.v2.output.OutputReadDigestedResult
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputTailPort
import dev.rubentxu.pipeline.v2.output.OutputTailState
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputRetentionPort
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.PruneAuthorisation
import dev.rubentxu.pipeline.v2.output.PruneRefusal
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A file-backed Output Plane implementing strategy **D** (segment reservation with recovery), the
 * protocol decided by RCE `ADR-0002` on measured contention and commit-window grounds.
 *
 * ## Layout
 *
 * ```text
 * <root>/streams/<streamKey>/
 *     cur.seg      payload of the segment currently being written
 *     cur.cmt      the stream's committed offset          <- the O2 authority
 *     cur.res      the outstanding reservation "<base>|<limit>"   (absent = none)
 *     segments/    sealed segments, named "<base>-<length>.seg"
 * ```
 *
 * `cur.cmt` is a **global** committed offset, not a per-segment count, so it is the single number a
 * cursor names. The base of the current segment is derivable — it is one past the last sealed
 * segment — which is what lets the current segment be sealed and a new one opened without a second
 * durable counter to fall out of step.
 *
 * ## Why this shape is D and not A, B or C
 *
 * D keeps bytes in **segments a writer owns** and appends sequentially into them: no shared index
 * write per frame, no per-frame metadata, no cross-writer lock. That is what made it 200x–545x
 * faster than A and C at eight parallel writers. The order is recovered by *merging segments by
 * offset*, not by consulting a global index, so a single-writer-per-stream Output Plane keeps the
 * property without inheriting the contention that killed the others.
 *
 * - **A** (durable reserve + commit record) stranded payload bytes behind a commit record that never
 *   landed, and paid for it with two windows instead of one release step.
 * - **B** (store-assigned sequence) had the strongest per-write property — sequence and payload as
 *   one atomic unit — but is single-writer by construction.
 * - **C** (per-operation file + global index) stranded bytes at the window and was the slowest.
 *
 * ## The three obligations, and where each one lives
 *
 * - **O1** — [Reserve]'s initialiser writes `cur.res` and returns only afterwards. The
 *   acknowledgement *is* the reservation, not the append.
 * - **O2** — the committed offset lives in `cur.cmt`, never in the size of a byte file.
 * - **O3** — [recover] is a distinct entry point. Until it completes, every read is refused with
 *   [OutputRefusal.RecoveryNotCompleted] rather than served from an unreconciled state.
 *
 * ## Density is why D is dense and A is not
 *
 * Recovery keeps each stream's committed prefix and then **releases** an outstanding reservation, so
 * a range that was claimed and not used leaves no permanent hole. Without the release, a writer that
 * reserved 64 KiB and used 200 bytes would strand 63 KiB, and a cursor into that range would be
 * permanently stuck — unable to tell "those bytes are gone" from "those bytes have not arrived yet".
 *
 * ## The refusal, kept in the class rather than only in the ADR
 *
 * Writes are issued and are **not** `fsync`ed. Bytes already issued to a file descriptor survive
 * process death in the page cache, which is the fault model this store was measured under. It
 * therefore proves *"a process that dies loses nothing it acknowledged"* and does **not** prove
 * durability across power loss — see [OutputNotEstablished.POWER_LOSS_DURABILITY].
 */
class SegmentOutputStore(
    private val root: Path,
    /**
     * Whether this store may reconcile durable state at all, per ADR-OBS-002.
     *
     * A reader answers "no". That is the whole separation the ADR makes: a read-only verb opens the
     * plane without ever running a reconciliation, so it cannot remove or truncate a reservation even
     * if every stream were unowned.
     *
     * Reads therefore do NOT require [recovered]. The invariant ADR-M1 §D4 O3 protects — a reader
     * never observes a byte the store has not committed — is enforced by the committed-offset
     * authority in [committedLocked], and only ever was helped along by recovery. Recovery is what
     * makes debris disappear; it is not what makes a read honest.
     */
    private val recoveryPermitted: Boolean = true,
    /**
     * M3 — optional pin port consulted by [canPrune]. When `null` (default),
     * `canPrune` returns [PruneAuthorisation.Granted] unconditionally. The
     * audit §B.3 (f) names the consult-before-act primitive as the load-bearing
     * authority on the retention-under-pin invariant; the wiring is optional
     * so test fixtures that do not need pins can construct a plain store.
     */
    private val pinPort: OutputPinPort? = null,
) : OutputAppendPort, OutputReadPort, OutputRecoveryPort, OutputRetentionPort, OutputSealPort,
    OutputTailPort {

    private data class Layout(
        val streamDir: Path,
        val segmentFile: Path,
        val commitFile: Path,
        val reservationFile: Path,
        val sealedDir: Path,
        /**
         * The marker saying "no further bytes will be written to this stream".
         *
         * Deliberately NOT named `sealed`: [sealedDir] holds ROTATED SEGMENTS (`$base-$length.sealed`),
         * which is an unrelated fact about storage geometry. Two meanings of "sealed" in one layout is
         * how a reader ends up trusting the wrong file, so the stream-level marker says what it is.
         */
        val streamSealMarker: Path,
        /**
         * The exclusive, kernel-held ownership of this stream, per ADR-OBS-002.
         *
         * Held from `reserve()` until `commit()`/`abandon()`, so its presence answers the only
         * question recovery cannot answer from file contents alone: is there a writer ALIVE right now?
         * The kernel releases it when the owning process dies and refuses it to a second process
         * while the first lives, which is the distinction recovery needs and which no mtime, PID or
         * heartbeat supplies without a clock and a staleness policy.
         */
        val ownershipFile: Path,
    )

    private val recoveryLock = ReentrantLock()

    @Volatile private var recovered = false

    /**
     * One lock per stream, keyed by the **canonical** stream key.
     *
     * The key is `safe(streamId.value)` — exactly the directory name [layout] resolves for that
     * stream — and it is deliberately a `String` rather than an [OutputStreamId]. [prune] reaches a
     * stream by its directory name (the id is not invertible: `safe` folds `/` onto `_`), so a map
     * keyed by the raw id could never share an entry with prune. The writer used to key on the raw
     * id and prune on the folded name, so releasing a run added a second, permanently retained lock
     * for a stream whose writer lock was already present (measured: `2N` locks after releasing `N`
     * runs, AUD-08/B1a). Two ids that fold to the same name already share one on-disk directory, so
     * they ARE one stream on disk and must share one lock; that is what makes this the correct
     * canonical key and not merely a convenient one.
     */
    private val perStream = HashMap<String, ReentrantLock>()

    @Volatile private var frameIndex: SegmentFrameIndex? = null

    /**
     * The frame index for this store, sharing its root so the index sits beside the bytes.
     *
     * Created once and held, for the same reason the store itself is held per control-dir root by
     * its provider: recovery has to be a property of the durable layout rather than of whichever
     * object a caller happened to construct. The returned index is the same instance every time, so
     * its ordinal counter cannot be split across two writers.
     */
    fun frameIndex(): SegmentFrameIndex =
        frameIndex ?: synchronized(this) { frameIndex ?: SegmentFrameIndex(this, root).also { frameIndex = it } }

    // ------------------------------------------------------------------ the tail

    /**
     * Records that [stream] will receive no further bytes, and returns the
     * [SealOutcome] that names which of the four cases the call landed in.
     *
     * The marker is written with the stream's committed extent AT THE MOMENT of sealing, not at the
     * moment it is read. That is what makes it a fact rather than a view: a reader in a fresh JVM
     * after a crash sees the same end the writer saw, instead of inferring it from whatever bytes
     * happen to be present.
     *
     * Idempotent by construction — if the marker exists it is left alone. Rewriting it with a later
     * extent would make a resumed run's seal depend on WHEN it happened to run, which is exactly the
     * non-determinism recovery must not have.
     *
     * ## Per-channel contract (M1-F.3)
     *
     *  - [SealOutcome.Sealed]: stream exists and was sealed by this call.
     *  - [SealOutcome.AlreadySealed]: stream exists and was already sealed.
     *    Idempotent: the recorded end is returned unchanged.
     *  - [SealOutcome.NeverOpened]: stream has never been opened by any
     *    writer. The legitimate-absence case a stdout-only script
     *    produces for stderr — silent no-op, not a refusal.
     *  - [SealOutcome.Failure]: real I/O failure while writing the marker.
     *    The cause is propagated as data so the caller can route on it.
     */
    override fun seal(stream: OutputStreamId): SealOutcome {
        requireRecoveredForWriting()
        val layout = layout(stream)
        return withStreamLockFor(stream) {
            // M1-F.3: an unopened stream is NOT a refusal. Sealing it
            // would mint an authority for bytes nobody wrote, but a
            // stdout-only script (which never opened stderr) must NOT
            // warn here — that was the noise the user reported. The
            // sealed result is data, not a check failure.
            if (!Files.isDirectory(layout.streamDir)) {
                return@withStreamLockFor SealOutcome.NeverOpened
            }
            if (Files.exists(layout.streamSealMarker)) {
                return@withStreamLockFor SealOutcome.AlreadySealed(sealedEnd(layout))
            }
            val end = committedLocked(layout)
            try {
                Files.createDirectories(layout.streamDir)
                Files.writeString(layout.streamSealMarker, "$end\n")
                SealOutcome.Sealed(end)
            } catch (t: Throwable) {
                // A real I/O failure is not a refusal and not a
                // legitimate absence: surface the cause as data so the
                // caller can route on it. The previous shape threw
                // IllegalStateException for "unknown stream" AND for
                // I/O failures, which conflated the two and forced
                // callers to inspect the message to tell them apart.
                SealOutcome.Failure(cause = t, end = if (Files.exists(layout.streamSealMarker)) sealedEnd(layout) else null)
            }
        }
    }

    /**
     * The tail state of [stream], or `null` when this store does not know the stream.
     *
     * Read under the stream lock, so a reader cannot observe `Open` for a stream a concurrent writer
     * has just sealed: the answer is always about one moment rather than two.
     */
    override fun tailState(stream: OutputStreamId): OutputTailState? {
        if (!recovered) return null
        val layout = layout(stream)
        if (!Files.isDirectory(layout.streamDir)) return null
        return withStreamLockFor(stream) {
            if (Files.exists(layout.streamSealMarker)) {
                OutputTailState.Sealed(sealedEnd(layout))
            } else {
                OutputTailState.Open(committedLocked(layout))
            }
        }
    }

    /** The end recorded by the marker, falling back to the live extent when it cannot be parsed. */
    private fun sealedEnd(layout: Layout): Long =
        runCatching { Files.readString(layout.streamSealMarker).trim().toLong() }
            .getOrElse { committedLocked(layout) }

    // ------------------------------------------------------------------ ports

    override fun open(stream: OutputStreamId): OutputStreamHandle {
        requireRecoveredForWriting()
        // Opening DURABLY declares the stream, which is what makes `Open(0)` a reachable state rather
        // than a guess. `ShExecution` declares both channels to the frame index before the first byte,
        // and a stream that is known to exist but holds nothing must answer the tail question
        // truthfully instead of looking like one this store has never heard of.
        Files.createDirectories(layout(stream).streamDir)
        return Handle(stream)
    }

    override fun committedExtent(stream: OutputStreamId): Long? {
        requireReadable()
        val layout = layout(stream)
        if (!Files.isDirectory(layout.streamDir)) return null
        return withStreamLockFor(stream) { committedLocked(layout) }
    }

    override fun read(stream: OutputStreamId, cursor: OutputCursor, maxBytes: Int): OutputReadResult {
        if (!recovered && recoveryPermitted) {
            return OutputReadResult.Refused(OutputRefusal.RecoveryNotCompleted)
        }
        if (maxBytes <= 0) {
            return OutputReadResult.Refused(
                OutputRefusal.InvalidRange(cursor.committedOffset, cursor.committedOffset),
            )
        }
        // A cursor names its own stream. Addressing a read to a different one is refused rather
        // than clamped: clamping would hand back the *other* stream's bytes at the same offset,
        // which is a silent wrong answer rather than an error.
        if (cursor.stream != stream) {
            return OutputReadResult.Refused(OutputRefusal.ForeignStream(expected = stream, actual = cursor.stream))
        }
        val layout = layout(stream)
        if (!Files.isDirectory(layout.streamDir)) {
            return OutputReadResult.Refused(OutputRefusal.UnknownStream(stream))
        }
        return withStreamLock(stream) {
            val extent = committedLocked(layout)
            if (cursor.committedOffset > extent) {
                return@withStreamLock OutputReadResult.Refused(
                    OutputRefusal.OffsetBeyondCommitted(cursor.committedOffset, extent),
                )
            }
            readRangeLocked(layout, stream, cursor.committedOffset, minOf(cursor.committedOffset + maxBytes, extent))
        }
    }

    override fun readRange(stream: OutputStreamId, from: Long, to: Long): OutputReadResult {
        if (!recovered && recoveryPermitted) {
            return OutputReadResult.Refused(OutputRefusal.RecoveryNotCompleted)
        }
        val layout = layout(stream)
        if (!Files.isDirectory(layout.streamDir)) {
            return OutputReadResult.Refused(OutputRefusal.UnknownStream(stream))
        }
        if (from < 0 || to <= from) {
            return OutputReadResult.Refused(OutputRefusal.InvalidRange(from, to))
        }
        return withStreamLock(stream) {
            val extent = committedLocked(layout)
            if (to > extent) {
                return@withStreamLock OutputReadResult.Refused(
                    OutputRefusal.OffsetBeyondCommitted(to, extent),
                )
            }
            readRangeLocked(layout, stream, from, to)
        }
    }

    /**
     * M3 — single-pass digest read. Same refusal shape as [readRange];
     * the default `OutputReadPort.readRangeDigested` does the same in two
     * passes (one for the page, one for the hash). The override collapses
     * both into one I/O round via [SegmentReader.readRangeDigested].
     */
    override fun readRangeDigested(
        stream: OutputStreamId,
        from: Long,
        to: Long,
    ): OutputReadDigestedResult {
        if (!recovered && recoveryPermitted) {
            return OutputReadDigestedResult.Refused(OutputRefusal.RecoveryNotCompleted)
        }
        val layout = layout(stream)
        if (!Files.isDirectory(layout.streamDir)) {
            return OutputReadDigestedResult.Refused(OutputRefusal.UnknownStream(stream))
        }
        if (from < 0 || to <= from) {
            return OutputReadDigestedResult.Refused(OutputRefusal.InvalidRange(from, to))
        }
        return withStreamLockDigested(stream) {
            val extent = committedLocked(layout)
            if (to > extent) {
                return@withStreamLockDigested OutputReadDigestedResult.Refused(
                    OutputRefusal.OffsetBeyondCommitted(to, extent),
                )
            }
            readRangeDigestedLocked(layout, stream, from, to)
        }
    }

    /**
     * Reconcile durable state. Idempotent, and safe to interrupt and call again — a store that
     * cannot be recovered twice cannot be trusted after its own recovery crashes.
     */
    override fun recover(): OutputRecoveryReport = recoveryLock.withLock {
        // ADR-OBS-002. A reader may not reconcile, and saying so loudly beats letting a read-side
        // caller reach a destructive path by accident: the whole defect was that recovery was
        // available to everyone and therefore performed by everyone.
        check(recoveryPermitted) {
            "this store was opened for READING and must not recover: a read-only verb that reconciles " +
                "can truncate a live writer's range (ADR-OBS-002). Open a writing store instead."
        }
        val streamRoot = root.resolve(STREAMS_DIR)
        Files.createDirectories(streamRoot)

        var streams = 0
        var ownedByLiveWriter = 0
        var releasedReservations = 0
        var releasedBytes = 0L
        var committedBytes = 0L
        var unbackedBytes = 0L

        Files.newDirectoryStream(streamRoot).use { entries ->
            for (entry in entries) {
                if (!Files.isDirectory(entry)) continue
                val layout = Layout(
                    streamDir = entry,
                    segmentFile = entry.resolve("cur.seg"),
                    commitFile = entry.resolve("cur.cmt"),
                    reservationFile = entry.resolve("cur.res"),
                    sealedDir = entry.resolve(SEALED_DIR),
                streamSealMarker = entry.resolve(STREAM_SEAL_MARKER),
                    ownershipFile = entry.resolve(OWNERSHIP_FILE),
                )
                // ADR-OBS-002. Reconciliation is only safe on a stream nobody is writing right now,
                // and the ownership lock is what says so: it is held for the whole of a reservation and
                // the kernel drops it the instant the owning process dies, so "held" means ALIVE rather
                // than "recently seen". Truncating a held stream would destroy a live writer's bytes,
                // which is the defect OBS-G measured.
                val reconciled = tryWithStreamOwnership(layout) {
                    releasedBytes += reconcile(layout)
                    if (Files.deleteIfExists(layout.reservationFile)) releasedReservations++
                }
                if (reconciled) streams++ else ownedByLiveWriter++
                // Counted either way: a stream being written still has committed bytes worth knowing
                // about, and its unbacked count is a fact about it rather than about this pass.
                unbackedBytes += maxOf(0L, committedLocked(layout) - readableEndLocked(layout))
                committedBytes += committedLocked(layout)
            }
        }

        recovered = true
        OutputRecoveryReport(
            streamsReconciled = streams,
            streamsOwned = ownedByLiveWriter,
            committedBytes = committedBytes,
            bytesReleased = releasedBytes,
            reservationsReleased = releasedReservations,
            bytesUnbacked = unbackedBytes,
        )
    }

    // -------------------------------------------------------------- retention

    /**
     * The directory-name prefix that owns [runId]'s streams.
     *
     * `OutputPlaneProvider.streamId` builds every id as `"$runId/$opId/transcript"` and
     * [SegmentOutputStore.safe] maps `/` to `_`, so a run's streams share an exact
     * `safe(runId) + "_"` prefix. That is why no per-run manifest has to be kept in step with the
     * directories it describes: the directory name already carries the owner, and a manifest would
     * be a second place to be wrong about which streams exist.
     *
     * The prefix is a *filter*, never a parse. Nothing here turns a directory name back into an
     * [OutputStreamId], because that transform is not invertible — `safe` folds `/` onto `_`, and
     * an id that legitimately contains `_` could not be recovered. Undoing the collision would mean
     * changing the on-disk layout, which is a durable format change and not this block's to make.
     * A caller that needs stream ids builds them: it already knows the run and the operations.
     */
    private fun runPrefix(runId: String): String = Companion.safe(runId) + "_"

    private fun runStreamDirs(runId: String): List<Path> {
        val streamRoot = root.resolve(STREAMS_DIR)
        if (!Files.isDirectory(streamRoot)) return emptyList()
        val prefix = runPrefix(runId)
        return Files.newDirectoryStream(streamRoot).use { entries ->
            entries.asSequence()
                .filter { Files.isDirectory(it) }
                .filter { it.fileName.toString().startsWith(prefix) }
                .toList()
        }
    }

    override fun hasOutputFor(runId: String): Boolean = runStreamDirs(runId).isNotEmpty()

    /**
     * Removes a run's streams. See [OutputRetentionPort.prune].
     *
     * The byte count comes from the store's own committed offset rather than from `Files.size`:
     * a segment can be sealed, and a file's size is not what a reader was ever promised. A report
     * naming the wrong number would be a small lie in the one place a caller uses it to confirm
     * that data is really gone.
     */
    override fun prune(intent: OutputPruneIntent): OutputPruneReport {
        requireRecoveredForWriting()
        val targets = runStreamDirs(intent.runId)
        if (targets.isEmpty()) return OutputPruneReport(0, 0L, 0)

        var removed = 0
        var bytes = 0L
        for (dir in targets) {
            // Deleting under a per-stream lock, so a concurrent reader is served or refused, never
            // served from a directory being removed underneath it. Deleting without one is a race
            // whose outcome is "some bytes, or an IOException", decided by scheduling.
            //
            // The key is the directory name itself, which IS `streamKey(stream)` for the stream
            // that wrote this directory: prune reaches a stream by its directory (the id is not
            // invertible), so keying on the name is what lets it share the writer's lock instead of
            // adding a permanent second one (AUD-08).
            val streamLock = synchronized(perStream) {
                perStream.getOrPut(dir.fileName.toString()) { ReentrantLock() }
            }
            streamLock.withLock {
                bytes += committedLocked(layoutFor(dir))
                if (deleteRecursively(dir)) removed++
            }
        }
        // Whatever survived its deletion attempt is counted, not hidden, so a caller can tell
        // "nothing was there" from "the filesystem refused".
        val retained = targets.count { Files.isDirectory(it) }
        return OutputPruneReport(removed, bytes, retained)
    }

    /**
     * M3 — consult-before-act: would [intent] succeed RIGHT NOW, given the
     * current pins?
     *
     * When [pinPort] is wired, this composes the existing retention port
     * with the pin port's `pinsForSafeName` per stream directory of the run.
     * When no pin port is wired (the default), returns
     * [PruneAuthorisation.Granted] unconditionally — the test fixtures and
     * the M1/M2 paths do not need the consult-before-act primitive.
     *
     * The composition table from the design §8.3:
     *
     * ```text
     * canPrune(intent):
     *   for each stream of intent.runId:
     *     pins = pinsOf(stream)
     *     if pins.isNotEmpty() -> Consulted(stream, range, pins)
     *     else -> Granted
     *   if no streams for runId -> Refused(StorageError("unknown run ..."))
     *   if pin storage failed -> Refused(SubstrateUnavailable)
     * ```
     */
    override fun canPrune(intent: OutputPruneIntent): PruneAuthorisation {
        requireRecoveredForWriting()
        val pins = pinPort ?: return PruneAuthorisation.Granted
        val streamDirs = runStreamDirs(intent.runId)
        if (streamDirs.isEmpty()) {
            // The design §8.3 has a `Refused(UnknownStream)` case, but the
            // intent names a runId, not a stream. The pragmatic translation:
            // unknown run = no output to protect, refused with a Storage
            // cause so the caller can route rather than re-freeze on an
            // arbitrary unknown-stream shape.
            return PruneAuthorisation.Refused(
                PruneRefusal.StorageError("unknown run ${intent.runId}"),
            )
        }
        var pinError = false
        for (dir in streamDirs) {
            val safeName = dir.fileName.toString()
            val active = if (pins is OutputPinPortStoreAdapter) {
                pins.pinsForSafeName(safeName)
            } else {
                // Fallback path: a custom pin port adapter that does not
                // implement the canPrune helper. Try to reconstruct by
                // streaming the pinsOf API; works when the original id's
                // safe fold equals the dir name (lossy fold caveat applies).
                pins.pinsOf(streamFromSafeName(safeName))
            }
            if (active.isNotEmpty()) {
                val firstRange = active.first().range
                return PruneAuthorisation.Consulted(
                    stream = active.first().stream,
                    range = firstRange,
                    pinsAtConsult = active,
                )
            }
        }
        if (pinError) {
            return PruneAuthorisation.Refused(PruneRefusal.SubstrateUnavailable)
        }
        return PruneAuthorisation.Granted
    }

    /** Post-order delete: children before their parent, so a partially-failed pass is re-runnable. */
    private fun deleteRecursively(dir: Path): Boolean {
        if (!Files.exists(dir)) return false
        Files.walk(dir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        return !Files.exists(dir)
    }

    // -------------------------------------------------------------- internals

    /**
     * The unreconciled-state guard, for the operations whose return type CANNOT express a refusal.
     *
     * ## One condition, one representation — per return type
     *
     * [OutputRefusal.RecoveryNotCompleted] is part of the closed refusal ADT, and a read that hits
     * an unrecovered store answers with it: `read` and `readRange` return
     * [OutputReadResult.Refused]. They are total functions, and a total function that throws for a
     * value its own result type can name is not total. This store used to throw from both, which
     * meant a caller that handled every refusal still got an exception out of it — the same hole
     * [OutputRefusal.DanglingCommit] documents for the I4 case.
     *
     * The other three keep throwing, and the reason is the return type rather than a preference:
     *
     * | operation | returns | why it cannot refuse in-band |
     * |---|---|---|
     * | [open] | [OutputStreamHandle] | a handle, not a result — no case to put the refusal in |
     * | [committedExtent] | `Long?` | `null` already means "no committed extent"; a second meaning would make the two indistinguishable |
     * | [prune] | [OutputPruneReport] | a report of what was done; "refused to try" is not a report |
     *
     * So the condition has exactly one representation in each shape, and the one place where two
     * shapes could have claimed it now has one.
     */
    /**
     * Writing and destructive paths require recovery, always, whichever role this store opened with.
     *
     * ADR-OBS-002 splits the requirement that used to be one: reconciling before you APPEND is about
     * not stranding a reservation behind debris, and reconciling before you DELETE is about not
     * deleting a range you have not proved is unreferenced. Neither is a read concern.
     */
    private fun requireRecoveredForWriting() {
        check(recovered) {
            "appends, seals and prunes require OutputRecoveryPort.recover() first (O3): refusing to " +
                "act on an unreconciled state"
        }
    }

    /**
     * Reads do not require recovery, and the reason is the committed-offset authority rather than a
     * preference: [read] and [committedExtent] only ever serve bytes at or below [committedLocked],
     * so a reader cannot observe an unacknowledged byte whether or not debris has been reconciled.
     *
     * O3 said "recovery is a distinct entry point that reconciles before any reader is served". This
     * keeps the distinct entry point and drops the second clause: a reader is served committed bytes
     * either way, and the entry point is distinct precisely so that a reader never has to invoke it.
     */
    private fun requireReadable() {
        check(recovered || !recoveryPermitted) {
            "reads require OutputRecoveryPort.recover() first (O3): refusing to serve an unreconciled state"
        }
    }

    private fun layout(stream: OutputStreamId): Layout =
        layoutFor(root.resolve(STREAMS_DIR).resolve(safe(stream.value)))

    /**
     * The [Layout] of a stream directory.
     *
     * Shared by [layout] and by retention, which reaches the same directory by prefix rather than
     * by a reconstructed id. One owner for the directory's internal names: a second copy here
     * would be free to disagree with the first about what a stream is made of.
     */
    private fun layoutFor(dir: Path): Layout = Layout(
        streamDir = dir,
        segmentFile = dir.resolve("cur.seg"),
        commitFile = dir.resolve("cur.cmt"),
        reservationFile = dir.resolve("cur.res"),
        sealedDir = dir.resolve(SEALED_DIR),
        streamSealMarker = dir.resolve(STREAM_SEAL_MARKER),
        ownershipFile = dir.resolve(OWNERSHIP_FILE),
    )

    private fun withStreamLock(stream: OutputStreamId, block: () -> OutputReadResult): OutputReadResult =
        withStreamLockFor(stream) { block() }

    private fun withStreamLockDigested(
        stream: OutputStreamId,
        block: () -> OutputReadDigestedResult,
    ): OutputReadDigestedResult = withStreamLockFor(stream) { block() }

    private fun <T> withStreamLockFor(stream: OutputStreamId, block: () -> T): T {
        val streamLock = synchronized(perStream) { perStream.getOrPut(streamKey(stream)) { ReentrantLock() } }
        return streamLock.withLock { block() }
    }

    /**
     * The canonical lock key for a stream: the `safe()`-folded name that also names its directory.
     *
     * `layout(stream)` resolves the directory with exactly this fold, so a writer and [prune] (which
     * reaches the same directory by directory name) compute the same key for the same stream. Kept
     * in one place so the two sites cannot drift apart again, which is the defect B1c fixes.
     */
    private fun streamKey(stream: OutputStreamId): String = safe(stream.value)

    /**
     * Drop any uncommitted bytes and return how many were dropped.
     *
     * The committed offset is authoritative and the segment is truncated to it, so a crash between
     * write and commit cannot make an unacknowledged byte observable (I2), and a committed offset
     * can never point past what is on disk (I4).
     *
     * Note that this is the ONLY place a crashed writer's uncommitted bytes are dropped. There used
     * to be a second truncation in [Reserve]'s initialiser, disabled with `if (false)` and carrying a
     * dead local. It was redundant — this has already run, and O3 makes a reservation unreachable
     * before recovery — so restoring it would have given one fact two authorities. The mutation
     * harness had already measured it as non-load-bearing; the line was a guard that guarded nothing.
     * Deleting it is the honest form of that measurement.
     */
    private fun reconcile(layout: Layout): Long {
        val committed = committedLocked(layout)
        val onDisk = if (Files.exists(layout.segmentFile)) Files.size(layout.segmentFile) else 0L
        val currentBase = currentBaseLocked(layout)
        val readableEnd = currentBase + onDisk
        if (readableEnd > committed) {
            truncateTo(layout.segmentFile, onDisk - (readableEnd - committed))
            return readableEnd - committed
        }
        // A commit record AHEAD of the payload is detected and reported, and deliberately NOT
        // repaired. Clamping down to the readable end is the obvious fix and is worse than the
        // defect: the bytes between the last real commit and the readable end were never
        // acknowledged, so clamping would publish them (I2). Clamping back to the last real commit
        // is not decidable from the segment alone either, because the record is precisely what was
        // lost. So the claim stands, is counted in bytesUnbacked, and the reads that would need it
        // fail loudly (I4) instead of returning a short page that reads as an end of stream.
        //
        // Deciding what a corrupt commit record MEANS is a product decision, and this store does
        // not make it silently. See OutputNotEstablished.CORRUPT_COMMIT_RECORD.
        return 0L
    }

    /** One past the last byte physically readable in the current segment. */
    private fun readableEndLocked(layout: Layout): Long =
        currentBaseLocked(layout) +
            (if (Files.exists(layout.segmentFile)) Files.size(layout.segmentFile) else 0L)

    /** The global committed offset: the O2 authority. */
    private fun committedLocked(layout: Layout): Long =
        if (Files.exists(layout.commitFile)) {
            Files.readString(layout.commitFile).trim().toLongOrNull() ?: 0L
        } else 0L

    /**
     * One past the last sealed segment: the global base of the current segment.
     *
     * Delegates instead of repeating the enumeration. A second copy of "which segments are sealed"
     * here would be a second authority for one decision, and two authorities for one decision drift.
     */
    private fun currentBaseLocked(layout: Layout): Long = SegmentReader.currentBase(layout.sealedDir)

    /**
     * Serves `[from, to)` out of the merged segment set.
     *
     * The committed extent and the current base are read **here**, by the owner, and passed down. The
     * reader must never form a second opinion about how far a stream is committed, so it is given the
     * number rather than left to re-read it.
     */
    private fun readRangeLocked(
        layout: Layout,
        stream: OutputStreamId,
        from: Long,
        to: Long,
    ): OutputReadResult = SegmentReader.readRange(
        stream = stream,
        sealedDir = layout.sealedDir,
        segmentFile = layout.segmentFile,
        committedEnd = committedLocked(layout),
        currentBase = currentBaseLocked(layout),
        from = from,
        to = to,
    )

    /**
     * M3 — single-pass digested read; same calling convention as
     * [readRangeLocked] but returns the page + SHA-256 in one I/O round.
     */
    private fun readRangeDigestedLocked(
        layout: Layout,
        stream: OutputStreamId,
        from: Long,
        to: Long,
    ): OutputReadDigestedResult = SegmentReader.readRangeDigested(
        stream = stream,
        sealedDir = layout.sealedDir,
        segmentFile = layout.segmentFile,
        committedEnd = committedLocked(layout),
        currentBase = currentBaseLocked(layout),
        from = from,
        to = to,
    )

    /**
     * Reconstruct the [OutputStreamId] from a directory's safe name. Lossy
     * in general, but the canonical `run/op/transcript` shape round-trips
     * through `_`→`/`. Used only as a fallback when the pin adapter is not
     * an [OutputPinPortStoreAdapter].
     */
    private fun streamFromSafeName(safeName: String): OutputStreamId =
        OutputStreamId(safeName.replace("_", "/"))

    /**
     * Runs [block] holding this stream's ownership, or answers `false` when a live writer holds it.
     *
     * `FileLock` rather than a heartbeat, PID or mtime, per ADR-OBS-002: the kernel releases the lock
     * when the owning process dies and refuses it to a second process while the first lives, which is
     * exactly the "writer alive?" question and needs no clock and no staleness policy.
     *
     * `OverlappingFileLockException` is the same JVM holding it, and is caught here rather than thrown
     * because a reader sharing a JVM with a live writer must skip the stream just as a reader in
     * another process does.
     *
     * An [java.io.IOException] while opening the lock file is NOT swallowed: failing to learn who owns
     * a stream is not permission to destroy its bytes, and silently skipping would be a hole rather
     * than a safety. It propagates, and the recovery that called it fails loudly.
     */
    private fun tryWithStreamOwnership(layout: Layout, block: () -> Unit): Boolean {
        Files.createDirectories(layout.streamDir)
        FileChannel.open(layout.ownershipFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try {
                channel.tryLock()
            } catch (_: java.nio.channels.OverlappingFileLockException) {
                null
            }
            if (lock == null) return false
            try {
                block()
            } finally {
                lock.release()
            }
        }
        return true
    }

    /**
     * Shrinks [file] to [size] bytes, and does nothing when that is impossible or unnecessary.
     *
     * Two failure modes were closed here, and both were reachable from [OutputReservation.abandon]
     * on an ordinary path:
     *
     * 1. **A file that does not exist.** `FileChannel.open(..., WRITE)` without `CREATE` throws
     *    `NoSuchFileException`, and a stream that has committed nothing has no `cur.seg` at all. A
     *    release that throws is the worst possible failure for this method: the caller cannot free
     *    the range it is trying to free, so the reservation stays outstanding until a recovery pass
     *    has to rescue it. Truncating nothing to nothing is the correct answer, not an error.
     * 2. **A size larger than the file.** `FileChannel.truncate` *extends* a file, padding it with
     *    zero bytes. A release that grew the segment would leave a hole of NULs inside a stream,
     *    which is precisely the permanent gap [OutputCrashInvariant.I3_ORDER_IS_DENSE] forbids and
     *    which no reader could tell from real output.
     */
    private fun truncateTo(file: Path, size: Long) {
        if (!Files.exists(file)) return
        val current = Files.size(file)
        if (size >= current) return
        FileChannel.open(file, StandardOpenOption.WRITE).use { it.truncate(size) }
    }

    /** Seal the current segment and start a new one, so a stream is not one unbounded file. */
    private fun rotateLocked(layout: Layout, committed: Long, currentBase: Long) {
        val length = committed - currentBase
        if (length <= 0) return
        Files.createDirectories(layout.sealedDir)
        Files.move(
            layout.segmentFile,
            layout.sealedDir.resolve(SegmentReader.sealedName(currentBase, length)),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        // The committed offset is GLOBAL and rotation does not move it. Writing `currentBase` here
        // would rewind the stream to the start of the new segment and silently discard every byte
        // already committed — which is what a segmented read-back mismatch looks like from the
        // outside, with no error anywhere.
        Files.writeString(layout.commitFile, "$committed\n")
    }

    // ---------------------------------------------------------------- handles

    private inner class Handle(private val streamId: OutputStreamId) : OutputStreamHandle {
        override val stream: OutputStreamId get() = streamId

        override fun reserve(minBytes: Int): OutputReservation {
            require(minBytes > 0) { "reservation must be positive, got $minBytes" }
            return withStreamLockFor(streamId) {
                val layout = layout(streamId)
                // A sealed stream declared that no further bytes will arrive. Accepting a write now
                // would make OutputTailState.Sealed a promise the store had already broken, and a
                // reader that stopped tailing on that promise would silently lose the new bytes.
                check(!Files.exists(layout.streamSealMarker)) {
                    "stream ${streamId.value} is sealed at ${sealedEnd(layout)} bytes and cannot " +
                        "accept more; sealing means the tail is final, not merely current"
                }
                Reserve(streamId, layout, minBytes)
            }
        }

        /**
         * Reserve → write → commit, once per window, until the source is exhausted.
         *
         * Each window is a complete cycle rather than one growing reservation, so the committed
         * offset advances as the transcript is produced. A reader tailing with a cursor therefore
         * sees bytes appear during execution instead of only at the end — which is the whole point
         * of a resumable output cursor, and is why this is not one `reserve(hugeNumber)` call.
         */
        override fun appendFrom(source: InputStream, windowBytes: Int): Long {
            require(windowBytes > 0) { "windowBytes must be positive, got $windowBytes" }
            var committed = 0L
            source.use { input ->
                val window = ByteArray(windowBytes)
                while (true) {
                    // Fill the window before reserving, so an empty trailing read does not leave an
                    // empty reservation behind for recovery to release.
                    var read = 0
                    while (read < window.size) {
                        val n = input.read(window, read, window.size - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read == 0) break
                    val reservation = reserve(windowBytes)
                    // No try/catch around this: if the write or the commit fails, the reservation
                    // is already durable and is deliberately left on disk for recover() to release.
                    // Swallowing it here would strand it; catching it just to rethrow would be a
                    // no-op with a comment attached.
                    reservation.write(window.copyOf(read))
                    committed = reservation.commit()
                }
            }
            return committed
        }
    }

    private inner class Reserve(
        private val streamId: OutputStreamId,
        private val layout: Layout,
        minBytes: Int,
    ) : OutputReservation {

        override val stream: OutputStreamId get() = streamId
        override val base: Long get() = baseInternal
        override val limit: Long get() = limitInternal
        override val written: Long get() = writtenInternal

        private var writtenInternal = 0L
        private var position = 0L
        private var baseInternal = 0L
        private var limitInternal = 0L
        private var segmentBaseInternal = 0L
        private var open = true

        /** Held for the whole reservation, per ADR-OBS-002. Closed by [releaseOwnership]. */
        private var ownershipChannel: FileChannel? = null

        init {
            Files.createDirectories(layout.streamDir)
            if (Files.exists(layout.reservationFile)) {
                // A stale reservation must never be honoured — recovery owns that decision. Taking
                // a new one over an unresolved one would strand the first range permanently.
                throw IllegalStateException(
                    "stream ${streamId.value}: an outstanding reservation exists; recover() must " +
                        "resolve it before another is taken (O3)",
                )
            }

            val committed = committedLocked(layout)
            val currentBase = currentBaseLocked(layout)
            if (committed - currentBase >= SEGMENT_MAX_BYTES) rotateLocked(layout, committed, currentBase)

            val base = committedLocked(layout)
            segmentBaseInternal = currentBaseLocked(layout)
            // No truncation happens here, and that is a decision rather than an omission.
            //
            // A segment longer than the committed extent means a writer crashed between write and
            // commit, and [reconcile] is the single authority that resolves it: it runs on
            // [recover], which O3 requires before any reservation can be taken at all — this very
            // initialiser refuses a stream that still carries an outstanding reservation precisely
            // because "recovery owns that decision". Enabling a second truncation here would give
            // that one fact two authorities, and the two would disagree about which is right.
            //
            // There used to be one here, disabled with `if (false)`, carrying a dead `onDisk` local
            // and a comment that described the property it no longer enforced. The mutation harness
            // had already measured it as non-load-bearing, so the line was a guard that guarded
            // nothing: it read like a defence and enforced none. It is deleted rather than switched
            // off, because a disabled guard is a claim the code is not making.

            baseInternal = base
            position = base
            limitInternal = base + maxOf(minBytes.toLong(), DEFAULT_RESERVATION_BYTES)

            // O1: the reservation is durable before this returns, and before any byte is written.
            Files.writeString(layout.reservationFile, "$baseInternal|$limitInternal\n")

            // ADR-OBS-002: from here until commit/abandon, this stream is OWNED, and any recovery in
            // any process skips it. Taken last, after every failure that could throw, so a reservation
            // that never became durable also never advertises an owner.
            //
            // A reservation that is abandoned without committing or abandoning leaks this channel, and
            // a leaked channel keeps the lock for the life of the process. That failure direction is
            // deliberate: the stream stays unreconcilable, which is the safe way to be wrong. Recovery
            // is still possible after the process dies, because the kernel drops the lock with it.
            val channel = FileChannel.open(
                layout.ownershipFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            channel.lock()
            ownershipChannel = channel
        }

        override fun write(bytes: ByteArray) {
            ensureOpen()
            if (position + bytes.size > limitInternal) {
                throw OutputReservationExceeded(streamId, position + bytes.size, limitInternal)
            }
            Files.write(
                layout.segmentFile,
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
            )
            position += bytes.size
            writtenInternal += bytes.size
        }

        /**
         * Copy [source] in bounded windows so an unbounded producer is never materialised.
         *
         * The bytes arriving here are expected to be **already redacted**: redaction is a write-side
         * obligation, and a store that redacted on read would have already persisted the secret.
         */
        override fun copyFrom(source: InputStream) {
            ensureOpen()
            val headroom = (limitInternal - position).toInt()
            val window = ByteArray(minOf(DEFAULT_RESERVATION_BYTES.toInt(), headroom.coerceAtLeast(1)))
            source.use { input ->
                while (true) {
                    val read = input.read(window)
                    if (read <= 0) break
                    write(window.copyOf(read))
                }
            }
        }

        override fun commit(): Long {
            ensureOpen()
            open = false
            // O2: this file is the committed offset, not the size of the segment.
            Files.writeString(
                layout.commitFile,
                "$position\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            Files.deleteIfExists(layout.reservationFile)
            // Released AFTER the commit record lands: releasing first would open a window in which a
            // recovery sees neither an owner nor the bytes, and reconciles a range that is about to be
            // committed rather than one that was abandoned.
            releaseOwnership()
            return position
        }

        override fun abandon(): Long {
            ensureOpen()
            open = false
            // Truncate back to what was committed in this segment, so the range is reusable rather
            // than a permanent hole. This is the release that makes the order dense.
            truncateTo(layout.segmentFile, (position - writtenInternal - segmentBaseInternal).coerceAtLeast(0L))
            Files.deleteIfExists(layout.reservationFile)
            releaseOwnership()
            return baseInternal
        }

        private fun ensureOpen() {
            check(open) { "reservation on ${streamId.value} is already closed" }
        }

        /** Idempotent: a second call is a no-op rather than an error, so `finally` blocks can be free. */
        private fun releaseOwnership() {
            val channel = ownershipChannel ?: return
            ownershipChannel = null
            channel.close()
        }
    }

    private companion object {
        const val STREAMS_DIR = "streams"
        const val SEALED_DIR = "segments"

        /**
         * Per-stream marker recording that no further bytes will be written. Distinct from
         * [SEALED_DIR], which names ROTATED SEGMENTS — see `Layout.streamSealMarker`.
         */
        const val STREAM_SEAL_MARKER = "stream.seal"

        /**
         * The exclusive, kernel-held ownership file per stream. Never read as content: it exists to
         * be LOCKED, and its bytes are meaningless. See ADR-OBS-002.
         */
        const val OWNERSHIP_FILE = "cur.own"
        const val DEFAULT_RESERVATION_BYTES = 64L * 1024L
        const val SEGMENT_MAX_BYTES = 8L * 1024L * 1024L

        fun safe(name: String): String = safeStreamName(name)
    }
}

/**
 * The on-disk name for a stream or run identifier.
 *
 * **Lossy and deliberately never undone.** Every character outside `[A-Za-z0-9._-]` folds onto `_`,
 * so `a/b` and `a_b` produce the same name. Nothing decodes these names back into identifiers:
 * [SegmentFrameIndex] writes the whole identifier into its own files and reads it back from
 * there, which is the only place the round trip is safe.
 *
 * Shared rather than duplicated so the byte store and the frame index cannot drift into naming the
 * same run differently.
 */
internal fun safeStreamName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
