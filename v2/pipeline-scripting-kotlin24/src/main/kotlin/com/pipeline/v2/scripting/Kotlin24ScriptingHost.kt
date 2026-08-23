package com.pipeline.v2.scripting

import com.pipeline.v2.events.CompilationFinished
import com.pipeline.v2.events.CompilationStarted
import com.pipeline.v2.events.DomainEvent
import com.pipeline.v2.events.EventSink
import com.pipeline.v2.events.NullEventSink
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * Kotlin 2.4.10 adapter that wraps [BasicJvmScriptingHost] and
 * exposes the [ScriptingHost] contract from `:pipeline-scripting-api`.
 *
 * Design contract (see design.md §"Adapter shape"):
 *  - Uses the canonical V1 pattern: `createJvmCompilationConfigurationFromTemplate<Any>`
 *    which resolves to the built-in Kotlin script definition (no custom
 *    `@KotlinScript` annotation needed — the file extension is conveyed
 *    via [SourceCodeFactory] producing a [kotlin.script.experimental.host.FileScriptSource]
 *    whose `name` is the file's basename; the host recognises the
 *    `.pipeline.kts` extension through that).
 *  - Builds the classpath via `jvm { dependenciesFromCurrentContext() }`
 *    (default `wholeClasspath = false` — i.e. the current compilation
 *    context classpath only: kotlin-stdlib, kotlin-script-runtime,
 *    kotlin-reflect, the scripting-jvm-host artifacts on this module's
 *    compile classpath). No `wholeClasspath = true` appears anywhere in
 *    production.
 *  - Per-call jars supplied via [ScriptDefinition.classpath] are appended
 *    through `jvm { updateClasspath(files) }` inside the eval body.
 *  - Returns a stable [ScriptCompilationResult.cacheKey] computed from
 *    sha256(scriptText | sortedClasspath | kotlinVersion | hostVersion).
 *  - Maps [ScriptDiagnostic] fields 1:1 to [ScriptingDiagnostic] so the
 *    editor/UAT harness can render source-mapped errors.
 */
class Kotlin24ScriptingHost(
    private val eventSink: EventSink = NullEventSink,
    private val runId: String? = null,
) : ScriptingHost {

    private val host = BasicJvmScriptingHost()

    /** Kotlin language version fed into the cache key. */
    private val kotlinVersion = "2.4.10"

    /** Host implementation version fed into the cache key. */
    private val hostVersion = "1.0.0"

    override fun compile(definition: ScriptDefinition): ScriptCompilationResult {
        val effectiveRunId = runId ?: definition.sourcePath?.fileName?.toString() ?: UUID.randomUUID().toString()
        val compilationStartedId = UUID.randomUUID().toString()
        val compilationFinishedId = UUID.randomUUID().toString()
        val compilationStartedAt = Instant.now()

        eventSink.append(
            CompilationStarted(
                eventId = compilationStartedId,
                runId = effectiveRunId,
                sequence = 0L,
                occurredAt = compilationStartedAt,
            )
        )

        val source: SourceCode = SourceCodeFactory.toSourceCode(definition)

        // Per-call classpath files from the script definition (may be empty).
        // We resolve to absolute canonical paths so the cache key stays stable
        // across relative/absolute invocations of the same logical script.
        val classpathFiles = definition.classpath.map { File(it).canonicalFile }
        val sortedClasspath = classpathFiles.map { it.canonicalPath }.sorted().joinToString(",")

        // Base compilation configuration: template defaults (kotlin-stdlib,
        // scripting runtime, reflect) plus the current context's classpath
        // at `wholeClasspath = false` (the default). Per-call jars are
        // appended via `updateClasspath` when present.
        val compilationConfig: ScriptCompilationConfiguration =
            createJvmCompilationConfigurationFromTemplate<Any>(
                body = {
                    jvm {
                        dependenciesFromCurrentContext()
                        if (classpathFiles.isNotEmpty()) {
                            updateClasspath(classpathFiles)
                        }
                    }
                }
            )

        val evaluationConfig: ScriptEvaluationConfiguration = ScriptEvaluationConfiguration {}

        val rwd: ResultWithDiagnostics<*> = host.eval(
            source,
            compilationConfig,
            evaluationConfig
        )

        val compilationFinishedAt = Instant.now()

        val diagnostics = rwd.reports
            .filter { it.severity >= ScriptDiagnostic.Severity.INFO }
            .map(::mapDiagnostic)
        val isSuccess = rwd is ResultWithDiagnostics.Success

        val scriptText = definition.sourceText
            ?: definition.sourcePath?.toFile()?.readText()
            ?: ""

        val cacheKey = CacheKey(
            CacheKey.sha256Hex(scriptText, sortedClasspath, kotlinVersion, hostVersion),
            CacheKey.V1,
        )

        val result = ScriptCompilationResult(
            isSuccess = isSuccess,
            value = if (rwd is ResultWithDiagnostics.Success) {
                @Suppress("UNCHECKED_CAST")
                val evalResult = rwd.value as kotlin.script.experimental.api.EvaluationResult
                evalResult.returnValue.scriptInstance
            } else null,
            diagnostics = diagnostics,
            cacheKey = cacheKey,
        )

        eventSink.append(
            CompilationFinished(
                eventId = compilationFinishedId,
                runId = effectiveRunId,
                sequence = 0L,
                occurredAt = compilationFinishedAt,
                cacheKey = cacheKey,
                diagnostics = diagnostics,
            )
        )

        return result
    }

    private fun mapDiagnostic(diag: ScriptDiagnostic): ScriptingDiagnostic {
        val severity = when (diag.severity) {
            ScriptDiagnostic.Severity.DEBUG -> ScriptDiagnosticSeverity.DEBUG
            ScriptDiagnostic.Severity.INFO -> ScriptDiagnosticSeverity.INFO
            ScriptDiagnostic.Severity.WARNING -> ScriptDiagnosticSeverity.WARNING
            ScriptDiagnostic.Severity.ERROR -> ScriptDiagnosticSeverity.ERROR
            ScriptDiagnostic.Severity.FATAL -> ScriptDiagnosticSeverity.FATAL
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