package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.MapRuntimeConfig
import dev.rubentxu.pipeline.v2.domain.RuntimeConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The boundary between a nested body and the scope that surrounds it.
 *
 * ## The guarantees under trial
 *
 * Two of them, and they are the two sides of the same boundary:
 *
 * - what a nested body DECLARES reaches the steps of that body and nothing
 *   else: not the steps that follow it in the same stage, and not the steps of
 *   the sibling stage.
 * - what a nested body PRODUCES is consumed wherever the author consumes it: a
 *   carrier produced in one body and consumed in another body is a carrier
 *   that was used, and the stage admits it.
 *
 * ## Why the boundary is worth a row of its own
 *
 * Because the scope of a nested block is a NEW scope: it has its own steps, its
 * own environment and its own carrier registry — and the two facts above are
 * the ones that must NOT be new. The declaration of a body is the body's own;
 * the obligation of a carrier is the STAGE's, because the stage is the unit
 * that is admitted.
 *
 * The rows that already exist cover the two directions of the carrier rule at
 * the level of the stage: a carrier discarded inside a nested body is refused
 * (which proves the nested body SHARES the stage's registry), and a carrier
 * produced at stage level and consumed inside a nested body is admitted. What
 * was not measured is the boundary in the middle: a carrier produced in one
 * body and consumed in a DIFFERENT body, and a variable declared in a body of
 * one stage reaching the sibling stage.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class NestedScopeBoundaryTest {

    /**
     * The variables a nested body declares do not reach the sibling stage.
     *
     * The block declares the variable its OWN steps run with. The stage that
     * comes after it is a different stage, and it runs with the variables the
     * PIPELINE declares — the declaration of a block of the previous stage is
     * not part of them.
     *
     * The pipeline declares the variable on purpose: a declaration that is only
     * in the block is one the sibling stage could not be expected to run with
     * anyway. Declaring it at the pipeline level gives the sibling stage a
     * value of its own, so the row can tell the two apart — the sibling stage
     * answers with the value the PIPELINE declared and not with the value the
     * BLOCK declared.
     */
    @Test
    @DisplayName("the variables a nested body declares do not reach the sibling stage")
    fun theVariablesANestedBodyDeclaresDoNotReachTheSiblingStage() {
        var inside: String? = null
        var siblingTarget: String? = null
        var siblingRegistry: String? = null

        pipeline(host()) {
            environment {
                env("TARGET", "from-the-pipeline")
                env("REGISTRY", "from-the-pipeline")
            }
            stages {
                stage("build") {
                    withEnv(listOf("TARGET=from-the-block")) {
                        inside = env("TARGET")
                    }
                }
                stage("verify") {
                    siblingTarget = env("TARGET")
                    siblingRegistry = env("REGISTRY")
                }
            }
        }

        assertEquals(
            "from-the-block",
            inside,
            "the block declares the variable its own steps run with",
        )
        assertEquals(
            "from-the-pipeline",
            siblingTarget,
            "the block declares the variables of ITS steps, and the sibling stage runs with " +
                "the variables the pipeline declares",
        )
        assertEquals(
            "from-the-pipeline",
            siblingRegistry,
            "the sibling stage runs with the variables the pipeline declares",
        )
    }

    /**
     * A carrier produced in one body and consumed in another body is admitted.
     *
     * The obligation of a carrier belongs to the STAGE, and the registry of the
     * stage is shared by every body of that stage. A carrier produced inside
     * one body and consumed inside another body of the same stage is a carrier
     * that was used: the stage admits the pipeline, and the checkout reaches
     * the IR.
     */
    @Test
    @DisplayName("a carrier produced in one body and consumed in another body is admitted")
    fun aCarrierProducedInOneBodyAndConsumedInAnotherBodyIsAdmitted() {
        val spec = pipeline(host()) {
            stages {
                stage("build") {
                    dir("workspace") {
                        val carrier = scmGit("https://example.test/otro-cuerpo.git")
                        withEnv(listOf("TARGET=from-the-block")) {
                            checkout(carrier)
                        }
                    }
                }
            }
        }

        val steps = spec.stages.single().steps
        assertTrue(
            steps.none { it is StepSpec.Error },
            "an admitted stage carries no error step: $steps",
        )
        val nested = steps.filterIsInstance<StepSpec.Dir>().single().steps
        val inner = nested.filterIsInstance<StepSpec.WithEnv>().single().steps
        assertTrue(
            inner.any { it is StepSpec.Checkout },
            "the checkout the author asked for is in the IR: $inner",
        )
    }

    /** The host the rows read from: a machine that declares nothing of its own. */
    private fun host(): RuntimeConfig =
        MapRuntimeConfig(env = emptyMap(), properties = emptyMap())
}
