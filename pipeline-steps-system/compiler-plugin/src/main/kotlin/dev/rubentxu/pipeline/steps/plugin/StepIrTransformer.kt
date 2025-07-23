package dev.rubentxu.pipeline.steps.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * Stable IR transformer that validates @Step function signatures.
 * This implementation validates that @Step functions have context: PipelineContext as first parameter.
 */
class StepIrTransformer : IrGenerationExtension {

    companion object {
        val STEP_ANNOTATION_FQ_NAME = FqName("dev.rubentxu.pipeline.annotations.Step")
        val PIPELINE_CONTEXT_FQ_NAME = FqName("dev.rubentxu.pipeline.context.PipelineContext")
        val STEP_ANNOTATION_CLASS_ID = ClassId.topLevel(STEP_ANNOTATION_FQ_NAME)
        val PIPELINE_CONTEXT_CLASS_ID = ClassId.topLevel(PIPELINE_CONTEXT_FQ_NAME)
    }

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        println("========================================")
        println("StepIrTransformerStable: Starting transformation for module: ${moduleFragment.descriptor.name.asString()}")
        println("========================================")

        // Get required class symbols
        val pipelineContextSymbol = pluginContext.referenceClass(PIPELINE_CONTEXT_CLASS_ID)

        if (pipelineContextSymbol == null) {
            println("❌ StepIrTransformerStable: PipelineContext class not found")
            return
        } else {
            println("✅ StepIrTransformerStable: PipelineContext class found")
        }

        // Phase 1: Find and analyze @Step functions
        val stepFunctions = mutableSetOf<IrSimpleFunctionSymbol>()
        val functionCollector = StepFunctionCollector(stepFunctions)
        moduleFragment.acceptChildrenVoid(functionCollector)

        println("✅ StepIrTransformerStable: Found ${stepFunctions.size} @Step functions")

        // Phase 2: Validate @Step functions have required context: PipelineContext parameter
        val functionValidator = StepFunctionValidator(pluginContext, pipelineContextSymbol)
        moduleFragment.acceptChildrenVoid(functionValidator)

        println("✅ StepIrTransformerStable: Validated function signatures")

        // Phase 3: Validate step function calls (no transformation needed)
        val callValidator = StepCallSiteValidator(pluginContext, stepFunctions)
        moduleFragment.acceptChildrenVoid(callValidator)

        println("✅ StepIrTransformerStable: Transformation complete")
        println("========================================")
    }

    /**
     * Collects @Step functions for later transformation
     */
    private class StepFunctionCollector(
        private val stepFunctions: MutableSet<IrSimpleFunctionSymbol>
    ) : IrVisitorVoid() {

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitSimpleFunction(declaration: IrSimpleFunction) {
            super.visitSimpleFunction(declaration)

            if (declaration.hasStepAnnotation()) {
                println("🔍 StepIrTransformerStable: Found @Step function: ${declaration.name}")
                stepFunctions.add(declaration.symbol)
            }
        }

        private fun IrFunction.hasStepAnnotation(): Boolean {
            return annotations.any { annotation ->
                annotation.type.getClass()?.kotlinFqName == STEP_ANNOTATION_FQ_NAME
            }
        }
    }

    /**
     * Validates that @Step functions have context: PipelineContext as first parameter
     */
    private class StepFunctionValidator(
        private val pluginContext: IrPluginContext,
        private val pipelineContextSymbol: IrClassSymbol
    ) : IrVisitorVoid() {

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitSimpleFunction(declaration: IrSimpleFunction) {
            super.visitSimpleFunction(declaration)

            if (declaration.hasStepAnnotation()) {
                println("🔍 StepIrTransformerStable: Validando función @Step: ${declaration.name}")
                
                validateStepFunctionSignature(declaration)
            }
        }

        private fun validateStepFunctionSignature(declaration: IrSimpleFunction) {
            // Encontrar el primer parámetro regular (no dispatch/extension receiver)
            val regularParams = declaration.parameters.filter { it.kind == IrParameterKind.Regular }
            
            if (regularParams.isEmpty()) {
                println("❌ StepIrTransformerStable: @Step function '${declaration.name}' must have 'context: PipelineContext' as first parameter")
                return
            }
            
            val firstRegularParam = regularParams.first()
            
            // Validar que el primer parámetro sea de tipo PipelineContext
            val isPipelineContext = firstRegularParam.type.getClass()?.kotlinFqName == PIPELINE_CONTEXT_FQ_NAME
            
            if (!isPipelineContext) {
                println("❌ StepIrTransformerStable: @Step function '${declaration.name}' must have 'context: PipelineContext' as first parameter, but found '${firstRegularParam.name}: ${firstRegularParam.type}'")
                return
            }
            
            // Validar que el parámetro se llame 'context' o similar
            val paramName = firstRegularParam.name.asString()
            if (paramName != "context" && paramName != "pipelineContext") {
                println("⚠️  StepIrTransformerStable: @Step function '${declaration.name}' should name the context parameter 'context' (found: '$paramName')")
            }
            
            println("✅ StepIrTransformerStable: @Step function '${declaration.name}' has valid context parameter: $paramName")
        }

        private fun IrFunction.hasStepAnnotation(): Boolean {
            return annotations.any { annotation ->
                annotation.type.getClass()?.kotlinFqName == STEP_ANNOTATION_FQ_NAME
            }
        }
    }

    /**
     * Validates call sites to @Step functions
     */
    private class StepCallSiteValidator(
        private val pluginContext: IrPluginContext,
        private val stepFunctions: Set<IrSimpleFunctionSymbol>
    ) : IrVisitorVoid() {

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitCall(call: IrCall) {
            super.visitCall(call)

            if (call.symbol in stepFunctions) {
                println("🔍 StepIrTransformerStable: Verificando llamada a función @Step: ${call.symbol.owner.name}")

                val targetFunction = call.symbol.owner
                val regularParams = targetFunction.parameters.filter { it.kind == IrParameterKind.Regular }
                
                if (regularParams.isNotEmpty()) {
                    val firstRegularParam = regularParams.first()
                    val isContextParam = firstRegularParam.type.getClass()?.kotlinFqName == PIPELINE_CONTEXT_FQ_NAME
                    
                    if (isContextParam) {
                        println("✅ StepIrTransformerStable: Función @Step ${targetFunction.name} correctamente definida con context como primer parámetro")
                    } else {
                        println("❌ StepIrTransformerStable: Función @Step ${targetFunction.name} no tiene context como primer parámetro")
                    }
                } else {
                    println("❌ StepIrTransformerStable: Función @Step ${targetFunction.name} no tiene parámetros regulares")
                }
            }
        }
    }

}