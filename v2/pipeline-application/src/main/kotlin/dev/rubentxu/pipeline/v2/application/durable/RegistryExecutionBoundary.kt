package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
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
 * ## LB-02 / G3-A3: atomic outcome + encodedOutput
 *
 * The registry boundary returns the same shape as every other [CommonExecutionBoundary]
 * implementation: a [CommonExecutionResult] carrying both `outcome` and `encodedOutput?`. The encoded
 * output is the Step's typed `O` after `outputCodec.encode(O)`. After this boundary returns, the typed
 * `O` is GONE — only the `EncodedStepValue` (a `String` under a value class) crosses the seam.
 * The boundary NEVER persists either field; durability is a separate concern.
 *
 * A Step whose handler returns `Unit` reduces to `encodedOutput = null` (mirroring echo's
 * `EchoInput -> String` shape; echo's typed output crosses via `EVENT_SINK`, not via this slot).
 * A thrown handler is an adapter/engine defect and surfaces as `StepOutcome.Failure` (`ENGINE`);
 * the encoded output in that case is `null` because there is no successful terminal to encode.
 *
 * ## Atomicity / no-retention invariant (A3.6 user rule)
 *
 * The boundary MUST NOT retain execution output after `execute` returns. The carrier is the
 * single source of truth and is computed in one pass — there is no `lastOutput` field, no
 * thread-local, no per-key cache. Future parallel / remote / retry scenarios rely on the
 * atomicity of the carrier value.
 */
object RegistryExecutionBoundary {

    fun adapt(): CommonExecutionBoundary = CommonExecutionBoundary { prepared, context ->
        when (prepared) {
            is PreparedRegistryExecution -> coexecute(prepared, context)
            is PreparedLegacyExecution -> throw EngineInvariantViolation(
                "RegistryExecutionBoundary cannot route a legacy-family PreparedExecution",
            )
        }
    }

    /**
     * Executes a [PreparedRegistryExecution] and returns both the closed
     * [dev.rubentxu.pipeline.v2.domain.StepOutcome] AND the encoded typed `O` (or `null` if the
     * handler returned `Unit`, threw, or surfaced a Failure).
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun coexecute(
        prepared: PreparedRegistryExecution,
        context: CanonicalRuntimeContext,
    ): CommonExecutionResult {
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
            // A1/A3: persist typed O only when it is NOT `Unit`. Encoded under the
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
            CommonExecutionResult(
                outcome = dev.rubentxu.pipeline.v2.domain.StepOutcome.Success,
                encodedOutput = encoded,
            )
        } catch (e: Exception) {
            CommonExecutionResult(
                outcome = dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure(
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
