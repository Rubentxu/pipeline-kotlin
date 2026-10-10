package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputDigest
import dev.rubentxu.pipeline.v2.output.OutputPage
import dev.rubentxu.pipeline.v2.output.OutputReadDigestedResult
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Serving bytes out of a stream's segment set, and nothing else.
 *
 * ## Why this is split out
 *
 * `SegmentOutputStore` does two structurally different jobs and had grown enough to hold both: it
 * **owns** reservations, commits, sealing and recovery — every mutation passes through its
 * `FileLock` and its `ReentrantLock` — and it **serves** already-committed bytes to readers, which
 * mutates nothing and takes no lock at all. The second job crossed the first job's size budget,
 * and `detekt`'s `TooManyFunctions` flagged it.
 *
 * The repository's own policy for that rule (`config/detekt/detekt.yml`) says the honest response to
 * a class that accumulates logic is an extraction and not a wider number, so the threshold was left
 * alone and this is the extraction.
 *
 * The seam chosen is the one where the two jobs stop touching: **reads**. Everything moved here is
 * pure with respect to the store — it reads files and returns a value, never reserving, committing,
 * rotating or truncating — so the ownership discipline that `ADR-OBS-002` and the `M-OWN` mutations
 * were written against stays in one place, unweakened and still covered by the same laws.
 *
 * ## What moved, and what deliberately did not
 *
 * Moved: enumerating and parsing sealed segments, reading a chunk positionally, and assembling a page
 * from the merged segment set. Stayed: `reconcile`, `rotateLocked`, `truncateTo`, the lock helpers and
 * every write path, because those are the authority and moving them would be refactoring semantics
 * for a lint budget.
 *
 * The sealed-segment *naming* moved here too, so that `sealedName` and `parseSealed` are adjacent and
 * the format has one authority rather than a writer in one class and a reader in another.
 */
internal object SegmentReader {

    /** Suffix of a sealed segment file. Owned here because this object also parses it back. */
    const val SEALED_SUFFIX: String = ".seg"

    /** One sealed segment: where it starts, how long it is, and the file holding it. */
    data class SealedSegment(val base: Long, val length: Long, val file: Path)

    /** The file name a segment with this [base] and [length] is sealed under. */
    fun sealedName(base: Long, length: Long): String = "$base-$length$SEALED_SUFFIX"

    fun sealedSegments(sealedDir: Path): List<SealedSegment> =
        if (!Files.isDirectory(sealedDir)) {
            emptyList()
        } else {
            Files.newDirectoryStream(sealedDir).use { entries ->
                entries.mapNotNull { parseSealed(it) }
            }
        }

    /** One past the last sealed segment: the global base of the current segment. */
    fun currentBase(sealedDir: Path): Long =
        sealedSegments(sealedDir).maxOfOrNull { it.base + it.length } ?: 0L

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

    fun readChunk(file: Path, offset: Long, length: Long): ByteArray {
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

    /**
     * Assembles `[from, to)` out of the merged segment set: the sealed segments in base order, then
     * the current segment.
     *
     * [committedEnd] is the O2 authority and is passed in rather than re-read, so this object cannot
     * hold a second opinion about how far a stream is committed.
     */
    fun readRange(
        stream: OutputStreamId,
        sealedDir: Path,
        segmentFile: Path,
        committedEnd: Long,
        currentBase: Long,
        from: Long,
        to: Long,
    ): OutputReadResult {
        val target = ByteArray((to - from).toInt())
        var cursor = from
        var written = 0

        val sealed = sealedSegments(sealedDir).sortedBy { it.base }

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
            val want = minOf(committedEnd, to) - cursor
            val chunk = readChunk(segmentFile, localStart, want)
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
                next = if (to >= committedEnd) null else OutputCursor(stream, to),
                committedEnd = committedEnd,
            ),
        )
    }

    /**
     * M3 — single-pass read: serves `[from, to)` out of the merged segment set
     * AND computes a deterministic SHA-256 digest of the bytes in the SAME pass.
     *
     * The hash is updated as each chunk is read, so the I/O and the digest
     * share the buffer. The default `OutputReadPort.readRangeDigested`
     * implementation does the same in two passes; this override exists so a
     * single I/O round produces both the page and the digest.
     *
     * Refusal cases mirror `readRange` exactly: a partial read becomes
     * `OutputRefusal.DanglingCommit` so the contract test
     * `DigestRefusalTranslation` can pin it.
     */
    fun readRangeDigested(
        stream: OutputStreamId,
        sealedDir: Path,
        segmentFile: Path,
        committedEnd: Long,
        currentBase: Long,
        from: Long,
        to: Long,
    ): OutputReadDigestedResult {
        val size = (to - from).toInt()
        val target = ByteArray(size)
        var cursor = from
        var written = 0
        val digest = java.security.MessageDigest.getInstance(OutputDigest.DEFAULT_ALGORITHM)

        val sealed = sealedSegments(sealedDir).sortedBy { it.base }

        for (seg in sealed) {
            if (cursor >= to) break
            val segEnd = seg.base + seg.length
            if (segEnd <= cursor) continue
            val localStart = (cursor - seg.base).coerceAtLeast(0L)
            val want = minOf(segEnd, to) - (seg.base + localStart)
            val chunk = readChunk(seg.file, localStart, want)
            chunk.copyInto(target, written)
            digest.update(chunk, 0, chunk.size)
            written += chunk.size
            cursor += chunk.size
        }

        if (cursor < to && cursor >= currentBase) {
            val localStart = cursor - currentBase
            val want = minOf(committedEnd, to) - cursor
            val chunk = readChunk(segmentFile, localStart, want)
            chunk.copyInto(target, written)
            digest.update(chunk, 0, chunk.size)
            written += chunk.size
            cursor += chunk.size
        }

        if (written != target.size) {
            return OutputReadDigestedResult.Refused(
                OutputRefusal.DanglingCommit(requestedEnd = to, readableBytes = written.toLong()),
            )
        }

        val page = OutputPage(
            bytes = target,
            stream = stream,
            from = from,
            next = if (to >= committedEnd) null else OutputCursor(stream, to),
            committedEnd = committedEnd,
        )
        return OutputReadDigestedResult.Digested(
            page = page,
            digest = OutputDigest(
                digest.digest().joinToString("") { "%02x".format(it) },
            ),
        )
    }
}
