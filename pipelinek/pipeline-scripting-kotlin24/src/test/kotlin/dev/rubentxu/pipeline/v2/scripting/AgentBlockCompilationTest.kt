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
 * The block of the agent, as the COMPILER sees it.
 *
 * ## The guarantee under trial
 *
 * That the script a reader who comes from Jenkins writes —an `agent { }` block
 * with a `label` inside it— compiles here without a single diagnostic, at the
 * level of the pipeline and at the level of a stage, and that the block does
 * NOT admit a step.
 *
 * ## Why this instrument is the compiler
 *
 * Because the fact is a fact of the COMPILER and of nothing else: whether a
 * shape of Jenkins is accepted is decided where the script is compiled, and a
 * catalogue that published the shape without the compiler behind it would
 * publish a promise the product does not keep.
 *
 * ## Why the block admits NO step
 *
 * Because Jenkins does not admit one either: the block `agent { }` of Jenkins
 * holds the OPTIONS of the agent —`label`, `customWorkspace`, `reuseNode`,
 * `args`, `image`, `yaml`— and not a single step. The block of this product
 * restricts its vocabulary the way Jenkins restricts it, and the way it
 * restricts it is the TYPE of the block: a step of the stage is out of reach
 * from inside it.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that the agent the block declares is the agent the stage
 * runs with: that is a fact of the DSL, and the row that pins it lives with the
 * DSL.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AgentBlockCompilationTest {

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
     * The block compiles in a stage, and it compiles silently.
     */
    @Test
    @DisplayName("the block of the agent compiles in a stage, the way a Jenkinsfile writes it")
    fun theBlockOfTheAgentCompilesInAStage() {
        val result = compile("jenkins-agent-block-stage.pipeline.kts", AGENT_BLOCK_IN_A_STAGE)

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
     * The block compiles at the level of the pipeline, which is where Jenkins
     * writes it.
     */
    @Test
    @DisplayName("the block of the agent compiles at the level of the pipeline")
    fun theBlockOfTheAgentCompilesAtTheLevelOfThePipeline() {
        val result = compile("jenkins-agent-block-pipeline.pipeline.kts", AGENT_BLOCK_IN_A_PIPELINE)

        assertTrue(
            result.isSuccess,
            "the block of Jenkins does not compile at the level of the pipeline: " +
                "${result.diagnostics}",
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
     * The block does not admit a step.
     *
     * The row that says the restriction is REAL: without it, the block would
     * carry the scope that opened it, and a step written inside it would be
     * declared as a step of the stage — a shape Jenkins refuses.
     */
    @Test
    @DisplayName("the block of the agent does not admit a step")
    fun theBlockOfTheAgentDoesNotAdmitAStep() {
        val result = compile("jenkins-agent-block-step.pipeline.kts", AGENT_BLOCK_WITH_A_STEP)

        assertFalse(
            result.isSuccess,
            "the block of the agent admits a step, and the block of Jenkins does not: " +
                "${result.diagnostics}",
        )
        assertTrue(
            result.diagnostics.any { diagnostic -> diagnostic.message.contains(STEP_SIGNATURE) },
            "the compiler refuses the step of the block, and it names the step it refuses: " +
                "${result.diagnostics}",
        )
    }

    /**
     * The block does not admit a SECTION either, at the level of the pipeline.
     *
     * The row that says the restriction reaches the other level: the block
     * opens inside a pipeline as well, and a `stages { }` written inside it
     * would declare the stages of the pipeline from inside the declaration of
     * its agent.
     */
    @Test
    @DisplayName("the block of the agent does not admit a section at the level of the pipeline")
    fun theBlockOfTheAgentDoesNotAdmitASectionAtTheLevelOfThePipeline() {
        val result = compile("jenkins-agent-block-section.pipeline.kts", AGENT_BLOCK_WITH_A_SECTION)

        assertFalse(
            result.isSuccess,
            "the block of the agent admits a section, and the block of Jenkins does not: " +
                "${result.diagnostics}",
        )
        assertTrue(
            result.diagnostics.any { diagnostic -> diagnostic.message.contains(SECTION_SIGNATURE) },
            "the compiler refuses the section of the block, and it names what it refuses: " +
                "${result.diagnostics}",
        )
    }

    private companion object {
        /**
         * The signature of the step the block does not admit.
         *
         * It is the SIGNATURE and not the name alone: the compiler does not
         * answer "Unresolved reference 'sh'" here —the scope that opened the
         * block is hidden, and the step is still known— so a row that looked
         * for the name would not find it.
         */
        const val STEP_SIGNATURE = "sh(command: String)"

        /**
         * The signature of the section the block does not admit.
         */
        const val SECTION_SIGNATURE = "stages(block: StagesScope"

        /**
         * A stage that declares its agent the way a Jenkinsfile writes it.
         */
        val AGENT_BLOCK_IN_A_STAGE = """
            pipeline {
                stages {
                    stage("s") {
                        agent {
                            label("linux")
                        }
                    }
                }
            }
        """.trimIndent()

        /**
         * A pipeline that declares its agent the way a Jenkinsfile writes it.
         */
        val AGENT_BLOCK_IN_A_PIPELINE = """
            pipeline {
                agent {
                    label("linux")
                }
                stages {
                    stage("s") {
                        echo("hello")
                    }
                }
            }
        """.trimIndent()

        /**
         * A step written inside the block, which Jenkins does not admit either.
         */
        val AGENT_BLOCK_WITH_A_STEP = """
            pipeline {
                stages {
                    stage("s") {
                        agent {
                            sh("echo hello")
                        }
                    }
                }
            }
        """.trimIndent()

        /**
         * A section written inside the block, which Jenkins does not admit
         * either.
         */
        val AGENT_BLOCK_WITH_A_SECTION = """
            pipeline {
                agent {
                    stages {
                        stage("s") {
                            echo("hello")
                        }
                    }
                }
            }
        """.trimIndent()
    }
}
