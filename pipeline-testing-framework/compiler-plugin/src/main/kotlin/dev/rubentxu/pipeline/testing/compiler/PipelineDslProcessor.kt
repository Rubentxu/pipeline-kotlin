package dev.rubentxu.pipeline.testing.compiler

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import dev.rubentxu.pipeline.annotations.PipelineDsl
import dev.rubentxu.pipeline.annotations.PipelineStep
import dev.rubentxu.pipeline.annotations.ExtensionStep
import dev.rubentxu.pipeline.annotations.TestFrameworkConfig

/**
 * Procesador KSP2 que genera frameworks de pruebas para DSLs de pipeline.
 * 
 * Implementa las mejores prácticas identificadas en la investigación:
 * - Procesamiento incremental eficiente
 * - Generación de código type-safe con KotlinPoet
 * - Integración nativa con Kotest
 * - Manejo robusto de errores con logging detallado
 */
class PipelineDslProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val dslAnnotationName = PipelineDsl::class.qualifiedName!!
        val stepAnnotationName = PipelineStep::class.qualifiedName!!
        val extensionStepAnnotationName = ExtensionStep::class.qualifiedName!!

        logger.info("🔍 Iniciando procesamiento de DSLs de pipeline...")

        // Encontrar todas las clases anotadas con @PipelineDsl
        val dslClasses = resolver.getSymbolsWithAnnotation(dslAnnotationName)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        if (dslClasses.isEmpty()) {
            logger.info("ℹ️ No se encontraron clases anotadas con @PipelineDsl")
            return emptyList()
        }

        logger.info("📋 Encontradas ${dslClasses.size} clase(s) DSL para procesar")

        // Encontrar funciones de extensión anotadas con @ExtensionStep
        val extensionFunctions = resolver.getSymbolsWithAnnotation(extensionStepAnnotationName)
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { it.extensionReceiver != null }
            .toList()

        logger.info("🔧 Encontradas ${extensionFunctions.size} función(es) de extensión para procesar")

        val unprocessedSymbols = mutableListOf<KSAnnotated>()

        dslClasses.forEach { dslClass ->
            try {
                processDslClass(dslClass, resolver, extensionFunctions)
            } catch (e: Exception) {
                logger.error("❌ Error procesando clase ${dslClass.qualifiedName?.asString()}: ${e.message}")
                unprocessedSymbols.add(dslClass)
            }
        }

        return unprocessedSymbols
    }

    private fun processDslClass(dslClass: KSClassDeclaration, resolver: Resolver, extensionFunctions: List<KSFunctionDeclaration>) {
        val className = dslClass.simpleName.asString()
        logger.info("⚙️ Procesando DSL: $className")

        // Extraer configuración del framework de pruebas
        val config = extractTestFrameworkConfig(dslClass)
        
        // Encontrar pasos del pipeline
        val steps = findPipelineSteps(dslClass)
        
        // Encontrar pasos de extensión relevantes para esta clase DSL
        val extensionSteps = findExtensionSteps(dslClass, extensionFunctions)
        
        val allSteps = steps + extensionSteps
        
        if (allSteps.isEmpty()) {
            logger.warn("⚠️ No se encontraron pasos @PipelineStep o @ExtensionStep en $className")
            return
        }

        logger.info("📝 Encontrados ${allSteps.size} pasos en $className: ${allSteps.map { it.name }}")
        if (extensionSteps.isNotEmpty()) {
            logger.info("🔧 Incluyendo ${extensionSteps.size} función(es) de extensión: ${extensionSteps.map { it.name }}")
        }

        // Generar el framework de pruebas
        generateTestFramework(dslClass, allSteps, config)
        
        logger.info("✅ Framework de pruebas generado exitosamente para $className")
    }

    private fun extractTestFrameworkConfig(dslClass: KSClassDeclaration): TestFrameworkConfiguration {
        val configAnnotation = dslClass.annotations.find { 
            it.annotationType.resolve().declaration.qualifiedName?.asString() == TestFrameworkConfig::class.qualifiedName 
        }

        return if (configAnnotation != null) {
            TestFrameworkConfiguration(
                generateVerification = configAnnotation.arguments.find { it.name?.asString() == "generateVerification" }?.value as? Boolean ?: true,
                enableStateTracking = configAnnotation.arguments.find { it.name?.asString() == "enableStateTracking" }?.value as? Boolean ?: false,
                baseTestClass = configAnnotation.arguments.find { it.name?.asString() == "baseTestClass" }?.value as? String ?: "io.kotest.core.spec.style.FunSpec",
                packageName = configAnnotation.arguments.find { it.name?.asString() == "packageName" }?.value as? String ?: ""
            )
        } else {
            TestFrameworkConfiguration()
        }
    }

    private fun findPipelineSteps(dslClass: KSClassDeclaration): List<StepMetadata> {
        val steps = mutableListOf<StepMetadata>()

        // Pasos definidos como métodos de la clase
        dslClass.getAllFunctions()
            .filter { function ->
                function.annotations.any { annotation ->
                    annotation.annotationType.resolve().declaration.qualifiedName?.asString() == PipelineStep::class.qualifiedName
                }
            }
            .forEach { function ->
                steps.add(extractStepMetadata(function))
            }

        return steps
    }

    private fun findExtensionSteps(dslClass: KSClassDeclaration, extensionFunctions: List<KSFunctionDeclaration>): List<StepMetadata> {
        val dslClassName = dslClass.qualifiedName?.asString()
        
        return extensionFunctions
            .filter { function ->
                // Verificar que la función de extensión sea para esta clase DSL
                val receiverType = function.extensionReceiver?.resolve()?.declaration?.qualifiedName?.asString()
                receiverType == dslClassName
            }
            .map { function ->
                extractExtensionStepMetadata(function)
            }
    }

    private fun extractStepMetadata(function: KSFunctionDeclaration): StepMetadata {
        val stepAnnotation = function.annotations.first { 
            it.annotationType.resolve().declaration.qualifiedName?.asString() == PipelineStep::class.qualifiedName 
        }

        val customName = stepAnnotation.arguments.find { it.name?.asString() == "name" }?.value as? String
        val description = stepAnnotation.arguments.find { it.name?.asString() == "description" }?.value as? String

        return StepMetadata(
            name = customName?.takeIf { it.isNotBlank() } ?: function.simpleName.asString(),
            originalName = function.simpleName.asString(),
            description = description ?: "",
            parameters = function.parameters.map { param ->
                ParameterMetadata(
                    name = param.name!!.asString(),
                    type = param.type.toTypeName(),
                    hasDefault = param.hasDefault
                )
            },
            returnType = function.returnType?.toTypeName() ?: Unit::class.asTypeName(),
            isSuspend = function.modifiers.contains(Modifier.SUSPEND)
        )
    }

    private fun extractExtensionStepMetadata(function: KSFunctionDeclaration): StepMetadata {
        val stepAnnotation = function.annotations.first { 
            it.annotationType.resolve().declaration.qualifiedName?.asString() == ExtensionStep::class.qualifiedName 
        }

        val customName = stepAnnotation.arguments.find { it.name?.asString() == "name" }?.value as? String
        val description = stepAnnotation.arguments.find { it.name?.asString() == "description" }?.value as? String

        return StepMetadata(
            name = customName?.takeIf { it.isNotBlank() } ?: function.simpleName.asString(),
            originalName = function.simpleName.asString(),
            description = description ?: "",
            parameters = function.parameters.map { param ->
                ParameterMetadata(
                    name = param.name!!.asString(),
                    type = param.type.toTypeName(),
                    hasDefault = param.hasDefault
                )
            },
            returnType = function.returnType?.toTypeName() ?: Unit::class.asTypeName(),
            isSuspend = function.modifiers.contains(Modifier.SUSPEND)
        )
    }

    private fun generateTestFramework(
        dslClass: KSClassDeclaration,
        steps: List<StepMetadata>,
        config: TestFrameworkConfiguration
    ) {
        val packageName = config.packageName.takeIf { it.isNotBlank() } 
            ?: dslClass.packageName.asString()
        val className = dslClass.simpleName.asString()
        val frameworkClassName = "${className}TestFramework"

        val fileSpec = FileSpec.builder(packageName, frameworkClassName)
            .addFileComment("Generado automáticamente por PipelineDslProcessor")
            .addFileComment("No editar manualmente - se sobrescribirá en la próxima compilación")
            .addImport("dev.rubentxu.pipeline.annotations", "StepInvocation")
            .addImport("dev.rubentxu.pipeline.annotations", "TestConfiguration")
            .addImport("dev.rubentxu.pipeline.annotations", "VerificationScope")
            .addImport("dev.rubentxu.pipeline.dsl", "Step")
            .addImport("io.kotest.core.spec.style", "FunSpec")

        val frameworkClass = generateFrameworkClass(className, frameworkClassName, steps, config)
        fileSpec.addType(frameworkClass)

        // Escribir archivo con dependencias correctas para procesamiento incremental
        val dependencies = Dependencies(
            aggregating = false,
            sources = arrayOf(dslClass.containingFile!!)
        )

        fileSpec.build().writeTo(codeGenerator, dependencies)
    }

    private fun generateFrameworkClass(
        dslClassName: String,
        frameworkClassName: String,
        steps: List<StepMetadata>,
        config: TestFrameworkConfiguration
    ): TypeSpec {
        val baseTestClassName = ClassName.bestGuess(config.baseTestClass)

        return TypeSpec.classBuilder(frameworkClassName)
            .addModifiers(KModifier.OPEN)
            .addKdoc("""
                Framework de pruebas generado para el DSL $dslClassName.
                
                Proporciona un API type-safe para configurar mocks y verificar 
                la ejecución de pasos del pipeline.
                
                ## Uso:
                ```kotlin
                class My${dslClassName}Test : $frameworkClassName({
                    // Configurar mocks
                    ${steps.take(2).joinToString("\n    ") { "on${it.name.replaceFirstChar { c -> c.uppercase() }}(...)" }}
                    
                    test("mi prueba") {
                        // Ejecutar y verificar pipeline
                    }
                })
                ```
            """.trimIndent())
            .superclass(baseTestClassName)
            .primaryConstructor(generatePrimaryConstructor())
            .addProperties(generateProperties(config))
            .addFunctions(generateMockMethods(steps))
            .apply {
                if (config.generateVerification) {
                    addFunctions(generateVerificationMethods(steps))
                }
            }
            .build()
    }

    private fun generatePrimaryConstructor(): FunSpec {
        return FunSpec.constructorBuilder()
            .addParameter(
                "testConfiguration",
                LambdaTypeName.get(
                    receiver = ClassName("dev.rubentxu.pipeline.annotations", "TestConfiguration"),
                    returnType = Unit::class.asTypeName()
                )
            )
            .build()
    }

    private fun generateProperties(config: TestFrameworkConfiguration): List<PropertySpec> {
        val properties = mutableListOf<PropertySpec>()

        // Registro de invocaciones para verificación
        properties.add(
            PropertySpec.builder(
                "invocations",
                List::class.asTypeName().parameterizedBy(
                    ClassName("dev.rubentxu.pipeline.annotations", "StepInvocation")
                ),
                KModifier.PRIVATE
            ).mutable()
            .initializer("mutableListOf()")
            .build()
        )

        return properties
    }

    private fun generateMockMethods(steps: List<StepMetadata>): List<FunSpec> {
        return steps.map { step ->
            val methodName = "on${step.name.replaceFirstChar { it.uppercase() }}"
            
            // Construir tipo de la lambda del handler
            val handlerLambdaType = LambdaTypeName.get(
                parameters = step.parameters.map { it.type }.toTypedArray(),
                returnType = step.returnType
            )

            FunSpec.builder(methodName)
                .addKdoc("""
                    Configura el mock para el paso '${step.name}'.
                    
                    ${if (step.description.isNotBlank()) step.description else ""}
                    
                    @param handler Lambda que define el comportamiento del mock
                """.trimIndent())
                .addParameter("handler", handlerLambdaType)
                .addCode("""
                    // TODO: Implementar lógica de mock para ${step.name}
                    println("Mock configurado para paso: ${step.name}")
                """.trimIndent())
                .build()
        }
    }

    private fun generateHandlerInvocation(step: StepMetadata): String {
        val parameterList = step.parameters.mapIndexed { index, param ->
            "args[${index}] as ${param.type}"
        }.joinToString(", ")
        
        return "handler($parameterList)"
    }

    private fun generateVerificationMethods(steps: List<StepMetadata>): List<FunSpec> {
        val methods = mutableListOf<FunSpec>()

        // Método genérico de verificación
        methods.add(
            FunSpec.builder("verify")
                .addParameter(
                    "verification", 
                    LambdaTypeName.get(
                        receiver = ClassName("dev.rubentxu.pipeline.annotations", "VerificationScope"),
                        returnType = Unit::class.asTypeName()
                    )
                )
                .addStatement("verification(VerificationScope(invocations))")
                .build()
        )

        // Métodos específicos para cada paso
        steps.forEach { step ->
            val verifyMethodName = "verify${step.name.replaceFirstChar { it.uppercase() }}"
            
            methods.add(
                FunSpec.builder(verifyMethodName)
                    .addParameter("times", Int::class, KModifier.VARARG)
                    .returns(Boolean::class)
                    .addStatement("""
                        val callCount = invocations.count { it.stepName == "${step.name}" }
                        return when {
                            times.isEmpty() -> callCount > 0
                            times.size == 1 -> callCount == times[0]
                            else -> callCount in times[0]..times[1]
                        }
                    """.trimIndent())
                    .build()
            )
        }

        return methods
    }
}

/**
 * Configuración del framework de pruebas extraída de las anotaciones.
 */
data class TestFrameworkConfiguration(
    val generateVerification: Boolean = true,
    val enableStateTracking: Boolean = false,
    val baseTestClass: String = "io.kotest.core.spec.style.FunSpec",
    val packageName: String = ""
)

/**
 * Metadatos de un paso del pipeline extraídos del análisis de símbolos.
 */
data class StepMetadata(
    val name: String,
    val originalName: String,
    val description: String,
    val parameters: List<ParameterMetadata>,
    val returnType: TypeName,
    val isSuspend: Boolean
)

/**
 * Metadatos de un parámetro de paso.
 */
data class ParameterMetadata(
    val name: String,
    val type: TypeName,
    val hasDefault: Boolean
)

/**
 * Proveedor del procesador KSP.
 */
class PipelineDslProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return PipelineDslProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger
        )
    }
}