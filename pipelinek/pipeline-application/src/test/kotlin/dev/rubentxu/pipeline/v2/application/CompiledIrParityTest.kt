package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The SAME stage, written three ways, and the ONE IR they have to produce.
 *
 * ## The guarantee under trial
 *
 * That the three spellings of a stage — the steps written in the body of the
 * stage, the steps written in a `steps { }` block, and the steps written in a
 * `script { }` block — compile to the SAME IR: the same steps, with the same
 * keys, with the same payloads, in the same order, and with no node of their
 * own in between.
 *
 * ## Why the IR and not the DECLARATION
 *
 * The three spellings are already pinned at the declaration level
 * (`StepsBlockTest` and `ScriptBlockStepsTest` in the DSL module): each block
 * declares the steps of the stage that opened it. What was NOT measured is the
 * thing that decides what the run does: the IR the COMPILER produces out of
 * those declarations. A block that folded its steps into one shell, a block
 * that lost one of them, a block that introduced an envelope node of its own,
 * or two blocks whose order the compiler reversed would all pass the
 * declaration rows and fail here.
 *
 * ## Why the ORDER is part of the parity and not a separate fact
 *
 * Because the order IS the difference between two IRs that carry the same
 * steps. A compiler that swapped two steps would produce a plan with the same
 * nodes and a different meaning, and a parity row that compared sets would call
 * the two equal. So the comparison here is of ORDERED sequences, and the last
 * row proves that the comparison discriminates: two spellings that declare the
 * same steps in a different order are NOT the same IR.
 *
 * ## What this row does NOT freeze
 *
 * The exact shape of the node ids, the bytes of the payload of a step the
 * compiler owns (`CoreShellWireCodec` owns those, and
 * `CompilerWireAuthorityTest` pins them), and the semantics of the `script { }`
 * block at run time. This row compares the IR the three spellings produce with
 * EACH OTHER.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CompiledIrParityTest {

    /**
     * The three spellings of one stage compile to the same IR.
     *
     * The direct spelling is the reference, and the other two are compared with
     * it: the same steps, the same keys, the same payloads, in the same order.
     */
    @Test
    @DisplayName("the three spellings of a stage compile to the same IR")
    fun theThreeSpellingsOfAStageCompileToTheSameIr() {
        val direct = irOf(
            pipeline {
                stages {
                    stage("build") {
                        echo("hello")
                        sh("echo hello")
                    }
                }
            },
        )
        val stepsBlock = irOf(
            pipeline {
                stages {
                    stage("build") {
                        steps {
                            echo("hello")
                            sh("echo hello")
                        }
                    }
                }
            },
        )
        val scriptBlock = irOf(
            pipeline {
                stages {
                    stage("build") {
                        script {
                            echo("hello")
                            sh("echo hello")
                        }
                    }
                }
            },
        )

        assertEquals(
            direct.projection(),
            stepsBlock.projection(),
            "a `steps { }` block does not compile to the IR the same stage compiles to " +
                "when its steps are written in the body",
        )
        assertEquals(
            direct.projection(),
            scriptBlock.projection(),
            "a `script { }` block does not compile to the IR the same stage compiles to " +
                "when its steps are written in the body",
        )
    }

    /**
     * None of the three spellings adds a node of its own.
     *
     * The envelope is not a step: a stage that declares two steps carries two
     * nodes, whichever spelling it used. A block that introduced a node — a
     * shell around the steps, or a wrapper of its own — would carry three.
     */
    @Test
    @DisplayName("none of the three spellings adds a node of its own to the IR")
    fun noneOfTheThreeSpellingsAddsANodeOfItsOwnToTheIr() {
        val spellings = listOf(
            "the steps written in the body" to pipeline {
                stages {
                    stage("build") {
                        echo("hello")
                        sh("echo hello")
                    }
                }
            },
            "the steps written in a `steps { }` block" to pipeline {
                stages {
                    stage("build") {
                        steps {
                            echo("hello")
                            sh("echo hello")
                        }
                    }
                }
            },
            "the steps written in a `script { }` block" to pipeline {
                stages {
                    stage("build") {
                        script {
                            echo("hello")
                            sh("echo hello")
                        }
                    }
                }
            },
        )

        spellings.forEach { (spelling, spec) ->
            val nodes = irOf(spec).nodes
            assertEquals(
                2,
                nodes.size,
                "$spelling carries a node that is not a step of the stage: the nodes are $nodes",
            )
            assertFalse(
                nodes.any { it is BlockStepNode },
                "$spelling wraps its steps in a node of its own: the nodes are $nodes",
            )
            assertEquals(
                listOf("core.echo", "core.sh"),
                nodes.map { it.pluginStepId.value },
                "$spelling does not carry the two steps the stage declares",
            )
        }
    }

    /**
     * Several contiguous blocks keep their order in the IR.
     *
     * A stage may write its steps in more than one block — a `steps { }` and a
     * `script { }`, or two `steps { }` — and the order the author wrote is the
     * order the plan runs. The row declares three blocks with one step each and
     * reads the IR back: the steps are in the order they were declared.
     */
    @Test
    @DisplayName("several contiguous blocks keep their order in the IR")
    fun severalContiguousBlocksKeepTheirOrderInTheIr() {
        val spec = pipeline {
            stages {
                stage("build") {
                    steps { echo("first") }
                    script { echo("second") }
                    steps { echo("third") }
                }
            }
        }

        assertEquals(
            listOf("first", "second", "third"),
            irOf(spec).nodes.map { echoTextOf(it) },
            "the blocks of the stage do not keep their order in the IR",
        )
    }

    /**
     * The comparison DISCRIMINATES: two spellings that declare the same steps
     * in a different order are not the same IR.
     *
     * This is the control of the rows above. A parity row that compared sets
     * would call these two equal, and the row that says so would be a row that
     * passes for the wrong reason. Here the two orders are different IRs, and
     * the comparison of ordered sequences is what says so.
     *
     * The two steps are of DIFFERENT keys on purpose: a compiler that sorted
     * the steps by their key would leave two steps of the same key in the order
     * they were declared, and the difference between the two spellings would
     * hide in the payload. With two keys, a sort folds the two spellings into
     * one, and the comparison says so.
     */
    @Test
    @DisplayName("two spellings that declare the same steps in a different order are not the same IR")
    fun twoSpellingsThatDeclareTheSameStepsInADifferentOrderAreNotTheSameIr() {
        val declared = irOf(
            pipeline {
                stages {
                    stage("build") {
                        steps {
                            sh("first")
                            echo("second")
                        }
                    }
                }
            },
        )
        val swapped = irOf(
            pipeline {
                stages {
                    stage("build") {
                        steps {
                            echo("second")
                            sh("first")
                        }
                    }
                }
            },
        )

        assertEquals(
            listOf("core.sh", "core.echo"),
            declared.nodes.map { it.pluginStepId.value },
            "the first spelling does not carry the two steps it declares",
        )
        assertEquals(
            listOf("core.echo", "core.sh"),
            swapped.nodes.map { it.pluginStepId.value },
            "the second spelling does not carry the two steps it declares, in the order it " +
                "declares them",
        )
        assertNotEquals(
            declared.projection(),
            swapped.projection(),
            "the two spellings declare the same steps in a different order, and the IR does " +
                "not say so: the ORDER of the steps is the difference the comparison has to see",
        )
    }

    /**
     * The IR of a compiled pipeline: the nodes of the stage and the projection
     * the comparison reads.
     *
     * The projection is an ORDERED sequence, and each step is described by the
     * four facts that make it the step it is: the identity it carries, the key
     * it runs under, the schema of its payload and the payload itself. The
     * IDENTITY is part of the parity and not an extra: the id of a node is what
     * the durable journal keys the operation under, so two spellings that
     * produced the same steps under DIFFERENT ids would run the same work under
     * two different durable identities — and a run resumed under the other
     * spelling would not find the row the first one wrote.
     */
    private class CompiledIr(val nodes: List<StepNode>) {

        /** The IR, described in the order the steps are in. */
        fun projection(): List<String> = nodes.map { node ->
            "${node.id.value}|${node.pluginStepId.value}|" +
                "${node.payload.schemaVersion}|${node.payload.encoded}"
        }
    }

    /** The IR the compiler produces out of a specification. */
    private fun irOf(spec: PipelineSpec): CompiledIr {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = Digest("lock"),
        )
        val stage = compiled.stages.single()
        return CompiledIr((stage.body as StageBody.Steps).steps)
    }

    /** The text an echo step carries, read out of its payload. */
    private fun echoTextOf(step: StepNode): String =
        kotlinx.serialization.json.Json.parseToJsonElement(step.payload.encoded)
            .let { it as kotlinx.serialization.json.JsonObject }
            .getValue("text")
            .let { it as kotlinx.serialization.json.JsonPrimitive }
            .content
}
