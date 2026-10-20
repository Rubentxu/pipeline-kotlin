package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.events.registry.PluginEventEmission
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.registry.EmissionOutcome
import dev.rubentxu.pipeline.v2.events.registry.EventRegistry
import dev.rubentxu.pipeline.v2.events.registry.RegistryEventEmitter
import java.time.Instant

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

/**
 * Binds the open event registry to one run's sink and to THAT run's clock (P3-C / S6.4).
 *
 * It lives beside the adapter rather than in the coordinator because it is not a coordinator
 * responsibility: composing the emission seam belongs to the dispatch machinery that owns the sink
 * and the clock, and `CoordinatorGrowthGuardrailTest` exists precisely to stop unrelated seams
 * accumulating in that file.
 *
 * The clock adapter is the whole reason this is a function and not a `Clock.systemUTC()`. The run's
 * clock is a port ([dev.rubentxu.pipeline.v2.domain.durable.Clock]) so a replay can be given a
 * fixed one, while the published seam takes a `java.time.Clock`. Reading the wall clock here would
 * stamp every plugin event with the moment it was OBSERVED rather than the moment the run says it
 * happened — the value a replay has to reproduce. So the port is adapted, never replaced.
 *
 * UTC because the seam produces an `Instant`, which carries no zone of its own; a fixed zone keeps
 * `withZone` from quietly changing what the port is asked for.
 */
internal fun pluginEventEmitter(
    registry: EventRegistry,
    sink: EventSink,
    clock: Clock,
): RegistryEventEmitter = RegistryEventEmitter(
    registry = registry,
    sink = sink,
    clock = object : java.time.Clock() {
        override fun instant(): Instant = clock.now()

        override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): java.time.Clock =
            throw UnsupportedOperationException(
                "the run clock is a port over Instant and carries no zone; a plugin event's " +
                    "timestamp comes from the run, not from a time zone",
            )
    },
)
