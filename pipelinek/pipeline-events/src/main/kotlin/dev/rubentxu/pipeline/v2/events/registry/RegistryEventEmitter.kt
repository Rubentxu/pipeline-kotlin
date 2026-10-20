package dev.rubentxu.pipeline.v2.events.registry

import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.PluginEventEmitted
import java.time.Clock
import java.util.UUID

/**
 * The fail-closed bridge between a registered [EventDefinition] and the event stream (P3 slice
 * 2): emission requires registration, encoding goes through the registered codec, and the
 * sequence comes from the store's own authority — this class never invents one.
 *
 * Laws:
 *  - an UNREGISTERED kind cannot be emitted at all (Constitution §9: no registration, no
 *    event — the refusal is typed, not an exception);
 *  - the payload bytes are produced by the definition's own codec, so what a reader decodes
 *    is provably what the contributor emitted;
 *  - emission is an INTERPRETER of a pure decision: everything decidable (registered? encodes?)
 *    is decided before the store is touched, and the store append is the only effect.
 */
class RegistryEventEmitter(
    private val registry: EventRegistry,
    private val sink: EventSink,
    private val clock: Clock,
) {

    /**
     * Emits [payload] under [kind] into [runId]'s stream. The sequence is the store-assigned
     * one (`appendAssigned`), so the returned event is the authority's own decision, never a
     * local echo.
     */
    fun <P : Any> emit(
        kind: String,
        runId: String,
        payload: P,
    ): EmissionOutcome {
        val definition = registry.definition(kind)
            ?: return EmissionOutcome.UnregisteredKind(kind)
        @Suppress("UNCHECKED_CAST")
        val typed = definition as EventDefinition<P>
        val encoded = typed.codec.encode(payload)
        val assigned = sink.appendAssigned(
            PluginEventEmitted(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequence = 0L, // assigned by the store; the returned value carries it
                occurredAt = clock.instant(),
                registryKind = definition.kind,
                schemaVersion = definition.schemaVersion,
                payload = encoded,
                emittedBy = definition.emittedBy,
            ),
        ) as? PluginEventEmitted
            ?: return EmissionOutcome.EncodingFailed(
                kind,
                "the store returned a foreign type for an assigned plugin event; refusing to pass it on",
            )
        return EmissionOutcome.Emitted(assigned)
    }

    /**
     * Typed read-back: decodes a carried plugin event through the registry. A core event is not
     * a plugin event; a carried event whose kind is no longer registered fails closed.
     */
    fun decode(carrier: PluginEventEmitted): PayloadDecode<Any> {
        val definition = registry.definition(carrier.registryKind)
            ?: return PayloadDecode.Malformed(
                "kind '${carrier.registryKind}' is not registered; refusing to guess",
            )
        @Suppress("UNCHECKED_CAST")
        val typed = definition as EventDefinition<Any>
        return typed.codec.decode(carrier.payload, carrier.schemaVersion)
    }
}

/** Outcome of an emission attempt. Every refusal is typed and diagnosable. */
sealed interface EmissionOutcome {
    /** Admitted: carries the STORE-ASSIGNED event (the sequence authority's decision). */
    data class Emitted(val event: PluginEventEmitted) : EmissionOutcome

    /** Refused: the kind was never registered with this registry. */
    data class UnregisteredKind(val kind: String) : EmissionOutcome

    /** Refused: the definition's own codec refused the payload. */
    data class EncodingFailed(val kind: String, val reason: String) : EmissionOutcome
}
