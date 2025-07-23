package dev.rubentxu.pipeline.steps.registry

import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import dev.rubentxu.pipeline.annotations.Step
import dev.rubentxu.pipeline.steps.enhanced.*

/**
 * Registry for built-in enhanced step functions
 */
object BuiltinStepsRegistration {
    
    /**
     * Register all built-in enhanced step functions manually
     * This avoids issues with reflection on file-level functions
     */
    fun registerBuiltinSteps(registry: UnifiedStepRegistry) {
        // Use class introspection approach to register all @Step functions
        val stepFunctions = listOf(
            ::enhancedEcho,
            ::enhancedSh,
            ::enhancedSleep,
            ::enhancedWriteFile,
            ::enhancedReadFile,
            ::enhancedSetParam,
            ::enhancedGetParam,
            ::enhancedSetEnv,
            ::enhancedGetEnv
        )
        
        stepFunctions.forEach { function ->
            registry.registerStep(function as KFunction<*>)
        }
    }
}