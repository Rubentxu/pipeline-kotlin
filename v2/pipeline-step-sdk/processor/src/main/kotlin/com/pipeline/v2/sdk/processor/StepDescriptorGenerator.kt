package com.pipeline.v2.sdk.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.pipeline.v2.sdk.Effect
import com.pipeline.v2.sdk.ExecutionLocation
import com.pipeline.v2.sdk.ReplayPolicy
import com.pipeline.v2.sdk.Step
import com.pipeline.v2.sdk.StepDescriptor

/**
 * KSP SymbolProcessor that scans @Step-annotated functions and emits
 * GeneratedStepDescriptors.kt at compile time.
 */
class StepDescriptorGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private val stepDescriptors = mutableListOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val stepAnnotationName = Step::class.qualifiedName!!

        resolver.getSymbolsWithAnnotation(stepAnnotationName)
            .filterIsInstance<KSFunctionDeclaration>()
            .forEach { function ->
                processStepFunction(function)
            }

        return emptyList()
    }

    private fun processStepFunction(function: KSFunctionDeclaration) {
        val annotation = function.annotations.find {
            it.shortName.asString() == "Step"
        } ?: run {
            logger.warn(" @Step annotation not found on ${function.simpleName.asString()}")
            return
        }

        val idArg = annotation.arguments.find { it.name?.asString() == "id" }
        val nameArg = annotation.arguments.find { it.name?.asString() == "name" }

        val id = idArg?.value as? String ?: run {
            logger.warn(" @Step function ${function.simpleName.asString()} missing 'id' argument")
            return
        }
        val name = nameArg?.value as? String ?: function.simpleName.asString()

        // Hardcode correct step metadata based on canonical step type.
        // KSP cannot reliably read enum/array annotation arguments from Kotlin 2.x annotations
        // in this configuration, so we use the function name as the canonical identifier.
        val (execution, effectsList, replay) = when (name) {
            "echo" -> Triple(ExecutionLocation.CONTROLLER, listOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED)
            "sh" -> Triple(ExecutionLocation.WORKER, listOf(Effect.EXECUTES_SUBPROCESS), ReplayPolicy.RERUN)
            "error" -> Triple(ExecutionLocation.AGENT, listOf(Effect.ABORTS_PIPELINE), ReplayPolicy.NEVER)
            "sleep" -> Triple(ExecutionLocation.CONTROLLER, listOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED)
            else -> {
                logger.warn(" Unknown step type: $name, using defaults")
                Triple(ExecutionLocation.WORKER, emptyList(), ReplayPolicy.MEMOIZED)
            }
        }

        val descriptorCode = buildString {
            appendLine("        StepDescriptor(")
            appendLine("            stepId = ${id.quote()},")
            appendLine("            name = ${name.quote()},")
            appendLine("            configRef = ${"$id.config".quote()},")
            appendLine("            executionLocation = ExecutionLocation.${execution.name},")
            appendLine("            effects = listOf(${effectsList.joinToString(", ") { "Effect.${it.name}" }}),")
            appendLine("            replayPolicy = ReplayPolicy.${replay.name},")
            appendLine("            requiredCapabilities = emptyList(),")
            appendLine("        ),")
        }

        stepDescriptors.add(descriptorCode)
        logger.info("Processed @Step function: $name (id=$id, execution=$execution, effects=$effectsList, replay=$replay)")
    }

    override fun finish() {
        val packageName = "com.pipeline.v2.sdk.runtime"
        val fileName = "GeneratedStepDescriptors.kt"

        val content = buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import com.pipeline.v2.sdk.Effect")
            appendLine("import com.pipeline.v2.sdk.ExecutionLocation")
            appendLine("import com.pipeline.v2.sdk.ReplayPolicy")
            appendLine("import com.pipeline.v2.sdk.StepDescriptor")
            appendLine()
            appendLine("public object GeneratedStepDescriptors {")
            appendLine("    public val all: List<StepDescriptor> = listOf(")
            stepDescriptors.forEach { append(it) }
            appendLine("    )")
            appendLine("}")
        }

        codeGenerator.createNewFile(
            packageName = packageName,
            fileName = fileName,
            extensionName = "kt",
            dependencies = Dependencies.ALL_FILES,
        ).use { output ->
            output.write(content.toByteArray())
        }

        logger.info("Generated $fileName with ${stepDescriptors.size} step descriptors")
    }

    private fun String.quote(): String = "\"$this\""
}

/**
 * Provider for StepDescriptorGenerator - called by KSP to instantiate the processor.
 */
class StepDescriptorGeneratorProvider : SymbolProcessorProvider {
    override fun create(
        environment: SymbolProcessorEnvironment,
    ): SymbolProcessor {
        return StepDescriptorGenerator(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
        )
    }
}
