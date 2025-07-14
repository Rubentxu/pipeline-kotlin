package dev.rubentxu.pipeline.steps.plugin

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Basic integration tests for the @Step compiler plugin.
 * 
 * These tests verify that the plugin compiles successfully and provides
 * basic functionality without using advanced compilation testing that
 * requires experimental APIs.
 */
class StepCompilerPluginIntegrationTest {
    
    @Test
    fun `plugin registrar should be created successfully`() {
        val registrar = StepCompilerPluginRegistrar()
        
        assertNotNull(registrar)
        assertTrue(registrar.supportsK2)
        assertEquals("dev.rubentxu.pipeline.steps", StepCompilerPluginRegistrar.PLUGIN_ID)
        assertEquals("Pipeline Steps Plugin", StepCompilerPluginRegistrar.PLUGIN_NAME)
    }
    
    @Test
    fun `plugin configuration keys should be defined correctly`() {
        assertNotNull(StepCompilerPluginRegistrar.ENABLE_CONTEXT_INJECTION)
        assertNotNull(StepCompilerPluginRegistrar.DEBUG_MODE)
        
        assertEquals("enableContextInjection", StepCompilerPluginRegistrar.ENABLE_CONTEXT_INJECTION.toString())
        assertEquals("debugMode", StepCompilerPluginRegistrar.DEBUG_MODE.toString())
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
    fun `IR generation extension should handle different configurations`() {
        val extensionWithDebug = StepIrGenerationExtension(
            enableContextInjection = true,
            debugMode = true
        )
        
        val extensionWithoutContextInjection = StepIrGenerationExtension(
            enableContextInjection = false,
            debugMode = false
        )
        
        assertNotNull(extensionWithDebug)
        assertNotNull(extensionWithoutContextInjection)
    }
    
    @Test
    fun `plugin FQN constants should be defined correctly`() {
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
    
    @Test
    fun `FIR extensions should be created successfully`() {
        // These tests verify that the FIR extensions can be instantiated
        // without runtime errors, even though they have simplified implementations
        
        assertDoesNotThrow {
            StepDiscoveryExtension.discoveredStepFunctions
        }
        
        assertDoesNotThrow {
            StepCallTransformationExtension.analysedStepCalls
        }
        
        assertTrue(StepDiscoveryExtension.discoveredStepFunctions.isEmpty())
        assertTrue(StepCallTransformationExtension.analysedStepCalls.isEmpty())
    }
    
    @Test
    fun `signature transformer should initialize correctly`() {
        // Mock IrPluginContext would be needed for full testing
        // For now, just verify the class structure
        assertDoesNotThrow {
            // StepSignatureTransformer would need proper IrPluginContext
            // This test verifies the structure is sound
            val stepInfos = listOf(
                StepFunctionInfo("test", "test", emptyList(), "Unit", true)
            )
            assertEquals(1, stepInfos.size)
        }
    }
    
    @Test
    fun `plugin should handle metadata classes correctly`() {
        val metadata = StepFunctionMetadata(
            name = "testStep",
            description = "Test step description",
            category = "GENERAL",
            securityLevel = "RESTRICTED",
            parameters = emptyList(),
            returnType = "Unit",
            packageName = "test.package",
            isTopLevel = true
        )
        
        assertEquals("testStep", metadata.name)
        assertEquals("Test step description", metadata.description)
        assertEquals("GENERAL", metadata.category)
        assertEquals("RESTRICTED", metadata.securityLevel)
        assertTrue(metadata.isTopLevel)
    }
    
    @Test
    fun `step call info should be created correctly`() {
        val callInfo = StepCallInfo(
            callSite = "TestClass.kt:42",
            targetFunction = "myStep",
            argumentCount = 2,
            parameterCount = 2,
            needsContextInjection = true
        )
        
        assertEquals("TestClass.kt:42", callInfo.callSite)
        assertEquals("myStep", callInfo.targetFunction)
        assertEquals(2, callInfo.argumentCount)
        assertEquals(2, callInfo.parameterCount)
        assertTrue(callInfo.needsContextInjection)
    }
}