package dev.rubentxu.pipeline.v2.scripting

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
 * That the assignment of Jenkins does not compile here, that the compiler says
 * WHICH reference it cannot resolve, and that the declaration this product
 * takes compiles.
 *
 * ## Why this instrument is the compiler
 *
 * Because the two facts are facts of the COMPILER and of nothing else: the
 * absence of a declaration is not a fact a catalogue can state on its own, and
 * a catalogue that published it without the compiler behind it would publish a
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
     * The assignment of Jenkins does not compile, and the compiler names it.
     */
    @Test
    @DisplayName("the assignment of Jenkins does not compile, and the compiler names it")
    fun theAssignmentOfJenkinsDoesNotCompileAndTheCompilerNamesIt() {
        val result = compile("jenkins-assignment.pipeline.kts", JENKINS_ASSIGNMENT)

        assertFalse(
            result.isSuccess,
            "the assignment of Jenkins compiled: ${result.diagnostics}",
        )
        assertTrue(
            result.diagnostics.any { diagnostic ->
                diagnostic.message.contains("Unresolved reference") &&
                    diagnostic.message.contains("FOO")
            },
            "the compiler names the reference it cannot resolve, so the author knows WHICH " +
                "name failed: ${result.diagnostics}",
        )
    }

    /**
     * The declaration this product takes compiles.
     *
     * The control of the row above: a form the product refuses would make the
     * refusal above a fact about two broken forms instead of a fact about the
     * one of Jenkins.
     */
    @Test
    @DisplayName("the declaration this product takes compiles")
    fun theDeclarationThisProductTakesCompiles() {
        val result = compile("pipelinek-declaration.pipeline.kts", PIPELINEK_DECLARATION)

        assertTrue(
            result.isSuccess,
            "the declaration of this product did not compile: ${result.diagnostics}",
        )
    }

    private companion object {
        /**
         * A stage that declares a variable the way Jenkins declares it.
         */
        val JENKINS_ASSIGNMENT = """
            pipeline {
                stages {
                    stage("s") {
                        environment {
                            FOO = "x"
                        }
                        sh("echo hello")
                    }
                }
            }
        """.trimIndent()

        /**
         * A stage that declares a variable the way this product declares it.
         */
        val PIPELINEK_DECLARATION = """
            pipeline {
                stages {
                    stage("s") {
                        environment {
                            env("FOO", "x")
                        }
                        sh("echo hello")
                    }
                }
            }
        """.trimIndent()
    }
}
