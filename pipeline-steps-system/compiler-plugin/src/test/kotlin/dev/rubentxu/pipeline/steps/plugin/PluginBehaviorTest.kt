package dev.rubentxu.pipeline.steps.plugin

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Tests más específicos del plugin usando un enfoque directo
 */
class PluginBehaviorTest {

    @Test
    fun `plugin registrar should be accessible`() {
        val registrar = StepCompilerPluginRegistrar()
        assertTrue(registrar.supportsK2, "Plugin should support K2")
    }

    @Test
    fun `step IR transformer should be instantiable`() {
        val transformer = StepIrTransformer()
        // Si llega aquí sin excepción, el transformer es válido
        assertTrue(true, "IR transformer should instantiate successfully")
    }

    @Test
    fun `step DSL generator should be instantiable`() {
        val generator = StepDslRegistryGenerator()
        // Si llega aquí sin excepción, el generator es válido
        assertTrue(true, "DSL generator should instantiate successfully")
    }

    @Test
    fun `FIR extension registrar should be accessible`() {
        val registrar = StepFirExtensionRegistrar()
        // Si llega aquí sin excepción, el registrar es válido
        assertTrue(true, "FIR extension registrar should instantiate successfully")
    }

    @Test
    fun `plugin constants should be defined correctly`() {
        // Verificar que las constantes del plugin estén bien definidas
        assertTrue(StepCompilerPluginRegistrar.PLUGIN_ID.isNotEmpty(), "Plugin ID should not be empty")
        assertTrue(StepCompilerPluginRegistrar.PLUGIN_NAME.isNotEmpty(), "Plugin name should not be empty")

        // Verificar FQ names
        assertTrue(StepIrTransformer.STEP_ANNOTATION_FQ_NAME.asString().contains("Step"))
        assertTrue(StepIrTransformer.PIPELINE_CONTEXT_FQ_NAME.asString().contains("PipelineContext"))
    }
}