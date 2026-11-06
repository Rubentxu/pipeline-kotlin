package dev.rubentxu.pipeline.v2.sdk.files

import java.nio.file.Files
import java.nio.file.Path

/**
 * The JDK's own answers about a path.
 *
 * The port splits the filesystem into the QUESTIONS about a path and the
 * EFFECTS applied to one, and this side mirrors that split: the questions are
 * here, and the effects are in [JdkWorkspaceFileSystem], which delegates the
 * questions here so that a caller holds ONE filesystem and not two.
 *
 * The semantics here are the JDK's:
 *
 *  - [realPath] resolves every symbolic link, so a path without links in it is
 *    the canonical one.
 */
object JdkWorkspacePathSystem : WorkspacePathSystem {

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
}
