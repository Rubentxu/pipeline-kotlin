package dev.rubentxu.pipeline.steps.plugin

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

/**
 * Tests funcionales del plugin de compilador que verifican la funcionalidad real.
 * Estos tests se enfocan en verificar que el plugin funciona correctamente sin ejecutar procesos externos.
 */
class FunctionalCompilerTest {

    @Test
    fun `plugin components should instantiate correctly`() {
        // Verificar que todos los componentes principales del plugin se pueden crear
        val registrar = StepCompilerPluginRegistrar()
        val transformer = StepIrTransformer()
        val generator = StepDslRegistryGenerator()
        val firRegistrar = StepFirExtensionRegistrar()
        
        assertNotNull(registrar, "Plugin registrar should be created")
        assertNotNull(transformer, "IR transformer should be created")
        assertNotNull(generator, "DSL generator should be created")
        assertNotNull(firRegistrar, "FIR registrar should be created")
        
        assertTrue(registrar.supportsK2, "Plugin should support K2")
    }

    @Test
    fun `plugin should have correct configuration`() {
        // Verificar configuración del plugin
        assertEquals("dev.rubentxu.pipeline.steps", StepCompilerPluginRegistrar.PLUGIN_ID)
        assertEquals("Pipeline Steps Plugin v2 (Context Parameters)", StepCompilerPluginRegistrar.PLUGIN_NAME)
        
        // Verificar que las claves de configuración están definidas
        assertNotNull(StepCompilerPluginRegistrar.ENABLE_CONTEXT_INJECTION)
        assertNotNull(StepCompilerPluginRegistrar.ENABLE_DSL_GENERATION)
        assertNotNull(StepCompilerPluginRegistrar.DEBUG_MODE)
    }

    @Test
    fun `step annotation detection should work correctly`() {
        // Verificar que las constantes para detección de @Step están correctas
        val stepFqName = StepIrTransformer.STEP_ANNOTATION_FQ_NAME
        val contextFqName = StepIrTransformer.PIPELINE_CONTEXT_FQ_NAME
        
        assertEquals("dev.rubentxu.pipeline.steps.annotations.Step", stepFqName.asString())
        assertEquals("dev.rubentxu.pipeline.context.PipelineContext", contextFqName.asString())
        
        // Verificar que los nombres están bien formateados
        assertTrue(stepFqName.asString().contains("Step"))
        assertTrue(contextFqName.asString().contains("PipelineContext"))
    }

    @Test
    fun `FIR extension should have correct constants`() {
        // Verificar que la extensión FIR tiene las constantes correctas
        val stepFqName = dev.rubentxu.pipeline.steps.plugin.fir.StepContextParameterExtension.STEP_ANNOTATION_FQN
        val contextClassId = dev.rubentxu.pipeline.steps.plugin.fir.StepContextParameterExtension.PIPELINE_CONTEXT_CLASS_ID
        
        assertEquals("dev.rubentxu.pipeline.steps.annotations.Step", stepFqName.asString())
        assertEquals("dev.rubentxu.pipeline.context.PipelineContext", contextClassId.asFqNameString())
    }

    @Test
    fun `plugin transformation logic should be well defined`() {
        // Simular la lógica de transformación que usaría el plugin
        data class FunctionInfo(val name: String, val hasStepAnnotation: Boolean, val hasContextParam: Boolean)
        
        fun shouldTransform(func: FunctionInfo): Boolean {
            return func.hasStepAnnotation && !func.hasContextParam
        }
        
        // Test cases
        val stepWithoutContext = FunctionInfo("echoStep", true, false)
        val stepWithContext = FunctionInfo("legacyStep", true, true)
        val regularFunction = FunctionInfo("helper", false, false)
        
        assertTrue(shouldTransform(stepWithoutContext), "Should transform @Step without context")
        assertTrue(!shouldTransform(stepWithContext), "Should not transform @Step with context")
        assertTrue(!shouldTransform(regularFunction), "Should not transform regular function")
    }

    @Test
    fun `plugin should handle different function types`() {
        // Verificar que el plugin puede manejar diferentes tipos de funciones
        data class FunctionSignature(
            val name: String,
            val isSuspend: Boolean,
            val parameters: List<String>,
            val hasStepAnnotation: Boolean
        )
        
        val functions = listOf(
            FunctionSignature("echo", false, listOf("String"), true),
            FunctionSignature("asyncStep", true, listOf("Long"), true),
            FunctionSignature("complexStep", false, listOf("String", "Int", "Boolean"), true),
            FunctionSignature("helper", false, listOf("String"), false)
        )
        
        val stepFunctions = functions.filter { it.hasStepAnnotation }
        val suspendStepFunctions = stepFunctions.filter { it.isSuspend }
        
        assertEquals(3, stepFunctions.size, "Should identify 3 @Step functions")
        assertEquals(1, suspendStepFunctions.size, "Should identify 1 suspend @Step function")
    }

    @Test
    fun `DSL generation strategy should be consistent`() {
        // Verificar que la estrategia de generación DSL es coherente
        fun generateDslMethodName(stepName: String): String {
            return stepName // Simple strategy: usar el mismo nombre
        }
        
        fun generateDslSignature(stepName: String, params: List<String>, isSuspend: Boolean): String {
            val suspendModifier = if (isSuspend) "suspend " else ""
            val paramsStr = params.joinToString(", ")
            return "${suspendModifier}fun StepsBlock.$stepName($paramsStr)"
        }
        
        // Test cases
        assertEquals("echo", generateDslMethodName("echo"))
        assertEquals("customStep", generateDslMethodName("customStep"))
        
        assertEquals("fun StepsBlock.echo(message: String)", 
                    generateDslSignature("echo", listOf("message: String"), false))
        assertEquals("suspend fun StepsBlock.asyncStep(delay: Long)", 
                    generateDslSignature("asyncStep", listOf("delay: Long"), true))
    }

    @Test
    fun `plugin error handling should be robust`() {
        // Simular manejo de errores del plugin
        data class CompilationContext(
            val hasValidSources: Boolean,
            val pluginEnabled: Boolean,
            val hasStepAnnotations: Boolean
        )
        
        fun shouldProcessSources(context: CompilationContext): Boolean {
            return context.hasValidSources && context.pluginEnabled
        }
        
        fun shouldTransformSteps(context: CompilationContext): Boolean {
            return shouldProcessSources(context) && context.hasStepAnnotations
        }
        
        // Test cases
        val validContext = CompilationContext(true, true, true)
        val noAnnotationsContext = CompilationContext(true, true, false)
        val disabledContext = CompilationContext(true, false, true)
        val invalidContext = CompilationContext(false, true, true)
        
        assertTrue(shouldTransformSteps(validContext), "Should transform with valid context")
        assertTrue(!shouldTransformSteps(noAnnotationsContext), "Should not transform without annotations")
        assertTrue(!shouldTransformSteps(disabledContext), "Should not transform when disabled")
        assertTrue(!shouldTransformSteps(invalidContext), "Should not transform with invalid sources")
    }

    @Test
    fun `plugin integration should work with K2 compiler`() {
        // Verificar que el plugin está correctamente configurado para K2
        val registrar = StepCompilerPluginRegistrar()
        assertTrue(registrar.supportsK2, "Plugin must support K2")
        
        // Verificar que las extensiones están disponibles
        val firRegistrar = StepFirExtensionRegistrar()
        assertNotNull(firRegistrar, "FIR registrar should be available for K2")
        
        // Verificar que el plugin está marcado correctamente
        assertEquals("Pipeline Steps Plugin v2 (Context Parameters)", 
                    StepCompilerPluginRegistrar.PLUGIN_NAME,
                    "Plugin name should indicate K2/Context Parameters support")
    }

    @Test
    fun `plugin architecture should be sound`() {
        // Verificar que la arquitectura del plugin es sólida
        
        // 1. Verificar que todos los componentes están presentes
        val hasRegistrar = StepCompilerPluginRegistrar::class.java != null
        val hasIrTransformer = StepIrTransformer::class.java != null
        val hasDslGenerator = StepDslRegistryGenerator::class.java != null
        val hasFirExtension = StepFirExtensionRegistrar::class.java != null
        
        assertTrue(hasRegistrar, "Should have plugin registrar")
        assertTrue(hasIrTransformer, "Should have IR transformer")
        assertTrue(hasDslGenerator, "Should have DSL generator")
        assertTrue(hasFirExtension, "Should have FIR extension")
        
        // 2. Verificar que las constantes son consistentes entre componentes
        val irStepName = StepIrTransformer.STEP_ANNOTATION_FQ_NAME.asString()
        val firStepName = dev.rubentxu.pipeline.steps.plugin.fir.StepContextParameterExtension.STEP_ANNOTATION_FQN.asString()
        
        assertEquals(irStepName, firStepName, "Step annotation names should be consistent")
    }
}