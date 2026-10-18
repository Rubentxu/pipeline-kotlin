package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The durable frame index: one append-only log of frames per run, beside the bytes it describes.
 *
 * ## Two files per run, and why there are two
 *
 * ```text
 * output-plane/frames/<run>.streams   which streams this run declared, one per line
 * output-plane/frames/<run>.frames    the frames themselves, in publication order
 * ```
 *
 * The streams file exists because recovery has to answer "is any stream holding committed bytes
 * that no frame mentions?", and the **byte store cannot answer that on its own**. It stores a
 * directory named by `safe(streamId)`, and `safe` folds `/` onto `_`, which is not invertible — its
 * own KDoc says so and refuses to parse those names for exactly that reason. So this index records
 * stream identity itself, once per stream, **before the first byte is written**.
 *
 * That declaration is what makes the contract's second safety property hold. A crash between a byte
 * commit and its frame leaves bytes that are durable, readable and unattributed; because the
 * stream was declared up front, [recoverUnframedBytes] knows to look for it and closes the gap.
 * Without the declaration, a crash between a stream's **first** byte commit and its **first** frame
 * would leave bytes this index has never heard of — exactly the "committed but irrecoverably
 * outside the index" case the contract forbids, and exactly the one no amount of later recovery
 * could reconstruct, because those bytes have no durable position in the interleaving.
 *
 * Declaring costs one append per stream, not per chunk, so it does not scale with output volume.
 *
 * ## Listing runs is safe here, and would not be in the store
 *
 * The directory names under [FRAMES_DIR] are only used to find *files to open*. Every stream id
 * this index uses is read back out of the file's own content, where it was written whole and
 * percent-escaped. Nothing is recovered by decoding a directory name, which is the operation the
 * byte store rightly refuses to perform.
 *
 * ## The ordinal is assigned here, not by the caller
 *
 * Two channel pumps append concurrently, and an ordinal assigned by the caller would race. The lock
 * is held across both the ordinal assignment and the durable write, so the order of lines in the
 * file is the order of the frames and the ordinals are monotonic by construction rather than by
 * hope.
 *
 * ## What a half-written line means
 *
 * A crash can leave the final line torn. A torn line is **not** a frame: it has no ordinal, and its
 * range may name bytes that were never committed. Reading stops at the first malformed line and
 * truncates the file there, which is the same rule the byte store applies to a dangling commit.
 * Skipping the bad line and reading past it is precisely how a truncated index starts inventing
 * frames, so the read refuses instead.
 */
class SegmentFrameIndex(
    private val store: SegmentOutputStore,
    private val root: Path,
) : OutputFrameIndex {

    private val lock = ReentrantLock()

    /** Declared streams per run, as an in-memory cache over the `.streams` file. */
    private val declaredByRun = HashMap<String, MutableSet<OutputStreamId>>()

    /** Highest ordinal written per run, so the counter survives a reopen without re-reading. */
    private val lastOrdinalByRun = HashMap<String, Long>()

    /** Runs whose frames file has already been folded into [lastOrdinalByRun]. */
    private val ordinalsLoaded = HashSet<String>()

    /**
     * Per-run sparse offset index cache, paired with the offset-index file's mtime the entries
     * were parsed against. R3 / OBS-3 step 2 (ADR-OBS-004): one entry every [INDEX_STRIDE] frames
     * records the byte offset of that frame's line, so [framesOfRun] can binary-search the entry,
     * seek, and read `O(L)` frames forward instead of `O(file bytes)`.
     *
     * The cache is keyed on the index file's mtime, not the frames file's mtime. Appending a
     * frame that does NOT cross [INDEX_STRIDE] advances the frames file's mtime but leaves the
     * index file untouched — the entries are still valid pointers, the cache must not reload.
     */
    private data class CachedOffsetIndex(val entries: List<IndexEntry>, val indexMtime: FileTime?)

    private data class IndexEntry(val ordinal: Long, val byteOffset: Long)

    private val offsetIndexByRun = HashMap<String, CachedOffsetIndex>()

    override fun declareStream(stream: OutputStreamId, channel: OutputChannel) {
        // Refuses an id this producer could not have written, rather than recording an attribution
        // nobody can later re-derive.
        val address = OutputStreamAddress.parse(stream)
        require(address != null && address.channel == channel) {
            "stream $stream does not name channel $channel; declareStream requires the " +
                "{runId}/{operationId}/{channel} shape this producer writes"
        }
        val runId = address.runId
        lock.withLock {
            loadDeclared(runId)
            if (declaredByRun.getValue(runId).add(stream)) {
                appendLine(streamsFile(runId), "${stream.value}\t${channel.token}")
            }
        }
    }

    override fun append(
        stream: OutputStreamId,
        channel: OutputChannel,
        from: Long,
        to: Long,
    ): OutputFrame {
        val address = OutputStreamAddress.parse(stream)
        require(address != null && address.channel == channel) {
            "stream $stream does not name channel $channel"
        }
        require(to > from) { "a frame is never empty: to ($to) must exceed from ($from)" }

        val runId = address.runId
        return withOrdinalAuthority(runId) {
            sealTornTail(runId)
            val ordinal = nextOrdinal(runId)
            val frame = OutputFrame(ordinal, stream, channel, from, to)
            val encoded = encode(frame)
            appendLine(framesFile(runId), encoded)
            lastOrdinalByRun[runId] = ordinal
            maybeAppendIndexEntry(runId, ordinal, encoded)
            frame
        }
    }

    override fun framesOfRun(runId: String, afterOrdinal: Long, limit: Int): List<OutputFrame> {
        require(limit > 0) { "limit must be positive, got $limit" }
        val framesPath = framesFile(runId)
        if (!Files.isRegularFile(framesPath)) return emptyList()

        // Load the ordinal counter and the per-run offset index under the JVM lock so the
        // index lookup cannot observe a frames file that has just been extended by another
        // thread without also seeing the new mtime that would trigger a rebuild.
        val entries = lock.withLock {
            loadOrdinals(runId)
            loadOffsetIndex(runId)
        }

        val fileSize = Files.size(framesPath)
        val startByteOffset = binarySeekIndex(entries, afterOrdinal, fileSize)

        val frames = ArrayList<OutputFrame>()
        FileChannel.open(framesPath, StandardOpenOption.READ).use { channel ->
            channel.position(startByteOffset)
            BufferedReader(
                InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8),
            ).use { reader ->
                while (frames.size < limit) {
                    val line = reader.readLine() ?: break
                    val decoded = decode(line) ?: break
                    if (decoded.ordinal > afterOrdinal) frames.add(decoded)
                }
            }
        }
        return frames
    }

    override fun lastOrdinal(runId: String): Long? = lock.withLock {
        if (!Files.isRegularFile(framesFile(runId))) return@withLock null
        loadOrdinals(runId)
        lastOrdinalByRun[runId]
    }

    /**
     * Sorted, so a caller that lists streams gets a stable order without asking for one.
     *
     * [loadDeclared] is what makes this survive a reopen: the in-memory cache is a cache over a
     * durable file, and a consumer in a fresh JVM has to get the same answer as one that stayed up.
     */
    override fun streamsOfRun(runId: String): List<OutputStreamId> = lock.withLock {
        if (!declaredByRun.containsKey(runId)) loadDeclared(runId)
        declaredByRun[runId]?.sortedBy { it.value } ?: emptyList()
    }

    /**
     * Which run owns each declared stream, keyed by the **directory name** that holds its bytes.
     *
     * The key is the folded name rather than the stream id because the only question this answers is
     * "when [SegmentOutputStore] is standing in a directory, whose bytes are these" — and prune
     * reaches a stream by its directory, never by its id (`safe` folds `/` onto `_`, so the id is
     * not recoverable from the name). A consumer that wants ids asks [streamsOfRun].
     *
     * A stream absent from this map is not "owned by no run": it is **undeclared**, which is a
     * different fact. Streams written before the channels were separated carry no channel, so
     * [declareStream] refuses them by design and they never appear here, yet they hold real bytes.
     * The distinction is what lets prune tell "this directory is another run's" (an answer, from
     * this map) apart from "nothing proves who wrote this" (no answer), which are opposite
     * decisions.
     */
    fun declaredDirOwners(): Map<String, String> = lock.withLock {
        val owners = HashMap<String, String>()
        for (runId in runsWithDeclaredStreams()) {
            if (!declaredByRun.containsKey(runId)) loadDeclared(runId)
            declaredByRun[runId].orEmpty().forEach { stream ->
                owners.putIfAbsent(safeStreamName(stream.value), runId)
            }
        }
        owners
    }

    /**
     * Closes every gap between a stream's committed extent and its last indexed frame.
     *
     * Idempotent: with no newly committed bytes there is nothing to close, so recovery can run on
     * every open without growing the log.
     *
     * The recovered frame's ordinal is **later** than any the interrupted write would have had,
     * because that ordinal was never assigned. That is the honest placement: those bytes go after
     * everything published before the crash, because nothing durable recorded where they belonged.
     */
    override fun recoverUnframedBytes(): List<OutputFrame> = lock.withLock {
        val closed = ArrayList<OutputFrame>()

        for (runId in runsWithDeclaredStreams()) {
            loadDeclared(runId)
            loadOrdinals(runId)

            withOrdinalAuthority(runId) {
            val lastIndexedEnd = HashMap<OutputStreamId, Long>()
            for (frame in readFrames(runId, sealTornTail = false)) {
                val previous = lastIndexedEnd[frame.stream] ?: Long.MIN_VALUE
                if (frame.to > previous) lastIndexedEnd[frame.stream] = frame.to
            }

            for (stream in declaredByRun.getValue(runId)) {
                val committed = store.committedExtent(stream) ?: continue
                val indexed = lastIndexedEnd[stream] ?: 0L
                if (committed <= indexed) continue

                val channel = OutputStreamAddress.parse(stream)?.channel ?: continue
                val ordinal = nextOrdinal(runId)
                val frame = OutputFrame(ordinal, stream, channel, indexed, committed)
                appendLine(framesFile(runId), encode(frame))
                // Without this the counter keeps handing out `ordinal` again to the next append,
                // and two frames sharing an ordinal is worse than a gap: a consumer resuming by
                // ordinal would skip a frame entirely rather than merely re-read one.
                lastOrdinalByRun[runId] = ordinal
                lastIndexedEnd[stream] = committed
                closed.add(frame)
            }
            }
        }
        closed
    }

    // ------------------------------------------------------------------ internals

    private fun streamsFile(runId: String): Path =
        root.resolve(FRAMES_DIR).resolve("${safeStreamName(runId)}$STREAMS_SUFFIX")

    private fun framesFile(runId: String): Path =
        root.resolve(FRAMES_DIR).resolve("${safeStreamName(runId)}$FRAMES_SUFFIX")

    /**
     * The sparse per-run offset index that turns [framesOfRun] from `O(N)` into `O(L)` for
     * limit-bound reads. R3 / OBS-3 step 2 (ADR-OBS-004). One entry every [INDEX_STRIDE] frames.
     *
     * Lives beside the frames file in the same directory so a single directory walk (`Files.list`)
     * enumerates both. The file name is `<runId>.frames.offset-index`, derived by appending
     * [OFFSET_INDEX_SUFFIX] to the frames-file name; the offset index is a derived structure,
     * never an independent authority.
     */
    private fun offsetIndexFile(runId: String): Path =
        root.resolve(FRAMES_DIR).resolve("${safeStreamName(runId)}$FRAMES_SUFFIX$OFFSET_INDEX_SUFFIX")

    /**
     * The per-run ordinal authority: an OS lock file beside the frames it protects.
     *
     * Allocating an ordinal is a read-modify-write of "read the highest ordinal in the log, add one,
     * append that line". Inside one JVM [lock] makes that atomic. Between processes it was atomic
     * over nothing, and the collision was measured rather than assumed:
     * `SegmentFrameIndexCrossProcessOrdinalTest` released six writers together from a filesystem
     * barrier and got 4 distinct ordinals out of 6 writes, three runs in a row.
     *
     * A duplicate ordinal is byte loss, not untidiness: a reader paging with a limit stops after the
     * first of two frames sharing one, and the other is committed output no console can show again.
     */
    private fun ordinalAuthorityFile(runId: String): Path =
        root.resolve(FRAMES_DIR).resolve("${safeStreamName(runId)}$AUTHORITY_SUFFIX")

    /**
     * Runs [block] as the sole ordinal allocator for [runId], across every process.
     *
     * ## Why a lock, when re-reading already looked sufficient
     *
     * It is not obvious and it was believed to be broken first. [sealTornTail] re-reads the whole
     * durable log before every allocation and folds it into [lastOrdinalByRun], so the map is a
     * cache over the file rather than the authority — which is exactly what makes the SEQUENTIAL
     * cross-process case safe, and why `XPROC-1` is green. Re-reading cannot help when two processes
     * read before either writes. Only mutual exclusion closes that window, which is what `XPROC-2`
     * measures and this closes.
     *
     * ## Lock order, and why this order
     *
     * [lock] first, OS lock second. The [lock] serialises threads inside this JVM, so only one thread
     * at a time reaches `channel.lock()` and it cannot see an `OverlappingFileLockException` from a
     * sibling thread. [SegmentOutputStore] handles that same-JVM case by SKIPPING the stream, and
     * skipping is not available here: this is a write that has already committed bytes, so declining
     * would turn a moment of contention into lost output.
     *
     * Blocking rather than skipping for the same reason. The wait is one file read plus one append.
     *
     * `FileLock` over a heartbeat, PID or mtime, per ADR-OBS-002: the kernel releases it the instant
     * the owning process dies and refuses it to a second process while the first lives, which answers
     * "is a writer alive?" with no clock and no staleness policy to get wrong.
     */
    private fun <T> withOrdinalAuthority(runId: String, block: () -> T): T = lock.withLock {
        val file = ordinalAuthorityFile(runId)
        Files.createDirectories(file.parent)
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                block()
            }
        }
    }

    /** Runs that have a `.streams` file, taken from the file contents rather than their names. */
    private fun runsWithDeclaredStreams(): List<String> {
        val dir = root.resolve(FRAMES_DIR)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.newDirectoryStream(dir).use { entries ->
            entries.asSequence()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(STREAMS_SUFFIX) }
                .flatMap { file ->
                    readLines(file).asSequence().mapNotNull { declaredRunId(it) }
                }
                .distinct()
                .toList()
        }
    }

    /** The run an already-written declaration line belongs to, read back from its own content. */
    private fun declaredRunId(line: String): String? {
        val parts = line.split('\t')
        if (parts.size != 2) return null
        return OutputStreamAddress.parse(OutputStreamId(parts[0]))?.runId
    }

    private fun loadDeclared(runId: String) {
        if (declaredByRun.containsKey(runId)) return
        val file = streamsFile(runId)
        val declared = if (Files.isRegularFile(file)) {
            readLines(file).mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 2) return@mapNotNull null
                if (OutputChannel.fromToken(parts[1]) == null) return@mapNotNull null
                OutputStreamId(parts[0])
            }.toMutableSet()
        } else {
            mutableSetOf()
        }
        declaredByRun[runId] = declared
    }

    private fun loadOrdinals(runId: String) {
        if (!ordinalsLoaded.add(runId)) return
        val highest = readFrames(runId, sealTornTail = false).maxOfOrNull { it.ordinal }
        if (highest != null) lastOrdinalByRun[runId] = highest
    }

    /** Caller holds the lock. */
    private fun nextOrdinal(runId: String): Long {
        loadOrdinals(runId)
        return (lastOrdinalByRun[runId] ?: -1L) + 1L
    }

    /**
     * Removes a torn final line before anything is appended behind it.
     *
     * Without this, appending after a crash writes a valid frame **after** the fragment. Every later
     * read stops at the fragment — by design, because it cannot be trusted — so the new frame would
     * be unreachable forever, and the index would be permanently stuck at whatever preceded the
     * crash. Repairing the tail is therefore an obligation of the *write* path, not only of
     * recovery: any append has to make the file consistent again before making it longer.
     *
     * ## R3 / OBS-3 — O(1) tail read
     *
     * The previous implementation called [readFrames] which re-reads the **whole** log; for a
     * 1 GiB / 10⁷-frame index that made every append O(N) and the cumulative cost O(N²). This
     * version reads at most [TAIL_READ_BYTES] from the END of the file — enough for one frame
     * line (~100–200 bytes) plus a comfortable margin — and only inspects the LAST line.
     *
     * The contract that made the previous whole-read version safe is preserved:
     *
     * - The file is at most one torn line long at the time of an append, because a torn line is
     *   from a partial write and the writer process is gone; a second writer cannot start before
     *   the first one's reservation is reconciled (O3), and reconciliation seals the tail. So
     *   the only torn line that can exist is the LAST one, and tail-only repair is sufficient.
     * - If the tail is small enough that [TAIL_READ_BYTES] covers the whole file, the logic
     *   collapses to the original whole-file behaviour; correctness is unchanged.
     * - If the tail starts mid-line (because [readFrom] > 0), that mid-line is ignored — only the
     *   line after the LAST newline in the tail is the candidate last frame. A torn earlier
     *   fragment cannot escape, because the [readFrames] contract stops at the first torn line
     *   and recovery runs the same code path on every open.
     * - If the last line parses, the in-memory ordinal counter is updated from it; the next call
     *   to [loadOrdinals] short-circuits because [ordinalsLoaded] already has this run.
     *
     * Returns the whole frames only when the file fits in the tail; otherwise it returns the
     * frames actually inspected (zero or one). The only caller is [append], which discards the
     * return value; the only behaviour the caller relies on is the side effects (truncate +
     * counter update + ordinalsLoaded).
     */
    private fun sealTornTail(runId: String): List<OutputFrame> {
        val file = framesFile(runId)
        if (!Files.isRegularFile(file)) return emptyList()
        val size = Files.size(file)
        if (size == 0L) return emptyList()

        // Small file: collapse to the previous whole-read behaviour so the contract is preserved
        // bit-for-bit, and the cached [lastOrdinalByRun] is correct even for runs that have never
        // been loaded into memory.
        if (size <= TAIL_READ_BYTES) {
            val frames = readFrames(runId, sealTornTail = true)
            val highest = frames.maxOfOrNull { it.ordinal }
            if (highest != null) {
                lastOrdinalByRun[runId] = maxOf(lastOrdinalByRun[runId] ?: -1L, highest)
            }
            ordinalsLoaded.add(runId)
            return frames
        }

        // Large file: tail-only read. The window is bounded so the cost is O(1) regardless of
        // the file's total size, and the only line we parse is the LAST one.
        val readFrom = size - TAIL_READ_BYTES
        val tail = ByteArray(TAIL_READ_BYTES.toInt())
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            channel.position(readFrom)
            val buf = java.nio.ByteBuffer.wrap(tail)
            while (buf.hasRemaining()) {
                if (channel.read(buf) < 0) break
            }
        }
        val tailText = String(tail, StandardCharsets.UTF_8)
        val lastNewline = tailText.lastIndexOf('\n')
        val lastLine = if (lastNewline < 0) tailText else tailText.substring(lastNewline + 1)

        if (lastLine.isEmpty()) {
            // File ends with a clean newline; nothing to repair. The counter was already correct
            // from the previous append, and [ordinalsLoaded] is set so the next [loadOrdinals]
            // short-circuits.
            ordinalsLoaded.add(runId)
            return emptyList()
        }

        val decoded = decode(lastLine)
        return if (decoded == null) {
            // The last line is torn. Truncate it away and leave the counter alone — the
            // counter already points at the last *whole* line, set by the append that wrote it.
            val truncateTo = readFrom + lastNewline + 1L
            truncate(file, truncateTo)
            ordinalsLoaded.add(runId)
            emptyList()
        } else {
            // The last line is whole. Update the counter from it; this is the authoritative
            // last ordinal, and any previous in-memory value that disagrees is stale.
            val current = lastOrdinalByRun[runId] ?: -1L
            if (decoded.ordinal > current) lastOrdinalByRun[runId] = decoded.ordinal
            ordinalsLoaded.add(runId)
            listOf(decoded)
        }
    }

    /**
     * Reads whole frames from a run's log, stopping at the first malformed line.
     *
     * With [sealTornTail] the file is truncated back to the last whole frame, so the next append
     * starts from a consistent point instead of appending behind a torn record. Reads that are
     * only asking a question leave the file alone, so a concurrent reader cannot destroy state.
     */
    private fun readFrames(runId: String, sealTornTail: Boolean): List<OutputFrame> {
        val file = framesFile(runId)
        if (!Files.isRegularFile(file)) return emptyList()

        val frames = ArrayList<OutputFrame>()
        var lastGoodEnd = 0L
        var torn = false
        BufferedReader(Files.newBufferedReader(file, StandardCharsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val decoded = decode(line)
                if (decoded == null) {
                    torn = true
                    break
                }
                frames.add(decoded)
                lastGoodEnd += line.toByteArray(StandardCharsets.UTF_8).size + 1L
            }
        }
        if (torn && sealTornTail) truncate(file, lastGoodEnd)
        return frames
    }

    private fun readLines(file: Path): List<String> =
        Files.newBufferedReader(file, StandardCharsets.UTF_8).use { it.readLines() }

    private fun appendLine(file: Path, line: String) {
        Files.createDirectories(file.parent)
        FileChannel.open(
            file,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        ).use { channel ->
            channel.write(
                java.nio.ByteBuffer.wrap((line + "\n").toByteArray(StandardCharsets.UTF_8)),
            )
            // A frame that is only in the page cache is not durable, and "committed" would then be
            // a word the crash gets to contradict. One force per frame is the price of this index
            // being an authority at all; its cost per unit of output is measured in OBS-F.
            channel.force(false)
        }
    }

    private fun truncate(file: Path, size: Long) {
        FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
            channel.truncate(size)
            channel.force(true)
        }
    }

    // ------------------------------------------------------------------ R3 / OBS-3 step 2 — per-run offset index
    //
    // ADR-OBS-004. The whole reason this section exists: `framesOfRun(K, L)` used to re-read the
    // entire frames log via `readFrames` and filter, which made the limit-bound Follow path cost
    // `O(file bytes)` regardless of `L`. The fix is a sparse per-run index of `(ordinal,
    // byteOffset)` pairs — one entry every [INDEX_STRIDE] frames — that lets the reader binary-
    // search for the floor(afterOrdinal) entry, `seek(byteOffset)`, and read `O(L)` frames forward.
    //
    // The contract this section preserves:
    //
    //   * The index is a derived structure under [withOrdinalAuthority], never a parallel authority.
    //     Two writers cannot interleave a frames-line write with an index-entry write because
    //     both happen inside the same OS lock window.
    //   * `recoverUnframedBytes` and `loadOrdinals` keep using [readFrames]. Recovery must close
    //     gaps the index cannot describe (the last `INDEX_STRIDE - 1` frames), so it iterates the
    //     frames file directly and never consults the offset index.
    //   * A torn-tail truncate shrinks the frames file but leaves the index alone: an entry can
    //     only point at a frame that landed whole, and a torn line is at most one frame long.
    //   * The reader treats `byteOffset >= fileSize` as "missing" and walks back to the previous
    //     entry. Same fallback that `sealTornTail`'s discard rule already provides.

    /**
     * Records an offset-index entry for [justAppendedOrdinal] if and only if it is the
     * [INDEX_STRIDE]-th frame since the last entry, i.e. `(ordinal + 1) % INDEX_STRIDE == 0`.
     *
     * Called from [append] inside the [withOrdinalAuthority] block, after the frames-line
     * `appendLine` has returned, so the entry's `byteOffset` is computed against an on-disk file
     * the OS lock has serialised against any other writer.
     *
     * The index gets one `fsync` per entry, which is the same per-line `force(false)` discipline
     * the frames file already pays. One entry every 1000 frames is `0.1%` extra `fsync`s — in the
     * noise of the existing per-frame cost.
     */
    private fun maybeAppendIndexEntry(runId: String, justAppendedOrdinal: Long, justAppendedEncoded: String) {
        if ((justAppendedOrdinal + 1) % INDEX_STRIDE != 0L) return
        val framesPath = framesFile(runId)
        val lineByteLength = justAppendedEncoded.toByteArray(StandardCharsets.UTF_8).size
        // Frames file format is `line\n` per record; the byte offset is the position of the FIRST
        // byte of the just-appended line, so `filesize - lineBytes - 1` (one trailing newline).
        val byteOffset = Files.size(framesPath) - lineByteLength - 1L
        appendLine(offsetIndexFile(runId), "$justAppendedOrdinal\t$byteOffset")
    }

    /**
     * Returns the per-run offset index entries. Trusts the on-disk index if it is present and
     * has the same mtime as the cache; rebuilds from the frames file when the index is missing
     * (cold start, partial-write window, or external deletion).
     *
     * The cache is keyed on the index file's mtime, so:
     *   * same-JVM appends below [INDEX_STRIDE] advance the frames file's mtime but leave the
     *     cache valid — the entries are still useful pointers, no reload needed;
     *   * same-JVM appends that cross [INDEX_STRIDE] advance the index's mtime and force a
     *     reload, which picks up the new entry;
     *   * cross-JVM readers see whatever mtime the index file has on first read.
     *
     * Caller holds [lock].
     */
    private fun loadOffsetIndex(runId: String): List<IndexEntry> {
        val indexPath = offsetIndexFile(runId)
        val indexMtime: FileTime? =
            if (Files.isRegularFile(indexPath)) Files.getLastModifiedTime(indexPath) else null

        offsetIndexByRun[runId]?.let { cached ->
            if (cached.indexMtime == indexMtime) return cached.entries
        }

        val entries = if (indexMtime != null) {
            parseOffsetIndex(indexPath)
        } else {
            rebuildOffsetIndex(runId)
        }

        offsetIndexByRun[runId] = CachedOffsetIndex(entries, indexMtime)
        return entries
    }

    /**
     * One-pass rebuild of the offset index by walking the frames file line by line. Records an
     * entry at every [INDEX_STRIDE]-th frame (0-indexed: ordinal 999, 1999, …), exactly matching
     * the convention [maybeAppendIndexEntry] uses when it is the writer.
     *
     * The walk stops at the first malformed line, which is the same contract [readFrames]
     * applies — a torn last line is not a frame and not an index entry. Writes the rebuilt
     * entries back to the index file with a single `force(false)`.
     *
     * Caller holds [lock].
     */
    private fun rebuildOffsetIndex(runId: String): List<IndexEntry> {
        val framesPath = framesFile(runId)
        val entries = mutableListOf<IndexEntry>()
        var ordinalCount = 0L
        var lineStart = 0L
        FileChannel.open(framesPath, StandardOpenOption.READ).use { channel ->
            BufferedReader(
                InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8),
            ).use { reader ->
                while (true) {
                    val posBeforeRead = lineStart
                    val line = reader.readLine() ?: break
                    val decoded = decode(line) ?: break
                    if ((ordinalCount + 1) % INDEX_STRIDE == 0L) {
                        entries.add(IndexEntry(decoded.ordinal, posBeforeRead))
                    }
                    ordinalCount++
                    lineStart = posBeforeRead + line.toByteArray(StandardCharsets.UTF_8).size + 1L
                }
            }
        }
        writeOffsetIndex(offsetIndexFile(runId), entries)
        return entries
    }

    /**
     * Parses the offset index file, skipping any line that does not match `ordinal\tbyteOffset`.
     * A torn last line is dropped silently — same rule as [decode] for frames — so a partial
     * write to the index does not poison the whole read.
     */
    private fun parseOffsetIndex(file: Path): List<IndexEntry> {
        if (!Files.isRegularFile(file)) return emptyList()
        val entries = mutableListOf<IndexEntry>()
        BufferedReader(Files.newBufferedReader(file, StandardCharsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val parts = line.split('\t')
                if (parts.size != 2) continue
                val ordinal = parts[0].toLongOrNull() ?: continue
                val byteOffset = parts[1].toLongOrNull() ?: continue
                entries.add(IndexEntry(ordinal, byteOffset))
            }
        }
        return entries
    }

    /**
     * Truncates and rewrites the offset index file. Single `force(false)` at the end is enough
     * because rebuilds are infrequent (only on first read after a stale-mtime window) and the
     * index file is small (`O(N / INDEX_STRIDE)` entries).
     *
     * Empty-input is a no-op: the file is not materialised until the first stride boundary is
     * crossed, so an empty-run keeps the on-disk layout identical to pre-fix.
     */
    private fun writeOffsetIndex(file: Path, entries: List<IndexEntry>) {
        if (entries.isEmpty()) return
        Files.createDirectories(file.parent)
        FileChannel.open(
            file,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            for (entry in entries) {
                val line = "${entry.ordinal}\t${entry.byteOffset}\n"
                channel.write(java.nio.ByteBuffer.wrap(line.toByteArray(StandardCharsets.UTF_8)))
            }
            channel.force(false)
        }
    }

    /**
     * Binary search for the rightmost entry with `ordinal <= atOrBefore`, returning its
     * `byteOffset`. If that offset is at or past the current file size (a stale index after a
     * torn-tail truncate), walks back to the previous entry with `byteOffset < fileSize`. If
     * nothing valid remains, returns 0 — the read starts at the beginning of the frames file.
     */
    private fun binarySeekIndex(entries: List<IndexEntry>, atOrBefore: Long, fileSize: Long): Long {
        var lo = 0
        var hi = entries.size - 1
        var candidateIdx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].ordinal <= atOrBefore) {
                candidateIdx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (candidateIdx < 0) return 0L
        while (candidateIdx >= 0 && entries[candidateIdx].byteOffset >= fileSize) {
            candidateIdx--
        }
        return if (candidateIdx < 0) 0L else entries[candidateIdx].byteOffset
    }

    private fun encode(frame: OutputFrame): String = listOf(
        frame.ordinal.toString(),
        frame.from.toString(),
        frame.to.toString(),
        frame.channel.token,
        URLEncoder.encode(frame.stream.value, StandardCharsets.UTF_8),
    ).joinToString("\t")

    private fun decode(line: String): OutputFrame? {
        val parts = line.split('\t')
        if (parts.size != 5) return null
        val ordinal = parts[0].toLongOrNull() ?: return null
        val from = parts[1].toLongOrNull() ?: return null
        val to = parts[2].toLongOrNull() ?: return null
        val channel = OutputChannel.fromToken(parts[3]) ?: return null
        val stream = runCatching {
            OutputStreamId(URLDecoder.decode(parts[4], StandardCharsets.UTF_8))
        }.getOrNull() ?: return null
        if (from < 0 || to <= from) return null
        return OutputFrame(ordinal, stream, channel, from, to)
    }

    private companion object {
        const val FRAMES_DIR = "frames"
        const val FRAMES_SUFFIX = ".frames"
        const val STREAMS_SUFFIX = ".streams"

        /** The per-run cross-process ordinal lock. See [ordinalAuthorityFile]. */
        const val AUTHORITY_SUFFIX = ".authority"

        /**
         * R3 / OBS-3 step 2 (ADR-OBS-004) — suffix for the per-run sparse offset index that lets
         * [framesOfRun] seek past frames it has already returned instead of re-reading the log.
         */
        const val OFFSET_INDEX_SUFFIX = ".offset-index"

        /**
         * R3 / OBS-3 step 2 (ADR-OBS-004) — number of frames between consecutive entries in the
         * offset index. Fixed for the first implementation; revisited at the OBS-3 measurement
         * gate. `1000` keeps the index around `16 KB` at `10⁶` frames and `160 KB` at `10⁷`.
         */
        const val INDEX_STRIDE = 1000L

        /**
         * R3 / OBS-3 — window for the tail-only torn-tail read in [sealTornTail].
         *
         * One frame line is ~100–200 bytes (5 tab-separated fields: ordinal, from, to, channel,
         * URL-encoded stream). 4 KiB holds ~20–40 lines, which is more than enough margin for a
         * writer that crashed mid-line: the worst case is one torn line, never twenty.
         *
         * Bigger would still be O(1) for the read but wastes I/O on every append. Smaller would
         * be safer in theory but unnecessary: there is at most one torn line at a time, and that
         * line is at most one line long.
         */
        const val TAIL_READ_BYTES = 4L * 1024L
    }
}
