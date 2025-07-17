package dev.rubentxu.pipeline.steps.plugin

import com.google.auto.service.AutoService
import dev.rubentxu.pipeline.steps.plugin.logging.PluginEvent
import dev.rubentxu.pipeline.steps.plugin.logging.StructuredLogger
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

/**
 * Modern compiler plugin registrar for @Step functions (Kotlin 2.2+).
 * 
 * This registrar sets up both FIR (frontend) and IR (backend) extensions
 * to provide complete @Step functionality with Context Parameters.
 * 
 * Features:
 * - Context Parameters integration (FIR)
 * - Automatic DSL generation (IR)
 * - Step registry creation (IR)
 * - Unified validation (FIR)
 */
@OptIn(ExperimentalCompilerApi::class)
@AutoService(CompilerPluginRegistrar::class)
class StepCompilerPluginRegistrar : CompilerPluginRegistrar() {
    
    companion object {
        const val PLUGIN_ID = "dev.rubentxu.pipeline.steps"
        const val PLUGIN_NAME = "Pipeline Steps Plugin v2 (Context Parameters)"
        
        // Configuration keys for plugin options
        val ENABLE_CONTEXT_INJECTION = CompilerConfigurationKey<Boolean>("enableContextInjection")
        val ENABLE_DSL_GENERATION = CompilerConfigurationKey<Boolean>("enableDslGeneration")
        val DEBUG_MODE = CompilerConfigurationKey<Boolean>("debugMode")
    }
    
    override val supportsK2: Boolean = true
    
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        // Configure structured logging
        StructuredLogger.configure(configuration)
        
        StructuredLogger.logPluginEvent(PluginEvent.PLUGIN_REGISTERED, mapOf(
            "plugin_id" to PLUGIN_ID,
            "plugin_name" to PLUGIN_NAME,
            "supports_k2" to supportsK2
        ))
        
        // FIR (frontend) extensions are registered automatically via ServiceLoader
        // through StepFirExtensionRegistrar
        
        // IR (backend) extensions for code transformation and generation
        val enableContextInjection = configuration.get(ENABLE_CONTEXT_INJECTION, true)
        val enableDslGeneration = configuration.get(ENABLE_DSL_GENERATION, true)
        val debugMode = configuration.get(DEBUG_MODE, false)
        
        StructuredLogger.logPluginEvent(PluginEvent.PLUGIN_CONFIGURED, mapOf(
            "enable_context_injection" to enableContextInjection,
            "enable_dsl_generation" to enableDslGeneration,
            "debug_mode" to debugMode
        ))
        
        if (enableContextInjection) {
            IrGenerationExtension.registerExtension(StepIrTransformer())
            StructuredLogger.logPluginEvent(PluginEvent.IR_TRANSFORMATION_STARTED, mapOf(
                "extension" to "StepIrTransformerStable",
                "purpose" to "context_parameter_injection"
            ))
        }
        
        if (enableDslGeneration) {
            IrGenerationExtension.registerExtension(StepDslRegistryGenerator())
            StructuredLogger.logPluginEvent(PluginEvent.DSL_GENERATION_STARTED, mapOf(
                "extension" to "StepDslRegistryGenerator",
                "purpose" to "automatic_dsl_generation"
            ))
        }
    }
}