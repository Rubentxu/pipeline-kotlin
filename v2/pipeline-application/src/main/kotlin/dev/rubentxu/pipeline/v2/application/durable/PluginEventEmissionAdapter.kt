package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.PluginEventEmission
import dev.rubentxu.pipeline.v2.events.registry.EmissionOutcome
import dev.rubentxu.pipeline.v2.events.registry.RegistryEventEmitter

/**
 * Binds the open event registry to ONE invocation, and is the whole of what a plugin sees of it
 * (P3-C / S6.4).
 *
 * There is no logic here on purpose. Everything decidable — is the kind registered, does its codec
 * accept this payload — was already decided inside [RegistryEventEmitter], and the only effect left
 * is the store append. So the adapter's whole job is to hide four things a handler must not name:
 * the registry, the sink, the clock, and the `runId`.
 *
 * The `runId` is the one that would actually cause damage if it leaked. A Step that could pass its
 * own `runId` could attribute an observation to a different run, and the event would then be
 * persisted into a stream whose cursor another consumer is holding. Binding it here means the only
 * way to emit is into the run you are executing in.
 */
internal class PluginEventEmissionAdapter(
    private val emitter: RegistryEventEmitter,
    private val runId: String,
) : PluginEventEmission {

    override fun <P : Any> emit(kind: String, payload: P): EmissionOutcome =
        emitter.emit(kind, runId, payload)
}
