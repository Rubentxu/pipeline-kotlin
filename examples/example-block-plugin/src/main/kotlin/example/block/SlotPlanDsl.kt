package example.block

import dev.rubentxu.pipeline.v2.dsl.StageScope

/**
 * The external DSL facade for a Step that owns a plan with MORE THAN ONE body.
 *
 * The plugin teaches the DSL its own Step; core, compiler and coordinator never learn the name
 * `slotPlan`. The user-visible script becomes:
 *
 * ```kotlin
 * import example.block.slotPlan
 *
 * pipeline {
 *     stages {
 *         stage("Plan") {
 *             slotPlan(
 *                 forwardCommand = "echo forward",
 *                 compensateCommand = "echo compensate",
 *             )
 *         }
 *     }
 * }
 * ```
 *
 * The two bodies are the two children of this Step's body, and the plan NAMES them: the input
 * carries which child is the forward body and which is the compensation. The handler then asks
 * the engine for one of them by name, and the engine dispatches it.
 *
 * The slots are core blocks in this fixture (`core.dir` and `core.withEnv`): two real bodies with
 * two different keys. A slot is addressed by the plugin step key of the child, so two children of
 * the SAME kind would collide — and the seam refuses an ambiguous name instead of guessing.
 */
fun StageScope.slotPlan(
    forwardCommand: String,
    compensateCommand: String,
) {
    registryBlock(
        stepKey = SlotPlanStepDefinition.KEY,
        encodedInput = SlotPlanCodec.encode(
            SlotPlanInput(
                forwardSlot = FORWARD_SLOT.value,
                compensateSlot = COMPENSATE_SLOT.value,
            ),
        ),
    ) {
        dir(".") { sh(forwardCommand) }
        withEnv(mapOf("PIPELINEK_SLOT" to COMPENSATE_SLOT.value)) { sh(compensateCommand) }
    }
}

/** The forward body: a `dir` block, whose plugin step key is `core.dir`. */
val FORWARD_SLOT = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.dir")

/** The compensation body: a `withEnv` block, whose plugin step key is `core.withEnv`. */
val COMPENSATE_SLOT = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.withEnv")
