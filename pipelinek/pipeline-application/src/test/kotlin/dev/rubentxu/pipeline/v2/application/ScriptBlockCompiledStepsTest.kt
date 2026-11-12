package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.runtime.CoreEchoStep
import dev.rubentxu.pipeline.v2.runtime.CoreShellStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The steps of a `script { }` block, once the plan is compiled.
 *
 * ## The guarantee
 *
 * The steps a block declares are steps of their OWN: the plan gives each one its own
 * identity, and the kind of each one decides where it runs, what it does and how it
 * replays. A block that folded its calls into a single shell would take the class of
 * the shell — AGENT, EXECUTES_SUBPROCESS, RERUN — and a call that reads a value would
 * become a process that runs on an agent: the change is of CLASS, and it is silent.
 *
 * ## Why the policy is read from the DESCRIPTORS
 *
 * Because the descriptors are the declaration: the plan names the kind of a step, and
 * the kind is what the registry resolves into a policy. Reading the two descriptors
 * side by side is what shows that the step the block produces is NOT the folded one.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ScriptBlockCompiledStepsTest {

    @Test
    fun `the steps of the block are steps of their own, with their own identity and their own policy`() {
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

        val nodes = nodesOf(spec)

        // --- LA IDENTIDAD: cada llamada del bloque es un paso, y cada paso el suyo ---
        assertEquals(
            listOf("core.echo", "core.sh"),
            nodes.map { it.pluginStepId.value },
            "the block declares the kind of each call, and the kinds are the ones the author wrote",
        )
        assertEquals(
            2,
            nodes.map { it.id }.distinct().size,
            "two steps with two identities: a block that folded them would carry one",
        )

        // --- LA POLÍTICA: la del descriptor de ESE paso, y no la del shell plegado ---
        val echo = CoreEchoStep.definition.contract.descriptor
        val shell = CoreShellStep.definition.contract.descriptor
        assertEquals(
            ExecutionLocation.CONTROLLER,
            echo.executionLocation,
            "the echo runs where the controller is: the descriptor of the step the block produced",
        )
        assertEquals(listOf(Effect.READ_ONLY), echo.effects)
        assertEquals(ReplayPolicy.MEMOIZED, echo.replayPolicy)
        assertEquals(
            ExecutionLocation.AGENT,
            shell.executionLocation,
            "the shell runs on an agent: that is the class the block no longer folds into",
        )
        assertEquals(listOf(Effect.EXECUTES_SUBPROCESS), shell.effects)
        assertEquals(ReplayPolicy.RERUN, shell.replayPolicy)
    }

    /**
     * The nodes the compiler produces for a spec.
     *
     * The [Digest] goes with the FULL name because the domain publishes TWO types with
     * that name, and a file that imports the first one stops resolving the package
     * `domain.step`.
     */
    private fun nodesOf(spec: dev.rubentxu.pipeline.v2.dsl.PipelineSpec): List<StepNode> {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = Digest("lock"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        return body.steps
    }
}
