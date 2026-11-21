package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * Capability key under which the engine supplies the durable facts of a step's OWN operation.
 *
 * ## Why this is a capability and not a wider `StepHandlerContext`
 *
 * A step whose effect happens OUTSIDE the engine's durable substrate — an agent turn, a
 * request to a third party — has no control directory to reconcile from, and the engine
 * therefore has no terminal to hand back to it. The facts of that effect are the step's
 * own, and the step is the only party that can record them: it knows when the effect
 * began and what it produced.
 *
 * The context is not widened for it. `StepHandlerContext` is a published contract and every
 * handler in the system pays for a field it does not use; a capability is declared by the
 * step that needs it and refused at admission when the runtime cannot supply it. The same
 * reasoning as the lock coordination capability and the human-decision port: the step asks
 * for the seam, the bridge binds it, and a step that does not need it never sees it.
 *
 * ## What it is NOT
 *
 * ```text
 * NOT the journal        the facts of ONE operation, scoped to it
 * NOT a second authority the store decides nothing: it writes what it is given
 * NOT an authorisation   the capability says what a step may READ, never what it may do
 * ```
 */
val DURABLE_OPERATION_CAPABILITY: StepCapability = StepCapability("durableOperation")

/**
 * The identity of the durable operation a step runs as.
 *
 * [opId] is the operation's own identifier, as the runtime formats it, and [attempt] is the
 * attempt of that operation. The pair names the row: it is the identity a step needs to
 * find its OWN facts and not a reconstruction of them.
 *
 * The identity is a value and not a port: it travels with the facts and names what they
 * are about, so a reader can tell one operation's facts from another's without asking the
 * store anything.
 */
data class DurableOperationIdentity(
    val runId: String,
    val opId: String,
    val attempt: Int,
)

/**
 * The durable facts of ONE operation, and the way to record more of them.
 *
 * ## What a step may do with it
 *
 * [begin] says the effect is in flight, [record] says how it ended, and the two reads
 * answer what the last run left behind. A step that finds a recorded terminal does not
 * repeat its effect: the turn was already had, and asking again would be a second prompt
 * the run never made.
 *
 * ## What it guarantees
 *
 * - The facts belong to ONE operation, and the identity says which: a step can never read
 *   the facts of another operation through this port.
 * - What is recorded is what the step observed. The port stores a terminal and returns the
 *   same one; it never interprets it, and a step that recorded nothing reads `null` rather
 *   than a value nobody observed.
 * - The facts survive the process that wrote them: they are the store's rows, not memory.
 *
 * ## What it does NOT do
 *
 * It does not decide whether the recorded facts may be served again. That decision is the
 * normative replay table
 * ([dev.rubentxu.pipeline.v2.domain.durable.EffectReplayPolicy]), which every caller
 * reads; a port that decided it here would be a second authority over the same rule.
 */
interface DurableOperationFacts {

    /**
     * The identity of the operation these facts belong to.
     */
    val identity: DurableOperationIdentity

    /**
     * The status the durable record of this operation holds, or `null` when the operation
     * recorded nothing.
     *
     * A `RUNNING` status means the effect began and nothing finished it: the operation was
     * interrupted, and the state of its effect is unknown. That is a fact and not an
     * invitation to try again.
     */
    fun recordedStatus(): OperationStatus?

    /**
     * The terminal the durable record of this operation holds, or `null` when it holds
     * none.
     *
     * A terminal is what the effect produced; it is returned as it was recorded and never
     * reconstructed from the status.
     */
    fun recordedTerminal(): DurableTaskTerminal?

    /**
     * Records that the operation began: the effect is in flight and nothing has finished
     * it.
     *
     * Recording it BEFORE the effect is what makes an interrupted execution visible as
     * interrupted rather than as never started.
     */
    fun begin()

    /**
     * Records the terminal of the operation: the effect is over and this is what it
     * produced.
     *
     * @param terminal The observed terminal of the effect. It is stored as it is; the port
     *   decides nothing about what it means.
     */
    fun record(terminal: DurableTaskTerminal)
}
