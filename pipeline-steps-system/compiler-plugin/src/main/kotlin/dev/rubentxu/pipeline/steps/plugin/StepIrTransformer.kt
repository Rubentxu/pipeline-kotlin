package dev.rubentxu.pipeline.steps.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Stable IR transformer that performs @Step function transformation using stable APIs.
 * This implementation adds PipelineContext parameter and transforms call sites properly.
 */
class StepIrTransformer : IrGenerationExtension {

    companion object {
        val STEP_ANNOTATION_FQ_NAME = FqName("dev.rubentxu.pipeline.annotations.Step")
        val PIPELINE_CONTEXT_FQ_NAME = FqName("dev.rubentxu.pipeline.context.PipelineContext")
        val LOCAL_PIPELINE_CONTEXT_FQ_NAME = FqName("dev.rubentxu.pipeline.context.LocalPipelineContext")

        val STEP_ANNOTATION_CLASS_ID = ClassId.topLevel(STEP_ANNOTATION_FQ_NAME)
        val PIPELINE_CONTEXT_CLASS_ID = ClassId.topLevel(PIPELINE_CONTEXT_FQ_NAME)
        val LOCAL_PIPELINE_CONTEXT_CLASS_ID = ClassId.topLevel(LOCAL_PIPELINE_CONTEXT_FQ_NAME)
    }

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        println("========================================")
        println("StepIrTransformerStable: Starting transformation for module: ${moduleFragment.descriptor.name.asString()}")
        println("========================================")

        // Get required class symbols
        val pipelineContextSymbol = pluginContext.referenceClass(PIPELINE_CONTEXT_CLASS_ID)
        val localPipelineContextSymbol = pluginContext.referenceClass(LOCAL_PIPELINE_CONTEXT_CLASS_ID)

        if (pipelineContextSymbol == null) {
            println("❌ StepIrTransformerStable: PipelineContext class not found")
            return
        } else {
            println("✅ StepIrTransformerStable: PipelineContext class found")
        }

        if (localPipelineContextSymbol == null) {
            println("❌ StepIrTransformerStable: LocalPipelineContext class not found")
            return
        } else {
            println("✅ StepIrTransformerStable: LocalPipelineContext class found")
        }

        // Find 'current' property in LocalPipelineContext
        val currentProperty = localPipelineContextSymbol.owner.declarations
            .filterIsInstance<IrProperty>()
            .find { it.name.asString() == "current" }

        if (currentProperty?.getter == null) {
            println("❌ StepIrTransformerStable: LocalPipelineContext.current property not found")
            return
        } else {
            println("✅ StepIrTransformerStable: LocalPipelineContext.current property found")
        }

        // Phase 1: Find and analyze @Step functions
        val stepFunctions = mutableSetOf<IrSimpleFunctionSymbol>()
        val functionCollector = StepFunctionCollector(stepFunctions)
        moduleFragment.acceptChildrenVoid(functionCollector)

        println("✅ StepIrTransformerStable: Found ${stepFunctions.size} @Step functions")

        // Phase 2: Transform @Step functions by adding PipelineContext parameter
        val functionTransformer = StepFunctionTransformer(pluginContext, pipelineContextSymbol)
        moduleFragment.acceptChildrenVoid(functionTransformer)

        println("✅ StepIrTransformerStable: Processed function signatures")

        // Phase 3: Transform call sites to inject LocalPipelineContext.current
        val callTransformer =
            StepCallSiteTransformer(pluginContext, stepFunctions, localPipelineContextSymbol, currentProperty.getter!!)
        moduleFragment.transform(callTransformer, null)

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
     * Transforms @Step function bodies by injecting pipelineContext variable
     */
    private class StepFunctionTransformer(
        private val pluginContext: IrPluginContext,
        private val pipelineContextSymbol: IrClassSymbol
    ) : IrVisitorVoid() {

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitSimpleFunction(declaration: IrSimpleFunction) {
            super.visitSimpleFunction(declaration)

            if (declaration.hasStepAnnotation()) {
                println("🔧 StepIrTransformerStable: Procesando función @Step: ${declaration.name}")

                // Verificar si ya tiene parámetro PipelineContext usando la nueva API
                val hasPipelineContext = declaration.parameters.any { param ->
                    param.type.getClass()?.kotlinFqName == PIPELINE_CONTEXT_FQ_NAME
                }

                if (!hasPipelineContext) {
                    println("🔧 StepIrTransformerStable: Añadiendo parámetro PipelineContext a ${declaration.name}")

                    try {
                        // Crear el nuevo parámetro
                        val pipelineContextParam = pluginContext.irFactory.createValueParameter(
                            startOffset = declaration.startOffset,
                            endOffset = declaration.endOffset,
                            origin = IrDeclarationOrigin.DEFINED,
                            kind = IrParameterKind.Regular,
                            name = Name.identifier("pipelineContext"),
                            type = pipelineContextSymbol.defaultType,
                            isAssignable = false,
                            symbol = IrValueParameterSymbolImpl(),
                            varargElementType = null,
                            isCrossinline = false,
                            isNoinline = false,
                            isHidden = false
                        )

                        pipelineContextParam.parent = declaration

                        // Usar la nueva API: modificar directamente la lista parameters
                        // Mantener receptores (dispatch/extension) y agregar el nuevo parámetro al inicio de los regulares
                        val existingParams = declaration.parameters
                        val dispatchReceiver =
                            existingParams.firstOrNull { it.kind == IrParameterKind.DispatchReceiver }
                        val contextParams = existingParams.filter { it.kind == IrParameterKind.Context }
                        val extensionReceiver =
                            existingParams.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
                        val regularParams = existingParams.filter { it.kind == IrParameterKind.Regular }

                        val newParams = buildList {
                            dispatchReceiver?.let { add(it) }
                            addAll(contextParams)
                            extensionReceiver?.let { add(it) }
                            add(pipelineContextParam) // Agregar el nuevo parámetro
                            addAll(regularParams)     // Mantener los parámetros existentes
                        }

                        declaration.parameters = newParams

                        println("✅ StepIrTransformerStable: Parámetro PipelineContext añadido a ${declaration.name}")
                    } catch (e: Exception) {
                        println("❌ StepIrTransformerStable: Error añadiendo parámetro: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        }

        private fun IrFunction.hasStepAnnotation(): Boolean {
            return annotations.any { annotation ->
                annotation.type.getClass()?.kotlinFqName == STEP_ANNOTATION_FQ_NAME
            }
        }
    }

    /**
     * Transforms call sites to @Step functions by injecting PipelineContext
     */
    private class StepCallSiteTransformer(
        private val pluginContext: IrPluginContext,
        private val stepFunctions: Set<IrSimpleFunctionSymbol>,
        private val localPipelineContextSymbol: IrClassSymbol,
        private val currentGetter: IrSimpleFunction
    ) : IrElementTransformerVoid() {

        override fun visitCall(expression: IrCall): IrExpression {
            val transformedCall = super.visitCall(expression) as IrCall

            if (transformedCall.symbol in stepFunctions) {
                println("🔧 Transformando llamada a función @Step: ${transformedCall.symbol.owner.name}")

                try {
                    // Usar DeclarationIrBuilder para crear llamadas IR correctamente
                    val irBuilder = DeclarationIrBuilder(
                        pluginContext,
                        transformedCall.symbol,
                        expression.startOffset,
                        expression.endOffset
                    )

                    // Crear la llamada al getter de LocalPipelineContext.current
                    val pipelineContextCall = irBuilder.irCall(currentGetter.symbol)

                    // Crear la nueva llamada con argumentos modificados
                    val newCall = irBuilder.irCall(transformedCall.symbol).apply {
                        // Copiar receptores usando la nueva API
                        dispatchReceiver = transformedCall.dispatchReceiver

                        // Para extension receiver, usar arguments directamente en lugar de la propiedad obsoleta
                        val targetFunction = transformedCall.symbol.owner
                        val extensionReceiverIndex = targetFunction.parameters.indexOfFirst {
                            it.kind == IrParameterKind.ExtensionReceiver
                        }
                        if (extensionReceiverIndex >= 0) {
                            arguments[extensionReceiverIndex] = transformedCall.arguments[extensionReceiverIndex]
                        }

                        // Copiar argumentos de tipo
                        for (i in 0 until transformedCall.typeArguments.size) {
                            typeArguments[i] = transformedCall.typeArguments[i]
                        }

                        // Encontrar la posición donde insertar el contexto (después de receivers)
                        val dispatchReceiverCount =
                            if (targetFunction.parameters.any { it.kind == IrParameterKind.DispatchReceiver }) 1 else 0
                        val contextParamCount = targetFunction.parameters.count { it.kind == IrParameterKind.Context }
                        val extensionReceiverCount = if (extensionReceiverIndex >= 0) 1 else 0
                        val contextInsertIndex = dispatchReceiverCount + contextParamCount + extensionReceiverCount

                        // Insertar el contexto como primer parámetro regular
                        arguments[contextInsertIndex] = pipelineContextCall

                        // Copiar argumentos originales desplazados
                        var originalArgIndex = 0
                        for (i in 0 until targetFunction.parameters.size) {
                            val param = targetFunction.parameters[i]
                            when (param.kind) {
                                IrParameterKind.DispatchReceiver -> {
                                    arguments[i] = transformedCall.arguments[originalArgIndex++]
                                }

                                IrParameterKind.Context -> {
                                    arguments[i] = transformedCall.arguments[originalArgIndex++]
                                }

                                IrParameterKind.ExtensionReceiver -> {
                                    arguments[i] = transformedCall.arguments[originalArgIndex++]
                                }

                                IrParameterKind.Regular -> {
                                    if (i == contextInsertIndex) {
                                        // Ya insertamos el contexto arriba
                                    } else {
                                        arguments[i] = transformedCall.arguments[originalArgIndex++]
                                    }
                                }
                            }
                        }
                    }

                    println("✅ Transformación completada para ${transformedCall.symbol.owner.name}")
                    return newCall
                } catch (e: Exception) {
                    println("❌ Error en transformación: ${e.message}")
                    e.printStackTrace()
                    return transformedCall
                }
            }

            return transformedCall
        }
    }
}