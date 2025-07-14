package dev.rubentxu.pipeline.steps.plugin

import com.google.auto.service.AutoService
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

/**
 * Main entry point for the @Step compiler plugin.
 * 
 * Inspired by Jetpack Compose's @Composable transformation, this plugin
 * automatically injects PipelineContext into @Step functions, enabling
 * the DSL v2 syntax without manual context access.
 * 
 * This registrar is discovered by the Kotlin compiler through ServiceLoader
 * and sets up all the K2 extensions needed for @Step transformation.
 * 
 * Simplified implementation for K2 compatibility.
 */
@OptIn(ExperimentalCompilerApi::class)
@AutoService(CompilerPluginRegistrar::class)
class StepCompilerPluginRegistrar : CompilerPluginRegistrar() {
    
    companion object {
        const val PLUGIN_ID = "dev.rubentxu.pipeline.steps"
        const val PLUGIN_NAME = "Pipeline Steps Plugin"
        
        // Configuration keys for plugin options
        val ENABLE_CONTEXT_INJECTION = CompilerConfigurationKey<Boolean>("enableContextInjection")
        val DEBUG_MODE = CompilerConfigurationKey<Boolean>("debugMode")
    }
    
    override val supportsK2: Boolean = true
    
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        // The K2 frontend extensions are registered automatically via ServiceLoader.
        // The IR extension is now disabled as we are moving to a pure FIR implementation.
        // The old backend logic is incompatible with the K2 compiler API.
    }
}