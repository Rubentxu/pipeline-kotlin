package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * RED characterization test for wu-g5-restore — structural defect.
 *
 * Design §14.3 / design §3.1 law: `waitUntil` is a body-owning structural
 * orchestration construct. Its canonical IR MUST be `BlockStepNode` with
 * `pluginStepId("core.waitUntil")`, non-empty body, and payload kind
 * `"waitUntilBlock"`.
 *
 * The terminal `StepSpec.WaitUntil` algebraic variant that currently exists
 * (no body field) is the structural defect. This test pins that defect:
 *
 * - CURRENTLY: `DslCompiledPipelineCompiler.stepNode` lowers `StepSpec.WaitUntil`
 *   to `OpaqueStepNode("core.waitUntil", ...)` via the `else -> OpaqueStepNode`
 *   catch-all (DslCompiledPipelineCompiler.kt L224-230).
 *
 * - EXPECTED: the compiler MUST produce `BlockStepNode` with a non-empty body
 *   and the `core.waitUntil` plugin id — making the terminal variant's lack
 *   of a body field the actionable gap.
 *
 * This test is RED today (assertion fails for the expected reason: the compiler
 * produces `OpaqueStepNode`, not `BlockStepNode`). After WU-G5R.1 (DSL rename
 * to `WaitUntilBlock`) and WU-G5R.2 (compiler explicit arm), this test flips
 * GREEN.
 *
 * References:
 * - design.md §14.3 (P2 surgical-add, no exit-code fold, no event-as-authority)
 * - design.md §3.1 (waitUntil must be body-bearing BlockStepNode, not OpaqueStepNode)
 * - AGENTS.md §STEP CONSTITUTION (structural constructs are NOT registry Steps)
 * - AGENTS.md §8 (ADT-first modelling — body-owning construct needs body field)
 */
@DisplayName("LFC-2 G5-RESTORE: waitUntil DSL must project to BlockStepNode, not OpaqueStepNode")
class Lfc2WaitUntilDslCanonicalProjectionTest {

    /**
     * Constructs a `PipelineSpec` directly using the body-bearing
     * `StepSpec.WaitUntilBlock` algebraic variant (wu-g5-restore).
     *
     * The body-capturing `waitUntil { }` DSL fun is NOT used here to avoid
     * accidental eager evaluation. The test constructs `WaitUntilBlock`
     * directly with an inner `sh` step.
     *
     * The test proves that even with the algebraic variant in place,
     * the compiler's lowering to `OpaqueStepNode` is the defect — the
     * compiler needs an explicit `is StepSpec.WaitUntilBlock -> blockStepNode(...)`
     * arm (WU-G5R.2).
     */
    private fun waitUntilFixture(): PipelineSpec = PipelineSpec(
        stages = listOf(
            StageSpec(
                name = "wait-until-test",
                steps = listOf(
                    StepSpec.WaitUntilBlock(
                        initialRecurrencePeriod = 1L,
                        quiet = false,
                        body = listOf(
                            StepSpec.Shell(command = "test -f /tmp/marker"),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `waitUntil must compile to BlockStepNode, not OpaqueStepNode`() {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = waitUntilFixture(),
            sourcePath = "wait-until-test.pipeline.kts",
            sourceContent = """pipeline { stages { stage("wait-until-test") { waitUntil(1L) { sh("test -f /tmp/marker") } } } }""",
            pluginLockDigest = Digest("test-lock"),
        )

        val body = compiled.stages.single().body
        assertTrue(
            body is StageBody.Steps,
            "Stage body must be Steps, got: ${body::class.java.simpleName}",
        )

        val steps = (body as StageBody.Steps).steps
        assertTrue(
            steps.isNotEmpty(),
            "Stage must contain at least one compiled step",
        )

        val waitUntilStep: dev.rubentxu.pipeline.v2.domain.StepNode = steps.single()

        // PRIMARY ASSERTION: the step MUST be a BlockStepNode (currently fails → RED)
        val blockNode = waitUntilStep as? BlockStepNode
        val primaryFailureMessage = buildString {
            append("waitUntil MUST lower to BlockStepNode, not OpaqueStepNode. ")
            append("Current lowering produces: ${waitUntilStep::class.java.simpleName}. ")
            append("This is the structural defect: StepSpec.WaitUntil has no body field ")
            append("and the compiler has no explicit arm for WaitUntilBlock → blockStepNode(...). ")
            append("Expected: BlockStepNode(pluginStepId=core.waitUntil, body.isNotEmpty(), payload.kind=waitUntilBlock).")
        }
        assertTrue(blockNode != null, primaryFailureMessage)

        // SECONDARY ASSERTION: pluginStepId must be core.waitUntil
        assertTrue(
            waitUntilStep.pluginStepId == PluginStepId("core.waitUntil"),
            "pluginStepId must be 'core.waitUntil' but was: ${waitUntilStep.pluginStepId.value}",
        )

        // THIRD ASSERTION: body must be non-empty (for the body-bearing form)
        val blockStep = waitUntilStep as BlockStepNode
        assertNotNull(
            blockStep.body,
            "BlockStepNode body must not be null",
        )
        assertTrue(
            blockStep.body.isNotEmpty(),
            "BlockStepNode body must be non-empty (the body captures the inner predicate steps)",
        )

        // FOURTH ASSERTION: payload kind must be "waitUntilBlock"
        val payloadJson = Json.parseToJsonElement(blockStep.payload.encoded)
            as? JsonObject
        assertNotNull(
            payloadJson,
            "BlockStepNode payload must be a JSON object",
        )
        val pk = payloadJson!!
        assertTrue(
            pk.containsKey("kind"),
            "BlockStepNode payload must contain 'kind' field",
        )
        assertTrue(
            pk["kind"]?.toString()?.trim('"') == "waitUntilBlock",
            "BlockStepNode payload kind must be 'waitUntilBlock' but was: ${pk["kind"]}",
        )
    }

    @Test
    fun `waitUntil must NOT lower to OpaqueStepNode`() {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = waitUntilFixture(),
            sourcePath = "wait-until-test.pipeline.kts",
            sourceContent = """pipeline { stages { stage("wait-until-test") { waitUntil(1L) { sh("test -f /tmp/marker") } } } }""",
            pluginLockDigest = Digest("test-lock"),
        )

        val body = compiled.stages.single().body
        val steps = (body as StageBody.Steps).steps
        val waitUntilStep = steps.single()

        // This is the closure of design §3.1: OpaqueStepNode for this key is forbidden.
        // The else -> OpaqueStepNode catch-all MUST NOT fire for waitUntil.
        val isOpaque = waitUntilStep is OpaqueStepNode
        assertTrue(
            !isOpaque,
            "waitUntil MUST NOT lower to OpaqueStepNode('core.waitUntil', ...). " +
                "The 'else -> OpaqueStepNode' catch-all is firing because there is " +
                "no explicit 'is StepSpec.WaitUntilBlock -> blockStepNode(...)' arm. " +
                "Actual type: ${waitUntilStep::class.java.simpleName}. " +
                "pluginStepId: ${waitUntilStep.pluginStepId.value}.",
        )
    }
}
