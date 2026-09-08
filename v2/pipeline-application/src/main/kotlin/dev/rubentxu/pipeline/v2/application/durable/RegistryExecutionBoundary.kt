package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
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
 * A normal typed handler return is a durable [StepOutcome.Success]: the step's observable meaning is
 * the effects it emits through its declared capabilities (echo emits [EchoOutputCaptured] into the
 * event sink it received). A thrown handler is an adapter/engine defect, so it fails closed as a typed
 * [StepOutcome.Failure] (ENGINE). Generic handler-output normalization (typed/void output, output
 * encode) is owned by CDE.3-d4.
 */
object RegistryExecutionBoundary {

    fun adapt(): CommonExecutionBoundary = CommonExecutionBoundary { prepared, context ->
        when (prepared) {
            is PreparedRegistryExecution -> execute(prepared, context)
            is PreparedLegacyExecution -> throw EngineInvariantViolation(
                "RegistryExecutionBoundary cannot route a legacy-family PreparedExecution",
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun execute(
        prepared: PreparedRegistryExecution,
        context: CanonicalRuntimeContext,
    ): StepOutcome {
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
            definition.handler.execute(prepared.decodedInput, handlerContext)
            StepOutcome.Success
        } catch (e: Exception) {
            StepOutcome.Failure(
                PipelineFailure(
                    kind = FailureKind.ENGINE,
                    message = "registry step '${prepared.key.value}' handler failed: ${e.message ?: "unknown"}",
                    cause = e,
                ),
            )
        }
    }
}
