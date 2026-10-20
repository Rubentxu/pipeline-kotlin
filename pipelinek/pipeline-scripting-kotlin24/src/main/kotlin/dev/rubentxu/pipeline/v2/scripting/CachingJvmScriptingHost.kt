package dev.rubentxu.pipeline.v2.scripting

import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.plus
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

/**
 * A [BasicJvmScriptingHost] that reuses a previously compiled script instead of
 * recompiling it.
 * The cache key arrives as a **constructor argument** rather than being derived
 * here. That is the whole design: identity is owned by [Kotlin24ScriptingHost],
 * which already computes it once per compilation for the emitted
 * `CompilationFinished`. If this class re-derived it from the source and the
 * compilation configuration, two derivations would exist for one compilation,
 * and a divergence between them would serve the wrong artifact silently —
 * The second-authority defect forbids. Passing the key also means this
 * host carries no mutable state, which the explicit-immutable-context law
 * Requires of anything that participates in execution
 * ### Why the override is not a reimplementation
 * `BasicScriptingHost.eval` is `compiler.invoke(source, compilationConfiguration)`
 * and, on success, `evaluator.invoke(compiled.value,
 * evaluationConfiguration ?: ScriptEvaluationConfiguration.Default)` with the
 * compile diagnostics merged into the result via `ResultWithDiagnostics.plus`.
 * That body was read out of the shipped bytecode before this class was written,
 * and it is reproduced here call for call against the same `compiler` and
 * `evaluator` instances the superclass would have used. The only additions are
 * the store, consulted before the compile, and the recovery described below.
 * On a hit the compile is skipped and the loaded artifact goes straight to the
 * evaluator with the same configuration the superclass would have used, so a
 * reused script is evaluated exactly as a freshly compiled one.
 * ### A stored artifact that loads but cannot be evaluated
 * `loadScriptFromJar` returning a value is not proof that the artifact is
 * usable. Measured on the installed CLI : a jar truncated to a
 * quarter of its bytes still loads — the entry the loader reads is at the front
 * of the file — and it fails at evaluation, not at load. The failure arrives as
 * a `RuntimeException("Compiled script is not loaded yet")` thrown by
 * `KJvmCompiledScriptLazilyLoadedFromClasspath.getScriptOrError()`, which is why
 * the recovery below catches an exception rather than inspecting the result.
 * Because a hit never stored anything, the damaged entry then served that same
 * failure on every subsequent run — the exact opposite of the interface's
 * contract that every failure degrades to a miss.
 * A throwable from this call is infrastructure, not the author's script: a script
 * whose body throws is reported by the evaluator as a `ResultValue.Error`, so it
 * arrives as a result rather than an exception. The catch is therefore narrowed
 * to `RuntimeException` rather than `Throwable`, so an `Error` (an exhausted heap,
 * a linkage failure the JVM cannot continue past) still propagates instead of
 * being silently converted into a recompile.
 * Coverage boundary, stated rather than implied: a damage mode that made the
 * artifact load *and* evaluate into a wrong-but-successful result is not
 * addressed here and cannot be detected from inside the host. Two classes are
 * measured — unreadable at load, and throwable at evaluation — and those are the
 * two the store degrades from.
 * @param cache where to look and to store; [CompiledScriptCache.Disabled] to
 *   keep the uncached behaviour.
 * @param cacheKey the identity of the one compilation this host exists to run.
*/
class CachingJvmScriptingHost(
    private val cache: CompiledScriptCache,
    private val cacheKey: CacheKey,
) : BasicJvmScriptingHost() {

    override fun eval(
        script: SourceCode,
        compilationConfiguration: ScriptCompilationConfiguration,
        evaluationConfiguration: ScriptEvaluationConfiguration?,
    ): ResultWithDiagnostics<EvaluationResult> {
        // `eval` accepts a null evaluation configuration; `ScriptEvaluator.invoke`
        // does not. The superclass resolves the null to the default, and this
        // override has to do the same or the two paths would differ.
        val evaluationConfig = evaluationConfiguration ?: ScriptEvaluationConfiguration.Default
        return when (val lookup = cache.load(cacheKey)) {
            is CacheLookup.Hit -> try {
                runInCoroutineContext { evaluator.invoke(lookup.script, evaluationConfig) }
            } catch (unusable: RuntimeException) {
                // The artifact loaded and is still not usable, so treat it as a miss:
                // recompile, and let the store overwrite the damaged entry so the
                // next run starts healthy.
                compileAndEvaluate(script, compilationConfiguration, evaluationConfig)
            }

            is CacheLookup.Miss -> compileAndEvaluate(script, compilationConfiguration, evaluationConfig)
        }
    }

    /**
     * Compile, persist, then evaluate — the superclass's order, with the store
     * between the first and the second step so the artifact is durable before
     * anything is executed with it.
*/
    private fun compileAndEvaluate(
        script: SourceCode,
        compilationConfiguration: ScriptCompilationConfiguration,
        evaluationConfig: ScriptEvaluationConfiguration,
    ): ResultWithDiagnostics<EvaluationResult> =
        runInCoroutineContext {
            when (val compiled = compiler.invoke(script, compilationConfiguration)) {
                is ResultWithDiagnostics.Failure -> compiled
                is ResultWithDiagnostics.Success -> {
                    cache.store(cacheKey, compiled.value)
                    compiled.reports.plus(
                        evaluator.invoke(compiled.value, evaluationConfig),
                    )
                }
            }
        }
}