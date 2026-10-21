package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.scripting.Kotlin24ScriptingHost
import dev.rubentxu.pipeline.v2.scripting.ScriptCompilationResult
import dev.rubentxu.pipeline.v2.scripting.ScriptDefinition
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import java.nio.file.Path

/**
 * What a compiled script yields: the compilation, the IR it built (if any) and the
 * failure it failed with (if any).
 *
 * The compilation is kept because the scripted frontend needs to know whether the
 * source compiled at all before it decides whether that source is generator-level.
 */
internal data class ScriptCompilation(
    val result: ScriptCompilationResult,
    val pipelineSpec: PipelineSpec?,
    val failure: RunOutcome.Failure?,
)

/**
 * Compile the script and read the IR it built.
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
 * The durable side carries the reason in its own comment (S0-C1 / TRAIN-DSL-HONESTY):
 * swallowing the construction rejection turned it into "no pipeline", the runner
 * reported it downstream as a step failure, and the author's discarded value
 * disappeared. The volatile side still swallowed it.
 *
 * That is the same fact read by two readers, and the two readers disagreed — which is
 * exactly what LF-0208 (Single Runtime Spine) forbids: the storage choice must not
 * select a different behaviour. So the compilation is now ONE reader, and it is the
 * honest one.
 *
 * ## The failure it reports
 *
 * A compilation produces a LIST of diagnostics. The failure keeps the LAST error
 * message that is not blank, because that is the one the author has to act on, and
 * falls back to "Kotlin compilation failed" when there is none.
 */
internal fun compileScriptForRun(
    eventStore: EventSink,
    runId: String,
    scriptPath: Path,
    pluginJars: List<String>,
): ScriptCompilation {
    val host = Kotlin24ScriptingHost(eventStore, runId)
    val dslClasspath = computeScriptClasspath(pluginJars)
    val definition = ScriptDefinition.file(scriptPath, classpath = dslClasspath)
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

    val failure: RunOutcome.Failure? = if (result is ScriptCompilationResult.Failure) {
        // evaluation failures (any exception thrown while the DSL
        // body constructs its IR, including the fail-closed rejections of
        // whenCondition/retry) arrive here as a Failure. The user's own diagnostic
        // message must survive instead of becoming an anonymous NPE.
        val reason = result.diagnostics
            .lastOrNull { it.severity == ScriptDiagnosticSeverity.ERROR }
            ?.message
            ?.takeIf { it.isNotBlank() }
            ?: "Kotlin compilation failed"
        RunOutcome.Failure(
            PipelineFailure(
                kind = dev.rubentxu.pipeline.v2.domain.FailureKind.SCHEMA,
                message = reason,
            ),
        )
    } else null

    return ScriptCompilation(result = result, pipelineSpec = pipelineSpec, failure = failure)
}
