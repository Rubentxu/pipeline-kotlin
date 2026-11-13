package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.directivekey.AGENT_DIRECTIVE_KEY
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The agent a script declares with the BLOCK, as the DSL decides it.
 *
 * ## The guarantee under trial
 *
 * That the block a Jenkinsfile writes —`agent { label("linux") }`— declares the
 * SAME agent the call declares —`agent(label = "linux")`— and that the pipeline
 * refuses a second agent however it was declared.
 *
 * ## Why the block is not a second path
 *
 * Because a block and a call that declare the same agent have to declare the
 * same agent: the requirement is built by ONE authority and carried by ONE
 * directive (`core.agent`). Two spellings of one declaration are a
 * convenience; two spellings of two declarations would put two requirements of
 * the same directive on a stage, and the planner refuses that.
 *
 * ## Why the refusals live HERE
 *
 * Because a refusal that names the mistake where it is written is worth more
 * than a refusal that arrives later, naming a plan. The empty block and the
 * block with two labels are refused while the block is being read, and the
 * message says what to write.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that the requirement is SATISFIED: that is the runtime's
 * decision, and it is decided against the machine the run is on.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class AgentBlockTest {

    /**
     * The block declares the agent the call declares.
     */
    @Test
    @DisplayName("the block declares the agent the call declares")
    fun theBlockDeclaresTheAgentTheCallDeclares() {
        val fromBlock = pipeline {
            stages {
                stage("s") {
                    agent { label("linux") }
                }
            }
        }
        val fromCall = pipeline {
            stages {
                stage("s") {
                    agent(label = "linux")
                }
            }
        }

        assertEquals(
            fromCall.stages.single().directives,
            fromBlock.stages.single().directives,
            "the block and the call declare the SAME agent: one authority builds the requirement " +
                "and one directive carries it, so the two spellings cannot say different things",
        )
        assertEquals(
            1,
            fromBlock.stages.single().directives.count { directive ->
                directive.key == AGENT_DIRECTIVE_KEY.value
            },
            "the block declares ONE agent: a stage that carried two requirements of the same " +
                "directive would be refused by the planner",
        )
    }

    /**
     * The block at the level of the pipeline is the default of the stages that
     * declare none.
     */
    @Test
    @DisplayName("the block at the level of the pipeline is the default of the stages that declare none")
    fun theBlockAtTheLevelOfThePipelineIsTheDefaultOfTheStagesThatDeclareNone() {
        val spec = pipeline {
            agent { label("linux") }
            stages {
                stage("build") {
                    echo("hello")
                }
            }
        }
        val fromCall = pipeline {
            agent(label = "linux")
            stages {
                stage("build") {
                    echo("hello")
                }
            }
        }

        val agents = spec.stages.single().directives
            .filter { directive -> directive.key == AGENT_DIRECTIVE_KEY.value }
        assertEquals(
            1,
            agents.size,
            "the pipeline declares an agent with the block and the stage that declares none " +
                "inherits it: the stage carries ONE requirement of agent, the one of the pipeline",
        )
        assertEquals(
            agentArgumentsOf(fromCall),
            agents.single().encodedArguments,
            "the block of the pipeline declares the agent the call declares, and the stage that " +
                "inherits it inherits THAT agent",
        )
    }

    /**
     * A pipeline that declares a second agent with the block is refused where
     * it declares it.
     */
    @Test
    @DisplayName("a pipeline that declares a second agent with the block is refused where it declares it")
    fun aPipelineThatDeclaresASecondAgentWithTheBlockIsRefusedWhereItDeclaresIt() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            pipeline {
                agent { label("first") }
                agent(label = "second")
            }
        }

        assertTrue(
            refusal.message?.contains("the pipeline declares ONE agent") == true,
            "the refusal names the contradiction and what to do: the message was " +
                "'${refusal.message}'",
        )
    }

    /**
     * A block without a label is refused, and the message says what to write.
     */
    @Test
    @DisplayName("a block without a label is refused, and the message says what to write")
    fun aBlockWithoutALabelIsRefused() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            pipeline {
                stages {
                    stage("s") {
                        agent { }
                    }
                }
            }
        }

        assertTrue(
            refusal.message?.contains("label") == true,
            "the message names the option the block needs: the message was '${refusal.message}'",
        )
    }

    /**
     * A block with two labels is refused: the block of Jenkins has ONE `label`.
     */
    @Test
    @DisplayName("a block with two labels is refused")
    fun aBlockWithTwoLabelsIsRefused() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            pipeline {
                stages {
                    stage("s") {
                        agent {
                            label("first")
                            label("second")
                        }
                    }
                }
            }
        }

        assertTrue(
            refusal.message?.contains("one label") == true ||
                refusal.message?.contains("ONE label") == true,
            "the message says that the block declares one label: the message was " +
                "'${refusal.message}'",
        )
    }

    /**
     * The agent the spec declares, as the arguments of the directive that
     * carries it.
     */
    private fun agentArgumentsOf(spec: PipelineSpec): String =
        spec.stages.single().directives
            .single { directive -> directive.key == AGENT_DIRECTIVE_KEY.value }
            .encodedArguments
}
