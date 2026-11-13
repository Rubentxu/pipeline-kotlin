package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.directivekey.WHEN_DIRECTIVE_KEY
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The declarations of the STAGE, written inside a block that carries steps.
 *
 * ## The guarantee under trial
 *
 * That a declaration that belongs to the STAGE —the gate, the declarative
 * environment, the options, the post conditions and the directives— is
 * REFUSED where it is written when it is written inside a block that carries
 * steps, and that the stage itself keeps every one of those declarations.
 *
 * ## Why the refusal and not the MERGE
 *
 * Because a nested scope is not a stage: it is a lexical block of the stage
 * that opened it, and only its STEPS reach the plan. A gate declared inside
 * one used to be dropped in silence, and the stage then ran UNCONDITIONALLY:
 * a `when` that does not gate is the worst direction a gate can fail in.
 * Merging it into the stage would be worse than dropping it, because the
 * gate would then guard the WHOLE stage instead of the block it was written
 * in — a rule the author never asked for.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that the stage's own declarations work at RUN time: the
 * gate is decided by the coordinator. What it affirms is that the declaration
 * REACHES the plan, and that the one written in the wrong place never reaches
 * anything.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class NestedStageAuthorityTest {

    /**
     * A gate declared inside `steps { }` is refused.
     *
     * The row that names the defect: the block carries steps, so it has no
     * authority over the stage, and the gate it declares would never reach
     * the plan.
     */
    @Test
    @DisplayName("a gate declared inside `steps { }` is refused")
    fun aGateDeclaredInsideStepsIsRefused() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            pipeline {
                stages {
                    stage("deploy") {
                        steps {
                            (this as StageScope).whenEnvIs("APPROVED", "true")
                            sh("./deploy.sh")
                        }
                    }
                }
            }
        }

        assertTrue(
            refusal.message?.contains("`whenEnvIs`") == true,
            "the refusal names the declaration that is refused: the message was " +
                "'${refusal.message}'",
        )
        assertTrue(
            refusal.message?.contains("`steps { }`") == true,
            "the refusal names the context where it was written: the message was " +
                "'${refusal.message}'",
        )
        assertTrue(
            refusal.message?.contains("body of the stage") == true,
            "the refusal names the alternative: the message was '${refusal.message}'",
        )
    }

    /**
     * The refusal happens where the declaration is written, before any step of
     * the block is admitted.
     *
     * The row that says the gate is refused BEFORE the block runs: the step
     * that follows the refused declaration is never declared, so no plan can
     * carry it.
     */
    @Test
    @DisplayName("the refusal of a gate happens where the gate is written, before any step of the block")
    fun theRefusalOfAGateHappensWhereTheGateIsWritten() {
        var reachedTheStep = false

        assertThrows(IllegalArgumentException::class.java) {
            pipeline {
                stages {
                    stage("deploy") {
                        steps {
                            (this as StageScope).whenEnvIs("APPROVED", "true")
                            reachedTheStep = true
                            sh("./deploy.sh")
                        }
                    }
                }
            }
        }

        assertFalse(
            reachedTheStep,
            "the declaration after the refused one never ran: the refusal happens where the " +
                "declaration is written, so the block admits no step at all",
        )
    }

    /**
     * The gate of the BODY of the stage still reaches the plan.
     *
     * The row that says the refusal is about the PLACE and not about the
     * declaration: the same gate, written where the stage has authority,
     * reaches the plan.
     */
    @Test
    @DisplayName("the gate of the body of the stage still reaches the plan")
    fun theGateOfTheBodyOfTheStageStillReachesThePlan() {
        val spec = pipeline {
            stages {
                stage("deploy") {
                    (this as StageScope).whenEnvIs("APPROVED", "true")
                    sh("./deploy.sh")
                }
            }
        }

        val gates = spec.stages.single().directives
            .filter { directive -> directive.key == WHEN_DIRECTIVE_KEY.value }
        assertEquals(
            1,
            gates.size,
            "the gate the stage declares reaches the plan: the stage is where the gate has " +
                "authority",
        )
    }

    /**
     * EVERY block that carries steps refuses the declaration of the stage.
     *
     * The row that says the rule is about the KIND of block and not about one
     * spelling of it: the same declaration, in each of the blocks the DSL
     * opens, is refused with the same words.
     */
    @Test
    @DisplayName("every block that carries steps refuses the declaration of the stage")
    fun everyBlockThatCarriesStepsRefusesTheDeclarationOfTheStage() {
        for ((blockName, block) in BLOCKS) {
            val refusal = assertThrows(
                IllegalArgumentException::class.java,
                {
                    pipeline {
                        stages {
                            stage("deploy") {
                                block()
                            }
                        }
                    }
                },
                "the block `$blockName { }` accepted a declaration of the stage",
            )

            assertTrue(
                refusal.message?.contains("`$blockName { }`") == true,
                "the refusal of the block `$blockName { }` names the block: the message was " +
                    "'${refusal.message}'",
            )
        }
    }

    /**
     * The declarative environment of a nested block is refused.
     */
    @Test
    @DisplayName("the declarative environment of a nested block is refused")
    fun theDeclarativeEnvironmentOfANestedBlockIsRefused() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            pipeline {
                stages {
                    stage("deploy") {
                        steps {
                            (this as StageScope).environment { env("TARGET", "prod") }
                            sh("./deploy.sh")
                        }
                    }
                }
            }
        }

        assertTrue(
            refusal.message?.contains("`environment`") == true,
            "the refusal names the declaration that is refused: the message was " +
                "'${refusal.message}'",
        )
    }

    /**
     * `options { }` inside a nested block is refused.
     */
    @Test
    @DisplayName("`options { }` inside a nested block is refused")
    fun optionsInsideANestedBlockIsRefused() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            pipeline {
                stages {
                    stage("deploy") {
                        steps {
                            (this as StageScope).options { timeout(60) }
                            sh("./deploy.sh")
                        }
                    }
                }
            }
        }

        assertTrue(
            refusal.message?.contains("`options`") == true,
            "the refusal names the declaration that is refused: the message was " +
                "'${refusal.message}'",
        )
    }

    /**
     * `post { }` inside a nested block is refused.
     */
    @Test
    @DisplayName("`post { }` inside a nested block is refused")
    fun postInsideANestedBlockIsRefused() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            pipeline {
                stages {
                    stage("deploy") {
                        steps {
                            (this as StageScope).post { always { sh("./cleanup.sh") } }
                            sh("./deploy.sh")
                        }
                    }
                }
            }
        }

        assertTrue(
            refusal.message?.contains("`post`") == true,
            "the refusal names the declaration that is refused: the message was " +
                "'${refusal.message}'",
        )
    }

    /**
     * `directives { }` inside a nested block is refused.
     */
    @Test
    @DisplayName("`directives { }` inside a nested block is refused")
    fun directivesInsideANestedBlockIsRefused() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            pipeline {
                stages {
                    stage("deploy") {
                        steps {
                            (this as StageScope).directives { directive("acme.lock", """{"resource":"prod"}""") }
                            sh("./deploy.sh")
                        }
                    }
                }
            }
        }

        assertTrue(
            refusal.message?.contains("`directives`") == true,
            "the refusal names the declaration that is refused: the message was " +
                "'${refusal.message}'",
        )
    }

    /**
     * The wrappers that carry steps keep working inside a nested block.
     *
     * The row that says the refusal reaches the declarations of the STAGE and
     * not the steps: `withEnv` and the credentials wrapper are steps that
     * carry a body, and a body of steps is what a nested block is for.
     */
    @Test
    @DisplayName("the wrappers that carry steps keep working inside a nested block")
    fun theWrappersThatCarryStepsKeepWorkingInsideANestedBlock() {
        val spec = pipeline {
            stages {
                stage("deploy") {
                    steps {
                        withEnv(listOf("TARGET=prod")) {
                            sh("./deploy.sh")
                        }
                        environment("acme-credentials", "TOKEN") {
                            sh("./read-token.sh")
                        }
                    }
                }
            }
        }

        val steps = spec.stages.single().steps
        assertEquals(
            2,
            steps.size,
            "the two wrappers the block declares reach the plan as the steps they are",
        )
    }

    private companion object {

        /**
         * The blocks the DSL opens that carry STEPS and no authority over the
         * stage.
         *
         * The list is the rule's reach: the refusal is not about one spelling
         * of a block but about the KIND, and every block the product opens is
         * here — including the one a plugin opens with `registryBlock`.
         */
        val BLOCKS: List<Pair<String, StageScope.() -> Unit>> = listOf(
            "steps" to { steps { (this as StageScope).whenEnvIs("APPROVED", "true") } },
            "script" to { script { (this as StageScope).whenEnvIs("APPROVED", "true") } },
            "dir" to { dir("build") { (this as StageScope).whenEnvIs("APPROVED", "true") } },
            "retry" to { retry(2) { (this as StageScope).whenEnvIs("APPROVED", "true") } },
            "withEnv" to { withEnv(listOf("TARGET=prod")) { (this as StageScope).whenEnvIs("APPROVED", "true") } },
            "withCredentials" to {
                withCredentials(listOf(StepSpec.CredentialsBinding.string("acme", "TOKEN"))) {
                    (this as StageScope).whenEnvIs("APPROVED", "true")
                }
            },
            "registryBlock" to {
                registryBlock(
                    stepKey = PluginStepId("acme.block"),
                    encodedInput = EncodedStepValue("{}"),
                ) {
                    (this as StageScope).whenEnvIs("APPROVED", "true")
                }
            },
        )
    }
}
