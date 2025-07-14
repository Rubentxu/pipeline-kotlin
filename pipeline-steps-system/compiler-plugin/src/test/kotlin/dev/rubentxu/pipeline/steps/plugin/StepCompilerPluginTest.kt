package dev.rubentxu.pipeline.steps.plugin

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Basic tests for the @Step compiler plugin.
 * 
 * These tests verify that the plugin:
 * 1. Compiles without errors
 * 2. Registers extensions correctly
 * 3. Provides basic functionality
 */
class StepCompilerPluginTest {
    
    @Test
    fun `plugin registrar should have correct plugin ID`() {
        val registrar = StepCompilerPluginRegistrar()
        assertEquals("dev.rubentxu.pipeline.steps", StepCompilerPluginRegistrar.PLUGIN_ID)
    }
    
    @Test
    fun `plugin registrar should support K2`() {
        val registrar = StepCompilerPluginRegistrar()
        assertTrue(registrar.supportsK2)
    }
    
    @Test
    fun `plugin name should be correct`() {
        assertEquals("Pipeline Steps Plugin", StepCompilerPluginRegistrar.PLUGIN_NAME)
    }
    
    @Test
    fun `IR generation extension should initialize correctly`() {
        val extension = StepIrGenerationExtension(
            enableContextInjection = true,
            debugMode = false
        )
        
        assertNotNull(extension)
    }
    
    @Test
    fun `IR generation extension should handle debug mode`() {
        val extensionWithDebug = StepIrGenerationExtension(
            enableContextInjection = true,
            debugMode = true
        )
        
        val extensionWithoutDebug = StepIrGenerationExtension(
            enableContextInjection = true,
            debugMode = false
        )
        
        assertNotNull(extensionWithDebug)
        assertNotNull(extensionWithoutDebug)
    }
    
    @Test
    fun `plugin constants should be defined correctly`() {
        assertEquals(
            "dev.rubentxu.pipeline.steps.annotations.Step", 
            StepIrGenerationExtension.STEP_ANNOTATION_FQN.asString()
        )
        
        assertEquals(
            "dev.rubentxu.pipeline.context.PipelineContext", 
            StepIrGenerationExtension.PIPELINE_CONTEXT_FQN.asString()
        )
        
        assertEquals(
            "dev.rubentxu.pipeline.dsl.StepsBlock", 
            StepIrGenerationExtension.STEPS_BLOCK_FQN.asString()
        )
    }
    
    @Test
    fun `step function info should be created correctly`() {
        val stepInfo = StepFunctionInfo(
            name = "testStep",
            functionName = "testStep",
            parameters = emptyList(),
            returnType = "Unit",
            isTopLevel = true
        )
        
        assertEquals("testStep", stepInfo.name)
        assertEquals("testStep", stepInfo.functionName)
        assertEquals(emptyList<StepParameterInfo>(), stepInfo.parameters)
        assertEquals("Unit", stepInfo.returnType)
        assertTrue(stepInfo.isTopLevel)
    }
    
    @Test
    fun `step parameter info should be created correctly`() {
        val paramInfo = StepParameterInfo(
            name = "param1",
            type = "String",
            hasDefault = false
        )
        
        assertEquals("param1", paramInfo.name)
        assertEquals("String", paramInfo.type)
        assertFalse(paramInfo.hasDefault)
    }
}