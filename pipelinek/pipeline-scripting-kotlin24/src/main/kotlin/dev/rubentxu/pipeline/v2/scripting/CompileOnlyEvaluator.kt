package dev.rubentxu.pipeline.v2.scripting

import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.ScriptEvaluator

/**
 * An evaluator that COMPILES and never RUNS.
 *
 * ## Why the swap is at the evaluator and nowhere else
 *
 * A `.pipeline.kts` has to be compiled the way the product compiles it — with
 * the DSL's default imports, the per-call classpath and the product's own
 * compiler options — or "it compiles" would be a claim about a DIFFERENT
 * compilation than the one a run performs. That configuration is built in
 * one place, from the published definition of a `.pipeline.kts`
 * (`dev.rubentxu.pipeline.v2.scripting.definition.PipelineScript`), and both
 * paths of [Kotlin24ScriptingHost] share it.
 *
 * So the compilation is left EXACTLY as it was, and the only thing replaced
 * is the evaluator: the step that would RUN the author's body. The body is
 * the author's code; not running it is the whole promise of a compile-only
 * validation, and this class is the one place where that promise is kept.
 *
 * ## What it answers
 *
 * [ResultValue.NotEvaluated] is upstream's own name for "compiled, not
 * evaluated": a singleton with no script class and no script instance (read
 * out of the shipped bytecode, not assumed). The host maps it to
 * `ScriptEvaluationOutput.NoValue`, which is the honest projection — there
 * IS no value, because nothing was evaluated to produce one.
 *
 * ## What it deliberately does NOT do
 *
 * It does not load the artifact it is handed, it does not instantiate the
 * script class, and it does not report a diagnostic. A compile-only
 * compilation reports the COMPILER's diagnostics and nothing else: inventing
 * one here would report a problem the author cannot have.
 */
internal class CompileOnlyEvaluator : ScriptEvaluator {

    override suspend fun invoke(
        script: CompiledScript,
        evaluationConfiguration: ScriptEvaluationConfiguration,
    ): ResultWithDiagnostics<EvaluationResult> = ResultWithDiagnostics.Success(
        EvaluationResult(ResultValue.NotEvaluated, evaluationConfiguration),
        emptyList(),
    )
}
