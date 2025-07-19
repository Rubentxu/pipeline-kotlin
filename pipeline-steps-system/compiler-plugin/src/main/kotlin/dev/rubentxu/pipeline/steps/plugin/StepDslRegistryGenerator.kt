package dev.rubentxu.pipeline.steps.plugin

import dev.rubentxu.pipeline.steps.plugin.StepIrTransformer.Companion.LOCAL_PIPELINE_CONTEXT_CLASS_ID
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
        val regularParams = stepFunction.parameters.filter {
            it.kind == IrParameterKind.Regular &&
                    !it.type.toString().contains("PipelineContext")
        }
        val paramList = regularParams.joinToString(", ") { param ->
            val typeName = param.type.getClass()?.kotlinFqName?.shortName()?.asString() ?: param.type.toString()
            "${param.name}: $typeName"
        }
        val callParams = listOf("context") + regularParams.map { it.name.asString() }
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
            val regularParams = stepFunction.parameters.filter {
                it.kind == IrParameterKind.Regular &&
                        !it.type.toString().contains("PipelineContext")
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
        // Crea una lambda anónima: { stepFunction(LocalPipelineContext.current, ...params) }
        return irBuilder.irBlock {
            val call = irBuilder.irCall(stepFunction.symbol).apply {
                // Inyectar contexto como primer argumento
                val contextCall = createLocalPipelineContextCall(pluginContext, irBuilder)
                if (contextCall != null) {
                    arguments[0] = contextCall
                }
                // Agregar los demás parámetros
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
    private fun createLocalPipelineContextCall(
        pluginContext: IrPluginContext,
        irBuilder: DeclarationIrBuilder
    ): IrExpression? {
        val localPipelineContextSymbol = pluginContext.referenceClass(LOCAL_PIPELINE_CONTEXT_CLASS_ID)
        if (localPipelineContextSymbol != null) {
            val currentProperty = localPipelineContextSymbol.owner.declarations
                .filterIsInstance<IrProperty>()
                .find { it.name.asString() == "current" }
            if (currentProperty?.getter != null) {
                return irBuilder.irCall(currentProperty.getter!!.symbol)
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