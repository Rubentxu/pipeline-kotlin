package dev.rubentxu.pipeline.v2.sdk.files

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The filesystem the product runs on: the JDK's own.
 *
 * The semantics here are the JDK's, and the guarantee of this module is stated
 * against them. Two of them carry the containment:
 *
 *  - [realPath] resolves every symbolic link, so a path without links in it is
 *    the canonical one;
 *  - [move] replaces the entry at the target rather than following it, so
 *    writing over a link replaces the link.
 */
object JdkWorkspaceFileSystem : WorkspaceFileSystem {

    override fun exists(path: Path): Boolean = Files.exists(path)

    override fun isSymbolicLink(path: Path): Boolean = Files.isSymbolicLink(path)

    override fun isDirectory(path: Path): Boolean = Files.isDirectory(path)

    override fun isRegularFile(path: Path): Boolean = Files.isRegularFile(path)

    override fun realPath(path: Path): Path = path.toRealPath()

    override fun createDirectory(path: Path): Boolean = try {
        Files.createDirectory(path)
        true
    } catch (_: java.nio.file.FileAlreadyExistsException) {
        false
    }

    override fun readAllBytes(path: Path): ByteArray = Files.readAllBytes(path)

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

    override fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)

    override fun listFiles(path: Path): List<Path> = Files.list(path).use { it.toList() }

    override fun walk(path: Path): List<Path> = Files.walk(path).use { it.toList() }
}
