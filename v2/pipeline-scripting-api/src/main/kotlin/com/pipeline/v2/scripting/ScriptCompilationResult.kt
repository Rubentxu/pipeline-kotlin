package com.pipeline.v2.scripting

/**
 * The result of a [ScriptingHost.compile] invocation.
 *
 * @property isSuccess True when the script compiled and evaluated without fatal errors.
 * @property value The returned value from the script evaluation, if any.
 * @property diagnostics List of diagnostics (errors, warnings, info) from compilation/eval.
 * @property cacheKey Stable SHA-256 hex digest of (scriptText | sortedClasspath | kotlinVersion | hostVersion).
 */
data class ScriptCompilationResult(
    val isSuccess: Boolean,
    val value: Any?,
    val diagnostics: List<ScriptingDiagnostic>,
    val cacheKey: String,
)
