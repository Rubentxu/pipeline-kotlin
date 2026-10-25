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
 * a `RuntimeException` thrown by
 * `KJvmCompiledScriptLazilyLoadedFromClasspath.getScriptOrError()`, which is why
 * the recovery below catches an exception rather than inspecting the result.
 * Because a hit never stored anything, the damaged entry then served that same
 * failure on every subsequent run — the exact opposite of the interface's
 * contract that every failure degrades to a miss.
 * That failure is recognised by its **origin**, not by its text: the decision
 * reads the frame the exception was raised from, and compares it with the
 * loader's own class and method. The text of the message is upstream's, and a
 * decision that reads it is a decision about a string this project does not own.
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
/**
 * Why an evaluation of a cache-loaded artifact threw.
 *
 * The distinction is the whole safety rule. [UnloadedArtifact] is the one
 * measured damage mode — a lazily loaded artifact whose backing jar is
 * truncated fails BEFORE any script code runs, fetching its compiled entry,
 * so compiling again cannot repeat an effect the script never started.
 * Every other throwable from evaluation means the script's own body ran and
 * threw, and a body that ran may already have produced effects; evaluating
 * it again would repeat them. That case propagates, always.
 */
sealed interface EvaluationFailure {
    data object UnloadedArtifact : EvaluationFailure
    data object ScriptBody : EvaluationFailure
}

/**
 * The class that owns the refusal, named as a string because the scripting
 * runtime declares it package-private: this project cannot reference the type,
 * only the name it appears under in a stack frame.
 *
 * The name is a **contract observed, not a string owned**: the permanent test
 * resolves it against the scripting runtime actually on the classpath, so an
 * upstream move or rename turns the test red instead of leaving a rule that
 * quietly stopped matching.
 */
internal const val UNLOADED_ARTIFACT_CLASS: String =
    "kotlin.script.experimental.jvmhost.KJvmCompiledScriptLazilyLoadedFromClasspath"

/** The method of [UNLOADED_ARTIFACT_CLASS] that raises the refusal. */
internal const val UNLOADED_ARTIFACT_METHOD: String = "getScriptOrError"

/**
 * Classify a throwable from evaluating a cache-loaded artifact.
 *
 * The decision reads two things and nothing else: the **type** of the
 * throwable and the **origin** it was raised from. The origin is the frame the
 * exception was constructed in — the top of its stack trace — and the refusal
 * that means "this artifact is damaged" is raised by exactly one method of
 * exactly one class of the scripting runtime.
 *
 * It used to read the exception's *message* instead. That was a decision about
 * a string this project does not own: the text is the runtime's, it could
 * change without notice, and the test that guarded the rule asserted the very
 * same literal — so the rule and its guard could have been wrong together and
 * still green. Reading the origin removes the shared literal: the same message
 * raised anywhere else is now somebody else's failure, and the failure of the
 * real origin is recognised whatever the message says.
 *
 * Pure: nothing is read, nothing is loaded, and anything unrecognized is the
 * script's own failure — the safe direction, because an unknown failure mode is
 * treated as "effects may have happened", never as "cache is stale". An
 * [Error] is never retriable, whatever frame it carries: an exhausted heap or
 * a linkage failure is not a damaged cache, and recompiling would only ask for
 * more of the same.
 */
fun classifyEvaluationFailure(throwable: Throwable): EvaluationFailure {
    if (throwable !is RuntimeException) return EvaluationFailure.ScriptBody
    val origin = throwable.stackTrace.firstOrNull() ?: return EvaluationFailure.ScriptBody
    return if (origin.className == UNLOADED_ARTIFACT_CLASS && origin.methodName == UNLOADED_ARTIFACT_METHOD) {
        EvaluationFailure.UnloadedArtifact
    } else {
        EvaluationFailure.ScriptBody
    }
}

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
            } catch (failure: RuntimeException) {
                when (classifyEvaluationFailure(failure)) {
                    // The artifact never reached script code: recompile, and
                    // let the store overwrite the damaged entry so the next
                    // run starts healthy.
                    EvaluationFailure.UnloadedArtifact ->
                        compileAndEvaluate(script, compilationConfiguration, evaluationConfig)

                    // The script's own body threw. It may already have
                    // produced effects, and running it again would repeat
                    // them — so the failure propagates instead of being
                    // retried behind a cache-shaped mask.
                    EvaluationFailure.ScriptBody -> throw failure
                }
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