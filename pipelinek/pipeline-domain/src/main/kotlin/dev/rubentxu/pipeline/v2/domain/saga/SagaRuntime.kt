package dev.rubentxu.pipeline.v2.domain.saga

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * The capability a Step must declare to ask the ENGINE to run a Saga's compensation loop.
 *
 * ## Why the loop is the engine's and not the plugin's
 *
 * The plugin knows WHAT its actions are and WHICH body runs each phase. It does not know
 * WHICH obligation is owed next, in WHICH order, or whether the Saga must stop. That answer
 * comes from the durable state of the Saga, and the durable state is the engine's.
 *
 * So the plugin hands over a PLAN — data, no closures, no handles — and the engine walks the
 * obligations, decides, and dispatches the compensation bodies through the same dispatcher
 * that ran the forward ones.
 */
val SAGA_RUNTIME_CAPABILITY: StepCapability = StepCapability("saga.runtime")

/**
 * The Saga's plan, as DATA.
 *
 * ## What it says
 *
 * Which actions the Saga has, which body runs each one's forward effect, which body runs its
 * compensation, and what to do when an outcome is not established.
 *
 * ## What it does NOT say
 *
 * Nothing about the run, the store or the moment. A plan is what the plugin's CODE knows, so
 * it is the same in every process: a resumed run rebuilds the same plan and therefore reaches
 * the same decisions.
 *
 * ## The order of [actions] is the order of the Saga
 *
 * The list is the action order, and the arm order follows it, so the compensation order is
 * its reverse. An engine that read the plan in a different order would compensate in a
 * different order, which is a different Saga.
 */
data class SagaPlan(
    val sagaId: SagaId,
    val policy: CompensationPolicy,
    val actions: List<SagaActionPlan>,
) {
    init {
        require(actions.isNotEmpty()) { "a Saga plan declares at least one action" }
        require(actions.map { it.actionId }.distinct().size == actions.size) {
            "a Saga plan declares each action once"
        }
    }

    /** The plan of [actionId], or `null` when the plan does not declare it. */
    fun action(actionId: SagaActionId): SagaActionPlan? =
        actions.firstOrNull { it.actionId == actionId }
}

/**
 * One action of a Saga, and the bodies that implement it.
 *
 * The two slots are named by the PLUGIN STEP KEY of the child of the Saga Step's own body.
 * A slot is resolved by the engine against that body, so a slot that does not exist there is
 * a contract violation the engine reports, not a body the plugin hoped for.
 */
data class SagaActionPlan(
    val actionId: SagaActionId,
    val compensationId: CompensationId,
    val resourceRef: ResourceRef,
    val kind: CompensationKind,
    /** The body that performs the forward effect. */
    val forwardSlot: PluginStepId,
    /** The body that undoes it. */
    val compensateSlot: PluginStepId,
)

/**
 * The RUNTIME port of a Saga: the engine's side of the contract.
 *
 * ## What the caller gets
 *
 * A report that says which actions were compensated, IN WHICH ORDER, and what the Saga's
 * state is afterwards. The order is part of the answer: a Saga that compensated in the
 * wrong order is a different Saga, and the report is where that is visible.
 *
 * ## What the caller does not get
 *
 * A way to decide the order, to skip an obligation, or to mark something as compensated
 * without the engine having dispatched the body. The loop is the engine's; the plan is the
 * plugin's.
 */
interface SagaRuntime {

    /**
     * Runs the compensation loop of [plan] until the Saga is settled or a human is needed.
     *
     * The loop asks the durable state what is owed, dispatches the body that owes it, records
     * what happened, and asks again. It stops at the first obligation that cannot be settled:
     * a failed or indeterminate compensation, or an outcome that is not established and
     * cannot be reconciled.
     */
    suspend fun compensate(plan: SagaPlan): SagaCompensationReport
}

/**
 * What the compensation loop did.
 *
 * @param compensated the actions that were compensated AND durably recorded, in the order
 * they were compensated. An action whose compensation ran but could not be written down is
 * NOT here: the durable state does not know it, and a report that claimed it would disagree
 * with the rows a later process reads.
 * @param directive the Saga's state when the loop stopped: settled, or needing a human.
 */
data class SagaCompensationReport(
    val sagaId: SagaId,
    val compensated: List<SagaActionId>,
    val directive: CompensationDirective,
)
