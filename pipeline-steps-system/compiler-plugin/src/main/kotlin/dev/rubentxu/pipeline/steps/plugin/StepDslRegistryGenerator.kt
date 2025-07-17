package dev.rubentxu.pipeline.steps.plugin

import dev.rubentxu.pipeline.steps.plugin.StepIrTransformerStable.Companion.LOCAL_PIPELINE_CONTEXT_CLASS_ID
import dev.rubentxu.pipeline.steps.plugin.logging.PluginEvent
import dev.rubentxu.pipeline.steps.plugin.logging.StructuredLogger
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
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
            StructuredLogger.logPluginEvent(
                PluginEvent.DSL_GENERATION_STARTED, mapOf(
                    "module" to moduleFragment.name.asString()
                )
            )

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

            StructuredLogger.logPluginEvent(
                PluginEvent.DSL_GENERATION_COMPLETED, mapOf(
                    "module" to moduleFragment.name.asString(),
                    "processed_functions" to stepFunctions.size
                )
            )
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
     * Generate REAL DSL extension function for a @Step function
     * Creates: suspend fun StepsBlock.funcName(params) = step("funcName") { funcName(context, params) }
     */
    private fun createRealDslExtensionFunction(
        stepFunction: IrSimpleFunction,
        pluginContext: IrPluginContext,
        moduleFragment: IrModuleFragment
    ) {
        try {
            val stepsBlockSymbol = pluginContext.referenceClass(STEPS_BLOCK_CLASS_ID)
            if (stepsBlockSymbol == null) return

            val targetFile = moduleFragment.files.firstOrNull() ?: return

            // Obtener parámetros regulares (sin PipelineContext)
            val regularParams = stepFunction.parameters.filter {
                it.kind == IrParameterKind.Regular &&
                        !it.type.toString().contains("PipelineContext")
            }

            // Crear la función de extensión
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

            // ✅ Crear parámetros usando API estable - parameters list
            val parameters = mutableListOf<IrValueParameter>()

            // 1. Extension receiver (StepsBlock)
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

            // 2. Parámetros regulares
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

            // ✅ Asignar parámetros usando API estable
            extensionFunction.parameters = parameters

            // ✅ Crear cuerpo funcional real
            extensionFunction.body = createCompleteFunctionBody(
                extensionFunction, stepFunction, pluginContext, regularParams
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


    private fun createExtensionFunctionBody(
        stepFunction: IrSimpleFunction,
        extensionFunction: IrSimpleFunction,
        pluginContext: IrPluginContext,
        regularParams: List<IrValueParameter>
    ): IrBlockBody {
        // ✅ Delegar a la implementación completa
        return createCompleteFunctionBody(
            extensionFunction, stepFunction, pluginContext, regularParams
        )
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

    /**
     * Encontrar el método 'step' en StepsBlock
     */
    private fun findStepMethod(pluginContext: IrPluginContext): IrSimpleFunctionSymbol? {
        val stepsBlockSymbol = pluginContext.referenceClass(STEPS_BLOCK_CLASS_ID)
        return stepsBlockSymbol?.owner?.declarations
            ?.filterIsInstance<IrSimpleFunction>()
            ?.find { it.name.asString() == "step" }
            ?.symbol
    }

    /**
     * Crear la lambda que llama a la función original:
     * { originalFunction(context, param1, param2) }
     */
    private fun createLambdaCall(
        stepFunction: IrSimpleFunction,
        extensionFunction: IrSimpleFunction,
        pluginContext: IrPluginContext,
        irBuilder: DeclarationIrBuilder
    ): IrExpression {
        // Crear lambda que llama a stepFunction con context inyectado
        return irBuilder.irCall(stepFunction.symbol).apply {
            // Agregar LocalPipelineContext.current como primer argumento
            val contextCall = createLocalPipelineContextCall(pluginContext, irBuilder)
            if (contextCall != null) {
                // ✅ Usar API estable
                arguments[0] = contextCall
            }

            // Agregar los demás parámetros
            val regularParams = stepFunction.parameters.filter {
                it.kind == IrParameterKind.Regular &&
                        !it.type.toString().contains("PipelineContext")
            }

            val extensionRegularParams = extensionFunction.parameters.filter {
                it.kind == IrParameterKind.Regular
            }

            extensionRegularParams.forEachIndexed { index, param ->
                // ✅ Usar API estable y irGet correcto
                arguments[index + 1] = irBuilder.irGet(param)
            }
        }
    }

    /**
     * Crear el cuerpo completo usando la lambda corregida
     */
    private fun createCompleteFunctionBody(
        extensionFunction: IrSimpleFunction,
        stepFunction: IrSimpleFunction,
        pluginContext: IrPluginContext,
        regularParams: List<IrValueParameter>
    ): IrBlockBody {
        return pluginContext.irFactory.createBlockBody(
            startOffset = extensionFunction.startOffset,
            endOffset = extensionFunction.endOffset
        ).apply {
            val irBuilder = DeclarationIrBuilder(pluginContext, extensionFunction.symbol)

            // Encontrar método step
            val stepMethodSymbol = findStepMethod(pluginContext)
            if (stepMethodSymbol != null) {
                // Crear llamada a step() con lambda
                val stepCall = irBuilder.irCall(stepMethodSymbol).apply {
                    // Primer argumento: nombre de la función
                    putValueArgument(0, irBuilder.irString(stepFunction.name.asString()))

                    // Segundo argumento: lambda usando la versión corregida
                    putValueArgument(1, createStepLambda(stepFunction, extensionFunction, pluginContext, irBuilder, regularParams))
                }

                statements.add(irBuilder.irReturn(stepCall))
            }
        }
    }

    /**
     * Crea una lambda REAL: () -> T que envuelve la llamada a la función original
     * Usa solo APIs estables disponibles en Kotlin 2.2+
     */
    private fun createStepLambda(
        stepFunction: IrSimpleFunction,
        extensionFunction: IrSimpleFunction,
        pluginContext: IrPluginContext,
        irBuilder: DeclarationIrBuilder,
        regularParams: List<IrValueParameter>
    ): IrExpression {
        val irFactory = pluginContext.irFactory

        // 1. Crear función anónima (cuerpo de la lambda)
        val lambdaFunction = irFactory.createSimpleFunction(
            startOffset = irBuilder.startOffset,
            endOffset = irBuilder.endOffset,
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA,
            name = Name.special("<anonymous>"),
            visibility = DescriptorVisibilities.Local,
            isInline = false,
            isExpect = false,
            returnType = stepFunction.returnType,
            modality = Modality.FINAL,
            symbol = IrSimpleFunctionSymbolImpl(),
            isTailrec = false,
            isSuspend = stepFunction.isSuspend,
            isOperator = false,
            isInfix = false,
            isExternal = false
        ).apply {
            parent = extensionFunction
            parameters = emptyList() // Lambda sin parámetros

            // Crear cuerpo de la lambda
            body = createLambdaBody(stepFunction, extensionFunction, pluginContext, irBuilder)
        }

        // 2. Crear expresión de función usando irFunctionReference
        return irBuilder.irFunctionReference(
            type = determineLambdaType(stepFunction, pluginContext),
            symbol = lambdaFunction.symbol,
            typeArgumentsCount = 0,
            valueArgumentsCount = 0,
            reflectionTarget = null
        )
    }

    /**
     * Crear llamada a LocalPipelineContext.current usando APIs estables
     */
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

    /**
     * Crear el cuerpo de la lambda usando APIs estables
     */
    private fun createLambdaBody(
        stepFunction: IrSimpleFunction,
        extensionFunction: IrSimpleFunction,
        pluginContext: IrPluginContext,
        irBuilder: DeclarationIrBuilder
    ): IrBlockBody {
        return pluginContext.irFactory.createBlockBody(
            startOffset = irBuilder.startOffset,
            endOffset = irBuilder.endOffset
        ).apply {
            // Crear la llamada a la función original
            val originalCall = irBuilder.irCall(stepFunction.symbol).apply {
                // Inyectar contexto como primer argumento
                val contextCall = createLocalPipelineContextCall(pluginContext, irBuilder)
                if (contextCall != null) {
                    putValueArgument(0, contextCall)
                }

                // Agregar parámetros de la extensión
                val extensionRegularParams = extensionFunction.parameters.filter {
                    it.kind == IrParameterKind.Regular
                }

                extensionRegularParams.forEachIndexed { index, param ->
                    putValueArgument(index + 1, irBuilder.irGet(param))
                }
            }

            // Retornar el resultado
            statements.add(irBuilder.irReturn(originalCall))
        }
    }

    /**
     * Crear la expresión de función (objeto lambda) usando APIs estables
     */
    private fun createFunctionExpression(
        lambdaFunction: IrSimpleFunction,
        pluginContext: IrPluginContext,
        irBuilder: DeclarationIrBuilder
    ): IrExpression {
        // Determinar el tipo de la lambda basado en si es suspend
        val lambdaType = if (lambdaFunction.isSuspend) {
            // suspend () -> ReturnType
            pluginContext.irBuiltIns.getSuspendFunction(0).defaultType
        } else {
            // () -> ReturnType
            pluginContext.irBuiltIns.getFunction(0).defaultType
        }

        // Crear la expresión de función usando irBuilder
        return irBuilder.irBlock {
            // Agregar la función anónima como declaración
            +lambdaFunction

            // Crear referencia a la función como expresión
            +irBuilder.irFunctionReference(
                type = lambdaType,
                symbol = lambdaFunction.symbol,
                typeArgumentsCount = 0,
                valueArgumentsCount = 0,
                reflectionTarget = null
            )
        }
    }

    /**
     * Versión alternativa usando irLambda si está disponible en tu versión
     */
    private fun createStepLambdaSimplified(
        stepFunction: IrSimpleFunction,
        extensionFunction: IrSimpleFunction,
        pluginContext: IrPluginContext,
        irBuilder: DeclarationIrBuilder,
        regularParams: List<IrValueParameter>
    ): IrExpression {
        // ✅ Usar irLambda si está disponible (más simple)
        return irBuilder.irLambda(
            returnType = stepFunction.returnType,
            isSuspend = stepFunction.isSuspend
        ) { lambda ->
            // El cuerpo de la lambda
            +irReturn(
                irCall(stepFunction.symbol).apply {
                    // Inyectar contexto
                    val contextCall = createLocalPipelineContextCall(pluginContext, irBuilder)
                    if (contextCall != null) {
                        arguments[0] = contextCall
                    }

                    // Agregar parámetros
                    val extensionRegularParams = extensionFunction.parameters.filter {
                        it.kind == IrParameterKind.Regular
                    }

                    extensionRegularParams.forEachIndexed { index, param ->
                        arguments[index + 1] = irGet(param)
                    }
                }
            )
        }
    }
    /**
     * Versión alternativa más simple usando irBlock
     */
    private fun createStepLambdaSimple(
        stepFunction: IrSimpleFunction,
        extensionFunction: IrSimpleFunction,
        pluginContext: IrPluginContext,
        irBuilder: DeclarationIrBuilder,
        regularParams: List<IrValueParameter>
    ): IrExpression {
        // Crear la lambda directamente como bloque
        return irBuilder.irBlock {
            // Crear la llamada a la función original
            val originalCall = irCall(stepFunction.symbol).apply {
                // Inyectar contexto
                val contextCall = createLocalPipelineContextCall(pluginContext, this@irBlock)
                if (contextCall != null) {
                    putValueArgument(0, contextCall)
                }

                // Parámetros de la extensión
                extensionFunction.parameters.filter { it.kind == IrParameterKind.Regular }
                    .forEachIndexed { index, param ->
                        putValueArgument(index + 1, irGet(param))
                    }
            }

            +originalCall
        }
    }

    /**
     * Determinar el tipo correcto de la lambda usando APIs estables
     */
    private fun determineLambdaType(
        stepFunction: IrSimpleFunction,
        pluginContext: IrPluginContext
    ): IrType {
        // Buscar Function0 o SuspendFunction0 en el builtin
        val functionClass = if (stepFunction.isSuspend) {
            // Buscar kotlin.coroutines.SuspendFunction0
            pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.coroutines.SuspendFunction0")))
                ?: pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.jvm.functions.SuspendFunction0")))
        } else {
            // Buscar kotlin.Function0
            pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Function0")))
                ?: pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.jvm.functions.Function0")))
        }

        return functionClass?.defaultType ?: pluginContext.irBuiltIns.anyType
    }
}