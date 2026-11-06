package dev.rubentxu.pipeline.v2.sdk.files

import java.io.IOException
import java.nio.file.Path

/**
 * A filesystem the test owns.
 *
 * ## Why the workspace operations are tested against a fake filesystem
 *
 * The guarantee this module carries is about a SEQUENCE: decide, then act.
 * Between the two an actor can replace a symbolic link, and the decision that
 * was taken no longer describes the filesystem. Against a real filesystem a test
 * cannot put that actor between the two: the window is a few microseconds wide
 * and the test would be a race it does not control. A fake the test owns can.
 *
 * ## What the fake is faithful about
 *
 * The semantics that carry the containment are the JDK's, and they were
 * measured against it:
 *
 *  - a symbolic link is an entry of its own: `isSymbolicLink` answers about the
 *    entry, and `exists` follows the link to its target;
 *  - `realPath` resolves every link in the path and needs the path to exist;
 *  - `move` replaces the ENTRY at the target, so a link at the target is
 *    replaced and its target is not touched;
 *  - `writeBytes` FOLLOWS a link at the path, the whole way: the bytes land
 *    on the target of the link and the link itself is left alone. That is the
 *    fact that turns a link at the name of an effect into an exit;
 *  - `walk` does not descend into the target of a link;
 *  - `stageBytes` creates a file whose name no entry held — the name is the
 *    filesystem's own — and the write into it refuses to follow a link: a
 *    link that took the name between the creation and the write is a
 *    REFUSAL, and a staging file whose write failed does not survive.
 *
 * What the fake does NOT model is the concurrency of a real filesystem: it
 * answers one question at a time, and the change it is asked to make happens
 * at the exact point the product looks.
 */
class FakeWorkspaceFileSystem : WorkspaceFileSystem {

    /** One entry of the tree. */
    private sealed interface Entry {
        /** A directory. Its contents are the entries whose parent it is. */
        class Directory : Entry

        /** A file, with the bytes it holds. */
        class File(val bytes: ByteArray) : Entry

        /** A symbolic link, with the path it points at. */
        class Link(val target: Path) : Entry
    }

    /** The tree, keyed by the absolute path of each entry. */
    private val entries = mutableMapOf<Path, Entry>()

    /** A change armed on the resolution of the real path of a path. */
    private val afterRealPath = mutableMapOf<Path, Pair<Path, Path>>()

    /** A change armed on the first creation of a path. */
    private val creations = mutableMapOf<Path, Path>()

    /** The staging files this fake created, in the order they were created. */
    private val staged = mutableListOf<Path>()

    /** The link that takes the next staging name, before the bytes go into it. */
    private var nextStagingBecomesALink: Path? = null

    /** Whether the write into the next staging file fails. */
    private var nextStagingWriteFails = false

    /** Whether the next move fails. */
    private var nextMoveFails = false

    /** An action to run just before the next read, so two operations can overlap. */
    private var beforeNextRead: (() -> Unit)? = null

    init {
        entries[Path.of("/")] = Entry.Directory()
    }

    // --- the tree the test wants ---------------------------------------------------

    /** A directory at [path], with its parents created when they are missing. */
    fun directory(path: Path): FakeWorkspaceFileSystem = apply {
        val resolved = path.normalize()
        val parent = resolved.parent
        if (parent != null && !entries.containsKey(parent)) {
            directory(parent)
        }
        entries[resolved] = Entry.Directory()
    }

    /** A file at [path] with [content] in it, with its parents created when they are missing. */
    fun file(path: Path, content: String): FakeWorkspaceFileSystem = apply {
        val resolved = path.normalize()
        val parent = resolved.parent
        if (parent != null && !entries.containsKey(parent)) {
            directory(parent)
        }
        entries[resolved] = Entry.File(content.toByteArray())
    }

    /** A symbolic link at [path] pointing at [target]. */
    fun link(path: Path, target: Path): FakeWorkspaceFileSystem = apply {
        entries[path.normalize()] = Entry.Link(target)
    }

    /**
     * Arms a link at [path] pointing at [target], to appear right AFTER the
     * product resolves the real path of [watched].
     *
     * The decision is made of resolved real paths, and the effect is applied
     * after them. A change that lands between the two is the one the
     * containment has to survive, and this is how a test asks for it: the
     * product has already resolved what it will judge, and the change is there
     * when the chain is looked at.
     *
     * @param watched the path whose resolution marks the end of the decision.
     * @param path where the link appears.
     * @param target what the link points at.
     */
    fun linkAppearsAfterRealPathOf(
        watched: Path,
        path: Path,
        target: Path,
    ): FakeWorkspaceFileSystem = apply {
        afterRealPath[watched.normalize()] = path.normalize() to target
    }

    /**
     * Arms a link at [path] pointing at [target], to appear the first time the
     * product tries to create the directory [path].
     *
     * The creation is the moment the chain becomes real, so a link that appears
     * there is one the product has already looked past.
     */
    fun linkAppearsOnFirstCreate(path: Path, target: Path): FakeWorkspaceFileSystem = apply {
        creations[path.normalize()] = target
    }

    /** The bytes at [path], or null when there is no file there. */
    fun bytesAt(path: Path): ByteArray? {
        val entry = entries[resolve(path.normalize())]
        return (entry as? Entry.File)?.bytes
    }

    /**
     * The staging files created so far, in the order they were created.
     *
     * The name of a staging file is the filesystem's own, so this is how a
     * test sees that two operations did not share one.
     */
    fun stagedPaths(): List<Path> = staged.toList()

    /**
     * Arms a link to take the NEXT staging name, before the bytes go into it.
     *
     * The staging name is chosen by the filesystem, so the actor cannot be
     * pointed at a name in advance: it takes the name the moment it appears.
     * That is the race the JDK cannot close, and the write that does not
     * follow a link turns it into a refusal.
     */
    fun theNextStagingFileBecomesALink(target: Path): FakeWorkspaceFileSystem = apply {
        nextStagingBecomesALink = target.normalize()
    }

    /** Arms the write into the next staging file to fail. */
    fun theNextStagingWriteFails(): FakeWorkspaceFileSystem = apply {
        nextStagingWriteFails = true
    }

    /** Arms the next move to fail. */
    fun theNextMoveFails(): FakeWorkspaceFileSystem = apply {
        nextMoveFails = true
    }

    /**
     * Arms an action to run just before the next read.
     *
     * Two writes that overlap are the case this module has to survive, and a
     * real filesystem would not let a test place the second write between the
     * two operations of the first. The fake can: the action runs at the exact
     * point the product looks.
     */
    fun interleaveBeforeTheNextRead(action: () -> Unit): FakeWorkspaceFileSystem = apply {
        beforeNextRead = action
    }

    /** Whether the tree holds an entry at [path], whatever kind of entry it is. */
    fun holds(path: Path): Boolean = entries.containsKey(path.normalize())

    // --- the port -------------------------------------------------------------------

    override fun exists(path: Path): Boolean {
        val target = resolve(path.normalize())
        return entries.containsKey(target)
    }

    override fun isSymbolicLink(path: Path): Boolean {
        val resolved = path.normalize()
        return entries[resolved] is Entry.Link
    }

    override fun isDirectory(path: Path): Boolean {
        val entry = entries[resolve(path.normalize())]
        return entry is Entry.Directory
    }

    override fun isRegularFile(path: Path): Boolean {
        val entry = entries[resolve(path.normalize())]
        return entry is Entry.File
    }

    override fun realPath(path: Path): Path {
        val normalized = path.normalize()
        val resolved = resolve(normalized)
        check(entries.containsKey(resolved)) { "no such file: $resolved" }
        val armed = afterRealPath.remove(normalized)
        if (armed != null) {
            entries[armed.first] = Entry.Link(armed.second)
        }
        return resolved
    }

    override fun createDirectory(path: Path): Boolean {
        val resolved = path.normalize()
        val armed = creations.remove(resolved)
        if (armed != null) {
            entries[resolved] = Entry.Link(armed)
        }
        if (entries.containsKey(resolved)) return false
        entries[destinationOf(resolved)] = Entry.Directory()
        return true
    }

    override fun readAllBytes(path: Path): ByteArray {
        val armed = beforeNextRead
        if (armed != null) {
            beforeNextRead = null
            armed()
        }
        val entry = entries[resolve(path.normalize())]
        check(entry is Entry.File) { "not a file: $path" }
        return entry.bytes
    }

    override fun writeBytes(path: Path, bytes: ByteArray) {
        // The parent is resolved and has to be a directory — the JDK refuses
        // to write through a name whose parent is not one. Then the write
        // FOLLOWS a link at the leaf: the operating system opens the target of
        // the link and writes there, and the link itself is left alone. That is
        // what makes a link at the name of the effect an exit from the
        // workspace, so the fake has to model it.
        entries[resolve(destinationOf(path.normalize()))] = Entry.File(bytes)
    }

    override fun move(source: Path, target: Path): Boolean {
        if (nextMoveFails) {
            nextMoveFails = false
            throw IOException("the move failed: $source")
        }
        // A move is a rename: the ENTRY at the source is the one that travels,
        // and the ENTRY at the target is the one that is replaced. Neither end
        // is followed, so a link at either end is moved or replaced as a link.
        val from = destinationOf(source.normalize())
        val entry = entries.remove(from) ?: error("no such file: $from")
        entries[destinationOf(target.normalize())] = entry
        return true
    }

    /**
     * The staging file of a write, created the way the JDK creates it.
     *
     * The name is the filesystem's own and no entry held it, so two writes
     * never share one. The write into it does not follow a link: a link that
     * took the name is a refusal, and the staging file does not survive a
     * write that failed.
     */
    override fun stageBytes(target: Path, bytes: ByteArray): Path {
        val directory = target.parent
            ?: throw IOException("the write target has no directory: $target")
        val staging = directory.resolve(".${target.fileName}.${staged.size}.tmp").normalize()
        entries[staging] = Entry.File(ByteArray(0))
        staged.add(staging)

        val link = nextStagingBecomesALink
        if (link != null) {
            nextStagingBecomesALink = null
            entries[staging] = Entry.Link(link)
        }
        if (entries[staging] is Entry.Link) {
            entries.remove(staging)
            throw IOException("the staging file is a link: $staging")
        }
        if (nextStagingWriteFails) {
            nextStagingWriteFails = false
            entries.remove(staging)
            throw IOException("the staging write failed: $staging")
        }
        entries[staging] = Entry.File(bytes)
        return staging
    }

    override fun deleteIfExists(path: Path): Boolean {
        val resolved = path.normalize()
        if (!entries.containsKey(resolved)) return false
        entries.keys.filter { it.startsWith(resolved) }.forEach { entries.remove(it) }
        return true
    }

    override fun listFiles(path: Path): List<Path> {
        val resolved = resolve(path.normalize())
        return entries.keys.filter { it != resolved && it.parent == resolved }.sorted()
    }

    override fun walk(path: Path): List<Path> {
        val base = path.normalize()
        val descendants = entries.keys
            .filter { it != base && it.startsWith(base) }
            .filter { !crossesALink(base, it) }
            .sorted()
        return listOf(base) + descendants
    }

    // --- the shape of the fake -----------------------------------------------------

    /**
     * Whether the path from [base] to [path] crosses a symbolic link.
     *
     * The walk does not descend into the target of a link, so a link in the
     * middle of the path hides what is under it.
     */
    private fun crossesALink(base: Path, path: Path): Boolean {
        var current = base
        val relative = base.relativize(path)
        for (name in relative) {
            current = current.resolve(name)
            if (current != path && entries[current] is Entry.Link) return true
        }
        return false
    }

    /**
     * The path [path] denotes once every symbolic link in it is resolved.
     *
     * EVERY component is resolved, not only the last one: the JDK resolves the
     * whole path when it opens a file, so a link in the middle of it takes the
     * rest of the path with it.
     *
     * A link whose target is not there resolves to that target, which does not
     * exist: the JDK says the same, and it is what makes a broken link a path
     * that is not there rather than an entry that is.
     */
    private fun resolve(path: Path): Path {
        val normalized = path.normalize()
        var current = Path.of("/")
        var hops = 0
        for (name in normalized) {
            current = current.resolve(name)
            val entry = entries[current]
            if (entry is Entry.Link) {
                if (hops >= MAX_HOPS) return current
                hops++
                val rest = current.relativize(normalized)
                return resolve(entry.target.resolve(rest))
            }
        }
        return current
    }

    /**
     * Where a write at [path] lands.
     *
     * The parent is resolved and the name is kept: that is what the operating
     * system does, and it is why a link in the middle of a path carries the
     * write with it.
     */
    private fun destinationOf(path: Path): Path {
        val parent = path.parent
        if (parent == null) {
            check(entries[path] is Entry.Directory) { "not a directory: $path" }
            return path
        }
        val resolvedParent = resolve(parent)
        check(entries[resolvedParent] is Entry.Directory) {
            "parent is not a directory: $parent"
        }
        return resolvedParent.resolve(path.fileName)
    }

    private companion object {
        /** How many links a path may cross before the fake gives up. */
        const val MAX_HOPS = 16
    }
}
