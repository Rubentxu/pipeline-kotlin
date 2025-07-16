package dev.rubentxu.pipeline.steps.plugin

import com.google.auto.service.AutoService
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

/**
 * Modern FIR extension registrar for @Step compiler plugin (Kotlin 2.2+).
 * 
 * Simplified implementation that focuses on stability and basic functionality.
 * Uses only the most stable FIR APIs to avoid compatibility issues.
 */
@AutoService(FirExtensionRegistrar::class)
class StepFirExtensionRegistrar : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {
        // Modern checker extension with basic validation
        +::StepModernCheckerExtension
    }
}