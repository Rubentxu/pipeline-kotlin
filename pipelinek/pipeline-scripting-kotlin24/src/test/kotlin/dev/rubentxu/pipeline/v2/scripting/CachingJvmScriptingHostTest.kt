package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File
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
class CachingJvmScriptingHostTest {

    /**
     * A cache that always answers with the same artifact and counts what it
     * was asked to store. The count is the fact that matters: a store that
     * was written is a cache that was healed.
     */
    private class CountingCache(private val artifact: CompiledScript) : CompiledScriptCache {
        var stored: Int = 0
            private set

        override fun load(key: CacheKey): CacheLookup = CacheLookup.Hit(artifact)

        override fun store(key: CacheKey, script: CompiledScript) {
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

    private fun host(cache: CompiledScriptCache): CachingJvmScriptingHost =
        CachingJvmScriptingHost(cache, CacheKey("0000", CacheKey.V3))

    private fun source(text: String) = StringScriptSource(text, name = "probe.kts", locationId = "probe.kts")

    @Test
    fun `a failure that is not the loader's own refusal is propagated and never retried`() {
        // The failure carries the loader's own WORDS, and comes from nowhere
        // near the loader. A decision that read the words would recompile
        // and run the script's body a second time; this one reads the origin.
        val boom = RuntimeException("Compiled script is not loaded yet")
        val cache = CountingCache(UnreadableSourceLocation(boom))

        val thrown = assertThrows(RuntimeException::class.java) {
            host(cache).eval(
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

        val result = host(cache).eval(
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
}
