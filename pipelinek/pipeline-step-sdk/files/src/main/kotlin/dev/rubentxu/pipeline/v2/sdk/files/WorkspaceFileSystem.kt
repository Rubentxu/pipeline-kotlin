package dev.rubentxu.pipeline.v2.sdk.files

import java.nio.file.Path

/**
 * What a path is, and how a directory comes to be.
 *
 * ## What this port is NOT
 *
 * It is not an authority on containment. Containment is decided once, by the
 * anchor, and this port only answers questions about paths. The product runs
 * [JdkWorkspaceFileSystem], which answers the same questions about the JDK's
 * own filesystem.
 *
 * ## Why the questions are a port of their own
 *
 * The guarantee this module carries — that an operation never lands outside the
 * authorized root — is about a SEQUENCE: decide, then act. Between the two an
 * actor can replace a symbolic link, and the decision that was taken no longer
 * describes the filesystem. A test that owns the filesystem can replace a link
 * between the two and watch what the product does. Without a seam there is no
 * such observer, and the guarantee would be a claim about the code rather than a
 * fact about the behaviour.
 *
 * The anchor needs nothing but these questions, so it depends on this port and
 * not on the wider one.
 */
interface WorkspacePathSystem {

    /** Whether [path] exists, following a symbolic link to its target. */
    fun exists(path: Path): Boolean

    /** Whether [path] is itself a symbolic link, whatever its target says. */
    fun isSymbolicLink(path: Path): Boolean

    /** Whether [path] is a directory, following a symbolic link to its target. */
    fun isDirectory(path: Path): Boolean

    /** Whether [path] is a regular file, following a symbolic link to its target. */
    fun isRegularFile(path: Path): Boolean

    /**
     * The canonical path of [path], with every symbolic link resolved.
     *
     * @param path a path that exists.
     * @return the same path with no symbolic link in it.
     */
    fun realPath(path: Path): Path

    /**
     * Creates [path] as a directory, without creating its parents.
     *
     * The parent has to be there already, and a symbolic link at [path] is not
     * a directory to this call: the entry is left alone and the answer says so.
     *
     * @return true when this call created the directory, false when [path] was
     *   already there.
     */
    fun createDirectory(path: Path): Boolean
}

/**
 * The filesystem the workspace operations act on.
 *
 * It adds to [WorkspacePathSystem] the effects an operation applies, and the
 * traversal it needs to find what it is about to act on.
 *
 * ## Why the port speaks in bytes
 *
 * The port answers about files in bytes, and the caller decides what a byte
 * sequence means. A `size` here would be a second reading of content the
 * caller is already holding, and a `readText`/`writeText` pair would fix the
 * encoding of every caller inside the port. Both belong to the caller, which
 * is the one that knows whether the bytes are a digest, a marker or a payload.
 */
interface WorkspaceFileSystem : WorkspacePathSystem {

    /**
     * The bytes of the file at [path].
     *
     * The read follows a symbolic link: when [path] is a link, the bytes are
     * those of its target.
     */
    fun readAllBytes(path: Path): ByteArray

    /**
     * Writes [bytes] at [path], replacing what was there.
     *
     * The write follows a symbolic link: when [path] is a link, the bytes land
     * on the target of the link and the link itself is left alone. That is why
     * the name an effect is applied to has to be free of links BEFORE the
     * effect, and why [move] is the one operation that replaces an entry
     * instead of following it.
     */
    fun writeBytes(path: Path, bytes: ByteArray)

    /**
     * Moves [source] onto [target], replacing it.
     *
     * The move replaces the ENTRY at [target]: when [target] is a symbolic
     * link, the link itself is what goes away, and the target of the link is
     * not touched.
     *
     * @return true when the move was atomic, false when the filesystem refused
     *   an atomic move and the replacement was not.
     */
    fun move(source: Path, target: Path): Boolean

    /** Removes [path] when it is there. */
    fun deleteIfExists(path: Path): Boolean

    /** The entries directly under [path]. */
    fun listFiles(path: Path): List<Path>

    /**
     * [path] and every entry under it.
     *
     * A symbolic link is an entry of its own: the walk does not descend into
     * the target of a link.
     */
    fun walk(path: Path): List<Path>
}
