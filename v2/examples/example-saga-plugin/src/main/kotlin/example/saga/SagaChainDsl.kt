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
 * ## Why every body of this chain is the SAME kind
 *
 * Every body is a `core.withEnv` block, and the bodies are told apart by the NAME the
 * plugin gives them — `example.saga.reserve.forward`, `example.saga.charge.compensate`,
 * and so on. That is the whole point of the seam: a body is addressed by the address its
 * owner gave it, not by what happens to run inside it.
 *
 * It used to be the other way round, and the fixture shows how bad that was: the six
 * bodies needed six DIFFERENT core kinds (`core.dir`, `core.withEnv`, `core.timeout`,
 * `core.retry`, `core.sh`, `core.timestamps`) because the address WAS the kind, and two
 * children of one kind collided. A plugin with a real body per action would have had to
 * invent a kind per body — an apaño of the fixture that no consumer could copy, because
 * the kinds of a plugin's bodies are not the plugin's to invent.
 *
 * Now the plugin names its own bodies, in its own namespace, and the kind is free to be
 * the same everywhere. **That is what makes this facade copyable.**
 *
 * ## Why the names are derived from the ACTION
 *
 * The address of a body says WHICH action it belongs to and WHICH phase it is — the
 * forward of `reserve` and its compensation are two bodies of the same action, and the
 * compensation of `reserve` is not the forward of `charge`. Deriving the name from the
 * action's own identity means the plugin cannot spell two bodies the same way by
 * accident, and the name that reaches the durable log says what the body was FOR, which
 * the kind never did.
 *
 * ## Why every body RUNS A COMMAND
 *
 * Because a body nobody can observe is a body the demo cannot prove anything about. Every
 * body here runs a command and leaves a trace in the world — and the scenario's assertion
 * is that trace. The `PIPELINEK_SLOT` marker it exports is the body's own ADDRESS, so the
 * trace says not only what ran but under which name.
 *
 * ## Why this chain has TWO or THREE actions
 *
 * A chain of TWO actions is allowed because a scenario that reconciles a lost compensation needs
 * exactly that: with three actions the resumed run would also compensate the OLDEST obligation,
 * and the world would change for a reason that has nothing to do with what the scenario is
 * about. With two, the world after the resumed run must be the world the crashed run left, and
 * that is the whole claim.
 *
 * ## What the fixture does NOT pretend
 *
 * The fixture does not decide whether the chain WORKS: that is the SCRIPT's business, and the
 * scripts disagree on purpose. The failure scenarios write a command that fails, and the
 * success scenario writes one that works — because a Saga that ended well is a different path
 * through the engine, and a fixture that could only fail could not show it.
 *
 * What the fixture DOES decide is the chain's SHAPE, and that is where the seam's rules live:
 * two or three actions, each with a forward body and a compensation body of its own NAME.
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
            "compensation body of its own name"
    }
    require(actions.all { it.forwardCommand.isNotBlank() }) {
        "every action needs a forward command"
    }
    require(actions.all { it.compensateCommand.isNotBlank() }) {
        "every action needs a compensation command: a compensation the scenario cannot see " +
            "is a compensation the scenario cannot prove was not run"
    }

    val slots = actions.map { action ->
        SagaChainSlots(
            action = action,
            forward = slotName(action.actionId, "forward"),
            compensate = slotName(action.actionId, "compensate"),
        )
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
        // Every body is a `core.withEnv` block — the SAME kind for all of them. What tells
        // them apart is the name the plugin gives each one, and that name is what the
        // plugin will ask for when it wants the forward or the compensation of an action.
        slots.forEach { entry ->
            slot(entry.forward.value) {
                withEnv(mapOf("PIPELINEK_SLOT" to entry.forward.value)) {
                    sh(entry.action.forwardCommand)
                }
            }
            slot(entry.compensate.value) {
                withEnv(mapOf("PIPELINEK_SLOT" to entry.compensate.value)) {
                    sh(entry.action.compensateCommand)
                }
            }
        }
    }
}

/** The slot names of the demo chain: one per body, each one naming its own action and phase. */
private data class SagaChainSlots(
    val action: SagaChainScript,
    val forward: dev.rubentxu.pipeline.v2.domain.PluginStepId,
    val compensate: dev.rubentxu.pipeline.v2.domain.PluginStepId,
)

/**
 * The address of one body of the chain.
 *
 * It lives in the plugin's namespace, and it says which action the body belongs to and
 * which phase it is. The plugin's namespace is the plugin's to choose — these names are
 * not kinds, and nothing dispatches by them except the plugin that owns the body.
 */
private fun slotName(actionId: String, phase: String): dev.rubentxu.pipeline.v2.domain.PluginStepId =
    dev.rubentxu.pipeline.v2.domain.PluginStepId("example.saga.$actionId.$phase")
