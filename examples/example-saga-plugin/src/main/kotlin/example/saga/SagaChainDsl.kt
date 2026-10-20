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
 * ## Why this chain has TWO or THREE actions and four or six bodies
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
 * A chain of TWO actions is allowed because a scenario that reconciles a lost compensation needs
 * exactly that: with three actions the resumed run would also compensate the OLDEST obligation,
 * and the world would change for a reason that has nothing to do with what the scenario is
 * about. With two, the world after the resumed run must be the world the crashed run left, and
 * that is the whole claim.
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
 * The fixture does not decide whether the chain WORKS: that is the SCRIPT's business, and the
 * scripts disagree on purpose. The failure scenarios write a command that fails, and the
 * success scenario writes one that works — because a Saga that ended well is a different path
 * through the engine, and a fixture that could only fail could not show it.
 *
 * What the fixture DOES decide is the chain's SHAPE, and that is where the seam's rules live:
 * two or three actions, each with a forward body and a compensation body of its own kind.
 *
 * Whether a failing action leaves "nothing happened" or "nobody can say" is NOT decided here
 * either: it is the provider's contract, and the script says which one it is.
 */
fun StageScope.sagaChain(
    sagaId: String,
    actions: List<SagaChainScript>,
    providerContract: SagaProviderContract = SagaProviderContract.FailureLeavesNoEffect,
) {
    require(actions.size in 2..3) {
        "the demo chain has two or three actions, each with a forward body and a " +
            "compensation body of its own kind"
    }
    require(actions.all { it.forwardCommand.isNotBlank() }) {
        "every action needs a forward command"
    }
    require(actions.all { it.compensateCommand.isNotBlank() }) {
        "every action needs a compensation command: a compensation the scenario cannot see " +
            "is a compensation the scenario cannot prove was not run"
    }

    val slots = buildList {
        // The first action: a `dir` block, whose plugin step key is `core.dir`.
        add(SagaChainSlots(action = actions[0], forward = FORWARD_FIRST, compensate = COMPENSATE_FIRST))
        // The second action: a `timeout` block, whose plugin step key is `core.timeout`.
        add(SagaChainSlots(action = actions[1], forward = FORWARD_SECOND, compensate = COMPENSATE_SECOND))
        // The third action, when the chain has one: a bare `sh`, whose key is `core.sh`.
        actions.getOrNull(2)?.let {
            add(SagaChainSlots(action = it, forward = FORWARD_THIRD, compensate = COMPENSATE_THIRD))
        }
    }

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

        // 3. The third action's two bodies, when the chain has three. The forward one FAILS,
        //    and that is the trigger.
        actions.getOrNull(2)?.let {
            sh(it.forwardCommand)
            timestamps { sh(it.compensateCommand) }
        }
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
