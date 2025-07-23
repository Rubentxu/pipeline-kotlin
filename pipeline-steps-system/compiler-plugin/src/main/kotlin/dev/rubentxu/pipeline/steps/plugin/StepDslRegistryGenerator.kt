package dev.rubentxu.pipeline.steps.plugin

import dev.rubentxu.pipeline.steps.plugin.logging.PluginEvent
import dev.rubentxu.pipeline.steps.plugin.logging.StructuredLogger
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class StepDslRegistryGenerator : IrGenerationExtension {
    companion object {
        val STEP_ANNOTATION_FQ_NAME = FqName("dev.rubentxu.pipeline.steps.annotations.Step")
        val STEPS_BLOCK_FQ_NAME = FqName("dev.rubentxu.pipeline.dsl.StepsBlock")
        val STEPS_BLOCK_CLASS_ID = ClassId.topLevel(STEPS_BLOCK_FQ_NAME)
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        StructuredLogger.measureAndLog("dsl_generation_complete") {
            StructuredLogger.logPluginEvent(
                PluginEvent.DSL_GENERATION_STARTED, mapOf(
                    "module" to moduleFragment.descriptor.name.asString()
                )
            )
            val stepFunctions = mutableListOf<IrSimpleFunction>()
            moduleFragment.acceptVoid(StepFunctionCollector(stepFunctions))
            StructuredLogger.logPerformanceMetric(
                operation = "step_function_discovery",
                durationMs = 0,
                metadata = mapOf("found_functions" to stepFunctions.size)
            )
            stepFunctions.forEach { function ->
                StructuredLogger.measureAndLog("generate_dsl_extension_${function.name}") {
                    generateRealDslExtensionFunction(function, pluginContext, moduleFragment)
                }
            }
            StructuredLogger.logPluginEvent(
                PluginEvent.DSL_GENERATION_COMPLETED, mapOf(
                    "module" to moduleFragment.descriptor.name.asString(),
                    "processed_functions" to stepFunctions.size
                )
            )
        }
    }

    private fun buildDslExtensionSignature(stepFunction: IrSimpleFunction): String {
        val functionName = stepFunction.name.asString()
        val isAsync = stepFunction.isSuspend
        // Omitir el primer parámetro regular si es de tipo PipelineContext (context parameter)
        val allRegularParams = stepFunction.parameters.filter { it.kind == IrParameterKind.Regular }
        val regularParams = if (allRegularParams.isNotEmpty() && 
                                allRegularParams.first().type.getClass()?.kotlinFqName?.asString()?.contains("PipelineContext") == true) {
            allRegularParams.drop(1)  // Omitir el primer parámetro context
        } else {
            allRegularParams  // Mantener todos si no hay context parameter
        }
        val paramList = regularParams.joinToString(", ") { param ->
            val typeName = param.type.getClass()?.kotlinFqName?.shortName()?.asString() ?: param.type.toString()
            "${param.name}: $typeName"
        }
        // El contexto se obtiene del StepsBlock (this.context) para las extensiones
        val callParams = listOf("this.context") + regularParams.map { it.name.asString() }
        val callSignature = callParams.joinToString(", ")
        val suspendModifier = if (isAsync) "suspend " else ""
        return "${suspendModifier}fun StepsBlock.$functionName($paramList) = step(\"$functionName\") { $functionName($callSignature) }"
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun generateRealDslExtensionFunction(
        stepFunction: IrSimpleFunction,
        pluginContext: IrPluginContext,
        moduleFragment: IrModuleFragment
    ) {
        try {
            val stepsBlockSymbol = pluginContext.referenceClass(STEPS_BLOCK_CLASS_ID)
            if (stepsBlockSymbol == null) return
            val targetFile = moduleFragment.files.firstOrNull() ?: return
            // Omitir el primer parámetro regular si es de tipo PipelineContext (context parameter)
            val allRegularParams = stepFunction.parameters.filter { it.kind == IrParameterKind.Regular }
            val regularParams = if (allRegularParams.isNotEmpty() && 
                                    allRegularParams.first().type.getClass()?.kotlinFqName?.asString()?.contains("PipelineContext") == true) {
                allRegularParams.drop(1)  // Omitir el primer parámetro context
            } else {
                allRegularParams  // Mantener todos si no hay context parameter
            }
            val extensionFunction = pluginContext.irFactory.createSimpleFunction(
                startOffset = stepFunction.startOffset,
                endOffset = stepFunction.endOffset,
                origin = IrDeclarationOrigin.GENERATED_SINGLE_FIELD_VALUE_CLASS_MEMBER,
                name = stepFunction.name,
                visibility = stepFunction.visibility,
                isInline = false,
                isExpect = false,
                returnType = stepFunction.returnType,
                modality = stepFunction.modality,
                symbol = IrSimpleFunctionSymbolImpl(),
                isTailrec = false,
                isSuspend = stepFunction.isSuspend,
                isOperator = false,
                isInfix = false,
                isExternal = false,
                containerSource = null,
                isFakeOverride = false
            )
            extensionFunction.parent = targetFile
            val parameters = mutableListOf<IrValueParameter>()
            val extensionReceiver = pluginContext.irFactory.createValueParameter(
                startOffset = extensionFunction.startOffset,
                endOffset = extensionFunction.endOffset,
                origin = IrDeclarationOrigin.DEFINED,
                kind = IrParameterKind.ExtensionReceiver,
                name = Name.special("<this>"),
                type = stepsBlockSymbol.defaultType,
                isAssignable = false,
                symbol = IrValueParameterSymbolImpl(),
                varargElementType = null,
                isCrossinline = false,
                isNoinline = false,
                isHidden = false
            )
            extensionReceiver.parent = extensionFunction
            parameters.add(extensionReceiver)
            regularParams.forEach { originalParam ->
                val newParam = pluginContext.irFactory.createValueParameter(
                    startOffset = extensionFunction.startOffset,
                    endOffset = extensionFunction.endOffset,
                    origin = originalParam.origin,
                    kind = IrParameterKind.Regular,
                    name = originalParam.name,
                    type = originalParam.type,
                    isAssignable = false,
                    symbol = IrValueParameterSymbolImpl(),
                    varargElementType = originalParam.varargElementType,
                    isCrossinline = originalParam.isCrossinline,
                    isNoinline = originalParam.isNoinline,
                    isHidden = false
                )
                newParam.parent = extensionFunction
                parameters.add(newParam)
            }
            extensionFunction.parameters = parameters
            extensionFunction.body = createCompleteFunctionBody(
                extensionFunction, stepFunction, pluginContext
            )
            targetFile.declarations.add(extensionFunction)
            StructuredLogger.logPerformanceMetric(
                operation = "dsl_extension_generated",
                durationMs = 0,
                metadata = mapOf(
                    "function_name" to stepFunction.name.asString(),
                    "extension_created" to true,
                    "target_file" to targetFile.name
                )
            )
        } catch (e: Exception) {
            StructuredLogger.logError(
                operation = "dsl_extension_generation_real",
                error = e,
                context = mapOf("function_name" to stepFunction.name.asString())
            )
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun createCompleteFunctionBody(
        extensionFunction: IrSimpleFunction,
        stepFunction: IrSimpleFunction,
        pluginContext: IrPluginContext
    ): IrBlockBody {
        return pluginContext.irFactory.createBlockBody(
            startOffset = extensionFunction.startOffset,
            endOffset = extensionFunction.endOffset
        ).apply {
            val irBuilder = DeclarationIrBuilder(pluginContext, extensionFunction.symbol)
            val stepMethodSymbol = findStepMethod(pluginContext)
            if (stepMethodSymbol != null) {
                val stepCall = irBuilder.irCall(stepMethodSymbol).apply {
                    arguments[0] = irBuilder.irString(stepFunction.name.asString())
                    arguments[1] = createStepLambda(stepFunction, extensionFunction, pluginContext, irBuilder)
                }
                statements.add(irBuilder.irReturn(stepCall))
            }
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun createStepLambda(
        stepFunction: IrSimpleFunction,
        extensionFunction: IrSimpleFunction,
        pluginContext: IrPluginContext,
        irBuilder: DeclarationIrBuilder
    ): IrExpression {
        // Crea una lambda anónima: { stepFunction(this.context, ...params) }
        // Donde this.context proviene del StepsBlock
        return irBuilder.irBlock {
            val call = irBuilder.irCall(stepFunction.symbol).apply {
                // Obtener el contexto del StepsBlock (this.context)
                val contextCall = createStepsBlockContextCall(pluginContext, irBuilder, extensionFunction)
                if (contextCall != null) {
                    arguments[0] = contextCall  // Pasar contexto como primer argumento
                }
                // Agregar los demás parámetros (sin incluir el context parameter)
                val extensionRegularParams = extensionFunction.parameters.filter {
                    it.kind == IrParameterKind.Regular
                }
                extensionRegularParams.forEachIndexed { index, param ->
                    arguments[index + 1] = irBuilder.irGet(param)
                }
            }
            +irBuilder.irReturn(call)
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun createStepsBlockContextCall(
        pluginContext: IrPluginContext,
        irBuilder: DeclarationIrBuilder,
        extensionFunction: IrSimpleFunction
    ): IrExpression? {
        // Obtener el contexto del receptor de extensión (StepsBlock)
        val stepsBlockSymbol = pluginContext.referenceClass(STEPS_BLOCK_CLASS_ID)
        if (stepsBlockSymbol != null) {
            val contextProperty = stepsBlockSymbol.owner.declarations
                .filterIsInstance<IrProperty>()
                .find { it.name.asString() == "context" }
            if (contextProperty?.getter != null) {
                // Obtener 'this' del StepsBlock (extension receiver)
                val extensionReceiver = extensionFunction.parameters.firstOrNull { 
                    it.kind == IrParameterKind.ExtensionReceiver 
                }
                if (extensionReceiver != null) {
                    return irBuilder.irCall(contextProperty.getter!!.symbol).apply {
                        dispatchReceiver = irBuilder.irGet(extensionReceiver)
                    }
                }
            }
        }
        return null
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun findStepMethod(pluginContext: IrPluginContext): IrSimpleFunctionSymbol? {
        val stepsBlockSymbol = pluginContext.referenceClass(STEPS_BLOCK_CLASS_ID)
        return stepsBlockSymbol?.owner?.declarations
            ?.filterIsInstance<IrSimpleFunction>()
            ?.find { it.name.asString() == "step" }
            ?.symbol
    }

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

    private fun IrFunction.hasStepAnnotation(): Boolean {
        return annotations.any { annotation ->
            val annotationType = annotation.type
            annotationType.getClass()?.kotlinFqName == STEP_ANNOTATION_FQ_NAME
        }
    }
}