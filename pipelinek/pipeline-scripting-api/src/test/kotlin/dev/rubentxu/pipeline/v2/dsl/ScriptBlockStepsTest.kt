package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The steps of a `script { }` block.
 *
 * ## The guarantee
 *
 * A `script { }` block holds STEPS, the way a stage holds them: every call inside it
 * is a step of its own, with its own descriptor and its own policy. `script { echo("x") }`
 * is a `core.echo`, and not a line of shell inside a `core.sh`.
 *
 * ## Why the instrument is this cheap
 *
 * A declaration is plain data, so the question is a call over it: what the author
 * writes, and what the stage carries. The rows write the block and read the steps the
 * stage carries, so a block that folded them into one shell, or that lost one of them,
 * would fail here.
 *
 * ## What the block does NOT carry
 *
 * A recorder of shell lines. A block of shell lines is ONE step, written with
 * `sh("""…""")`, and the row that says so is the one that keeps the capability from
 * being lost with the recorder.
 *
 * ## The divergence the rows pin
 *
 * The control flow of the block is Kotlin, and it is evaluated while the PLAN is built:
 * the arm that was taken is the arm that is declared. A condition that has to be
 * decided while the run runs is written with the `when*` gates.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ScriptBlockStepsTest {

    @Test
    fun `the block declares an echo step and not a line of shell`() {
        val spec = pipeline {
            stages {
                stage("s") {
                    script { echo("x") }
                }
            }
        }

        val step = spec.stages.single().steps.single()
        assertInstanceOf(StepSpec.Echo::class.java, step, "the block declares a step of its own")
        assertEquals("x", (step as StepSpec.Echo).text)
    }

    @Test
    fun `the block declares a shell step`() {
        val spec = pipeline {
            stages {
                stage("s") {
                    script { sh("y") }
                }
            }
        }

        val step = spec.stages.single().steps.single()
        assertInstanceOf(StepSpec.Shell::class.java, step, "the block declares a step of its own")
        assertEquals("y", (step as StepSpec.Shell).command)
    }

    @Test
    fun `two calls in the block declare two steps`() {
        val spec = pipeline {
            stages {
                stage("s") {
                    script {
                        echo("x")
                        sh("y")
                    }
                }
            }
        }

        val steps = spec.stages.single().steps
        assertEquals(2, steps.size, "each call in the block is a step of its own")
        assertInstanceOf(StepSpec.Echo::class.java, steps[0])
        assertInstanceOf(StepSpec.Shell::class.java, steps[1])
    }

    @Test
    fun `a block of shell lines is one step`() {
        val spec = pipeline {
            stages {
                stage("s") {
                    script { sh("echo one\necho two") }
                }
            }
        }

        val step = spec.stages.single().steps.single()
        assertInstanceOf(StepSpec.Shell::class.java, step)
        assertEquals(
            "echo one\necho two",
            (step as StepSpec.Shell).command,
            "the lines of a shell block travel inside ONE step",
        )
    }

    @Test
    fun `the arm of a branch in the block is decided while the plan is built`() {
        val spec = pipeline {
            stages {
                stage("s") {
                    script {
                        val x = 1
                        if (x > 0) {
                            echo("positive")
                        } else {
                            echo("not positive")
                        }
                    }
                }
            }
        }

        val steps = spec.stages.single().steps
        assertEquals(1, steps.size, "only the arm that was taken is declared")
        assertEquals("positive", (steps.single() as StepSpec.Echo).text)
    }

    @Test
    fun `the steps of the block are the steps of the stage that opened it`() {
        val spec = pipeline {
            stages {
                stage("s") {
                    echo("before")
                    script { echo("inside") }
                    echo("after")
                }
            }
        }

        val steps = spec.stages.single().steps
        assertEquals(
            listOf("before", "inside", "after"),
            steps.map { (it as StepSpec.Echo).text },
            "the block opens no scope of its own: its steps are the steps of the stage, in order",
        )
    }
}
