package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry

/**
 * Registry strategy payload, opaque to the durable coordinator (CDE.3-c).
 *
 * Runtime-ephemeral: carries the admitted [StepDefinition] and its already-decoded input so the
 * registry EXECUTE path (CDE.3-d/e) can run the typed handler without re-decoding. Never persisted,
 * never fingerprinted, never replayed. Constructing it runs NO Step side effects.
 */
data class PreparedRegistryExecution(
    val key: PluginStepId,
    val definition: StepDefinition<*, *>,
    /** Erased decoded input behind the codec; never escapes to the durable protocol as `Any`. */
    internal val decodedInput: Any,
) : PreparedExecution

/**
 * Registry strategy preparation / admission (CDE.3-c). Mirrors the legacy split in CDE.3-b3:
 * [prepare] runs registry resolution, capability admission and typed decode, returning a closed
 * [ExecutionPreparation], and NEVER calls the handler. Producing a [Ready] means the invocation is
 * decoded and admitted and ready for the single [CommonExecutionBoundary]; [Rejected] is admission
 * failure BEFORE any effect (unknown key, missing capability, or decode failure).
 */
object RegistryExecutionPreparation {

    @Suppress("UNCHECKED_CAST")
    fun prepare(
        registry: StepRegistry,
        key: PluginStepId,
        encodedInput: EncodedStepValue,
        availableCapabilities: Set<StepCapability>,
    ): ExecutionPreparation {
        val definition = registry.definition(key)
            ?: return ExecutionPreparation.Rejected("no registry definition for step '${key.value}'")
        val contract = (definition as StepDefinition<Any, Any>).contract

        // Fail-closed admission BEFORE decode or handler: a declared capability the engine does not
        // supply means this invocation can never run, so it is rejected during preparation.
        val missing = contract.requiredCapabilities - availableCapabilities
        if (missing.isNotEmpty()) {
            return ExecutionPreparation.Rejected(
                "missing required capabilities for step '${key.value}': ${missing.joinToString { it.key }}",
            )
        }

        val decoded = try {
            contract.inputCodec.decode(encodedInput)
        } catch (e: Exception) {
            return ExecutionPreparation.Rejected(
                "schema mismatch for step '${key.value}': ${e.message ?: "decode failed"}",
            )
        }

        return ExecutionPreparation.Ready(
            PreparedRegistryExecution(
                key = key,
                definition = definition,
                decodedInput = decoded,
            ),
        )
    }
}
