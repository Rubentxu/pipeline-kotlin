package dev.rubentxu.pipeline.v2.scripting

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The block of the steps of a stage, as the COMPILER sees it.
 *
 * ## The guarantee under trial
 *
 * That the script a reader who comes from Jenkins writes —a `steps { }` block
 * inside a stage— compiles here without a single diagnostic, and that the product
 * does not answer with a signature of its own.
 *
 * ## Why this instrument is the compiler
 *
 * Because the fact is a fact of the COMPILER and of nothing else: whether a shape
 * of Jenkins is accepted is decided where the script is compiled, and a catalogue
 * that published the shape without the compiler behind it would publish a promise
 * the product does not keep. The script that writes the block is the one that has
 * to compile, and the compiler is the only thing that can accept it.
 *
 * ## What the diagnostic used to say
 *
 * Two diagnostics, and the second one named an internal signature of the product
 * (`Too many arguments for 'fun steps(): List<StepSpec>'`). A reader who wants to
 * write a pipeline does not know what a `StepSpec` is, and the diagnostic said
 * nothing about what to write instead.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that the steps the block declares are the steps of the stage:
 * that is a fact of the DSL, and the row that pins it lives with the DSL.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class StepsBlockCompilationTest {

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
     * The block of Jenkins compiles, and it compiles without a diagnostic.
     */
    @Test
    @DisplayName("the block of the steps of a stage compiles, the way a Jenkinsfile writes it")
    fun theBlockOfTheStepsOfAStageCompiles() {
        val result = compile("jenkins-steps-block.pipeline.kts", JENKINS_STEPS_BLOCK)

        assertTrue(
            result.isSuccess,
            "the block of Jenkins does not compile: ${result.diagnostics}",
        )
        assertTrue(
            result.diagnostics.none { diagnostic ->
                diagnostic.severity == ScriptDiagnosticSeverity.ERROR ||
                    diagnostic.severity == ScriptDiagnosticSeverity.WARNING
            },
            "the block of Jenkins compiles without an error and without a warning, and the " +
                "product does not answer with a diagnostic of its own: ${result.diagnostics}",
        )
    }

    /**
     * The product does not answer with a signature of its own.
     *
     * The row above says that the script compiles; this one says WHY it matters that
     * it compiles SILENTLY: a diagnostic that names an internal signature is a
     * diagnostic that spends the reader's attention on a detail of the product that
     * the reader cannot act on.
     */
    @Test
    @DisplayName("the product does not answer with a signature of its own")
    fun theProductDoesNotAnswerWithASignatureOfItsOwn() {
        val result = compile("jenkins-steps-block-signature.pipeline.kts", JENKINS_STEPS_BLOCK)

        assertTrue(
            result.diagnostics.none { diagnostic -> diagnostic.message.contains(INTERNAL_SIGNATURE) },
            "the product does not name its own signature to a reader who writes a pipeline: " +
                "${result.diagnostics}",
        )
    }

    private companion object {
        /**
         * The signature the product used to answer with.
         */
        const val INTERNAL_SIGNATURE = "fun steps(): List<StepSpec>"

        /**
         * A stage that declares its steps the way a Jenkinsfile writes them.
         */
        val JENKINS_STEPS_BLOCK = """
            pipeline {
                stages {
                    stage("s") {
                        steps {
                            sh("echo hello")
                        }
                    }
                }
            }
        """.trimIndent()
    }
}
