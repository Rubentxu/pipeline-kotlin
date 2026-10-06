package dev.rubentxu.pipeline.v2.sdk.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import dev.rubentxu.pipeline.v2.sdk.CompatibilityLevel
import dev.rubentxu.pipeline.v2.sdk.Step

/**
 * KSP SymbolProcessor that scans @Step-annotated functions and emits
 * GeneratedStepDescriptors.kt at compile time.
 *
 * ## What this processor may and may not decide
 *
 * It TRANSPORTS declared metadata. It never INFERRS semantics from a Step name.
 *
 * A previous revision branched on `when (name)` over the literal strings
 * `"echo"`, `"sh"`, `"error"` and `"sleep"` to choose ExecutionLocation, Effect
 * and ReplayPolicy, and used a companion `name -> JenkinsSurfaceMeta` map for
 * the Jenkins surface. That made a new Step family require editing the
 * processor: "extension by editing a semantic switch", which is exactly what
 * the Step Constitution forbids and what this class now refuses to do.
 *
 * The old code documented a reason: "KSP cannot reliably read enum/array annotation
 * arguments from Kotlin 2.x annotations in this configuration". That claim was
 * measured and it is FALSE here -- the values arrive as
 * `KSClassDeclarationEnumEntryImpl` and [enumConstantName] reads them. A semantic
 * `when` had been built on top of a limitation that did not exist.
 *
 * The correct response to a genuinely unreadable declaration is still to FAIL THE
 * BUILD, not to guess from the name and emit a descriptor whose semantics nobody
 * declared. That is what happens below.
 *
 * So every value below is either read from the declaration or the build stops.
 * There is no default branch and no `else -> <plausible value>`.
 *
 * ## Why there is no LSP metadata emission here
 *
 * This processor used to also write one JSON resource per Step under
 * `META-INF/pipeline/step-metadata/`, for an `LspMetadataLoader` that has zero
 * consumers, under a filename the loader could not have matched anyway. Dead
 * producer plus dead loader is not kept "for later"; a future LSP integration
 * arrives with a real consumer.
 */
class StepDescriptorGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private val stepDescriptors = mutableListOf<String>()
    private val failures = mutableListOf<String>()

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
        val where = function.simpleName.asString()
        val annotation = function.annotations.find { it.shortName.asString() == "Step" }
        if (annotation == null) {
            fail(where, "the @Step annotation was not readable on the declaration")
            return
        }

        val id = annotation.stringArgument("id")
        val name = annotation.stringArgument("name")

        // Declared, never inferred. Each of these returns null when unreadable,
        // which becomes a build error below rather than a default value.
        val execution = annotation.enumConstantArgument("execution")
        val replay = annotation.enumConstantArgument("replay")
        val effects = annotation.enumConstantListArgument("effects")

        val missing = buildList {
            if (id == null) add("id")
            if (name == null) add("name")
            if (execution == null) add("execution")
            if (replay == null) add("replay")
            if (effects == null) add("effects")
        }
        if (missing.isNotEmpty()) {
            fail(
                where,
                "could not read declared @Step metadata for [${missing.joinToString(", ")}]. " +
                    "This processor transports declarations and does not infer them from the Step " +
                    "name, so a descriptor cannot be emitted without them.",
            )
            return
        }

        val jenkinsSurface = readJenkinsSurface(function, where)

        stepDescriptors.add(
            buildString {
                appendLine("        StepDescriptor(")
                appendLine("            stepId = ${id!!.quote()},")
                appendLine("            name = ${name!!.quote()},")
                appendLine("            configRef = ${"$id.config".quote()},")
                appendLine("            executionLocation = ExecutionLocation.${execution},")
                appendLine("            effects = listOf(${effects!!.joinToString(", ") { "Effect.$it" }}),")
                appendLine("            replayPolicy = ReplayPolicy.${replay},")
                appendLine("            jenkinsSurface = ${jenkinsSurface.quote()},")
                appendLine("            requiredCapabilities = emptyList(),")
                appendLine("        ),")
            },
        )

        logger.info(
            "Processed @Step $name (id=$id, execution=$execution, effects=$effects, replay=$replay)",
        )
    }

    /**
     * The Jenkins surface is declared on a SEPARATE annotation, `@JenkinsSurface`.
     *
     * It is read from that declaration. There is no name-keyed table backing it
     * up, so a Step whose surface differs from its name needs no code change
     * anywhere in this module.
     */
    private fun readJenkinsSurface(function: KSFunctionDeclaration, where: String): String {
        val annotation: KSAnnotation = function.annotations
            .find { it.shortName.asString() == "JenkinsSurface" }
            ?: run {
                fail(
                    where,
                    "no @JenkinsSurface declaration found. The Jenkins surface is declared, not " +
                        "inferred, so a descriptor cannot be emitted without it.",
                )
                return ""
            }

        val step = annotation.stringArgument("step")
        val plugin = annotation.stringArgument("plugin")
        val compatibility = annotation.enumConstantArgument("compatibility")

        if (step == null || plugin == null || compatibility == null) {
            fail(
                where,
                "could not read the declared @JenkinsSurface metadata (step, plugin, compatibility).",
            )
            return ""
        }

        val level = CompatibilityLevel.entries
            .firstOrNull { it.name == compatibility }
        if (level == null) {
            fail(where, "declared CompatibilityLevel '$compatibility' is not a known constant")
            return ""
        }

        return "$step|$plugin|F${level.level}"
    }

    /** Records a hard failure. Nothing is emitted for a Step whose declaration is unreadable. */
    private fun fail(where: String, message: String) {
        val text = "$where: $message"
        failures += text
        logger.error("[StepDescriptorGenerator] $text")
    }

    override fun finish() {
        if (failures.isNotEmpty()) {
            logger.error(
                "[StepDescriptorGenerator] ${failures.size} Step declaration(s) could not be " +
                    "transported. No descriptors were emitted. Inferring them from Step names is " +
                    "not an available fallback.",
            )
            return
        }

        val packageName = "dev.rubentxu.pipeline.v2.sdk.runtime"
        val fileName = "GeneratedStepDescriptors"

        val content = buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("// Generated by StepDescriptorGenerator from @Step/@JenkinsSurface declarations.")
            appendLine("// Every value below is transported from a declaration; none is inferred from a Step name.")
            appendLine()
            appendLine("import dev.rubentxu.pipeline.v2.domain.durable.Effect")
            appendLine("import dev.rubentxu.pipeline.v2.domain.ExecutionLocation")
            appendLine("import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy")
            appendLine("import dev.rubentxu.pipeline.v2.domain.StepDescriptor")
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

        logger.info("Generated $fileName.kt with ${stepDescriptors.size} step descriptors")
    }

    private fun KSAnnotation.argument(name: String) =
        arguments.firstOrNull { it.name?.asString() == name }

    private fun KSAnnotation.stringArgument(name: String): String? =
        argument(name)?.value as? String

    /**
     * Reads an enum constant written as a bare enum entry in an annotation argument.
     *
     * KSP surfaces such a value as a [KSType] whose declaration simple name is the
     * constant, or as the constant name directly depending on the argument form.
     * Both are handled; anything else is `null`, which fails the build.
     */
    private fun KSAnnotation.enumConstantArgument(name: String): String? =
        enumConstantName(argument(name)?.value)

    private fun KSAnnotation.enumConstantListArgument(name: String): List<String>? {
        val items = argument(name)?.value as? List<*> ?: return null
        val names = items.map { enumConstantName(it) ?: return null }
        return names
    }

    /**
     * Resolves one annotation enum argument to its constant name.
     *
     * KSP hands a bare enum entry back as `KSClassDeclarationEnumEntryImpl`, which
     * implements [KSClassDeclaration]; its `simpleName` is the declared constant
     * (`CONTROLLER`, `MEMOIZED`, ...). A [KSType] is also accepted because that is
     * how the same constant arrives through a type-use form.
     *
     * MEASURED, not assumed. This module previously claimed in its KDoc that "KSP
     * cannot reliably read enum/array annotation arguments from Kotlin 2.x
     * annotations in this configuration", and branched on the Step name instead.
     * Probing the processor showed the declared values were present all along as
     * `KSClassDeclarationEnumEntryImpl` and only needed this case. The claim was
     * the reason a semantic `when` existed, so it was worth checking instead of
     * inheriting.
     *
     * Anything else is `null`, which fails the build rather than guessing.
     */
    private fun enumConstantName(raw: Any?): String? = when (raw) {
        is KSClassDeclaration -> raw.simpleName.asString()
        is KSType -> raw.declaration.simpleName.asString()
        is String -> raw
        else -> null
    }

    private fun String.quote(): String = "\"$this\""
}

/**
 * Provider for StepDescriptorGenerator - called by KSP to instantiate the processor.
 */
class StepDescriptorGeneratorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        StepDescriptorGenerator(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
        )
}
