package dev.rubentxu.pipeline.steps.plugin.services

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import dev.rubentxu.pipeline.steps.plugin.StepFirExtensionRegistrar
import dev.rubentxu.pipeline.steps.plugin.StepIrTransformer

class ExtensionRegistrarConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
        module: TestModule,
        configuration: CompilerConfiguration
    ) {
        // Register real compiler plugin extensions for testing
        FirExtensionRegistrarAdapter.registerExtension(StepFirExtensionRegistrar())
        IrGenerationExtension.registerExtension(StepIrTransformer())
        // Note: StepDslRegistryGenerator temporarily disabled due to API compatibility issues
    }
}