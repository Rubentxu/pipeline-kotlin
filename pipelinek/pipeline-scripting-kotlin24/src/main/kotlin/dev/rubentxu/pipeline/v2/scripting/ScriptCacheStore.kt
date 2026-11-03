package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.domain.credentials.CredentialFilePermissions
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/**
 * What is known about a candidate cache directory.
 *
 * These are OBSERVATIONS, not a verdict: the verdict is
 * [ScriptCachePolicy.mayHoldArtifacts], and keeping the two apart is what
 * lets the rule be a pure function of facts that a test can write down
 * without touching a filesystem.
 *
 * @property exists whether something is there at all.
 * @property isDirectory whether that something is a directory.
 * @property isSymbolicLink whether that something is a link to elsewhere.
 * @property ownedByCurrentUser whether the directory belongs to the user
 *   running the process.
 * @property readableByOthers whether the group or the others may reach it.
 */
data class StoreDirectoryFacts(
    val exists: Boolean,
    val isDirectory: Boolean,
    val isSymbolicLink: Boolean,
    val ownedByCurrentUser: Boolean,
    val readableByOthers: Boolean,
)

/**
 * What the product will do with the compiled-script cache.
 */
enum class ScriptCacheState {

    /** The cache is off: the user asked for it to be off. */
    OFF,

    /** The directory is there and only its owner may reach it. */
    PRIVATE,

    /** The directory is not there yet; it will be created private on first use. */
    WILL_CREATE,

    /** The directory may not hold artifacts, for the reason that comes with it. */
    REFUSED,
}

/**
 * Where the cache is, and what the product will do with it.
 *
 * @property root where the artifacts would live, or null when there is no cache.
 * @property state what the product will do.
 * @property reason the cause, in words a person can act on.
 */
data class ScriptCacheInspection(
    val root: Path?,
    val state: ScriptCacheState,
    val reason: String,
)

/**
 * The rules that decide where the compiled-script cache lives and whether it
 * may hold artifacts.
 *
 * The two halves are separated on purpose. [locate] is PURE: it reads an
 * environment map and does arithmetic on paths, so the location can be
 * written down in a test without a filesystem and without an environment.
 * [mayHoldArtifacts] is PURE too: it is a function of facts, and the facts
 * are gathered elsewhere by [ScriptCacheStore].
 *
 * The posture the facts are judged against — a directory only its owner may
 * reach — is the domain's canonical one
 * ([CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE]). The DECISION about
 * a candidate directory is this store's own door: no other module has to
 * answer the same question about a different kind of directory, and a
 * disagreement between two answers to the same question would be invisible.
 */
object ScriptCachePolicy {

    /** The value that turns the cache off. */
    const val OFF_VALUE: String = "off"

    /** The product's directory inside the user's cache home. */
    const val PRODUCT_DIRECTORY: String = "pipelinek"

    /** The directory that holds the compiled artifacts. */
    const val STORE_DIRECTORY: String = "script-cache"

    /**
     * Where the cache lives, or null when there is none.
     *
     * The explicit variable wins over the default, and the literal `off` turns
     * the cache off: an operator who says where the cache is has said it, and
     * an operator who says `off` has said it too. A blank value says nothing,
     * so the default answers.
     *
     * The default is the cache home of the XDG base directory specification:
     * `XDG_CACHE_HOME` when it is set to an ABSOLUTE path, and the `~/.cache`
     * of the user's home otherwise. A relative `XDG_CACHE_HOME` is ignored,
     * because a cache that lands wherever the process happened to start is
     * not a cache the user can find.
     *
     * Pure: [env] is the only thing it reads, and [fallbackHome] is the
     * caller's way of saying what the JVM believes the home is.
     */
    fun locate(env: Map<String, String>, fallbackHome: String? = null): Path? {
        val explicit = env[CompiledScriptCache.DIRECTORY_ENV_VAR]?.trim()
        if (!explicit.isNullOrEmpty()) {
            if (explicit.equals(OFF_VALUE, ignoreCase = true)) return null
            return pathOrNull(explicit)
        }
        val cacheHome = cacheHome(env, fallbackHome) ?: return null
        return cacheHome.resolve(PRODUCT_DIRECTORY).resolve(STORE_DIRECTORY)
    }

    /**
     * The rule: a directory may hold compiled artifacts only when it is
     * provably private.
     *
     * Four conditions, and each of them closes a different hole:
     *
     * - it EXISTS: there is nothing to reuse in a directory that is not there;
     * - it is a DIRECTORY and not a LINK: a link is a way of writing
     *   somewhere else, and the somewhere else is not the directory whose
     *   permissions were examined;
     * - it is OWNED BY THE CURRENT USER: a directory belonging to somebody
     *   else is not ours to read, and its contents are not ours to serve;
     * - it is NOT READABLE BY GROUP OR OTHERS: ownership alone does not stop
     *   a group from reading what is inside.
     *
     * The owner check is what also closes the substitution hole: a directory
     * that replaces ours between the check and the use belongs to whoever
     * put it there, and a directory nobody but us can create is one we own.
     */
    fun mayHoldArtifacts(facts: StoreDirectoryFacts): Boolean =
        facts.exists &&
            facts.isDirectory &&
            !facts.isSymbolicLink &&
            facts.ownedByCurrentUser &&
            !facts.readableByOthers

    /**
     * The cause of a refusal, in words.
     *
     * A refusal without a cause is a silence, and a silence is what a user
     * cannot report: "the cache silently did nothing" is indistinguishable
     * from "the cache did nothing because there was nothing to reuse".
     */
    fun reasonFor(facts: StoreDirectoryFacts): String = when {
        !facts.exists -> "the directory does not exist yet"
        facts.isSymbolicLink -> "the path is a symbolic link, not the directory itself"
        !facts.isDirectory -> "the path is not a directory"
        !facts.ownedByCurrentUser -> "the directory belongs to another user"
        facts.readableByOthers -> "the directory can be reached by the group or by others"
        else -> "the directory is private to its owner"
    }

    private fun cacheHome(env: Map<String, String>, fallbackHome: String?): Path? {
        val xdg = env["XDG_CACHE_HOME"]?.trim()
        if (!xdg.isNullOrEmpty()) {
            val path = pathOrNull(xdg)
            if (path != null && path.isAbsolute) return path
        }
        val home = env["HOME"]?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackHome?.trim()?.takeIf { it.isNotEmpty() }
        val homePath = pathOrNull(home) ?: return null
        return homePath.resolve(".cache")
    }

    private fun pathOrNull(text: String?): Path? {
        val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            Path.of(trimmed)
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * The door the compiled-script cache has to come through.
 *
 * It gathers the facts [ScriptCachePolicy] judges, and it can create the
 * directory the way it has to be created: private, and only by whoever asks
 * for it. Every operation degrades to "not used" rather than to an error: a
 * cache is a performance concern, and a pipeline that fails because a cache
 * directory could not be created would be a pipeline that fails over a
 * performance concern.
 */
object ScriptCacheStore {

    /** The permission bits that let anybody but the owner reach a directory. */
    private val REACHABLE_BY_OTHERS: Set<PosixFilePermission> = setOf(
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_WRITE,
        PosixFilePermission.OTHERS_EXECUTE,
    )

    /**
     * What is true about [path], observed without following a link.
     *
     * A filesystem that cannot answer the ownership question answers "not
     * ours", and a filesystem that cannot answer the permission question
     * answers "reachable by others": both are the safe direction, because
     * they refuse a store whose privacy nobody could prove.
     */
    fun factsFor(path: Path): StoreDirectoryFacts {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return StoreDirectoryFacts(
                exists = false,
                isDirectory = false,
                isSymbolicLink = false,
                ownedByCurrentUser = false,
                readableByOthers = true,
            )
        }
        val isLink = Files.isSymbolicLink(path)
        val isDirectory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        if (view == null) {
            return StoreDirectoryFacts(
                exists = true,
                isDirectory = isDirectory,
                isSymbolicLink = isLink,
                ownedByCurrentUser = false,
                readableByOthers = true,
            )
        }
        val attributes = try {
            view.readAttributes()
        } catch (_: Throwable) {
            null
        } ?: return StoreDirectoryFacts(
            exists = true,
            isDirectory = isDirectory,
            isSymbolicLink = isLink,
            ownedByCurrentUser = false,
            readableByOthers = true,
        )
        val owner = attributes.owner()?.name
        val permissions = attributes.permissions()
        return StoreDirectoryFacts(
            exists = true,
            isDirectory = isDirectory,
            isSymbolicLink = isLink,
            ownedByCurrentUser = owner != null && owner == currentUserName(),
            readableByOthers = permissions.any { it in REACHABLE_BY_OTHERS },
        )
    }

    /**
     * Whether [path] may be used as it stands, without changing anything.
     *
     * This is what a READ asks: a lookup that created the directory would
     * leave a directory behind for a run that had nothing to reuse.
     */
    fun isUsable(path: Path): Boolean {
        val facts = factsFor(path)
        return facts.exists && ScriptCachePolicy.mayHoldArtifacts(facts)
    }

    /**
     * Make sure [path] is a private directory of ours, creating it when it is
     * not there.
     *
     * An existing directory is NEVER changed: the permissions of a directory
     * somebody else created are not ours to tighten, and a store that
     * tightened them would be a store that quietly rewrote a path the user
     * pointed it at. An existing directory that is not private is refused,
     * and the refusal says why.
     */
    fun ensurePrivate(path: Path): Boolean {
        val facts = factsFor(path)
        if (facts.exists) return ScriptCachePolicy.mayHoldArtifacts(facts)
        return try {
            createPrivateDirectories(path)
            ScriptCachePolicy.mayHoldArtifacts(factsFor(path))
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * What the product will do with the cache, without changing anything.
     *
     * This is the reading the doctor publishes: it observes the directory and
     * says whether the artifacts will land there, and why not when they will
     * not. It never creates the directory — a report that asked a question
     * and left a directory behind would be a report with a side effect.
     */
    fun inspect(env: Map<String, String>, fallbackHome: String? = null): ScriptCacheInspection {
        val root = ScriptCachePolicy.locate(env, fallbackHome)
            ?: return ScriptCacheInspection(
                root = null,
                state = ScriptCacheState.OFF,
                reason = "the cache is off",
            )
        val facts = factsFor(root)
        val state = when {
            !facts.exists -> ScriptCacheState.WILL_CREATE
            ScriptCachePolicy.mayHoldArtifacts(facts) -> ScriptCacheState.PRIVATE
            else -> ScriptCacheState.REFUSED
        }
        return ScriptCacheInspection(
            root = root,
            state = state,
            reason = ScriptCachePolicy.reasonFor(facts),
        )
    }

    /**
     * Create [path] and the directories above it, private from the start.
     *
     * The directories above it are created private too: a directory that
     * anybody may write into is a directory where ours can be replaced, and
     * a replacement is only refused because the owner check catches it. The
     * creation is atomic in the sense that matters: `createDirectory` refuses
     * to adopt a directory that is already there, so the verification that
     * follows is the one that decides.
     */
    private fun createPrivateDirectories(path: Path) {
        val parent = path.parent
        if (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            createPrivateDirectories(parent)
        }
        try {
            Files.createDirectory(
                path,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE,
                ),
            )
        } catch (_: FileAlreadyExistsException) {
            // Somebody created it first. The verification that follows the
            // creation is what decides whether it may be used.
        }
    }

    private fun currentUserName(): String? = try {
        System.getProperty("user.name")
    } catch (_: Throwable) {
        null
    }
}
