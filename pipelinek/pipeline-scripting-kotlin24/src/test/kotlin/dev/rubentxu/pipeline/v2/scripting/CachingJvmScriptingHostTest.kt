package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.host.StringScriptSource

/**
 * The host's decision has two consumers and only one of them is a pure
 * function: [classifyEvaluationFailure] says *what* a failure is, and this
 * host says *what to do about it*. The two answers must agree:
 *
 * - a failure the loader itself raised means the artifact never reached
 *   script code, so it is recompiled and the store is handed a healthy one;
 * - any other failure may have run the script's own body, so it propagates
 *   and nothing is stored — recompiling it would repeat its effects.
 *
 * The instrument is an in-memory cache and, for the first row, the scripting
 * runtime's own lazy loader. Nothing is read from disk and no clock is
 * consulted.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CachingJvmScriptingHostTest {

    /**
     * A cache that always answers with the same artifact and counts what it
     * was asked to store. The count is the fact that matters: a store that
     * was written is a cache that was healed.
     */
    private class CountingCache(val artifact: CompiledScript) : CompiledScriptCache {
        var stored: Int = 0
            private set

        override fun load(key: CacheKey): CacheLookup = CacheLookup.Hit(artifact, CompilationReports.NONE)

        override fun store(key: CacheKey, script: CompiledScript, reports: CompilationReports) {
            stored++
        }
    }

    /**
     * An artifact whose source location cannot be read, so the failure
     * arrives from this class and from nowhere else.
     */
    private class UnreadableSourceLocation(private val failure: RuntimeException) : CompiledScript {
        override val sourceLocationId: String get() = throw failure

        override val compilationConfiguration: ScriptCompilationConfiguration
            get() = ScriptCompilationConfiguration.Default

        override suspend fun getClass(
            scriptEvaluationConfiguration: ScriptEvaluationConfiguration?,
        ): ResultWithDiagnostics<KClass<*>> = throw failure
    }

    /**
     * The host receives the ANSWER to the cache question, not the question:
     * the owner of the compilation asks the store once and hands the answer
     * down. These tests therefore say which answer they are exercising.
     */
    private fun host(cache: CompiledScriptCache, lookup: CacheLookup): CachingJvmScriptingHost =
        CachingJvmScriptingHost(cache, CacheKey("0000", CacheKey.V3), lookup)

    private fun source(text: String) = StringScriptSource(text, name = "probe.kts", locationId = "probe.kts")

    @Test
    fun `a failure that is not the loader's own refusal is propagated and never retried`() {
        // The failure carries the loader's own WORDS, and comes from nowhere
        // near the loader. A decision that read the words would recompile
        // and run the script's body a second time; this one reads the origin.
        val boom = RuntimeException("Compiled script is not loaded yet")
        val cache = CountingCache(UnreadableSourceLocation(boom))

        val thrown = assertThrows(RuntimeException::class.java) {
            host(cache, CacheLookup.Hit(cache.artifact, CompilationReports.NONE)).eval(
                source("1 + 1"),
                ScriptCompilationConfiguration.Default,
                ScriptEvaluationConfiguration.Default,
            )
        }

        // The very failure the script raised, not a wrapped one: the host
        // does not translate somebody else's failure into its own.
        assertSame(boom, thrown)
        // And nothing was stored: the damaged entry is not replaced by a
        // compilation of a script whose effects may already have happened.
        assertEquals(0, cache.stored)
    }

    @Test
    fun `an artifact the loader refuses is recompiled and the store is handed a healthy one`() {
        // The real lazy loader, asked for a class that was never compiled:
        // it has nothing to load, and its refusal is the one the host is
        // supposed to recognise.
        val loader = Class.forName("kotlin.script.experimental.jvmhost.KJvmCompiledScriptLazilyLoadedFromClasspath")
        val broken = loader.getDeclaredConstructor(String::class.java, List::class.java)
            .apply { isAccessible = true }
            .newInstance("com.example.NeverCompiled", emptyList<File>()) as CompiledScript

        val cache = CountingCache(broken)

        val result = host(cache, CacheLookup.Hit(broken, CompilationReports.NONE)).eval(
            source("1 + 1"),
            ScriptCompilationConfiguration.Default,
            ScriptEvaluationConfiguration.Default,
        )

        // The run got its result: the host recompiled instead of failing.
        val evaluation = (result as ResultWithDiagnostics.Success).value
        assertEquals(2, (evaluation.returnValue as ResultValue.Value).value)
        // And the damaged entry was replaced, so the next run starts from a
        // healthy artifact instead of serving the same failure forever.
        assertEquals(1, cache.stored)
    }

    /**
     * The origin is the OUTCOME, and the two differ in exactly one case: the
     * store answered "hit", the artifact was unusable, and the run compiled
     * after all. A record that only carried the decision would say "reused"
     * for a run that compiled, and the console would announce a reuse the run
     * did not perform.
     */
    @Test
    fun `an unusable stored artifact makes the run compile, and the origin says so`() {
        val loader = Class.forName("kotlin.script.experimental.jvmhost.KJvmCompiledScriptLazilyLoadedFromClasspath")
        val broken = loader.getDeclaredConstructor(String::class.java, List::class.java)
            .apply { isAccessible = true }
            .newInstance("com.example.NeverCompiled", emptyList<File>()) as CompiledScript

        val cache = CountingCache(broken)
        val host = host(cache, CacheLookup.Hit(broken, CompilationReports.NONE))

        host.eval(
            source("1 + 1"),
            ScriptCompilationConfiguration.Default,
            ScriptEvaluationConfiguration.Default,
        )

        // The announced answer was "reused"; what actually happened was a
        // compilation. The host reports the second.
        assertEquals(ScriptOrigin.COMPILED, host.effectiveOrigin)
    }

    /**
     * A hit that serves the run is a reuse, even when the script's own body
     * throws: the artifact WAS reused, and the failure is the script's, not
     * the cache's.
     */
    @Test
    fun `a hit that serves the run is reported as reused`() {
        val boom = RuntimeException("Compiled script is not loaded yet")
        val cache = CountingCache(UnreadableSourceLocation(boom))
        val host = host(cache, CacheLookup.Hit(cache.artifact, CompilationReports.NONE))

        assertThrows(RuntimeException::class.java) {
            host.eval(
                source("1 + 1"),
                ScriptCompilationConfiguration.Default,
                ScriptEvaluationConfiguration.Default,
            )
        }

        assertEquals(ScriptOrigin.REUSED, host.effectiveOrigin)
    }

    /**
     * A miss is a compilation, and the store is handed the artifact so the
     * next run can reuse it.
     */
    @Test
    fun `a miss compiles and is reported as compiled`() {
        val cache = CountingCache(UnreadableSourceLocation(RuntimeException("unused")))
        val host = host(cache, CacheLookup.Miss(CacheMissReason.ABSENT))

        host.eval(
            source("1 + 1"),
            ScriptCompilationConfiguration.Default,
            ScriptEvaluationConfiguration.Default,
        )

        assertEquals(ScriptOrigin.COMPILED, host.effectiveOrigin)
        assertEquals(1, cache.stored)
    }
}
