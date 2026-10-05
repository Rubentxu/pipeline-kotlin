package dev.rubentxu.pipeline.v2.events

import java.time.Instant

/**
 * The ONE closed carrier for events a PLUGIN contributes through the open registry (P3 slice 2).
 *
 * The `DomainEvent` hierarchy stays sealed: it is the engine's vocabulary, and opening it would
 * let any classpath stranger masquerade as a core event. A plugin event instead arrives as THIS
 * single case — the engine knows everything identity needs (who, when, in what order, under what
 * kind and schema version) and nothing it must not guess: the payload is the contributor's own
 * serialization, opaque at rest, and only becomes typed again through the
 * [dev.rubentxu.pipeline.v2.events.registry.EventRegistry] the contributor registered with.
 *
 * This is the standard foreign-payload envelope, not a `raw Map` API: the case is typed, the
 * kind and schema version are first-class, and no consumer is invited to parse the payload —
 * a consumer that wants semantics goes through the registry's codec or reads identity only.
 * The payload string MUST be the bytes the registered codec's `encode` produced; the emitter
 * enforces that (fail-closed) rather than trusting callers to keep the two in sync.
 */
data class PluginEventEmitted(
    override val eventId: String,
    override val runId: String,
    override val sequence: Long,
    override val occurredAt: Instant,
    /** The registry kind this payload was emitted under — e.g. `acme.validated`. */
    val registryKind: String,
    /** The schema version the payload was encoded under, at emission time. */
    val schemaVersion: Int,
    /** The contributor's own serialization of the payload, produced by the registered codec. */
    val payload: String,
    /** The declared emission authority (provenance; mirror of the definition's `emittedBy`). */
    val emittedBy: String,
) : DomainEvent {
    override val kind: String get() = "PluginEventEmitted"
}
