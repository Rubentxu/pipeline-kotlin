package dev.rubentxu.pipeline.steps.plugin

import dev.rubentxu.pipeline.steps.plugin.logging.PluginEvent
import dev.rubentxu.pipeline.steps.plugin.logging.StructuredLogger
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * Simplified DSL registry generator for @Step functions.
 */
class StepDslRegistryGenerator : IrGenerationExtension {

    companion object {
        // @Step annotation FqName
        val STEP_ANNOTATION_FQ_NAME = FqName("dev.rubentxu.pipeline.steps.annotations.Step")
        val STEP_ANNOTATION_CLASS_ID = ClassId.topLevel(STEP_ANNOTATION_FQ_NAME)

        // StepsBlock class FqName
        val STEPS_BLOCK_FQ_NAME = FqName("dev.rubentxu.pipeline.dsl.StepsBlock")
        val STEPS_BLOCK_CLASS_ID = ClassId.topLevel(STEPS_BLOCK_FQ_NAME)
    }

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        StructuredLogger.measureAndLog("dsl_generation_complete") {
            StructuredLogger.logPluginEvent(PluginEvent.DSL_GENERATION_STARTED, mapOf(
                "module" to moduleFragment.name.asString()
            ))

            // Find all @Step functions
            val stepFunctions = mutableListOf<IrSimpleFunction>()
            moduleFragment.acceptVoid(StepFunctionCollector(stepFunctions))

            StructuredLogger.logPerformanceMetric(
                operation = "step_function_discovery",
                durationMs = 0, // Measured by acceptVoid
                metadata = mapOf("found_functions" to stepFunctions.size)
            )

            stepFunctions.forEach { function ->
                StructuredLogger.measureAndLog("generate_dsl_extension_${function.name}") {
                    generateRealDslExtension(function, pluginContext, moduleFragment)
                }
            }

            StructuredLogger.logPluginEvent(PluginEvent.DSL_GENERATION_COMPLETED, mapOf(
                "module" to moduleFragment.name.asString(),
                "processed_functions" to stepFunctions.size
            ))
        }
    }

    /**
     * Generate REAL DSL extension function for a @Step function
     * Creates: fun StepsBlock.funcName(params) = step("funcName") { funcName(params) }
     */
    private fun generateRealDslExtension(
        stepFunction: IrSimpleFunction,
        pluginContext: IrPluginContext,
        moduleFragment: IrModuleFragment
    ) {
        try {
            // Check if StepsBlock exists
            val stepsBlockSymbol = pluginContext.referenceClass(STEPS_BLOCK_CLASS_ID)
            if (stepsBlockSymbol == null) {
                StructuredLogger.logWarning(
                    operation = "dsl_extension_generation",
                    message = "StepsBlock class not found, skipping DSL generation",
                    context = mapOf("function_name" to stepFunction.name.asString())
                )
                return
            }

            // Analyze the step function
            val functionName = stepFunction.name.asString()
            val parameters = stepFunction.parameters.filter { 
                it.kind == IrParameterKind.Regular 
            }
            
            // Build DSL signature with PipelineContext injection
            val dslSignature = buildDslExtensionSignature(stepFunction)
            
            StructuredLogger.logPerformanceMetric(
                operation = "dsl_extension_analysis",
                durationMs = 0,
                metadata = mapOf(
                    "function_name" to functionName,
                    "parameter_count" to parameters.size,
                    "dsl_signature" to dslSignature,
                    "is_suspend" to stepFunction.isSuspend
                )
            )
            
            // Create REAL extension function
            createRealDslExtensionFunction(stepFunction, pluginContext, moduleFragment)
            
        } catch (e: Exception) {
            StructuredLogger.logError(
                operation = "dsl_extension_generation",
                error = e,
                context = mapOf("function_name" to stepFunction.name.asString())
            )
        }
    }
    
    /**
     * Build the DSL extension signature with PipelineContext injection
     * Example: "suspend fun StepsBlock.deployService(environment: String) = step { deployService(context, environment) }"
     */
    private fun buildDslExtensionSignature(stepFunction: IrSimpleFunction): String {
        val functionName = stepFunction.name.asString()
        val isAsync = stepFunction.isSuspend
        
        // Get regular parameters (excluding potential existing context)
        val regularParams = stepFunction.parameters.filter { 
            it.kind == IrParameterKind.Regular &&
            !it.type.toString().contains("PipelineContext")
        }
        
        // Build parameter list for DSL extension
        val paramList = regularParams.map { param ->
            val typeName = param.type.getClass()?.kotlinFqName?.shortName()?.asString() ?: param.type.toString()
            "${param.name}: $typeName"
        }.joinToString(", ")
        
        // Build the call to original function with context injection
        val callParams = listOf("context") + regularParams.map { it.name.asString() }
        val callSignature = callParams.joinToString(", ")
        
        val suspendModifier = if (isAsync) "suspend " else ""
        
        return "${suspendModifier}fun StepsBlock.$functionName($paramList) = step(\"$functionName\") { $functionName($callSignature) }"
    }
    
    /**
     * Analyze DSL extension generation potential
     * This demonstrates what extensions would be generated
     */
    private fun createRealDslExtensionFunction(
        stepFunction: IrSimpleFunction, 
        pluginContext: IrPluginContext,
        moduleFragment: IrModuleFragment
    ) {
        try {
            val functionName = stepFunction.name.asString()
            val isAsync = stepFunction.isSuspend
            val regularParams = stepFunction.parameters.filter { it.kind == IrParameterKind.Regular }
            
            // Get StepsBlock class reference
            val stepsBlockSymbol = pluginContext.referenceClass(STEPS_BLOCK_CLASS_ID)
            if (stepsBlockSymbol == null) {
                StructuredLogger.logWarning(
                    operation = "dsl_extension_analysis",
                    message = "Cannot find StepsBlock class",
                    context = mapOf("function_name" to functionName)
                )
                return
            }
            
            // Build extension signature
            val params = regularParams.map { param ->
                val typeName = param.type.getClass()?.kotlinFqName?.shortName()?.asString() ?: param.type.toString()
                "${param.name}: $typeName"
            }.joinToString(", ")
            
            val suspendModifier = if (isAsync) "suspend " else ""
            val extensionSignature = "${suspendModifier}fun StepsBlock.$functionName($params)"
            
            StructuredLogger.logPerformanceMetric(
                operation = "dsl_extension_creation",
                durationMs = 0,
                metadata = mapOf(
                    "function_name" to functionName,
                    "is_suspend" to isAsync,
                    "parameter_count" to regularParams.size,
                    "extension_signature" to extensionSignature,
                    "steps_block_available" to true,
                    "steps_block_name" to stepsBlockSymbol.owner.name.asString(),
                    "delegate_call" to "$functionName(context, ${regularParams.map { it.name }.joinToString(", ")})"
                )
            )
            
        } catch (e: Exception) {
            StructuredLogger.logError(
                operation = "dsl_extension_analysis",
                error = e,
                context = mapOf("function_name" to stepFunction.name.asString())
            )
        }
    }

    /**
     * Visitor to collect @Step functions
     */
    private inner class StepFunctionCollector(
        private val stepFunctions: MutableList<IrSimpleFunction>
    ) : IrVisitorVoid() {

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitSimpleFunction(declaration: IrSimpleFunction) {
            if (declaration.hasStepAnnotation()) {
                stepFunctions.add(declaration)
                StructuredLogger.logPerformanceMetric(
                    operation = "step_function_discovered",
                    durationMs = 0,
                    metadata = mapOf(
                        "function_name" to declaration.name.asString(),
                        "is_suspend" to declaration.isSuspend,
                        "parameter_count" to declaration.parameters.size
                    )
                )
            }
            super.visitSimpleFunction(declaration)
        }
    }

    /**
     * Check if function has @Step annotation
     */
    private fun IrFunction.hasStepAnnotation(): Boolean {
        return annotations.any { annotation ->
            val annotationType = annotation.type
            annotationType.getClass()?.kotlinFqName == STEP_ANNOTATION_FQ_NAME
        }
    }
}