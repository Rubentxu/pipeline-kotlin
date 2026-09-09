package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext

/**
 * Registry strategy executor behind the [CommonExecutionBoundary] (CDE.3-d3).
 *
 * A [PreparedRegistryExecution] already passed registry resolution, capability admission and typed
 * decode during [RegistryExecutionPreparation.prepare]; it carries the admitted definition and its
 * already-decoded input. Therefore [CommonExecutionBoundary.execute] here runs the typed handler
 * WITHOUT re-decoding (the prepare/codec split is preserved).
 *
 * Direction: PreparedRegistryExecution -> capability admission (re-checked against the runtime access,
 * fail-closed) -> [StepCapabilityAccess] (via [CanonicalRuntimeCapabilityAccess], never the raw
 * context) -> typed handler -> durable outcome. The handler receives only a narrow
 * [StepHandlerContext] built from the runtime identity plus its declared capabilities; it never sees a
 * [CanonicalRuntimeContext].
 *
 * ## LB-02 / G3-A1: typed-output carrier (correction 1, user rule)
 *
 * The registry execution model returns BOTH the closed [StepOutcome] algebra
 * and the typed `O` encoded as [EncodedStepValue]. The boundary NEVER
 * persists either into a journal row; **execution produces data; the durable
 * layer decides how/when it is persisted**.
 *
 * Two sibling entry points:
 *
 *  - [coexecute] returns the full [RegistryExecutionResult] (outcome +
 *    encoded output).
 *  - [execute] is the existing [CommonExecutionBoundary] adapter; it is now
 *    a thin projection over [coexecute] and continues to return only
 *    [StepOutcome], preserving the [CommonExecutionBoundary] contract for
 *    the legacy-compatible path, the recording boundary, and any caller
 *    that does not need typed output.
 *
 * A Step whose handler returns `Unit` reduces to `encodedOutput = null`
 * (mirroring echo's `EchoInput -> String` shape but reduced at the carrier:
 * echo's `String` is observable through the `EVENT_SINK` capability, not
 * through the typed-output slot). A thrown handler is an adapter/engine
 * defect and surfaces as [StepOutcome.Failure] (`ENGINE`); the encoded
 * output in that case is `null` (no successful terminal to encode).
 */
object RegistryExecutionBoundary {

    fun adapt(): CommonExecutionBoundary = CommonExecutionBoundary { prepared, context ->
        when (prepared) {
            is PreparedRegistryExecution -> {
                coexecute(prepared, context).outcome
            }
            is PreparedLegacyExecution -> throw EngineInvariantViolation(
                "RegistryExecutionBoundary cannot route a legacy-family PreparedExecution",
            )
        }
    }

    /**
     * Executes a [PreparedRegistryExecution] and returns both the closed
     * [StepOutcome] AND the encoded typed `O` (or `null` if the handler
     * returned `Unit`, threw, or surfaced a Failure).
     *
     * This is the entry point the durable coordinator reaches into when it
     * wants both pieces (A3 onwards); the projection into a journal row
     * stays a coordinator concern.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun coexecute(
        prepared: PreparedRegistryExecution,
        context: CanonicalRuntimeContext,
    ): RegistryExecutionResult {
        // Erasure boundary: the concrete payload type lives behind the codec / in the prepared input.
        val definition = prepared.definition as StepDefinition<Any, Any>
        val contract = definition.contract
        val access = CanonicalRuntimeCapabilityAccess(context)

        // Hard fail-closed re-check against the ACTUAL runtime access, immediately before the handler.
        // Admission is authoritative in prepare; reaching execute already implies capabilities were
        // supplied, so a mismatch here is an engine invariant and must never start the handler.
        val missing = contract.requiredCapabilities - access.available()
        if (missing.isNotEmpty()) {
            throw EngineInvariantViolation(
                "registry step '${prepared.key.value}' reached execute without declared capabilities " +
                    "available: ${missing.joinToString { it.key }}",
            )
        }

        val handlerContext = StepHandlerContext(
            runId = RunId(context.runId),
            stepIndex = context.stepIndex,
            capabilities = access,
        )

        return try {
            val produced: Any = definition.handler.execute(prepared.decodedInput, handlerContext)
            // A1: persist typed O only when it is NOT `Unit`. Encoded under the
            // Step's declared output codec so the durable layer can replay-decode only
            // through that same codec, never through opaque assumptions.
            val encoded: EncodedStepValue? = if (produced is Unit) {
                null
            } else {
                @Suppress("UNCHECKED_CAST")
                val codec = (definition.contract.outputCodec
                    as dev.rubentxu.pipeline.v2.domain.step.StepCodec<Any>)
                codec.encode(produced)
            }
            RegistryExecutionResult(
                outcome = StepOutcome.Success,
                encodedOutput = encoded,
            )
        } catch (e: Exception) {
            RegistryExecutionResult(
                outcome = StepOutcome.Failure(
                    PipelineFailure(
                        kind = FailureKind.ENGINE,
                        message = "registry step '${prepared.key.value}' handler failed: ${e.message ?: "unknown"}",
                        cause = e,
                    ),
                ),
                encodedOutput = null,
            )
        }
    }
}
