package com.pipeline.v2.scripting

/**
 * Severity level for a [ScriptingDiagnostic], modelled after
 * [kotlin.script.experimental.api.ScriptDiagnostic.Severity].
 */
enum class ScriptDiagnosticSeverity {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
    FATAL,
}

/**
 * A diagnostic (warning, error, info) produced during script compilation
 * or evaluation.
 */
data class ScriptingDiagnostic(
    val severity: ScriptDiagnosticSeverity,
    val message: String,
    val line: Int,
    val column: Int,
    val path: String,
)
