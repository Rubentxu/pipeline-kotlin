package dev.rubentxu.pipeline.v2.events.registry

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.PluginEventEmitted
import dev.rubentxu.pipeline.v2.events.RunStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Slice 2 conformance: a registered plugin event crosses the SAME stream authority as core
 * events — emitted fail-closed, sequenced by the store, paged by `readSlice` in sequence order,
 * and typed again only through the registry on read. No second channel, no second sequence.
 */
class RegistryEventEmitterTest {

    private data class Validated(val note: String)

    private val codec = object : EventPayloadCodec<Validated> {
        override fun encode(payload: Validated): String = "v1:${payload.note}"
        override fun decode(raw: String, schemaVersion: Int): PayloadDecode<Validated> =
            when {
                schemaVersion != 1 -> PayloadDecode.UnknownSchemaVersion(schemaVersion)
                raw.startsWith("v1:") -> PayloadDecode.Decoded(Validated(raw.removePrefix("v1:")))
                else -> PayloadDecode.Malformed("not v1: $raw")
            }
    }

    /** Minimal ordered sink: sequences are the STORE's decision (appendAssigned override). */
    private class OrderedSink : EventSink {
        val appended = mutableListOf<DomainEvent>()
        private var next = 1L
        override fun append(event: DomainEvent) {
            appended += if (event.sequence == 0L) event.withAssignedSequence(next++) else event
        }
        override fun appendAssigned(event: DomainEvent): DomainEvent {
            val assigned = if (event.sequence == 0L) event.withAssignedSequence(next++) else event
            appended += assigned
            return assigned
        }
        override fun eventsFor(runId: String): Sequence<DomainEvent> = appended.asSequence()
        private fun DomainEvent.withAssignedSequence(s: Long): DomainEvent = when (this) {
            is PluginEventEmitted -> copy(sequence = s)
            is RunStarted -> copy(sequence = s)
            else -> error("test sink only carries the two kinds this fixture uses")
        }
    }

    private fun registeredRegistry(vararg kinds: String): EventRegistry = EventRegistry.builder().also { r ->
        kinds.forEach { k ->
            r.register(
                EventDefinition.create(
                    kind = k, schemaVersion = 1, payloadClass = Validated::class.java,
                    codec = codec, emittedBy = "acme-plugin",
                ),
            )
        }
    }.build()

    private fun emitter(registry: EventRegistry, sink: OrderedSink) =
        RegistryEventEmitter(registry, sink, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))

    @Nested
    @DisplayName("emission is fail-closed and sequenced by the store")
    inner class Emission {
        @Test
        fun `an unregistered kind cannot be emitted - typed refusal, nothing stored`() {
            val sink = OrderedSink()
            val outcome = emitter(registeredRegistry("acme.other"), sink)
                .emit("acme.validated", runId = "r1", payload = Validated("x"))
            assertEquals(EmissionOutcome.UnregisteredKind("acme.validated"), outcome)
            assertEquals(0, sink.appended.size, "a refused emission must store nothing")
        }

        @Test
        fun `emission carries the store-ASSIGNED sequence and the codec's own bytes`() {
            val sink = OrderedSink()
            val registry = registeredRegistry("acme.validated")
            val outcome = emitter(registry, sink).emit("acme.validated", "r1", Validated("hello"))
            val event = (outcome as EmissionOutcome.Emitted).event
            assertEquals(1L, event.sequence, "the SEQUENCE AUTHORITY assigned it, not the emitter")
            assertEquals("acme.validated", event.registryKind)
            assertEquals(1, event.schemaVersion)
            assertEquals("v1:hello", event.payload)
            assertEquals("acme-plugin", event.emittedBy)
        }
    }

    @Nested
    @DisplayName("one stream, one sequence authority, one pagination")
    inner class SingleAuthority {
        @Test
        fun `plugin events and core events share the sequence order under readSlice`() {
            val sink = OrderedSink()
            val registry = registeredRegistry("acme.validated")
            val em = emitter(registry, sink)
            sink.append(RunStarted("e0", "r1", 0, Instant.EPOCH, "p.kts"))
            em.emit("acme.validated", "r1", Validated("first"))
            em.emit("acme.validated", "r1", Validated("second"))

            val slice = sink.readSlice("r1", after = null, limit = 10)
            assertEquals(listOf(1L, 2L, 3L), slice.events.map { it.sequence }) {
                "core and plugin events share ONE strictly ascending sequence - no second channel"
            }
            assertTrue(slice.events[1] is PluginEventEmitted)
        }

        @Test
        fun `read-back types through the registry and fails closed on unregistered kinds`() {
            val sink = OrderedSink()
            val registry = registeredRegistry("acme.validated")
            val em = emitter(registry, sink)
            val emitted = (em.emit("acme.validated", "r1", Validated("hi")) as EmissionOutcome.Emitted).event

            assertEquals(
                PayloadDecode.Decoded(Validated("hi")),
                em.decode(emitted),
                "the payload becomes typed again through the registered codec - the only door",
            )

            val orphan = emitted.copy(registryKind = "acme.revoked")
            assertTrue(
                em.decode(orphan) is PayloadDecode.Malformed,
                "a carried event whose kind is no longer registered fails closed, never guesses",
            )
        }
    }
}
