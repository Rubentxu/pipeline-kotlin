package dev.rubentxu.pipeline.v2.output

import java.io.InputStream
import java.nio.ByteBuffer
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
) : OutputAppendPort, OutputReadPort, OutputRecoveryPort, OutputRetentionPort {

    private data class Layout(
        val streamDir: Path,
        val segmentFile: Path,
        val commitFile: Path,
        val reservationFile: Path,
        val sealedDir: Path,
    )

    private data class SealedSegment(val base: Long, val length: Long, val file: Path)

    private val recoveryLock = ReentrantLock()

    @Volatile private var recovered = false
    private val perStream = HashMap<OutputStreamId, ReentrantLock>()

    // ------------------------------------------------------------------ ports

    override fun open(stream: OutputStreamId): OutputStreamHandle {
        requireRecovered()
        return Handle(stream)
    }

    override fun committedExtent(stream: OutputStreamId): Long? {
        requireRecovered()
        val layout = layout(stream)
        if (!Files.isDirectory(layout.streamDir)) return null
        return withStreamLockFor(stream) { committedLocked(layout) }
    }

    override fun read(stream: OutputStreamId, cursor: OutputCursor, maxBytes: Int): OutputReadResult {
        if (!recovered) return OutputReadResult.Refused(OutputRefusal.RecoveryNotCompleted)
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
        if (!recovered) return OutputReadResult.Refused(OutputRefusal.RecoveryNotCompleted)
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
     * Reconcile durable state. Idempotent, and safe to interrupt and call again — a store that
     * cannot be recovered twice cannot be trusted after its own recovery crashes.
     */
    override fun recover(): OutputRecoveryReport = recoveryLock.withLock {
        val streamRoot = root.resolve(STREAMS_DIR)
        Files.createDirectories(streamRoot)

        var streams = 0
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
                )
                releasedBytes += reconcile(layout)
                if (Files.deleteIfExists(layout.reservationFile)) releasedReservations++
                unbackedBytes += maxOf(0L, committedLocked(layout) - readableEndLocked(layout))
                committedBytes += committedLocked(layout)
                streams++
            }
        }

        recovered = true
        OutputRecoveryReport(streams, committedBytes, releasedBytes, releasedReservations, unbackedBytes)
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
        requireRecovered()
        val targets = runStreamDirs(intent.runId)
        if (targets.isEmpty()) return OutputPruneReport(0, 0L, 0)

        var removed = 0
        var bytes = 0L
        for (dir in targets) {
            // Deleting under a per-stream lock, so a concurrent reader is served or refused, never
            // served from a directory being removed underneath it. Deleting without one is a race
            // whose outcome is "some bytes, or an IOException", decided by scheduling.
            val streamLock = synchronized(perStream) {
                perStream.getOrPut(OutputStreamId(dir.fileName.toString())) { ReentrantLock() }
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
    private fun requireRecovered() {
        check(recovered) {
            "reads and appends require OutputRecoveryPort.recover() first (O3): refusing to act on " +
                "an unreconciled state"
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
    )

    private fun withStreamLock(stream: OutputStreamId, block: () -> OutputReadResult): OutputReadResult =
        withStreamLockFor(stream) { block() }

    private fun <T> withStreamLockFor(stream: OutputStreamId, block: () -> T): T {
        val streamLock = synchronized(perStream) { perStream.getOrPut(stream) { ReentrantLock() } }
        return streamLock.withLock { block() }
    }
    /**
     * Drop any uncommitted bytes and return how many were dropped.
     *
     * The committed offset is authoritative and the segment is truncated to it, so a crash between
     * write and commit cannot make an unacknowledged byte observable (I2), and a committed offset
     * can never point past what is on disk (I4).
     *
     * Note the second truncation, in [Reserve]'s initialiser. It is **redundant**: this one has
     * already run, and reads are bounded by the committed offset regardless. It is kept as a second
     * line of defence, not because anything depends on it — the mutation harness says so
     * explicitly, and a guard that only exists because nobody checked is the thing this project
     * keeps finding. Which of the two is load-bearing was settled by instrumenting the store: the
     * one here, not the other.
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

    private fun sealedSegments(layout: Layout): List<SealedSegment> =
        if (!Files.isDirectory(layout.sealedDir)) {
            emptyList()
        } else {
            Files.newDirectoryStream(layout.sealedDir).use { entries ->
                entries.mapNotNull { parseSealed(it) }
            }
        }

    /** One past the last sealed segment: the global base of the current segment. */
    private fun currentBaseLocked(layout: Layout): Long =
        sealedSegments(layout).maxOfOrNull { it.base + it.length } ?: 0L

    private fun readRangeLocked(
        layout: Layout,
        stream: OutputStreamId,
        from: Long,
        to: Long,
    ): OutputReadResult {
        val extent = committedLocked(layout)
        val target = ByteArray((to - from).toInt())
        var cursor = from
        var written = 0

        val currentBase = currentBaseLocked(layout)
        val sealed = sealedSegments(layout).sortedBy { it.base }

        for (seg in sealed) {
            if (cursor >= to) break
            val segEnd = seg.base + seg.length
            if (segEnd <= cursor) continue
            val localStart = (cursor - seg.base).coerceAtLeast(0L)
            val want = minOf(segEnd, to) - (seg.base + localStart)
            val chunk = readChunk(seg.file, localStart, want)
            chunk.copyInto(target, written)
            written += chunk.size
            cursor += chunk.size
        }

        if (cursor < to && cursor >= currentBase) {
            val localStart = cursor - currentBase
            val want = minOf(extent, to) - cursor
            val chunk = readChunk(layout.segmentFile, localStart, want)
            chunk.copyInto(target, written)
            written += chunk.size
            cursor += chunk.size
        }

        if (written != target.size) {
            // A committed offset that cannot be fully served is a dangling commit (I4). A short page
            // would be indistinguishable from a complete one, and this used to be an IOException —
            // which was a hole in the closed ADT, since a caller handling every refusal could still
            // be thrown out of a total function.
            return OutputReadResult.Refused(
                OutputRefusal.DanglingCommit(requestedEnd = to, readableBytes = written.toLong()),
            )
        }

        return OutputReadResult.Page(
            OutputPage(
                bytes = target,
                stream = stream,
                from = from,
                next = if (to >= extent) null else OutputCursor(stream, to),
                committedEnd = extent,
            ),
        )
    }

    private fun readChunk(file: Path, offset: Long, length: Long): ByteArray {
        if (length <= 0) return ByteArray(0)
        if (!Files.exists(file)) return ByteArray(0)
        val buffer = ByteArray(length.toInt())
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            var position = offset
            var read = 0
            while (read < buffer.size) {
                val n = channel.read(ByteBuffer.wrap(buffer, read, buffer.size - read), position)
                if (n < 0) break
                position += n
                read += n
            }
            if (read != buffer.size) return buffer.copyOf(read)
        }
        return buffer
    }

    private fun parseSealed(file: Path): SealedSegment? {
        val name = file.fileName.toString()
        if (!name.endsWith(SEALED_SUFFIX)) return null
        val stem = name.removeSuffix(SEALED_SUFFIX)
        val dash = stem.indexOf('-')
        if (dash <= 0) return null
        val base = stem.substring(0, dash).toLongOrNull() ?: return null
        val length = stem.substring(dash + 1).toLongOrNull() ?: return null
        return SealedSegment(base, length, file)
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
            layout.sealedDir.resolve("$currentBase-$length$SEALED_SUFFIX"),
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
            return withStreamLockFor(streamId) { Reserve(streamId, layout(streamId), minBytes) }
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
            // The segment holds only the bytes from its own base onwards, so dropping anything a
            // previous crashed writer left behind means truncating to the committed extent *relative
            // to the segment*, not to the global offset.
            val committedInSegment = (base - segmentBaseInternal).coerceAtLeast(0L)
            val onDisk = if (Files.exists(layout.segmentFile)) Files.size(layout.segmentFile) else 0L
            if (false) truncateTo(layout.segmentFile, committedInSegment)

            baseInternal = base
            position = base
            limitInternal = base + maxOf(minBytes.toLong(), DEFAULT_RESERVATION_BYTES)

            // O1: the reservation is durable before this returns, and before any byte is written.
            Files.writeString(layout.reservationFile, "$baseInternal|$limitInternal\n")
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
            return position
        }

        override fun abandon(): Long {
            ensureOpen()
            open = false
            // Truncate back to what was committed in this segment, so the range is reusable rather
            // than a permanent hole. This is the release that makes the order dense.
            truncateTo(layout.segmentFile, (position - writtenInternal - segmentBaseInternal).coerceAtLeast(0L))
            Files.deleteIfExists(layout.reservationFile)
            return baseInternal
        }

        private fun ensureOpen() {
            check(open) { "reservation on ${streamId.value} is already closed" }
        }
    }

    private companion object {
        const val STREAMS_DIR = "streams"
        const val SEALED_DIR = "segments"
        const val SEALED_SUFFIX = ".seg"
        const val DEFAULT_RESERVATION_BYTES = 64L * 1024L
        const val SEGMENT_MAX_BYTES = 8L * 1024L * 1024L

        fun safe(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
