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
 * ## Both bodies are the SAME kind, and they are told apart by their names
 *
 * The forward body and the compensation body are both `core.withEnv` blocks. What tells them
 * apart is the ADDRESS the plugin gave them — `example.block.plan.forward` and
 * `example.block.plan.compensate` — and not what happens to run inside them.
 *
 * It used to be the other way round: the address WAS the plugin step key of the child, so two
 * children of the same kind collided and the seam refused to guess which one was meant. A
 * plugin with a real body per alternative would have had to invent a kind per body — and the
 * kinds of a plugin's bodies are not the plugin's to invent. Now the plugin names its own
 * bodies in its own namespace, and the kind is free to be the same everywhere.
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
        // The SAME kind for both bodies — `core.withEnv` — and the name is what tells them
        // apart. The marker each body exports is its own address, so the trace says not only
        // what ran but under which name.
        slot(FORWARD_SLOT.value) {
            withEnv(mapOf("PIPELINEK_SLOT" to FORWARD_SLOT.value)) { sh(forwardCommand) }
        }
        slot(COMPENSATE_SLOT.value) {
            withEnv(mapOf("PIPELINEK_SLOT" to COMPENSATE_SLOT.value)) { sh(compensateCommand) }
        }
    }
}

/** The forward body: the address the plugin gave it. */
val FORWARD_SLOT = dev.rubentxu.pipeline.v2.domain.PluginStepId("example.block.plan.forward")

/** The compensation body: the address the plugin gave it. */
val COMPENSATE_SLOT = dev.rubentxu.pipeline.v2.domain.PluginStepId("example.block.plan.compensate")
