package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.post.PostCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The owner of a sequence of steps, and the three scopes that declare into it.
 *
 * ## The guarantee under trial
 *
 * That a step is BUILT in one place and DECLARED into one list:
 *
 * - the stage scope, the branch scope and the post scope declare the SAME step
 *   for the same call, because the step is built by the owner and not written
 *   three times;
 * - a scope publishes the list it declares into, so the steps a consumer reads
 *   are the steps the scope declared;
 * - the post scope keeps a sequence per condition, and a nested body keeps a
 *   sequence of its own.
 *
 * ## Why the BRANCH and the POST scope are under trial
 *
 * Because they are the two scopes that had NO row: the stage scope was
 * measured, and the branch scope and the post scope were written with the same
 * builders and never measured. A scope that is written twice and measured
 * once is a scope that can drift, and the drift is invisible until somebody
 * compares the two.
 *
 * ## What this row does NOT freeze
 *
 * The shape of the `StepSpec` family, the vocabulary of the scopes, and the
 * order in which the steps of a scope run — the order is the compiler's and
 * the engine's business, and the rows of the compiler and of the runtime
 * measure it. This file reads the DECLARED steps.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StepSequenceOwnershipTest {

    /**
     * The three scopes declare the same step for the same call.
     *
     * The stage scope, the branch scope and the post scope declare the four
     * builders they share, and the steps they declare are EQUAL: a step that
     * is built in two places is a step that can differ in two places, and this
     * row is what says that it does not.
     *
     * The legacy spelling of `error` declares the same step as the typed one:
     * the token is validated and translated in one place, and the step that
     * comes out is the step the typed call declares.
     */
    @Test
    @DisplayName("the three scopes declare the same step for the same call")
    fun theThreeScopesDeclareTheSameStepForTheSameCall() {
        val spec = pipeline {
            stages {
                stage("build") {
                    echo("hello")
                    sh("echo hello")
                    error("boom")
                    sleep(2)
                    post {
                        always {
                            echo("hello")
                            sh("echo hello")
                            error("boom")
                            sleep(2)
                        }
                    }
                    parallel {
                        branch("left") {
                            echo("hello")
                            sh("echo hello")
                            error("boom")
                            sleep(2)
                        }
                    }
                }
            }
        }

        val stage = spec.stages.single()
        val branch = stage.steps.filterIsInstance<StepSpec.Parallel>().single()
            .branches.single().steps
        val post = stage.post.conditions.getValue(PostCondition.ALWAYS)

        val expected = listOf(
            StepSpec.Echo("hello"),
            StepSpec.Shell("echo hello"),
            StepSpec.Error("boom"),
            StepSpec.Sleep(2),
        )
        assertEquals(expected, stage.steps.filter { it !is StepSpec.Parallel }, "the stage scope declares another step")
        assertEquals(expected, branch, "the branch scope declares another step")
        assertEquals(expected, post, "the post scope declares another step")

        val legacy = pipeline {
            stages {
                stage("build") {
                    error("boom", "USER")
                }
            }
        }
        assertEquals(
            listOf(StepSpec.Error("boom", FailureKind.USER)),
            legacy.stages.single().steps,
            "the legacy spelling of error declares another step than the typed one",
        )
    }

    /**
     * A step declared by a scope is in the list that scope publishes.
     *
     * The scope publishes the list it declares into: there is ONE list, and
     * the step a consumer reads is the step the scope declared. A scope that
     * kept a list of its own beside the published one had two lists, and the
     * step could land in the one nobody reads.
     */
    @Test
    @DisplayName("a step declared by a scope is in the list that scope publishes")
    fun aStepDeclaredByAScopeIsInTheListThatScopePublishes() {
        val branch = BranchScope()
        branch.echo("hello")
        branch.sh("echo hello")

        assertEquals(
            listOf(StepSpec.Echo("hello"), StepSpec.Shell("echo hello")),
            branch.steps,
            "the branch scope publishes a list that is not the one it declares into",
        )

        val post = PostStepsScope()
        post.echo("hello")
        post.sleep(3)

        assertEquals(
            listOf(StepSpec.Echo("hello"), StepSpec.Sleep(3)),
            post.steps,
            "the post scope publishes a list that is not the one it declares into",
        )
    }

    /**
     * The post scope keeps a sequence per condition.
     *
     * The seven conditions are seven bodies, and a body is a sequence of its
     * own: the steps of the `always` block are the steps that block declared,
     * and the steps of the `failure` block are the steps that block declared.
     * A scope that kept ONE list for the seven would put the steps of a
     * condition in a condition the author did not write.
     */
    @Test
    @DisplayName("the post scope keeps a sequence per condition")
    fun thePostScopeKeepsASequencePerCondition() {
        val spec = pipeline {
            stages {
                stage("build") {
                    post {
                        always {
                            echo("first")
                        }
                        failure {
                            echo("second")
                        }
                    }
                }
            }
        }

        val conditions = spec.stages.single().post.conditions
        assertEquals(
            listOf(StepSpec.Echo("first")),
            conditions[PostCondition.ALWAYS],
            "the always condition carries another sequence",
        )
        assertEquals(
            listOf(StepSpec.Echo("second")),
            conditions[PostCondition.FAILURE],
            "the failure condition carries another sequence",
        )
    }

    /**
     * The body of a nested block has a sequence of its own.
     *
     * The body of a `dir { }` inside a branch is the body's own sequence, and
     * the branch's sequence carries the BLOCK. A body that declared into the
     * sequence of the scope that opened it would put its steps beside the
     * block that governs them.
     */
    @Test
    @DisplayName("the body of a nested block has a sequence of its own")
    fun theBodyOfANestedBlockHasASequenceOfItsOwn() {
        val spec = pipeline {
            stages {
                stage("build") {
                    parallel {
                        branch("left") {
                            dir("sub") {
                                sh("echo inside")
                            }
                        }
                    }
                }
            }
        }

        val branch = spec.stages.single().steps.filterIsInstance<StepSpec.Parallel>().single()
            .branches.single().steps
        assertEquals(
            listOf("dir"),
            branch.map { it.name },
            "the branch carries another step than the block it declares",
        )
        val block = branch.single() as StepSpec.Dir
        assertEquals(
            listOf(StepSpec.Shell("echo inside")),
            block.steps,
            "the body of the block carries another sequence",
        )
    }
}
