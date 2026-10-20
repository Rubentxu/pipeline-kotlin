package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId

/**
 * Runs ONE named child of the handler's OWN body.
 *
 * A body-bearing Step declares ONE body, and the handler reaches it through
 * [BodyContinuation] — which runs the WHOLE body. That is the right shape for
 * a loop (`repeat` runs it N times) and the wrong one for a plan with
 * alternatives: a Saga needs to run the FORWARD body on the happy path and the
 * COMPENSATE body after a failure, and it has to be able to CHOOSE.
 *
 * This port is that choice, and nothing more:
 *
 *  - The slot is named by a [PluginStepId], and it names a child of the
 *    handler's OWN body. The name is the one the plugin gave that child when
 *    it wrote the body, or the child's kind when it gave none — so a handler
 *    can chain two bodies of the same kind and still tell them apart. The
 *    engine resolves it; the handler never sees a [BodyRef], never holds one
 *    and never iterates children. The same separation the continuation keeps.
 *  - The selected child is dispatched through the SAME canonical spine as any
 *    other body child, with the identity it would have had if the engine had
 *    dispatched it itself. One authority, one journal, one exactly-once law.
 *  - The handler still decides WHEN; the engine still decides HOW and WITH
 *    WHICH IDENTITY.
 *
 * An unknown or ambiguous slot is a CONTAINED failure, not an exception: the
 * engine answers [BodyOutcome.Completed] carrying a typed ENGINE failure.
 * A handler that asks for a slot that does not exist is a contract violation,
 * and the handler's own outcome is where that violation belongs — an
 * exception would tear down the run instead of reporting it.
 */
fun interface BodySlotInvoker {
    suspend fun invoke(slot: PluginStepId, context: BodyInvocationContext): BodyOutcome
}

/**
 * The capability a Step declares to receive a [BodySlotInvoker].
 *
 * Exposed ONLY to a handler whose body is already bound
 * ([BodyExecutionOwner.HANDLER_CONTINUATION]) and only when the engine bound
 * the invoker for that invocation. A Step that declares it and does not get it
 * fails closed at capability admission, like every other capability.
 */
val BODY_SLOT_CAPABILITY: StepCapability = StepCapability("bodySlot")

/**
 * Which child of a body a slot names.
 *
 * The engine owns the body, so the only thing worth deciding — and the only
 * thing worth testing on its own — is which child a name refers to, and what
 * to answer when the name refers to none or to several.
 */
sealed interface SlotResolution {

    /** Exactly one child carries that key. Its POSITION is what the identity is built from. */
    data class Found(val childIndex: Int) : SlotResolution

    /** No child carries that key. */
    data class Missing(val slot: PluginStepId) : SlotResolution

    /** More than one child carries that key, so the name does not identify a child. */
    data class Ambiguous(val slot: PluginStepId, val matches: Int) : SlotResolution
}

/**
 * The resolution of a slot name against the addresses of a body's children.
 *
 * PURE and TOTAL: it reads a list of addresses and answers, and it never looks at
 * the engine, the journal or the run. That is what makes it decidable in one
 * place and testable without a pipeline.
 */
object BodySlots {

    /**
     * The child [slot] names among [childAddresses], in body order.
     *
     * An address is the name the plugin gave a child, or the child's kind when
     * nobody named it — so a body that names nothing is addressed exactly as it
     * was before names existed, and a body that names its children can hold two
     * children of the same kind without them colliding.
     *
     * Ambiguity is answered, not resolved by guessing: two children may share
     * an address (two `sh` steps in one body do, and two steps with the same
     * declared name do), and a name that fits both does not identify either.
     * Picking the first would give the handler a body it did not ask for, with
     * the identity of the other one.
     */
    fun resolve(childAddresses: List<PluginStepId>, slot: PluginStepId): SlotResolution {
        val matches = childAddresses.withIndex().filter { (_, address) -> address == slot }
        return when (matches.size) {
            0 -> SlotResolution.Missing(slot)
            1 -> SlotResolution.Found(matches.first().index)
            else -> SlotResolution.Ambiguous(slot, matches.size)
        }
    }
}
