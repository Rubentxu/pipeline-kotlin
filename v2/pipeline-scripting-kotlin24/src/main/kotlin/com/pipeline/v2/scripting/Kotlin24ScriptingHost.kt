package com.pipeline.v2.scripting

import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.host.FileScriptSource
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.api.ScriptCompilationConfiguration

/**
 * Kotlin 2.4.10 adapter that wraps [BasicJvmScriptingHost] and
 * exposes the [ScriptingHost] contract from `:pipeline-scripting-api`.
 *
 * Key design decisions:
 * - Uses [updateClasspath] for explicit classpath,
 *   NEVER `wholeClasspath = true`.
 * - Returns a stable [ScriptCompilationResult.cacheKey] computed from
 *   sha256(scriptText | sortedClasspath | kotlinVersion | hostVersion).
 * - Maps [ScriptDiagnostic] fields 1:1 to [ScriptingDiagnostic].
 */
class Kotlin24ScriptingHost : ScriptingHost {

    private val host = BasicJvmScriptingHost()

    /** Kotlin language version fed into the cache key. */
    private val kotlinVersion = "2.4.10"

    /** Host implementation version fed into the cache key. */
    private val hostVersion = "1.0.0"

    override fun compile(definition: ScriptDefinition): ScriptCompilationResult {
        val source: SourceCode = SourceCodeFactory.toSourceCode(definition)

        // Build explicit classpath from definition.classpath
        val classpathFiles = definition.classpath.map { java.io.File(it) }

        // Use updateClasspath for explicit classpath — NEVER wholeClasspath
        // Build configuration from scratch without a template
        val cfg = ScriptCompilationConfiguration {
            updateClasspath(classpathFiles)
        }

        val evalCfg = ScriptEvaluationConfiguration {}

        // Compile and evaluate in one step using eval (synchronous)
        val rwd = host.eval(source, cfg, evalCfg)

        val diagnostics: List<ScriptingDiagnostic> = rwd.reports.map { diag ->
            mapDiagnostic(diag)
        }

        val isSuccess = rwd is ResultWithDiagnostics.Success

        val scriptText = definition.sourceText
            ?: definition.sourcePath?.toFile()?.readText()
            ?: ""

        val cacheKey = CacheKey.sha256Hex(
            scriptText,
            classpathFiles.map { it.canonicalPath }.sorted().joinToString(","),
            kotlinVersion,
            hostVersion
        )

        val value: Any? = if (rwd is ResultWithDiagnostics.Success) {
            rwd.value.returnValue.scriptInstance
        } else {
            null
        }

        return ScriptCompilationResult(
            isSuccess = isSuccess,
            value = value,
            diagnostics = diagnostics,
            cacheKey = cacheKey
        )
    }

    private fun mapDiagnostic(diag: kotlin.script.experimental.api.ScriptDiagnostic): ScriptingDiagnostic {
        val severity = when (diag.severity) {
            kotlin.script.experimental.api.ScriptDiagnostic.Severity.DEBUG -> ScriptDiagnosticSeverity.DEBUG
            kotlin.script.experimental.api.ScriptDiagnostic.Severity.INFO -> ScriptDiagnosticSeverity.INFO
            kotlin.script.experimental.api.ScriptDiagnostic.Severity.WARNING -> ScriptDiagnosticSeverity.WARNING
            kotlin.script.experimental.api.ScriptDiagnostic.Severity.ERROR -> ScriptDiagnosticSeverity.ERROR
            kotlin.script.experimental.api.ScriptDiagnostic.Severity.FATAL -> ScriptDiagnosticSeverity.FATAL
        }

        val location = diag.location
        val line = location?.start?.line ?: 0
        val column = location?.start?.col ?: 0
        val path = diag.sourcePath ?: "<synthetic>"

        return ScriptingDiagnostic(
            severity = severity,
            message = diag.message,
            line = line,
            column = column,
            path = path
        )
    }
}
