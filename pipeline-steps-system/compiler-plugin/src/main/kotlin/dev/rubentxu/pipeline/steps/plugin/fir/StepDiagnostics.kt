package dev.rubentxu.pipeline.steps.plugin.fir

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.rendering.RootDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.psi.KtParameter

object StepDiagnostics {
    val MANUAL_PIPELINE_CONTEXT_PARAMETER by error1<KtParameter, String>()

    private fun <P : Any> error1(positioningStrategy: PositioningStrategy<in P>? = null): KtDiagnosticFactory1.Builder<P, String> {
        return KtDiagnosticFactory1.Builder("STEP_PLUGIN", Severity.ERROR, positioningStrategy)
    }

    init {
        RootDiagnosticRendererFactory.registerFactory(StepPluginErrors)
    }
}

object StepPluginErrors : RootDiagnosticRendererFactory() {
    override val renderers = mapOf(
        StepDiagnostics.MANUAL_PIPELINE_CONTEXT_PARAMETER to "Manual PipelineContext parameter is not allowed. The context is implicitly provided by the compiler plugin."
    )
}