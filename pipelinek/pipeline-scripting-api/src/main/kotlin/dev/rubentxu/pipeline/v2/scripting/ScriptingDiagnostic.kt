package dev.rubentxu.pipeline.v2.scripting

import kotlinx.serialization.Serializable

/**
 * Severity level for a [ScriptingDiagnostic], modelled after
 * `ScriptDiagnostic.Severity`.
*/
@Serializable
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
@Serializable
data class ScriptingDiagnostic(
    val severity: ScriptDiagnosticSeverity,
    val message: String,
    val line: Int,
    val column: Int,
    val path: String,
)
