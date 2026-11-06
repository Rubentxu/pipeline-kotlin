package dev.rubentxu.pipeline.v2.sdk.files

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The filesystem the product runs on: the JDK's own.
 *
 * The semantics here are the JDK's, and the guarantee of this module is stated
 * against them. Two of them carry the containment:
 *
 *  - [JdkWorkspacePathSystem.realPath] resolves every symbolic link, so a path
 *    without links in it is the canonical one;
 *  - [move] replaces the entry at the target rather than following it, so
 *    writing over a link replaces the link.
 *
 * The questions about a path are [JdkWorkspacePathSystem]'s, and this object
 * delegates them: a caller holds ONE filesystem and not two, and every
 * question has exactly one answer.
 */
object JdkWorkspaceFileSystem : WorkspaceFileSystem, WorkspacePathSystem by JdkWorkspacePathSystem {

    override fun readAllBytes(path: Path): ByteArray = Files.readAllBytes(path)

    /**
     * The bytes of the file, at most the number that were asked for, and the
     * size the file has.
     *
     * The size is measured first, because it is cheap and because a file
     * that does not fit is not read at all: the answer then carries the size
     * and no bytes, and the caller refuses on the size. The read that follows
     * is BOUNDED — it asks for no more than it was told to — so a file that
     * grew while it was being read comes back short instead of whole.
     *
     * Measured on temurin 21.0.8: `readNBytes(n)` returns at most `n` bytes,
     * a file of exactly `n` bytes returns all of them, and a 64 MiB file read
     * with a limit of 100 bytes returns 100 bytes without walking the file.
     */
    override fun readUpTo(path: Path, maxBytes: Long): BoundedBytes {
        require(maxBytes in 0..Int.MAX_VALUE.toLong()) {
            "the read limit is beyond what a byte array can hold: $maxBytes"
        }
        val size = Files.size(path)
        if (size > maxBytes) return BoundedBytes(bytes = ByteArray(0), sizeBytes = size)
        val bytes = Files.newInputStream(path).use { it.readNBytes(maxBytes.toInt()) }
        return BoundedBytes(bytes = bytes, sizeBytes = size)
    }

    override fun writeBytes(path: Path, bytes: ByteArray) {
        Files.write(path, bytes)
    }

    override fun move(source: Path, target: Path): Boolean = try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        false
    }

    /**
     * The staging file of a write, created by the JDK's own exclusive creation.
     *
     * `createTempFile` opens the file with `O_EXCL`: the name it picks is one
     * that no entry held, and the entry that appears is the regular file this
     * call made. Measured on temurin 21.0.8: the name carries a random suffix
     * (`.file.txt.10755920476827843234.tmp`), the file is created `rw-------`,
     * and two calls never collide.
     *
     * The write that follows does NOT follow a link: `NOFOLLOW_LINKS` makes
     * the open fail when the name is a link, so a link that took the name
     * between the creation and the write is a REFUSAL and not a write on the
     * other side of it. Measured on the same JDK: the open fails with an
     * `IOException` and the target of the link keeps its bytes.
     */
    override fun stageBytes(target: Path, bytes: ByteArray): Path {
        val directory = target.parent
            ?: throw IOException("the write target has no directory: $target")
        val staging = Files.createTempFile(directory, ".${target.fileName}.", ".tmp")
        try {
            Files.write(
                staging,
                bytes,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (t: Throwable) {
            // Nobody else knows this name: the caller never received it, so the
            // cleanup has to happen here or not at all.
            Files.deleteIfExists(staging)
            throw t
        }
        return staging
    }

    override fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)

    override fun listFiles(path: Path): List<Path> = Files.list(path).use { it.toList() }

    override fun walk(path: Path): List<Path> = Files.walk(path).use { it.toList() }
}
