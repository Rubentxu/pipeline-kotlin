package dev.rubentxu.pipeline.steps.plugin

import com.google.auto.service.AutoService
import dev.rubentxu.pipeline.steps.plugin.fir.StepContextParameterExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

/**
 * Modern FIR extension registrar for @Step compiler plugin (Kotlin 2.2+).
 * 
 * This registrar configures the new Context Parameters-based implementation.
 */
@AutoService(FirExtensionRegistrar::class)
class StepFirExtensionRegistrar : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {
        // Modern implementation using Context Parameters (Kotlin 2.2+)
        +::StepContextParameterExtension
        +::StepModernCheckerExtension
    }
}