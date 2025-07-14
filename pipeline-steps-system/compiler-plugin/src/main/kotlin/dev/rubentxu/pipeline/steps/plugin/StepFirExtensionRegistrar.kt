package dev.rubentxu.pipeline.steps.plugin

import com.google.auto.service.AutoService
import com.google.auto.service.AutoService
import dev.rubentxu.pipeline.steps.plugin.fir.StepAdditionalCheckersExtension
import dev.rubentxu.pipeline.steps.plugin.fir.StepExpressionResolutionExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

/**
 * Registers all FIR extensions for the @Step compiler plugin.
 *
 * The logic is being rebuilt from scratch using TDD.
 */
@AutoService(FirExtensionRegistrar::class)
class StepFirExtensionRegistrar : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {
        // Register the new FIR extension for expression resolution.
        +::StepExpressionResolutionExtension
        +::StepAdditionalCheckersExtension
    }
}