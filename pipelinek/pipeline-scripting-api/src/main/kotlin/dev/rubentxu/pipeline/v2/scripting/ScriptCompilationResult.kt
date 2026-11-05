package dev.rubentxu.pipeline.v2.scripting

/**
 * The closed outcome of a [ScriptingHost.compile] invocation.
 * Consumers branch on [Success] and [Failure] rather than coordinating a
 * boolean with nullable values. The legacy script instance is deliberately
 * separate from the evaluated [ScriptEvaluationOutput].
*/
sealed interface ScriptCompilationResult {
    val diagnostics: List<ScriptingDiagnostic>
    val cacheKey: CacheKey

    val isSuccess: Boolean
        get() = this is Success

    /** Compatibility projection for callers not yet migrated to [Success.output]. */
    val value: Any?
        get() = (this as? Success)?.output?.value

    /**
     * Host-specific technical instance retained for the quarantined legacy
     * static runner. New callers must consume [Success.output] instead.
*/
    val scriptInstance: Any?
        get() = (this as? Success)?.scriptInstance

    /**
     * The script compiled and evaluated, and it produced a value.
     *
     * [output] is the value the script yielded and the only thing a new
     * caller needs. [scriptInstance] is the host's own technical handle,
     * kept for the quarantined legacy static runner; a caller that reads it
     * depends on a host implementation this API does not promise.
     */
    data class Success(
        val output: ScriptEvaluationOutput,
        override val scriptInstance: Any?,
        override val diagnostics: List<ScriptingDiagnostic>,
        override val cacheKey: CacheKey,
    ) : ScriptCompilationResult

    /**
     * The script did not compile, or it compiled and did not evaluate.
     *
     * The diagnostics say why; a failure without one gives the caller
     * nothing to report and nothing to act on. The cache key still names
     * the inputs the failure belongs to, so the same broken inputs are
     * recognisable as the same failure.
     */
    data class Failure(
        override val diagnostics: List<ScriptingDiagnostic>,
        override val cacheKey: CacheKey,
    ) : ScriptCompilationResult
}

/** All values a successful script evaluation may expose through the public port. */
sealed interface ScriptEvaluationOutput {
    /** Compatibility projection; use the concrete case for new code. */
    val value: Any?

    /** A durable entry point that application runtime adapters may execute. */
    data class CompiledEntryPoint(val entryPoint: CompiledScriptedEntryPoint) : ScriptEvaluationOutput {
        override val value: Any = entryPoint
    }

    /** A non-null result outside the compiled-artifact contract. */
    data class ReturnedValue(override val value: Any) : ScriptEvaluationOutput

    /** An explicit `null` result, distinct from no result. */
    data object ReturnedNull : ScriptEvaluationOutput {
        override val value: Nothing? = null
    }

    /** A Kotlin `Unit` result. */
    data object Unit : ScriptEvaluationOutput {
        override val value: Nothing? = null
    }

    /** A host succeeded without a value it can expose through this port. */
    data object NoValue : ScriptEvaluationOutput {
        override val value: Nothing? = null
    }
}
