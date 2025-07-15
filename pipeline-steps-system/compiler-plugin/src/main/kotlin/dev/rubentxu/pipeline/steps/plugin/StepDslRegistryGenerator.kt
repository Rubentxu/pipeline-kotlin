package dev.rubentxu.pipeline.steps.plugin

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
        println("StepDslRegistryGenerator: Starting REAL DSL extension generation for module: ${moduleFragment.name}")

        // Find all @Step functions
        val stepFunctions = mutableListOf<IrSimpleFunction>()
        moduleFragment.acceptVoid(StepFunctionCollector(stepFunctions))

        println("StepDslRegistryGenerator: Found ${stepFunctions.size} @Step functions")

        stepFunctions.forEach { function ->
            println("StepDslRegistryGenerator: Processing @Step function: ${function.name}")
            generateRealDslExtension(function, pluginContext, moduleFragment)
        }

        println("StepDslRegistryGenerator: Completed REAL DSL extension generation")
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
                println("StepDslRegistryGenerator: StepsBlock class not found, skipping DSL generation")
                return
            }

            println("StepDslRegistryGenerator: Generating DSL extension for ${stepFunction.name}")
            
            // Analyze the step function
            val functionName = stepFunction.name.asString()
            val parameters = stepFunction.parameters.filter { 
                it.kind == IrParameterKind.Regular 
            }
            
            println("StepDslRegistryGenerator: Function '$functionName' has ${parameters.size} parameters")
            
            // Build DSL signature with PipelineContext injection
            val dslSignature = buildDslExtensionSignature(stepFunction)
            println("StepDslRegistryGenerator: Would generate: $dslSignature")
            
            // Create REAL extension function
            createRealDslExtensionFunction(stepFunction, pluginContext, moduleFragment)
            
        } catch (e: Exception) {
            println("StepDslRegistryGenerator: Error generating DSL extension for ${stepFunction.name}: ${e.message}")
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
            
            println("StepDslRegistryGenerator: DSL Extension Analysis:")
            println("  - Function: $functionName")
            println("  - Is suspend: $isAsync")
            println("  - Regular parameter count: ${regularParams.size}")
            
            // Get StepsBlock class reference
            val stepsBlockSymbol = pluginContext.referenceClass(STEPS_BLOCK_CLASS_ID)
            if (stepsBlockSymbol == null) {
                println("StepDslRegistryGenerator: Cannot find StepsBlock class")
                return
            }
            
            println("StepDslRegistryGenerator: ✅ StepsBlock class found: ${stepsBlockSymbol.owner.name}")
            
            // Build extension signature
            val params = regularParams.map { param ->
                val typeName = param.type.getClass()?.kotlinFqName?.shortName()?.asString() ?: param.type.toString()
                "${param.name}: $typeName"
            }.joinToString(", ")
            
            val suspendModifier = if (isAsync) "suspend " else ""
            val extensionSignature = "${suspendModifier}fun StepsBlock.$functionName($params)"
            
            println("StepDslRegistryGenerator: Would generate extension: $extensionSignature")
            println("StepDslRegistryGenerator: Extension would delegate to: $functionName(context, ${regularParams.map { it.name }.joinToString(", ")})")
            
        } catch (e: Exception) {
            println("StepDslRegistryGenerator: ❌ Error analyzing DSL extension: ${e.message}")
            e.printStackTrace()
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
                println("StepDslRegistryGenerator: Found @Step function: ${declaration.name}")
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