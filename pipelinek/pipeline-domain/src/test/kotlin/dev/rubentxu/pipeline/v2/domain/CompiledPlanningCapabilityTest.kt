package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A body shape this build cannot plan is refused by name, and refused BEFORE
 * the plan is accepted.
 *
 * ## The guarantee
 * A stage body that the planner cannot expand does not pass the structural
 * validation as if it were executable, and the refusal names the capability
 * that is missing. The refusal is a fact the reader can act on: it says what
 * the plan needs and that this build does not serve it, instead of leaving a
 * generic error at the end of a run that had already been accepted.
 *
 * ## Why this is a unit test and not a deeper one
 * The validator and the planner are pure functions over the compiled IR, and
 * the verdict is an exception: no clock, no file, no process, no port. A test
 * that composed the durable coordinator would read the SAME exception and
 * discriminate nothing more, and AGENTS.md section 2 forbids the E2E layer.
 *
 * ## Why the refusal is a type and not a sentence
 * The refusal has to be switchable: a consumer that has to read a message to
 * learn whether the plan needed a capability this build does not serve cannot
 * tell that refusal apart from any other invalid plan. So the capability
 * travels as a value of a closed vocabulary.
 */
class CompiledPlanningCapabilityTest {

    @Test
    fun `a matrix stage is refused by the structural validation`() {
        val failure = assertThrows(MissingPlanningCapabilityException::class.java) {
            CompiledPipelineValidator.validate(matrixPipeline())
        }

        assertTrue(
            failure.message.orEmpty().contains("stage 'matrix-stage'"),
            "the refusal does not name the stage it refuses: ${failure.message}",
        )
    }

    @Test
    fun `the refusal names the capability that is missing`() {
        val failure = assertThrows(MissingPlanningCapabilityException::class.java) {
            CompiledPipelineValidator.validate(matrixPipeline())
        }

        assertEquals(
            PlanningCapability.MatrixExpansion,
            failure.capability,
            "the refusal does not name the capability that is missing",
        )
        assertTrue(
            failure.message.orEmpty().contains("MatrixExpansion"),
            "the refusal does not name the capability that is missing: ${failure.message}",
        )
    }

    @Test
    fun `the planner refuses a matrix stage with the same named refusal`() {
        val failure = assertThrows(MissingPlanningCapabilityException::class.java) {
            CompiledExecutionPlanner.plan(matrixPipeline())
        }

        assertEquals(
            PlanningCapability.MatrixExpansion,
            failure.capability,
            "the planner refuses without naming the capability",
        )
    }

    /**
     * The refusal is an invalid argument.
     *
     * That is the contract the durable coordinator relies on: it catches the
     * invalid argument of an invalid pipeline and turns it into a failure of
     * the run, attributed to the pipeline and not to the machine. A refusal
     * with another parent would escape that catch, and the operator would
     * read a crash where the truth is a plan this build cannot run.
     */
    @Test
    fun `the refusal is an invalid argument`() {
        assertThrows(IllegalArgumentException::class.java) {
            CompiledPipelineValidator.validate(matrixPipeline())
        }
    }

    @Test
    fun `a stage whose body this build can plan is still admitted`() {
        assertDoesNotThrow {
            CompiledPipelineValidator.validate(pipelineWith(StageBody.Steps(listOf(opaqueStep()))))
        }
    }

    private fun matrixPipeline(): CompiledPipeline = pipelineWith(
        StageBody.Matrix(MatrixSpec(mapOf("os" to listOf("linux", "macos")))),
    )

    private fun pipelineWith(body: StageBody): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("definition"),
        source = SourceDescriptor("pipeline.kts", Digest("digest")),
        stages = listOf(
            StageNode(StageId("stage"), "matrix-stage", body = body),
        ),
        pluginLockDigest = Digest("lock"),
    )

    private fun opaqueStep() = OpaqueStepNode(
        id = StepId("child"),
        pluginStepId = PluginStepId("core.sh"),
        payload = VersionedStepPayload("dsl-v1", "{}"),
    )
}
