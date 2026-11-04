package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.runtime.LiveSpanAttributes
import dev.rubentxu.pipeline.v2.runtime.LiveSpanNames
import dev.rubentxu.pipeline.v2.runtime.LiveSpanStatus
import dev.rubentxu.pipeline.v2.runtime.LiveSpans
import dev.rubentxu.pipeline.v2.runtime.MetricBoundary
import dev.rubentxu.pipeline.v2.runtime.MetricOutcome
import dev.rubentxu.pipeline.v2.runtime.MetricStatus
import dev.rubentxu.pipeline.v2.runtime.PipelineTelemetry
import dev.rubentxu.pipeline.v2.runtime.SystemRuntimeConfig
import dev.rubentxu.pipeline.v2.runtime.metricOutcomeOf
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptCache
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint
import dev.rubentxu.pipeline.v2.scripting.Kotlin24ScriptingHost
import dev.rubentxu.pipeline.v2.scripting.ScriptCompilationResult
import dev.rubentxu.pipeline.v2.scripting.ScriptDefinition
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import dev.rubentxu.pipeline.v2.scripting.ScriptEvaluationOutput
import dev.rubentxu.pipeline.v2.scripting.ScriptingHost
import java.nio.file.Path

/**
 * What a compiled script yields: the compilation, the IR it built (if any), the
 * scripted entry point it produced (if any) and the failure it failed with (if
 * any).
 *
 * ## The two shapes of a compiled script
 *
 * A `.pipeline.kts` source reaches the runner in one of two shapes, and each
 * shape carries a different product:
 *
 * | shape | what the compilation yields |
 * |---|---|
 * | the eager DSL | a [PipelineSpec], built while the script's body ran |
 * | the generator-level form | a [CompiledScriptedEntryPoint], whose body runs INSIDE the run |
 *
 * The two are alternatives, never both: a source that builds a [PipelineSpec]
 * has no entry point to offer, and a source that returns a value has no spec.
 * The fields are nullable so the absence is a fact of the shape, not a
 * silently missing value.
 */
internal data class ScriptCompilation(
    val result: ScriptCompilationResult,
    val pipelineSpec: PipelineSpec?,
    val entryPoint: CompiledScriptedEntryPoint?,
    val failure: RunOutcome.Failure?,
)

/**
 * Compile the script of a run and read the IR it built.
 *
 * ## Why this is one function and not two
 *
 * The two run worlds used to compile the script THEMSELVES, and they did not agree on
 * what to do when the script's body threw while it was building its IR:
 *
 * | world | what it did |
 * |---|---|
 * | durable | unwrapped the reflective wrapper and **rethrew** the author's exception, so the author read their own message |
 * | volatile | swallowed every exception and answered "no pipeline" |
 *
 * The durable side carries the reason in its own comment:
 * swallowing the construction rejection turned it into "no pipeline", the runner
 * reported it downstream as a step failure, and the author's discarded value
 * disappeared. The volatile side still swallowed it.
 *
 * That is the same fact read by two readers, and the two readers disagreed — which is
 * exactly what (Single Runtime Spine) forbids: the storage choice must not
 * select a different behaviour. So the compilation is now ONE reader, and it is the
 * honest one.
 *
 * ## The failure it reports
 *
 * A compilation produces a LIST of diagnostics. The failure keeps the LAST error
 * message that is not blank, because that is the one the author has to act on, and
 * falls back to "Kotlin compilation failed" when there is none.
 *
 * ## The live span it opens
 *
 * The compilation is a boundary of the run, so it opens a span of its own: the
 * time a script took to compile is a fact an operator asks for, and it is
 * invisible if the only visible boundary is the run as a whole. The span ends
 * with the compilation's own verdict — a script that did not compile never
 * reaches the run's span, so this is the only place where that fact appears.
 *
 * ## The metric it records
 *
 * The span says what happened to ONE compilation; the metric says how the
 * compilations of many runs went. The compilation is measured as a boundary
 * of its own because it is a boundary of its own: a script that takes thirty
 * seconds to compile is a fact about the script, not about the run it starts.
 *
 * A compilation that threw instead of failing is recorded as an unclassified
 * ending. The product does not turn the author's exception into a failure
 * kind here — it rethrows it so the author reads their own message — so
 * claiming a class would be a claim the product did not make.
 */
internal fun compileScriptForRun(
    eventStore: EventSink,
    runId: String,
    scriptPath: Path,
    pluginJars: List<String>,
    telemetry: PipelineTelemetry,
    cache: CompiledScriptCache = CompiledScriptCache.fromEnvironment(),
): ScriptCompilation = measureCompilation(runId, telemetry) {
    val host = Kotlin24ScriptingHost(eventStore, runId, cache)
    val dslClasspath = computeScriptClasspath(pluginJars)
    val definition = ScriptDefinition.file(scriptPath, classpath = dslClasspath)
    compileEagerDefinition(host, definition)
}

/**
 * Compile a script whose text is already PREPARED — the lowered source of a
 * generator-level program — and read the entry point it produced.
 *
 * ## Why the prepared text is compiled and not the original
 *
 * The generator-level form is a program whose body runs INSIDE the run: its
 * runtime calls are rewritten onto the scripted facade, and the body is
 * suspended around each of them. The text that expresses that is the LOWERED
 * source, and the lowered source is the program that runs.
 *
 * Compiling the ORIGINAL as well meant compiling a program nobody runs, paying
 * for it twice, and — worse — evaluating the author's body at compile time, so
 * a runtime call happened once before the run and once inside it. One host,
 * one compilation, one evaluation: the compiled artifact that is cached is the
 * compiled artifact that runs.
 */
internal fun compileScriptedForRun(
    eventStore: EventSink,
    runId: String,
    definition: ScriptDefinition,
    telemetry: PipelineTelemetry,
    cache: CompiledScriptCache = CompiledScriptCache.fromEnvironment(),
): ScriptCompilation = measureCompilation(runId, telemetry) {
    val host = Kotlin24ScriptingHost(eventStore, runId, cache)
    compileScriptedDefinition(host, definition)
}

/**
 * The boundary every compilation of a run is measured by.
 *
 * The span and the metric are the same for every shape of script, because the
 * question they answer is the same: how long did compiling this script take,
 * and did it succeed. A shape that measured itself differently would answer a
 * different question, and the operator would be comparing two different facts.
 */
private fun measureCompilation(
    runId: String,
    telemetry: PipelineTelemetry,
    compile: () -> ScriptCompilation,
): ScriptCompilation {
    val span = LiveSpans.open(
        telemetry = telemetry,
        name = LiveSpanNames.COMPILE,
        parent = telemetry.sessionParent,
        attributes = listOf(LiveSpanAttributes.runId(runId)),
    )
    val measurement = telemetry.metrics.start(MetricBoundary.Compile)
    val compilation = try {
        compile()
    } catch (throwable: Throwable) {
        measurement?.end(MetricOutcome(MetricStatus.Unknown))
        LiveSpans.end(span, LiveSpanStatus.Failed)
        throw throwable
    }
    val outcome = compilation.failure?.let { metricOutcomeOf(it) } ?: MetricOutcome(MetricStatus.Success)
    measurement?.end(outcome)
    LiveSpans.end(
        span,
        if (compilation.failure == null) LiveSpanStatus.Completed else LiveSpanStatus.Failed,
    )
    return compilation
}

/**
 * The eager compilation itself, without the live span around it.
 *
 * The body is the reader of the script; the span above is the observation of
 * that reading. Splitting them keeps the reader's own logic — the reflective
 * unwrapping, the diagnostic choice — free of the observation, so a change in
 * one does not have to be reasoned about in the other.
 */
private fun compileEagerDefinition(host: ScriptingHost, definition: ScriptDefinition): ScriptCompilation {
    // Inject the production RuntimeConfig so DSL `pwd()` / `isUnix()` synchronous
    // return values reflect the host environment. :pipeline-scripting-api must read
    // no global state; this scope is the single bridge. Cleared in a finally block.
    dev.rubentxu.pipeline.v2.dsl.DslRuntimeConfigScope.set(SystemRuntimeConfig())
    val result = try {
        host.compile(definition)
    } finally {
        dev.rubentxu.pipeline.v2.dsl.DslRuntimeConfigScope.clear()
    }

    val pipelineSpec: PipelineSpec? = if (result.isSuccess) {
        val scriptInstance = result.scriptInstance
        scriptInstance?.let { inst ->
            try {
                val resultMethod = inst.javaClass.getMethod("get\$\$result")
                @Suppress("UNCHECKED_CAST")
                resultMethod.invoke(inst) as? PipelineSpec
            } catch (invocationError: java.lang.reflect.InvocationTargetException) {
                // `get$$result` runs the script BODY, so this is where a fail-closed DSL
                // guard fires (e.g. the Pure Builder Consumption gate rejecting an
                // unconsumed MUST_CONSUME carrier). Swallowing it into `null` turned a
                // construction rejection into "no pipeline", which the runner then
                // reported downstream as a runtime step failure — the author's discarded
                // value disappeared and a StepFailed appeared instead.
                //
                // Unwrap the reflective wrapper so the real, actionable message reaches
                // the author verbatim.
                throw (invocationError.targetException ?: invocationError)
            }
        }
    } else null

    return ScriptCompilation(
        result = result,
        pipelineSpec = pipelineSpec,
        entryPoint = null,
        failure = failureOf(result),
    )
}

/**
 * The scripted compilation itself, without the live span around it.
 *
 * No RuntimeConfig is injected: the body of a generator-level program reads
 * the platform through the scripted facade, inside the run, and a
 * runtime-returned body must never observe the platform through the eager
 * DSL port.
 */
private fun compileScriptedDefinition(host: ScriptingHost, definition: ScriptDefinition): ScriptCompilation {
    val result = host.compile(definition)
    val entryPoint: CompiledScriptedEntryPoint? = if (result is ScriptCompilationResult.Success) {
        when (val output = result.output) {
            is ScriptEvaluationOutput.CompiledEntryPoint -> output.entryPoint
            else -> null
        }
    } else null
    return ScriptCompilation(
        result = result,
        pipelineSpec = null,
        entryPoint = entryPoint,
        failure = failureOf(result),
    )
}

/**
 * The failure a failed compilation reports.
 *
 * evaluation failures (any exception thrown while the DSL
 * body constructs its IR, including the fail-closed rejections of
 * whenCondition/retry) arrive here as a Failure. The user's own diagnostic
 * message must survive instead of becoming an anonymous NPE.
 */
private fun failureOf(result: ScriptCompilationResult): RunOutcome.Failure? {
    if (result !is ScriptCompilationResult.Failure) return null
    val reason = result.diagnostics
        .lastOrNull { it.severity == ScriptDiagnosticSeverity.ERROR }
        ?.message
        ?.takeIf { it.isNotBlank() }
        ?: "Kotlin compilation failed"
    return RunOutcome.Failure(
        PipelineFailure(
            kind = FailureKind.SCHEMA,
            message = reason,
        ),
    )
}
