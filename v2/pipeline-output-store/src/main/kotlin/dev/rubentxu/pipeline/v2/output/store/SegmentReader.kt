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
 * ## Why this is split out
 * `SegmentOutputStore` does two structurally different jobs and had grown enough to hold both: it
 * **owns** reservations, commits, sealing and recovery — every mutation passes through its
 * `FileLock` and its `ReentrantLock` — and it **serves** already-committed bytes to readers, which
 * mutates nothing and takes no lock at all. The second job crossed the first job's size budget,
 * and `detekt`'s `TooManyFunctions` flagged it.
 * The repository's own policy for that rule (`config/detekt/detekt.yml`) says the honest response to
 * a class that accumulates logic is an extraction and not a wider number, so the threshold was left
 * alone and this is the extraction.
 * The seam chosen is the one where the two jobs stop touching: **reads**. Everything moved here is
 * pure with respect to the store — it reads files and returns a value, never reserving, committing,
 * Rotating or truncating — so the ownership discipline that `` and the `` mutations
 * were written against stays in one place, unweakened and still covered by the same laws.
 * ## What moved, and what deliberately did not
 * Moved: enumerating and parsing sealed segments, reading a chunk positionally, and assembling a page
 * from the merged segment set. Stayed: `reconcile`, `rotateLocked`, `truncateTo`, the lock helpers and
 * every write path, because those are the authority and moving them would be refactoring semantics
 * for a lint budget.
 * The sealed-segment *naming* moved here too, so that `sealedName` and `parseSealed` are adjacent and
 * the format has one authority rather than a writer in one class and a reader in another.
*/
internal object SegmentReader {

    /** Suffix of a sealed segment file. Owned here because this object also parses it back. */
    const val SEALED_SUFFIX: String = ".seg"

    /** One sealed segment: where it starts, how long it is, and the file holding it. */
    data class SealedSegment(val base: Long, val length: Long, val file: Path) {
        init {
            // The sibling value types:pipeline-output all guard their ranges at construction
            // (OutputFrame.init, OutputPage.init, OutputPin.init). SealedSegment was the one that
            // did not, so a nonsensical extent could travel the whole read path before failing.
            require(base >= 0) { "segment base must be non-negative, got $base" }
            require(length >= 0) { "segment length must be non-negative, got $length" }
        }
    }

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

    internal fun parseSealed(file: Path): SealedSegment? {
        val name = file.fileName.toString()
        if (!name.endsWith(SEALED_SUFFIX)) return null
        val stem = name.removeSuffix(SEALED_SUFFIX)
        val dash = stem.indexOf('-')
        if (dash <= 0) return null
        val base = stem.substring(0, dash).toLongOrNull() ?: return null
        val length = stem.substring(dash + 1).toLongOrNull() ?: return null
        // `sealedName` splits at the FIRST dash, so a stem like "10--5" parses as base=10 with
        // length=-5: toLongOrNull accepts the sign. A negative length understates base+length,
        // which lowers currentBase, which in turn makes the caller's localStart too large and
        // re-serves bytes already emitted — the exact "misdirected segment duplicates output"
        // failure this reader exists to prevent. The writer cannot emit it (rotateLocked refuses
        // length <= 0), so this is reachable only by corruption; the reader's contract is to
        // return null on any malformation, so it rejects the value rather than throwing.
        if (base < 0L || length < 0L) return null
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
     * [committedEnd] is the authority and is passed in rather than re-read, so this object cannot
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
        val sealed = sealedSegments(sealedDir).sortedBy { it.base }
        firstMissingOffset(sealed, from, to, currentBase)?.let { missing ->
            return OutputReadResult.Refused(retentionGap(stream, from, to, missing))
        }

        val target = ByteArray((to - from).toInt())
        var cursor = from
        var written = 0

        for (seg in sealed) {
            if (cursor >= to) break
            val segEnd = seg.base + seg.length
            if (segEnd <= cursor) continue
            // Contiguity is settled above, so this subtraction cannot go negative. It used to be
            // `(cursor - seg.base).coerceAtLeast(0)`, and that coercion is what let a gap produce a
            // full-length page of the WRONG bytes: the copy filled `target` and the only remaining
            // check was `written == target.size`, which a substituted answer satisfies perfectly.
            val localStart = cursor - seg.base
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
            // A committed offset that cannot be fully served is a dangling commit. A short page
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
     * Single-pass read: serves `[from, to)` out of the merged segment set
     * AND computes a deterministic SHA-256 digest of the bytes in the SAME pass.
     * The hash is updated as each chunk is read, so the I/O and the digest
     * share the buffer. The default `OutputReadPort.readRangeDigested`
     * implementation does the same in two passes; this override exists so a
     * single I/O round produces both the page and the digest.
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
        val sealed = sealedSegments(sealedDir).sortedBy { it.base }
        firstMissingOffset(sealed, from, to, currentBase)?.let { missing ->
            return OutputReadDigestedResult.Refused(retentionGap(stream, from, to, missing))
        }

        val size = (to - from).toInt()
        val target = ByteArray(size)
        var cursor = from
        var written = 0
        val digest = java.security.MessageDigest.getInstance(OutputDigest.DEFAULT_ALGORITHM)

        for (seg in sealed) {
            if (cursor >= to) break
            val segEnd = seg.base + seg.length
            if (segEnd <= cursor) continue
            // Contiguity settled above; see `readRange` for why the coercion is gone.
            val localStart = cursor - seg.base
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

    /**
     * The refusal for a read whose requested bytes are not all present.
     * [lastRetained] is the offset of the last byte that WAS served; the missing region starts at
     * `lastRetained + 1`, which is the address a consumer re-anchors at if it chooses to continue
     * rather than fail. One constructor for both readers: the two read paths must not be able to
     * describe the same gap differently, because a consumer branching on the refusal would then
     * have to handle two shapes of one fact.
*/
    private fun retentionGap(
        stream: OutputStreamId,
        from: Long,
        to: Long,
        lastRetained: Long,
    ): OutputRefusal.RetentionGap = OutputRefusal.RetentionGap(
        stream = stream,
        requestedRange = from until to,
        lastCommitted = lastRetained,
    )
}

/**
 * The first offset in `[from, to)` that no sealed segment and no current segment can serve, or
 * `null` when the whole range is covered.
 * This is the decision the two readers used to make by accident. Both assembled `[from, to)` by
 * walking segments and deriving each chunk's offset as `(cursor - seg.base).coerceAtLeast(0)`, so a
 * segment that began ABOVE the cursor was read from its own offset 0 and copied into the middle of
 * the answer. The page came back exactly the requested length, so the only guard left —
 * `written == target.size` — could not see it: a substituted answer satisfies it perfectly. A
 * consumer asking for `[500, 1500)` on a stream whose segments below 1000 had been released was
 * handed the CONTENT of `[1000, 1500)` and told it was the range it named. For the digested
 * reader it was worse: the digest certified bytes the caller never asked for, and a digest is
 * exactly what a consumer stores to detect corruption later.
 * A gap is currently unreachable — `SegmentOutputStore.prune` deletes a whole stream DIRECTORY,
 * which leaves the reader with no stream at all (`UnknownStream`) rather than a short one, so the
 * surviving segments are always contiguous from offset 0. That is a property of the writer, not a
 * property of the reader, and it is not guaranteed: the ADT case
 * [OutputRefusal.RetentionGap], the `MainConsoleCli` render branch and the `OutputReadPort`
 * Contract have all existed since and describe a reader that detects the gap. Answering here
 * makes the declared contract true, and it costs one comparison per segment.
 * The walk is over [sealed] in base order and treats the current segment as covering
 * `[currentBase, ∞)` — the store passes the base, not a length, so nothing beyond `currentBase`
 * is a gap on this account; a commit past the bytes that physically exist is the separate
 * `DanglingCommit` case, which the reader still raises on its own short-read check.
*/
internal fun firstMissingOffset(
    sealed: List<SegmentReader.SealedSegment>,
    from: Long,
    to: Long,
    currentBase: Long,
): Long? {
    var cursor = from
    for (seg in sealed.sortedBy { it.base }) {
        if (cursor >= to) return null
        val segEnd = seg.base + seg.length
        if (segEnd <= cursor) continue // entirely behind the cursor
        if (seg.base > cursor) return cursor // [cursor, seg.base) is in no segment
        cursor = segEnd
    }
    return if (cursor < to && cursor < currentBase) cursor else null
}
