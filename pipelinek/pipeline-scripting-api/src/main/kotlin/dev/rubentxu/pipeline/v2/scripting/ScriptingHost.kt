package dev.rubentxu.pipeline.v2.scripting

/**
 * Host capable of compiling and evaluating Kotlin script files.
 */
interface ScriptingHost {
    /**
     * Compiles and evaluates the given [definition].
     *
     * @return A [ScriptCompilationResult] describing whether compilation/eval
     *         succeeded, the produced value (if any), any diagnostics, and
     *         a stable [ScriptCompilationResult.cacheKey].
     */
    fun compile(definition: ScriptDefinition): ScriptCompilationResult
}
