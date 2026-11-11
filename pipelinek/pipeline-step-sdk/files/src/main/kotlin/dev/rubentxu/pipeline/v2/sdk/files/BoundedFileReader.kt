package dev.rubentxu.pipeline.v2.sdk.files

import java.nio.file.Path

/**
 * What a read held under a quota found.
 *
 * ## The two facts
 *
 * [Fits] carries the bytes, and they are ALL of them: a file that fits is
 * read whole or not at all. [TooLarge] carries the boundary — the size the
 * file has, as a lower bound, and the limit it did not fit — and no bytes at
 * all: a refusal that carried a prefix would be a file the caller could read
 * as if it were whole.
 */
sealed interface BoundedRead {

    /**
     * The file fits the quota: these are its bytes, and they are all of them.
     */
    class Fits(val bytes: ByteArray) : BoundedRead

    /**
     * The file does not fit the quota, and nothing of it was read.
     *
     * @property atLeastBytes the size the file has, as a lower bound: it holds
     *   at least this many bytes.
     * @property limitBytes the limit the read was held under.
     */
    data class TooLarge(val atLeastBytes: Long, val limitBytes: Long) : BoundedRead
}

/**
 * The read of a file under a quota: the one every reader of the product uses.
 *
 * ## What it guarantees
 *
 * That a file which does not fit the quota is NOT read: the port measures
 * the size before it reads, and a file over the limit hands over no bytes at
 * all. And that a file which fits is read WHOLE: the bytes that come back are
 * all of them, and a file that does not fit is refused rather than truncated.
 *
 * ## Why the limit is checked TWICE
 *
 * Once BEFORE the read, on the size the filesystem reports, and once DURING
 * it, on the bytes that came back. The second check is the one that catches a
 * file that grew while it was being read: the port is asked for one byte more
 * than the limit, so such a file comes back with one byte too many and is
 * refused, instead of handing over a prefix that looks like a whole file.
 *
 * ## What it does NOT do
 *
 * It does not decide what the bytes MEAN: the caller decodes them, parses
 * them, or refuses them in its own vocabulary. And it does not swallow the
 * filesystem: an IOException reaches the caller, which is the one that knows
 * whether a file that cannot be read is the author's mistake or the machine's.
 */
object BoundedFileReader {

    /**
     * The bytes of the file at [path], if it fits under [maxBytes].
     *
     * @param path the file to read.
     * @param maxBytes the limit the read is held under.
     * @param fileSystem the filesystem the read is applied to.
     * @return the bytes of the file, or the fact that it does not fit.
     */
    fun read(
        path: Path,
        maxBytes: Long,
        fileSystem: WorkspaceFileSystem = JdkWorkspaceFileSystem,
    ): BoundedRead {
        require(maxBytes in 0..Int.MAX_VALUE.toLong()) {
            "the read limit is beyond what a byte array can hold: $maxBytes"
        }
        // The port is asked for ONE byte more than the limit, so that a file
        // which grew while it was being read is told apart from a file that
        // fits. The port measures the size first and hands over no bytes for a
        // file that does not fit, so the file is never held whole.
        val read = fileSystem.readUpTo(path, maxBytes + 1)
        if (read.sizeBytes > maxBytes) {
            return BoundedRead.TooLarge(atLeastBytes = read.sizeBytes, limitBytes = maxBytes)
        }
        if (read.bytes.size > maxBytes) {
            return BoundedRead.TooLarge(atLeastBytes = read.bytes.size.toLong(), limitBytes = maxBytes)
        }
        return BoundedRead.Fits(read.bytes)
    }
}
