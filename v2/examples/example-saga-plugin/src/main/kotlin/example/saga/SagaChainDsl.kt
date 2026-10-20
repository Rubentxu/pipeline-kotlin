package example.saga

import dev.rubentxu.pipeline.v2.dsl.StageScope

/**
 * One action of the demo chain, as the SCRIPT declares it.
 *
 * @param forwardCommand the command that performs the forward effect.
 * @param compensateCommand the command that undoes it.
 */
data class SagaChainScript(
    val actionId: String,
    val compensationId: String,
    val resourceRef: String,
    val forwardCommand: String,
    val compensateCommand: String,
)

/**
 * The external DSL facade for a Saga CHAIN.
 *
 * ```kotlin
 * import example.saga.SagaChainScript
 * import example.saga.sagaChain
 *
 * pipeline {
 *     stages {
 *         stage("Checkout") {
 *             sagaChain(
 *                 sagaId = "checkout",
 *                 actions = listOf(
 *                     SagaChainScript("reserve", "release", "stock/42", "…", "…"),
 *                     SagaChainScript("charge", "refund", "payment/42", "…", "…"),
 *                     SagaChainScript("ship", "unship", "parcel/42", "…", "…"),
 *                 ),
 *             )
 *         }
 *     }
 * }
 * ```
 *
 * ## Why this chain has THREE actions and SIX bodies
 *
 * A slot is addressed by the PLUGIN STEP KEY of a child of this Step's own body, and two children
 * of the SAME kind collide — the seam refuses an ambiguous name instead of guessing. So every
 * action needs two bodies of its OWN, and every body of the chain needs a kind nobody else in
 * the chain is using.
 *
 * That is why the demo uses six distinct core kinds: `core.dir`, `core.withEnv`, `core.timeout`,
 * `core.retry`, `core.sh` and `core.timestamps`. It is a property of the seam, not of the Saga: a
 * real plugin with a real body per action would give each action its own kind.
 *
 * ## Why every one of those six kinds RUNS A COMMAND
 *
 * Because a body nobody can observe is a body the demo cannot prove anything about. The six
 * kinds are all command-running wrappers, so every forward and every compensation leaves a
 * trace in the world — and the scenario's assertion is that trace. A kind that only printed
 * (`core.echo`) would have made the third action's compensation unprovable: the demo would
 * have CLAIMED that it never ran while being unable to tell whether it had.
 *
 * ## What the fixture does NOT pretend
 *
 * The third action FAILS — that is the trigger of the whole demonstration — and its body is a
 * command that fails. Whether that failure means "nothing happened" or "nobody can say" is NOT
 * decided here: it is the provider's contract, and the script says which one it is.
 */
fun StageScope.sagaChain(
    sagaId: String,
    actions: List<SagaChainScript>,
    providerContract: SagaProviderContract = SagaProviderContract.FailureLeavesNoEffect,
) {
    require(actions.size == 3) {
        "the demo chain has three actions, and the third one is the one that fails"
    }
    require(actions.all { it.forwardCommand.isNotBlank() }) {
        "every action needs a forward command"
    }
    require(actions.all { it.compensateCommand.isNotBlank() }) {
        "every action needs a compensation command: a compensation the scenario cannot see " +
            "is a compensation the scenario cannot prove was not run"
    }

    val slots = listOf(
        // The first action: a `dir` block, whose plugin step key is `core.dir`.
        SagaChainSlots(action = actions[0], forward = FORWARD_FIRST, compensate = COMPENSATE_FIRST),
        // The second action: a `timeout` block, whose plugin step key is `core.timeout`.
        SagaChainSlots(action = actions[1], forward = FORWARD_SECOND, compensate = COMPENSATE_SECOND),
        // The third action: a bare `sh`, whose plugin step key is `core.sh`.
        SagaChainSlots(action = actions[2], forward = FORWARD_THIRD, compensate = COMPENSATE_THIRD),
    )

    registryBlock(
        stepKey = SagaChainStepDefinition.KEY,
        encodedInput = SagaChainCodec.encode(
            SagaChainInput(
                sagaId = sagaId,
                actions = slots.map { slot ->
                    SagaChainAction(
                        actionId = slot.action.actionId,
                        compensationId = slot.action.compensationId,
                        resourceRef = slot.action.resourceRef,
                        forwardSlot = slot.forward.value,
                        compensateSlot = slot.compensate.value,
                    )
                },
                providerContract = providerContract.wireName,
            ),
        ),
    ) {
        // 1. The first action's two bodies.
        dir(".") { sh(actions[0].forwardCommand) }
        withEnv(mapOf("PIPELINEK_SLOT" to COMPENSATE_FIRST.value)) {
            sh(actions[0].compensateCommand)
        }

        // 2. The second action's two bodies.
        timeout(60, "SECONDS") { sh(actions[1].forwardCommand) }
        retry(1) { sh(actions[1].compensateCommand) }

        // 3. The third action's two bodies. The forward one FAILS, and that is the trigger.
        sh(actions[2].forwardCommand)
        timestamps { sh(actions[2].compensateCommand) }
    }
}

/** The slot names of the demo chain, one kind each. */
private data class SagaChainSlots(
    val action: SagaChainScript,
    val forward: dev.rubentxu.pipeline.v2.domain.PluginStepId,
    val compensate: dev.rubentxu.pipeline.v2.domain.PluginStepId,
)

private val FORWARD_FIRST = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.dir")
private val COMPENSATE_FIRST = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.withEnv")
private val FORWARD_SECOND = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.timeout")
private val COMPENSATE_SECOND = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.retry")
private val FORWARD_THIRD = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.sh")
private val COMPENSATE_THIRD = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.timestamps")
