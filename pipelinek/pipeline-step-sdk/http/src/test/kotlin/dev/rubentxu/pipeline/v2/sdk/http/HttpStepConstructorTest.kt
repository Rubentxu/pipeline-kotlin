package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.StepBodyScope
import dev.rubentxu.pipeline.v2.dsl.StepEmitter
import dev.rubentxu.pipeline.v2.dsl.StageScope
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The step constructor of a plugin, written on the contract.
 *
 * ## The guarantee under trial
 *
 * That the constructor of a plugin's step works wherever steps are declared,
 * and that it declares the same step through either of its two routes:
 *
 * - the constructor applies in a stage, in a `steps { }` block, in a
 *   `script { }` block, in a wrapper and in a branch of a `parallel { }`
 *   block —the branch is the case the contract opens, because a branch is a
 *   body of steps and not a stage;
 * - the route of the contract and the historic route declare the SAME step,
 *   so a script that names either gets the same plan.
 *
 * ## What this file does NOT freeze
 *
 * The shape of the `StepSpec` family and the order in which the steps run.
 * This file reads the DECLARED steps.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class HttpStepConstructorTest {

    /**
     * The two routes of the constructor declare the same step.
     *
     * The route of the contract and the historic route are two ways to write
     * the same declaration. The step that comes out has to be the same one:
     * the same key, the same payload and the same schema version, because a
     * route that declared another step would make the choice of a route a
     * change of behaviour, and the choice is invisible at the call site.
     */
    @Test
    @DisplayName("the two routes of the constructor declare the same step")
    fun theTwoRoutesOfTheConstructorDeclareTheSameStep() {
        val contractRoute = pipeline {
            stages {
                stage("build") {
                    val emitter: StepEmitter<StepBodyScope> = this
                    emitter.httpRequest(HttpRequestInput(url = "https://example.test/one"))
                }
            }
        }

        val historicRoute = pipeline {
            stages {
                stage("build") {
                    httpRequest("https://example.test/one")
                }
            }
        }

        assertEquals(
            historicRoute.stages.single().steps,
            contractRoute.stages.single().steps,
            "the two routes declare another step",
        )
        assertEquals(
            1,
            contractRoute.stages.single().steps.size,
            "the constructor did not declare exactly one step",
        )
        val step = contractRoute.stages.single().steps.single()
        assertEquals(
            httpRequestStepKey(),
            (step as StepSpec.RegistryStepSpec).stepKey,
            "the step declares another key",
        )
        assertEquals(
            "dsl-v1",
            step.schemaVersion,
            "the step declares another schema version",
        )
    }

    /**
     * The constructor applies in every place a step is declared.
     *
     * A stage, a `steps { }` block, a `script { }` block and a wrapper are
     * four places the same constructor has to work in, and the difference
     * between them is the scope that hosts the declaration and not the
     * declaration itself. The route under trial is the one of the contract:
     * it is the route that has no stage to name.
     */
    @Test
    @DisplayName("the constructor applies in every place a step is declared")
    fun theConstructorAppliesInEveryPlaceAStepIsDeclared() {
        val spec = pipeline {
            stages {
                stage("build") {
                    httpRequest(HttpRequestInput(url = "https://example.test/direct"))
                    steps {
                        httpRequest(HttpRequestInput(url = "https://example.test/steps"))
                    }
                    script {
                        httpRequest(HttpRequestInput(url = "https://example.test/script"))
                    }
                    registryBlock(
                        stepKey = PluginStepId("example.wrapper"),
                        encodedInput = EncodedStepValue("{}"),
                    ) {
                        httpRequest(HttpRequestInput(url = "https://example.test/wrapper"))
                    }
                }
            }
        }

        val steps = spec.stages.single().steps
        assertEquals(
            3,
            steps.count { it is StepSpec.RegistryStepSpec },
            "the constructor did not apply in the stage, in the steps block or in the script " +
                "block: $steps",
        )
        assertEquals(
            1,
            steps.count { it is StepSpec.RegistryBlockSpec },
            "the wrapper did not carry its body: $steps",
        )
        val wrapper = steps.filterIsInstance<StepSpec.RegistryBlockSpec>().single()
        assertEquals(
            1,
            wrapper.body.count { it is StepSpec.RegistryStepSpec },
            "the constructor did not apply inside the wrapper: ${wrapper.body}",
        )
    }

    /**
     * The constructor applies inside a branch of a parallel block.
     *
     * A branch is a body of steps of its own, and it is not a stage: the
     * historic route of the constructor does not apply there, and the route
     * of the contract does. That is the case the contract opens, and it is
     * the reason the constructor does not name a stage.
     */
    @Test
    @DisplayName("the constructor applies inside a branch of a parallel block")
    fun theConstructorAppliesInsideABranchOfAParallelBlock() {
        val spec = pipeline {
            stages {
                stage("build") {
                    parallel {
                        branch("left") {
                            httpRequest(HttpRequestInput(url = "https://example.test/left"))
                        }
                        branch("right") {
                            httpRequest(HttpRequestInput(url = "https://example.test/right"))
                        }
                    }
                }
            }
        }

        val parallel = spec.stages.single().steps.filterIsInstance<StepSpec.Parallel>().single()
        assertEquals(
            2,
            parallel.branches.size,
            "the parallel block did not keep its branches",
        )
        parallel.branches.forEach { branch ->
            assertEquals(
                1,
                branch.steps.count { it is StepSpec.RegistryStepSpec },
                "the constructor did not apply in a branch: ${branch.steps}",
            )
        }
    }
}
