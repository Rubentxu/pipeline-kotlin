package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal

/**
 * S4-F1-C2 — the ADDITIVE, Step-owned capability for materialising a recovered terminal.
 *
 * ## Why this exists, and why it is an interface and not a field on [StepContract]
 *
 * A subprocess can terminate while the runtime that started it is down. On the next run the durable
 * facts are back — the exit code is in `result.txt`, the captured stdout is in `output.txt` — and
 * somebody has to turn them into the value a Kotlin program was promised. Historically nobody did:
 * the observer classified the exit code itself, and got `sh(returnStatus = true)` with exit 42
 * wrong, because deciding that requires the invocation's `returnMode`, which lives in the Step's
 * own input and is reachable only through the Step's own codec.
 *
 * So the question «what does exit code 42 mean?» is answered by whoever OWNS the contract, and that
 * is what this interface is. A definition may implement it; the engine asks for the CAPABILITY, never
 * for a Step key. Adding it costs no change to [StepContract], and a Step that does not implement it
 * simply cannot have a recovered value materialised — which fails closed rather than falling back.
 *
 * ## What it is NOT
 *
 * ```text
 * NOT a handler      the process already finished; nothing is executed
 * NOT a capability   materialising needs no runtime capability, least of all process launch
 * NOT a second
 *   reconciliation
 *  authority         it receives a terminal and returns a value; it decides nothing about
 *                    Execute / Reuse / Recover / Diverged / Abort
 * ```
 *
 * It is an [InsufficientEvidence] reporter as much as a projector. Declaring a recovered value
 * possible is not a promise that one is: a Step whose return mode needs evidence the substrate never
 * recorded must say so, because the alternative is inventing a value.
 */
fun interface RecoveredStepProjection<I : Any, O : Any> {

    /**
     * Projects the observed [terminal] onto this Step's declared value type, given the invocation's
     * already-decoded [input].
     *
     * Pure. No I/O, no clock, no process, no registry: everything needed is in the two arguments, and
     * the facts in [terminal] are exactly what the substrate recorded.
     */
    fun project(input: I, terminal: DurableTaskTerminal): RecoveredProjection<O>
}

/**
 * The closed result of a [RecoveredStepProjection].
 *
 * Two cases, and the second one is the one that keeps this honest. A projector that could only
 * return a value would be forced to answer when it has nothing to answer with.
 */
sealed interface RecoveredProjection<out O : Any> {

    /**
     * The terminal honestly yields a value under this contract.
     *
     * [outcome] travels WITH the value rather than being derived from it afterwards, because the
     * Step already computed both from the same terminal and deriving one from the other would be a
     * second classification of the same fact.
     */
    data class Materialised<O : Any>(val value: O, val outcome: StepOutcome) : RecoveredProjection<O>

    /**
     * The observed facts are real but insufficient for this contract, so no value may be produced.
     *
     * This is NOT a failure of the Step and NOT a defect in the substrate: it is the correct answer
     * for, say, `returnStdout` when the process succeeded and no `output.txt` was ever written. The
     * caller fails closed on it, because the alternatives are `""`, `0` and `Unit` — all of which are
     * values the substrate never observed.
     */
    data class InsufficientEvidence(val reason: String) : RecoveredProjection<Nothing>
}
