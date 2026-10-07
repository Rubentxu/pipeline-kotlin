package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.io.BufferedReader
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
    ): OutputFrame = lock.withLock {
        val address = OutputStreamAddress.parse(stream)
        require(address != null && address.channel == channel) {
            "stream $stream does not name channel $channel"
        }
        require(to > from) { "a frame is never empty: to ($to) must exceed from ($from)" }

        val runId = address.runId
        sealTornTail(runId)
        val ordinal = nextOrdinal(runId)
        val frame = OutputFrame(ordinal, stream, channel, from, to)
        appendLine(framesFile(runId), encode(frame))
        lastOrdinalByRun[runId] = ordinal
        frame
    }

    override fun framesOfRun(runId: String, afterOrdinal: Long, limit: Int): List<OutputFrame> {
        require(limit > 0) { "limit must be positive, got $limit" }
        if (!Files.isRegularFile(framesFile(runId))) return emptyList()
        lock.withLock { loadOrdinals(runId) }
        return readFrames(runId, sealTornTail = false)
            .asSequence()
            .filter { it.ordinal > afterOrdinal }
            .take(limit)
            .toList()
    }

    override fun lastOrdinal(runId: String): Long? = lock.withLock {
        if (!Files.isRegularFile(framesFile(runId))) return@withLock null
        loadOrdinals(runId)
        lastOrdinalByRun[runId]
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
        closed
    }

    // ------------------------------------------------------------------ internals

    private fun streamsFile(runId: String): Path =
        root.resolve(FRAMES_DIR).resolve("${safeStreamName(runId)}$STREAMS_SUFFIX")

    private fun framesFile(runId: String): Path =
        root.resolve(FRAMES_DIR).resolve("${safeStreamName(runId)}$FRAMES_SUFFIX")

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
     * Returns the whole frames, so the caller reuses the read rather than paying for it twice.
     */
    private fun sealTornTail(runId: String): List<OutputFrame> {
        if (!Files.isRegularFile(framesFile(runId))) return emptyList()
        val frames = readFrames(runId, sealTornTail = true)
        val highest = frames.maxOfOrNull { it.ordinal }
        if (highest != null) {
            lastOrdinalByRun[runId] = maxOf(lastOrdinalByRun[runId] ?: -1L, highest)
        }
        ordinalsLoaded.add(runId)
        return frames
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
    }
}