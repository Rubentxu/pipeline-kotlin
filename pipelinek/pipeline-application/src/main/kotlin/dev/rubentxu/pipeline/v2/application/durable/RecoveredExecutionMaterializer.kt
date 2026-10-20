package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.RecoveredProjection
import dev.rubentxu.pipeline.v2.domain.step.RecoveredStepProjection
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * S4-F1-C2 — the GENERIC adapter that turns an observed terminal into a typed Step value.
 *
 * ## The problem this solves, in one sentence
 *
 * The reconciliation authority knows **that** a subprocess must be recovered; the Step's contract
 * knows **what its exit code means**; and until this existed nothing connected them, so the observer
 * guessed — and guessed wrong for `returnStatus`, because deciding that requires the invocation's
 * `returnMode`, which lives in the Step's own input behind the Step's own codec.
 *
 * ## Why this is an adapter and not a fifth authority
 *
 * Every decision it appears to make is somebody else's, and it makes none of them:
 *
 * ```text
 * resolve definition   from the StepRegistry               — the registry owns identity
 * decode input         with the Step's inputCodec          — the Step owns its payload
 * project the terminal the Step's RecoveredStepProjection  — the Step owns its semantics
 * encode the value     with the Step's outputCodec         — the Step owns its wire form
 * ```
 *
 * There is no `when (stepKey)`, no table of exit codes, and no fallback. A Step that does not
 * implement [RecoveredStepProjection] gets [RecoveryMaterialisation.NoProjection] and fails closed,
 * which is the right answer for a Step that has not declared how it would materialise a recovery.
 *
 * ## Where the type erasure lives
 *
 * `StepDefinition<I, O>` is generic, and a registry hands back `StepDefinition<*, *>`, so recovering
 * `I` and `O` needs casts. All of them are in [materialize], and none is unchecked in spirit: the
 * [RecoveredStepProjection] IS the witness that the definition's `I` and `O` line up, because only a
 * definition that implements it can be reached, and it declares those same two type parameters.
 *
 * ## What it deliberately does NOT do
 *
 * ```text
 * no handler            the process already terminated in another process
 * no capability check   materialising needs none — ADR-S4-R1 §2.7
 * no I/O, no clock      pure in (definition, input, terminal)
 * no journal write      persistence belongs to the interpretation engine
 * ```
 */
internal object RecoveredExecutionMaterializer {

    /**
     * Pure: `(definition, encoded input, observed terminal) -> materialisation`.
     *
     * @param encodedInput the journal row's own input, which already carries `returnMode` and every
     *   other field the contract needs. It is decoded, never re-derived.
     */
    fun materialize(
        definition: StepDefinition<*, *>,
        encodedInput: OperationInput,
        terminal: DurableTaskTerminal,
        stepPayload: String? = null,
    ): RecoveryMaterialisation {
        val key = definition.contract.key
        val projection = definition as? RecoveredStepProjection<Any, Any>
            ?: return RecoveryMaterialisation.NoProjection(key)

        val input = decodeInput(definition, stepPayload ?: JsonObject(encodedInput.params).toString())
            ?: return RecoveryMaterialisation.MalformedInput(key)

        return when (val projected = projection.project(input, terminal)) {
            is RecoveredProjection.Materialised -> RecoveryMaterialisation.Materialised(
                CommonExecutionResult(
                    outcome = projected.outcome,
                    encodedOutput = encodeOutput(definition, projected.value),
                ),
            )

            is RecoveredProjection.InsufficientEvidence -> RecoveryMaterialisation.InsufficientEvidence(
                reason = projected.reason,
                terminal = terminal,
            )
        }
    }

    /**
     * Decodes the Step's OWN payload through the Step's own codec, in whatever shape the writing
     * surface recorded it.
     *
     * [payload] is the Step's own encoding: either the canonical durable params verbatim, or the
     * scripted surface's nested `encodedInput`. It is passed as the string the writing surface
     * produced rather than reconstructed, so a codec can never be handed a re-serialisation that
     * differs by key order or number formatting from the one the product actually wrote.
     *
     * A failure here is the codec rejecting a payload the product itself wrote, which is a
     * durable-record defect — so it is reported as one instead of being coerced into a default input.
     */
    @Suppress("UNCHECKED_CAST")
    private fun decodeInput(definition: StepDefinition<*, *>, payload: String): Any? =
        runCatching {
            val codec = definition.contract.inputCodec as StepCodec<Any>
            codec.decode(EncodedStepValue(payload))
        }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun encodeOutput(definition: StepDefinition<*, *>, value: Any): EncodedStepValue? =
        runCatching {
            val codec = definition.contract.outputCodec as StepCodec<Any>
            codec.encode(value)
        }.getOrNull()
}

/**
 * The closed result of a recovery materialisation.
 *
 * [Materialised] is the interesting one: it is the SAME [CommonExecutionResult] the fresh execution
 * path produces, reached from the other direction. That convergence is the point — not because a
 * recovered run and a fresh run are epistemologically identical, but because both have now been
 * through the authorities that own them.
 */
internal sealed interface RecoveryMaterialisation {

    /** A typed value, its outcome, and its encoded wire form. */
    data class Materialised(val result: CommonExecutionResult) : RecoveryMaterialisation

    /**
     * The facts are real, but this Step's contract cannot honestly yield a value from them.
     *
     * The terminal is carried so the caller can still journal an accurate status and an accurate
     * message: the process DID finish, and recording that is not the same as inventing what it
     * printed.
     */
    data class InsufficientEvidence(
        val reason: String,
        val terminal: DurableTaskTerminal,
    ) : RecoveryMaterialisation

    /** The Step declares no recovered projection, so no value may be materialised. Fail closed. */
    data class NoProjection(val key: PluginStepId) : RecoveryMaterialisation

    /** The durable input could not be decoded by the Step's own codec. */
    data class MalformedInput(val key: PluginStepId) : RecoveryMaterialisation
}

/**
 * The durable status to store for a terminal that yielded no typed value.
 *
 * Derived from the [DurableTaskTerminal] ALONE, never from a Step contract, because this is only
 * reached when there is no contract-derived outcome to use — a Step that declares no projection, a
 * payload the codec rejected, or evidence the contract admits it cannot use.
 *
 * [DurableTaskTerminal.Exited] maps to `FAILED` and that is a decision, not an omission: the process
 * finished, but the STEP did not deliver its declared value, so the invocation failed. Reading the
 * exit code here instead would reintroduce the very guess ADR-S4-R1 §2.4 removed, one layer further
 * from the observer.
 *
 * The TIMEOUT distinction stays a CASE rather than a message read: `Cancelled(TIMEOUT)` maps to
 * `FAILED_TIMEOUT` because the watchdog wrote `timeout.flag` before killing the process. Inferring
 * that from a failure kind is the second-guess that was removed.
 */
internal fun terminalStorageStatus(terminal: DurableTaskTerminal): OperationStatus = when (terminal) {
    is DurableTaskTerminal.Lost -> OperationStatus.LOST
    is DurableTaskTerminal.LaunchFailed -> OperationStatus.FAILED
    is DurableTaskTerminal.Cancelled ->
        if (terminal.interruption.kind == InterruptionKind.TIMEOUT) {
            OperationStatus.FAILED_TIMEOUT
        } else {
            OperationStatus.FAILED
        }
    is DurableTaskTerminal.Exited -> OperationStatus.FAILED
}

/** The typed failure to report when a terminal is real but no value may be produced from it. */
internal fun insufficientEvidenceOutcome(
    key: PluginStepId,
    reason: String,
    terminal: DurableTaskTerminal,
): StepOutcome = StepOutcome.Failure(
    PipelineFailure(
        kind = FailureKind.INFRASTRUCTURE,
        message = "Recovered terminal for '$key' cannot yield a value under this Step's contract: " +
            "$reason. The terminal itself was ${terminal::class.simpleName}, and no value has " +
            "been invented for it.",
    ),
)
