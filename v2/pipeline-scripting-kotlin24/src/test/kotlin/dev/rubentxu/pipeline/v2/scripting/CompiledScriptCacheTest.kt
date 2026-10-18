package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The compilation cache store, and the reuse decision built on it.
 *
 * Fidelity: these rows enter through the production authority — [Kotlin24ScriptingHost.compile]
 * and the `CachingJvmScriptingHost` it builds. Nothing here re-derives a cache key, and no row
 * asserts on elapsed time: a duration is a property of the machine, and "it got faster" is a
 * claim a regression would silently satisfy for the wrong reason.
 *
 * What each row observes is a discrete fact: whether the store was consulted, what it answered,
 * and whether anything was written. The reuse row leans on a causal link rather than a count of
 * anything incidental: `store` is reachable only from inside the compile branch of
 * `CachingJvmScriptingHost.eval`, so "a hit and no store" means the compiler was not called.
 */
class CompiledScriptCacheTest {

    /** A real store plus an observation log of what the host asked it and told it. */
    private class RecordingCache(private val delegate: CompiledScriptCache) : CompiledScriptCache {
        val loads = mutableListOf<CacheLookup>()
        var stores = 0
            private set

        override fun load(key: CacheKey): CacheLookup =
            delegate.load(key).also { loads += it }

        override fun store(key: CacheKey, script: kotlin.script.experimental.api.CompiledScript) {
            stores++
            delegate.store(key, script)
        }
    }

    private fun host(cache: CompiledScriptCache) = Kotlin24ScriptingHost(cache = cache)

    private fun dslClasspath(): List<String> {
        val dslJar = ScriptDefinition.dslApiJar()
        return if (dslJar != null) listOf(dslJar) else emptyList()
    }

    private fun definition(resource: String): ScriptDefinition =
        ScriptDefinition.file(Path.of(javaClass.getResource(resource)!!.toURI()), classpath = dslClasspath())

    /** A second, genuinely different script, so a hit would mean the wrong artifact was served. */
    private fun otherDefinition(): ScriptDefinition = ScriptDefinition.inline(
        text = "pipeline { stages { stage(\"other\") { echo(\"other\") } } }",
        classpath = dslClasspath(),
    )

    // ---------------------------------------------------------------- naming

    /**
     * The version label is part of the file name, so a `v1` value and a `v2` value can never
     * occupy the same path. `CACHE_KEY_V2_MIGRATION_PLAN.md` states the constraint in prose
     * ("the store must key on `(version, value)`"); this row is what makes it structural instead
     * of a reviewer's memory. Both versions emit 64 hex characters, so a name built from the
     * value alone would be genuinely ambiguous.
     */
    @Test
    fun `the version label is part of the stored file name`() {
        val shared = "a".repeat(64)
        val v1 = JarCompiledScriptCache.fileNameFor(CacheKey(shared, "v1"))
        val v2 = JarCompiledScriptCache.fileNameFor(CacheKey(shared, "v2"))

        assertNotEquals(v1, v2, "two versions of the same value must not share a file name")
        assertTrue(v1.startsWith("v1-") && v2.startsWith("v2-"), "the label must be readable from the name: $v1 / $v2")
    }

    // ------------------------------------------------------------ opt-in gate

    /**
     * The cache is off unless asked for. The product has no established user-home convention to
     * put a cache in, and guessing one fails differently on a read-only HOME, a sandboxed CI and
     * an ephemeral container — so the absence of the switch must mean "today's behaviour, exactly".
     */
    @Test
    fun `no cache unless the directory is configured`(@TempDir root: Path) {
        assertTrue(
            CompiledScriptCache.fromEnvironment(emptyMap()) is CompiledScriptCache.Disabled,
            "an absent variable must not enable a cache",
        )
        assertTrue(
            CompiledScriptCache.fromEnvironment(mapOf(CompiledScriptCache.DIRECTORY_ENV_VAR to "  ")) is
                CompiledScriptCache.Disabled,
            "a blank variable is not a directory",
        )
        assertTrue(
            CompiledScriptCache.fromEnvironment(
                mapOf(CompiledScriptCache.DIRECTORY_ENV_VAR to root.toString()),
            ) is JarCompiledScriptCache,
        )
    }

    // ------------------------------------------------------------ miss reasons

    @Test
    fun `an empty store answers ABSENT, never an exception`(@TempDir root: Path) {
        val lookup = JarCompiledScriptCache(root).load(CacheKey("b".repeat(64), "v2"))
        assertEquals(CacheLookup.Miss(CacheMissReason.ABSENT), lookup)
    }

    /**
     * A file that is not a compiled script must degrade to a miss, not to a load that fails later
     * inside evaluation with an error the author cannot act on. This is the guarantee that lets
     * the cache be consulted on every compilation without becoming a way for a pipeline to fail.
     */
    @Test
    fun `a corrupt artifact is UNREADABLE rather than an exception`(@TempDir root: Path) {
        val cache = JarCompiledScriptCache(root)
        val key = CacheKey("c".repeat(64), "v2")
        Files.write(root.resolve(JarCompiledScriptCache.fileNameFor(key)), "not a jar".toByteArray())

        assertEquals(CacheLookup.Miss(CacheMissReason.UNREADABLE), cache.load(key))
    }

    // --------------------------------------------------------------- reuse row

    /**
     * The capability this whole slice exists for: a second identical compilation reuses the first
     * one's artifact and still produces the same result.
     */
    @Test
    fun `a second identical compilation reuses the stored artifact`(@TempDir root: Path) {
        val cache = RecordingCache(JarCompiledScriptCache(root))
        val scriptingHost = host(cache)
        val def = definition("/hello.pipeline.kts")

        val first = scriptingHost.compile(def)
        assertTrue(first.isSuccess, "first compile: ${first.diagnostics}")
        assertEquals(CacheLookup.Miss(CacheMissReason.ABSENT), cache.loads.single())
        assertEquals(1, cache.stores, "the first compilation is the one that populates the store")

        val second = scriptingHost.compile(def)
        assertTrue(second.isSuccess, "reuse must not change the outcome: ${second.diagnostics}")

        assertTrue(cache.loads[1] is CacheLookup.Hit, "second compile should have found the artifact")
        assertEquals(
            1, cache.stores,
            "no store on the reuse path proves the compiler was not invoked again: `store` is only " +
                "reachable from inside the compile branch of CachingJvmScriptingHost.eval",
        )
        assertEquals(first.cacheKey, second.cacheKey, "reuse must report the same identity as the compile it replaces")
    }

    /**
     * A different script is a different compilation. If the store answered a hit for it, the
     * cache would be serving one script's bytecode under another's key — the failure this design
     * exists to make impossible.
     */
    @Test
    fun `a different script is a miss, never a hit`(@TempDir root: Path) {
        val cache = RecordingCache(JarCompiledScriptCache(root))
        val scriptingHost = host(cache)

        assertTrue(scriptingHost.compile(definition("/hello.pipeline.kts")).isSuccess)
        val other = scriptingHost.compile(otherDefinition())
        assertTrue(other.isSuccess, "second script: ${other.diagnostics}")

        assertTrue(cache.loads[1] is CacheLookup.Miss, "a different script must not reuse the first one's artifact")
        assertEquals(2, cache.stores, "each distinct compilation stores its own artifact")
    }

    /**
     * The fail-open guarantee, end to end: a store that cannot serve its artifact must still
     * leave the compilation working. This is the row that would go red if the miss branch were
     * ever short-circuited into an error.
     */
    @Test
    fun `a corrupt stored artifact still compiles successfully`(@TempDir root: Path) {
        val cache = JarCompiledScriptCache(root)
        val scriptingHost = host(cache)
        val def = definition("/hello.pipeline.kts")

        assertTrue(scriptingHost.compile(def).isSuccess, "populate the store first")

        val key = scriptingHost.compile(def).cacheKey
        Files.write(root.resolve(JarCompiledScriptCache.fileNameFor(key)), "truncated".toByteArray())

        val after = scriptingHost.compile(def)
        assertTrue(after.isSuccess, "an unreadable artifact must degrade to compiling, not to failing")
        assertEquals(key, after.cacheKey)
    }

    /**
     * A stored artifact must never survive into the next run.
     *
     * This row exists because the unit rows above were not enough. A jar truncated to a quarter
     * of its bytes still *loads* — the entry the loader reads sits at the front of the file — so
     * it arrives as a [CacheLookup.Hit] and only fails at evaluation, with
     * `Compiled script is not loaded yet`. Measured on the installed CLI: without recovery the
     * poisoned entry served that same failure on every later run, which is the opposite of the
     * store's contract.
     *
     * The row asserts the guarantee, not the codec's failure taxonomy: whichever way the damaged
     * artifact fails to load, the run must succeed and the entry must be rewritten whole. Asserting
     * that a *specific* truncation produced a specific miss reason would bind the test to the
     * jar format's internals, and would break for reasons that are not defects.
     */
    @Test
    fun `a damaged stored artifact heals instead of poisoning the next run`(@TempDir root: Path) {
        val cache = JarCompiledScriptCache(root)
        val scriptingHost = host(cache)
        val def = definition("/hello.pipeline.kts")

        assertTrue(scriptingHost.compile(def).isSuccess, "populate the store first")
        val key = scriptingHost.compile(def).cacheKey
        val jar = root.resolve(JarCompiledScriptCache.fileNameFor(key))

        val whole = Files.size(jar)
        val truncated = whole / 4
        Files.write(jar, Files.readAllBytes(jar).copyOf(truncated.toInt()))

        val after = scriptingHost.compile(def)
        assertTrue(after.isSuccess, "a damaged artifact must never fail the run: ${after.diagnostics}")
        assertEquals(key, after.cacheKey)
        assertEquals(whole, Files.size(jar), "the artifact must be rewritten whole, not left damaged")
    }

    /**
     * CONTROL: the uncached path still works. Without this row, a change that made every
     * compilation "succeed" by returning a fabricated result would pass the rows above.
     */
    @Test
    fun `the disabled cache leaves compilation untouched`() {
        val result = host(CompiledScriptCache.Disabled).compile(definition("/hello.pipeline.kts"))
        assertTrue(result.isSuccess, "disabled cache must not change compilation: ${result.diagnostics}")
        assertEquals("v2", result.cacheKey.version)
    }
}