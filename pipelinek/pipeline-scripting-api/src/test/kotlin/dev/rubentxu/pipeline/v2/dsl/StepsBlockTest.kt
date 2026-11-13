package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The block of the steps of a stage, as Jenkins writes it.
 *
 * ## The guarantee
 *
 * A `steps { }` block holds the STEPS of the stage that declares it: every call
 * inside it is a step of its own, with its own descriptor and its own policy, and
 * those steps are the steps of the stage, in the order they were declared. The
 * block is an ENVELOPE: it does not declare a step of its own and it does not
 * change what the stage carries.
 *
 * ## Why the block exists at all
 *
 * Because it is how a Jenkinsfile writes it:
 *
 * ```groovy
 * stage('build') {
 *     steps {
 *         sh 'make'
 *     }
 * }
 * ```
 *
 * A reader who comes from Jenkins writes that block, and the product used to answer
 * with a diagnostic that named an internal signature of the product
 * (`Too many arguments for 'fun steps(): List<StepSpec>'`) and said nothing about
 * what to write instead. The name of Jenkins is the name of the block here.
 *
 * ## Why the instrument is this cheap
 *
 * A declaration is plain data, so the question is a call over it: what the author
 * writes, and what the stage carries. The rows write the block and read the steps
 * the stage carries, so a block that folded them into one shell, that lost one of
 * them, or that wrapped them in a step of its own would fail here.
 *
 * ## What the block does NOT declare
 *
 * A step of its own. The block is the envelope of the steps of the stage, and the
 * stage carries exactly the steps that were declared, in order, whether they were
 * written in the body of the stage or inside the block.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StepsBlockTest {

    @Test
    fun `the block declares an echo step of the stage`() {
        val spec = pipeline {
            stages {
                stage("s") {
                    steps { echo("x") }
                }
            }
        }

        val step = spec.stages.single().steps.single()
        assertInstanceOf(StepSpec.Echo::class.java, step, "the block declares a step of its own")
        assertEquals("x", (step as StepSpec.Echo).text)
    }

    @Test
    fun `the block declares a shell step of the stage`() {
        val spec = pipeline {
            stages {
                stage("s") {
                    steps { sh("y") }
                }
            }
        }

        val step = spec.stages.single().steps.single()
        assertInstanceOf(StepSpec.Shell::class.java, step, "the block declares a step of its own")
        assertEquals("y", (step as StepSpec.Shell).command)
    }

    @Test
    fun `two calls in the block declare two steps of the stage`() {
        val spec = pipeline {
            stages {
                stage("s") {
                    steps {
                        echo("x")
                        sh("y")
                    }
                }
            }
        }

        val steps = spec.stages.single().steps
        assertEquals(2, steps.size, "the stage carries the two steps the block declares")
        assertInstanceOf(StepSpec.Echo::class.java, steps[0], "the first step is the echo")
        assertInstanceOf(StepSpec.Shell::class.java, steps[1], "the second step is the shell")
    }

    @Test
    fun `the block does not declare a step of its own`() {
        val spec = pipeline {
            stages {
                stage("s") {
                    steps { echo("x") }
                }
            }
        }

        val steps = spec.stages.single().steps
        assertEquals(
            1,
            steps.size,
            "the block is the envelope of the steps of the stage, and not a step of its own",
        )
    }

    @Test
    fun `the steps of the body and the steps of the block are one list, in order`() {
        val spec = pipeline {
            stages {
                stage("s") {
                    echo("before")
                    steps {
                        echo("inside")
                        sh("y")
                    }
                    echo("after")
                }
            }
        }

        val steps = spec.stages.single().steps
        assertEquals(
            4,
            steps.size,
            "the stage carries the steps of the body and the steps of the block, and no more",
        )
        assertEquals("before", (steps[0] as StepSpec.Echo).text, "the order is the order of the declaration")
        assertEquals("inside", (steps[1] as StepSpec.Echo).text, "the order is the order of the declaration")
        assertInstanceOf(StepSpec.Shell::class.java, steps[2], "the step of the block keeps its kind")
        assertEquals("after", (steps[3] as StepSpec.Echo).text, "the order is the order of the declaration")
    }
}
