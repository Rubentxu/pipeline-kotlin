package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.domain.step.StepCatalogue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The DECLARATION of a variable, as the COMPILER sees it.
 *
 * ## The guarantee under trial
 *
 * That the form the CATALOGUE PUBLISHES as the assignment of Jenkins does not
 * compile here, that the compiler says WHICH reference it cannot resolve, and
 * that the form the catalogue publishes as the declaration of this product
 * compiles — at the level of the stage and at the level of the pipeline.
 *
 * ## Why the forms are READ and not written down
 *
 * Because a row that carried its own copy of the published form would compare
 * the text with itself: it would stay green the day the catalogue published
 * another form, and it would stay green the day the catalogue published a form
 * the DSL does not take. So the shapes are read out of the published reason,
 * and what the compiler is asked to compile is what the catalogue says.
 *
 * ## Why this instrument is the compiler
 *
 * Because the facts are facts of the COMPILER and of nothing else: the absence
 * of a declaration is not a fact a catalogue can state on its own, and a
 * catalogue that published it without the compiler behind it would publish a
 * promise the product does not keep. The script that writes the assignment is
 * the one that has to be refused, and the compiler is the only thing that can
 * refuse it.
 *
 * ## Why the step reads a plain command
 *
 * Because the row is about the DECLARATION: a command that reads the variable
 * would add a diagnostic of its own, and a row that counted diagnostics could
 * not tell which of the two forms it was looking at.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class EnvironmentDeclarationCompilationTest {

    @TempDir
    lateinit var dir: Path

    private val scriptClasspath: List<String> =
        listOfNotNull(ScriptDefinition.domainJar(), ScriptDefinition.dslApiJar())

    private fun compile(name: String, text: String): ScriptCompilationResult {
        val script = dir.resolve(name)
        Files.writeString(script, text)
        val host = Kotlin24ScriptingHost(cache = CompiledScriptCache.Disabled)
        return host.compile(ScriptDefinition.file(script, classpath = scriptClasspath))
    }

    /**
     * The assignment of Jenkins does not compile in a stage, and the compiler names it.
     */
    @Test
    @DisplayName("the assignment of Jenkins does not compile in a stage, and the compiler names it")
    fun theAssignmentOfJenkinsDoesNotCompileInAStageAndTheCompilerNamesIt() {
        val result = compile("jenkins-assignment-stage.pipeline.kts", stageWithAssignment())

        assertRefusedByTheCompiler(result, "the assignment of Jenkins compiled in a stage")
    }

    /**
     * The assignment of Jenkins does not compile at the level of the pipeline either.
     *
     * The published reason says that the assignment does not compile, without
     * saying WHERE: the block of an environment is Kotlin at both levels, and a
     * row that only looked at one of them would let the other one answer
     * something the catalogue does not publish.
     */
    @Test
    @DisplayName("the assignment of Jenkins does not compile at the level of the pipeline either")
    fun theAssignmentOfJenkinsDoesNotCompileAtTheLevelOfThePipelineEither() {
        val result = compile("jenkins-assignment-pipeline.pipeline.kts", pipelineWithAssignment())

        assertRefusedByTheCompiler(result, "the assignment of Jenkins compiled at the level of the pipeline")
    }

    /**
     * The declaration this product takes compiles in a stage.
     *
     * The control of the rows above: a form the product refuses would make
     * the refusals above a fact about two broken forms instead of a fact about
     * the one of Jenkins.
     */
    @Test
    @DisplayName("the declaration this product takes compiles in a stage")
    fun theDeclarationThisProductTakesCompilesInAStage() {
        val result = compile("pipelinek-declaration-stage.pipeline.kts", stageWithDeclaration())

        assertTrue(
            result.isSuccess,
            "the declaration of this product did not compile in a stage: ${result.diagnostics}",
        )
    }

    /**
     * The declaration this product takes compiles at the level of the pipeline.
     *
     * The published reason says that the variable the PIPELINE declares
     * reaches the steps of every stage, so the declaration has to compile at
     * that level too: a form that only compiles in a stage does not reach the
     * steps of the other stages.
     */
    @Test
    @DisplayName("the declaration this product takes compiles at the level of the pipeline")
    fun theDeclarationThisProductTakesCompilesAtTheLevelOfThePipeline() {
        val result = compile("pipelinek-declaration-pipeline.pipeline.kts", pipelineWithDeclaration())

        assertTrue(
            result.isSuccess,
            "the declaration of this product did not compile at the level of the pipeline: " +
                "${result.diagnostics}",
        )
    }

    /**
     * Asserts that the compiler REFUSED the script and that it NAMED the
     * reference it could not resolve.
     */
    private fun assertRefusedByTheCompiler(result: ScriptCompilationResult, what: String) {
        assertFalse(
            result.isSuccess,
            "$what: ${result.diagnostics}",
        )
        assertTrue(
            result.diagnostics.any { diagnostic ->
                diagnostic.message.contains("Unresolved reference") &&
                    diagnostic.message.contains(publishedName())
            },
            "the compiler names the reference it cannot resolve, so the author knows WHICH " +
                "name failed: ${result.diagnostics}",
        )
    }

    /**
     * A stage whose environment declares the variable the way Jenkins declares it.
     */
    private fun stageWithAssignment(): String = """
        pipeline {
            stages {
                stage("s") {
                    ${environmentBlockHolding(publishedAssignment())}
                    sh("echo hello")
                }
            }
        }
    """.trimIndent()

    /**
     * A pipeline whose environment declares the variable the way Jenkins declares it.
     */
    private fun pipelineWithAssignment(): String = """
        pipeline {
            ${environmentBlockHolding(publishedAssignment())}
            stages {
                stage("s") {
                    sh("echo hello")
                }
            }
        }
    """.trimIndent()

    /**
     * The block of an environment, with [body] inside it.
     *
     * The block is the one the catalogue publishes, and the body is the one it
     * publishes too: the braces are the syntax of Kotlin, and everything the
     * script says about the declaration is what the catalogue says.
     */
    private fun environmentBlockHolding(body: String): String =
        publishedEnvironmentBlock().substringBefore(" {").trim() + " {\n" + body + "\n}"

    /**
     * A stage whose environment declares the variable the way this product declares it.
     */
    private fun stageWithDeclaration(): String = """
        pipeline {
            stages {
                stage("s") {
                    ${publishedDeclaration()}
                    sh("echo hello")
                }
            }
        }
    """.trimIndent()

    /**
     * A pipeline whose environment declares the variable the way this product declares it.
     */
    private fun pipelineWithDeclaration(): String = """
        pipeline {
            ${publishedDeclaration()}
            stages {
                stage("s") {
                    sh("echo hello")
                }
            }
        }
    """.trimIndent()

    /**
     * The reason the catalogue publishes about the declaration of a variable.
     */
    private fun publishedReason(): String = requireNotNull(
        StepCatalogue.divergences().singleOrNull { divergence -> divergence.name == DECLARATION },
    ) {
        "the catalogue publishes no difference about the declaration of a variable: " +
            StepCatalogue.divergences().map { divergence -> divergence.name }
    }.reason

    /**
     * The block of an environment, as the catalogue publishes it.
     */
    private fun publishedEnvironmentBlock(): String = requireNotNull(
        ENVIRONMENT_BLOCK.find(publishedReason())?.groupValues?.get(1),
    ) {
        "the published reason names no block of an environment: ${publishedReason()}"
    }

    /**
     * The assignment of Jenkins, as the catalogue publishes it.
     */
    private fun publishedAssignment(): String = requireNotNull(
        ASSIGNMENT.find(publishedReason())?.groupValues?.get(1),
    ) {
        "the published reason names no assignment of Jenkins: ${publishedReason()}"
    }

    /**
     * The declaration of this product, as the catalogue publishes it.
     */
    private fun publishedDeclaration(): String = requireNotNull(
        DECLARATION_BLOCK.find(publishedReason())?.groupValues?.get(1),
    ) {
        "the published reason names no declaration of this product: ${publishedReason()}"
    }

    /**
     * The name of the variable that the published assignment assigns.
     */
    private fun publishedName(): String = requireNotNull(
        ASSIGNED_NAME.find(publishedAssignment())?.groupValues?.get(1),
    ) {
        "the published assignment names no variable: ${publishedAssignment()}"
    }

    private companion object {
        /** The name the difference carries in the catalogue. */
        const val DECLARATION = "the declaration of a variable"

        /** The block of an environment, as the reason quotes it. */
        val ENVIRONMENT_BLOCK = Regex("""`(environment \{ \})`""")

        /** The assignment of Jenkins, as the reason quotes it. */
        val ASSIGNMENT = Regex("""`([A-Za-z_][A-Za-z0-9_]*\s*=\s*[^`]+)`""")

        /** The declaration of this product, as the reason quotes it. */
        val DECLARATION_BLOCK = Regex("""`(environment \{[^`]*env\([^`]*\)[^`]*\})`""")

        /** The name that an assignment assigns. */
        val ASSIGNED_NAME = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*=""")
    }
}
