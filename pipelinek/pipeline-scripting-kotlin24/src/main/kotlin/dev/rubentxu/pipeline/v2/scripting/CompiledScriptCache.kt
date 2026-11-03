package dev.rubentxu.pipeline.v2.scripting

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvmhost.loadScriptFromJar
import kotlin.script.experimental.jvmhost.saveToJar

/**
 * Why a cache lookup produced no reusable artifact.
 * These are NOT all the same fact. `ABSENT` is the ordinary first run; the other
 * two are a store that exists but cannot be trusted. Keeping them apart is what
 * makes "the cache silently did nothing" visible instead of indistinguishable
 * from a cold cache — a distinction that decides whether a user reports a bug.
*/
enum class CacheMissReason {
    /** Nothing was ever stored under this key. The ordinary cold case. */
    ABSENT,

    /** A file exists but could not be read as a compiled script: truncated, corrupt, or a foreign file. */
    UNREADABLE,

    /** The compiled script is not a JVM script, so there is no jar format to save it in. */
    NOT_JVM_SCRIPT,

    /**
     * The store is there but may not be used: its privacy could not be proven.
     * A store nobody else may read is the only store a compiled script may
     * live in, so a store that fails that requirement is not consulted and
     * not written to.
     */
    UNSAFE_STORE,
}

/**
 * The typed outcome of asking the store for a compiled artifact.
 * A miss is a value, not an absence: every miss carries its [CacheMissReason]
 * so that "compile it myself" is a decision with a stated cause instead of a
 * bare `null` that a caller can silently drop.
*/
sealed interface CacheLookup {
    data class Hit(val script: CompiledScript) : CacheLookup
    data class Miss(val reason: CacheMissReason) : CacheLookup
}

/**
 * Durable store for compiled script artifacts.
 * Implementations MUST be safe to consult on every compilation: a store that
 * can fail a pipeline is the wrong kind of store, because a compilation cache
 * is a performance concern and never a correctness one. Every failure mode
 * therefore degrades to [CacheLookup.Miss], never to an exception.
 * The key is the whole identity ([CacheKey], version plus value). An
 * implementation MUST NOT read a value with another version's recipe.
*/
interface CompiledScriptCache {
    fun load(key: CacheKey): CacheLookup

    /** Persist [script] under [key]. Failure is silent by contract; see the interface KDoc. */
    fun store(key: CacheKey, script: CompiledScript)

    /**
     * The store that stores nothing.
     * It is what the cache becomes when the user turns it off, and what it
     * becomes when the directory it would use cannot be proven private.
*/
    object Disabled : CompiledScriptCache {
        override fun load(key: CacheKey): CacheLookup = CacheLookup.Miss(CacheMissReason.ABSENT)
        override fun store(key: CacheKey, script: CompiledScript) = Unit
    }

    companion object {
        /**
         * Where the cache lives, when the user says where it lives.
         * The literal `off` turns the cache off; without a value the location
         * is the one [ScriptCachePolicy] resolves.
*/
        const val DIRECTORY_ENV_VAR: String = "PIPELINEK_SCRIPT_CACHE_DIR"

        /**
         * Resolve the cache from an environment map.
         * Taking the map as a parameter (defaulting to the process environment)
         * keeps the decision testable without touching the real environment: a
         * test that called `System.getenv()` would be non-hermetic, and a
         * non-hermetic default is how a suite starts passing for the wrong reason.
         *
         * The cache is ON by default, at the location [ScriptCachePolicy]
         * resolves, and the store only uses it when it can prove the directory
         * is private. A user who wants no cache at all says `off`.
*/
        fun fromEnvironment(env: Map<String, String> = System.getenv()): CompiledScriptCache {
            val root = ScriptCachePolicy.locate(env, System.getProperty("user.home")) ?: return Disabled
            return JarCompiledScriptCache(root)
        }
    }
}

/**
 * A [CompiledScriptCache] backed by one jar per key under [root].
 * Serialization is delegated to the Kotlin scripting runtime's own
 * `saveToJar` / `loadScriptFromJar`, which is the same codec that
 * `CompiledScriptJarsCache` uses; re-implementing a jar format here would be a
 * second answer to a question the platform already answers.
 * What this adds over `CompiledScriptJarsCache` is that identity is a
 * *parameter* ([CacheKey]) instead of being re-derived from the source and the
 * compilation configuration. Re-deriving it would create a second authority
 * for "what is this compilation's identity", which is the exact defect class
 * Forbids: two keys for one compilation is a cache that serves the wrong
 * artifact without any visible symptom.
 *
 * The store has a DOOR: the directory is only used when it can be proven
 * private ([ScriptCachePolicy.mayHoldArtifacts]). A compiled script carries
 * the literals of the script that produced it, and those literals may be
 * anybody's business, so a directory another user can read is a directory
 * that is not used at all — neither read from nor written to.
 * @param root directory holding the jars; created private on first store.
*/
class JarCompiledScriptCache(private val root: Path) : CompiledScriptCache {

    override fun load(key: CacheKey): CacheLookup {
        // The door is asked BEFORE the file: a store that may not be used
        // must not even be looked at, because looking is how another user's
        // artifact would reach this one. Reading does not create the
        // directory: a lookup that left a directory behind would be a lookup
        // with a side effect.
        if (!ScriptCacheStore.isUsable(root)) {
            return CacheLookup.Miss(
                if (ScriptCacheStore.factsFor(root).exists) {
                    CacheMissReason.UNSAFE_STORE
                } else {
                    CacheMissReason.ABSENT
                },
            )
        }
        val jar = root.resolve(fileNameFor(key))
        if (!Files.isRegularFile(jar)) return CacheLookup.Miss(CacheMissReason.ABSENT)
        return try {
            // checkMissingDependencies defaults to true: a jar whose recorded
            // classpath no longer exists is a miss, not a load that fails later
            // inside evaluation with an error the author cannot act on.
            val loaded = jar.toFile().loadScriptFromJar(true)
            if (loaded == null) {
                CacheLookup.Miss(CacheMissReason.UNREADABLE)
            } else {
                CacheLookup.Hit(loaded)
            }
        } catch (t: Throwable) {
            // Truncated, corrupt, or a foreign file. Compiling is always available;
            // serving this is not.
            CacheLookup.Miss(CacheMissReason.UNREADABLE)
        }
    }

    override fun store(key: CacheKey, script: CompiledScript) {
        if (script !is KJvmCompiledScript) return
        if (!ScriptCacheStore.ensurePrivate(root)) return
        val target = root.resolve(fileNameFor(key))
        val tmp = try {
            root.resolve("${fileNameFor(key)}.${System.nanoTime()}.tmp")
        } catch (t: Throwable) {
            return
        }
        try {
            script.saveToJar(tmp.toFile())
            // Move into place only once the bytes are complete. A kill between
            // write and move leaves a `.tmp` file, which `load` never reads; a
            // move without this step would leave a truncated jar under the real
            // name, and a truncated jar that still loads is a cache serving a
            // wrong script with no symptom.
            moveIntoPlace(tmp, target)
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun moveIntoPlace(tmp: Path, target: Path) {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    internal companion object {
        /**
         * The version label is part of the file name on purpose.
         * `v1` and `v2` values are both 64 hex characters, so a name built from
         * the value alone would let one version's artifact be served under the
         * other's key. Putting the label in the path makes that collision
         * unrepresentable instead of merely discouraged.
*/
        fun fileNameFor(key: CacheKey): String = "${key.version}-${key.value}.jar"
    }
}