package dev.rubentxu.pipeline.fabric.consumer

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.PluginEventEmitted
import dev.rubentxu.pipeline.v2.events.registry.EventDefinition
import dev.rubentxu.pipeline.v2.events.registry.EventPayloadCodec
import dev.rubentxu.pipeline.v2.events.registry.EventRegistry
import dev.rubentxu.pipeline.v2.events.registry.PayloadDecode
import dev.rubentxu.pipeline.v2.events.registry.RegistryEventEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * P3 slice 3 — an EXTERNAL consumer declares, registers, emits, pages and re-types a plugin
 * event using ONLY the published `pipeline-events` contract. Everything this file needs comes
 * from the four published coordinates; a failure here is a broken publication, not a broken
 * implementation module, because this build cannot see one.
 */
class RegistryEventContractTest {

    private data class ContractValidated(val evidence: String)

    private val codec = object : EventPayloadCodec<ContractValidated> {
        override fun encode(payload: ContractValidated): String = "v1#${payload.evidence}"
        override fun decode(raw: String, schemaVersion: Int): PayloadDecode<ContractValidated> =
            when {
                schemaVersion != 1 -> PayloadDecode.UnknownSchemaVersion(schemaVersion)
                raw.startsWith("v1#") -> PayloadDecode.Decoded(ContractValidated(raw.removePrefix("v1#")))
                else -> PayloadDecode.Malformed("unexpected shape: $raw")
            }
    }

    /** The consumer's OWN sink implementation against the published EventSink interface. */
    private class ConsumerSink : EventSink {
        val events = mutableListOf<DomainEvent>()
        private var next = 1L
        override fun append(event: DomainEvent) {
            events += event
        }
        override fun appendAssigned(event: DomainEvent): DomainEvent {
            val assigned = if (event.sequence == 0L) {
                when (event) {
                    is PluginEventEmitted -> event.copy(sequence = next++)
                    else -> error("fixture only emits plugin events")
                }
            } else event
            events += assigned
            return assigned
        }
        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
    }

    @Test
    fun `a plugin declares, registers, emits, pages and re-types its event against published contracts only`() {
        // 1. DECLARE + REGISTER (fail-closed admission, published registry).
        val creation = EventDefinition.create(
            kind = "fabric.contractvalidated",
            schemaVersion = 1,
            payloadClass = ContractValidated::class.java,
            codec = codec,
            emittedBy = "fabric-tripwire",
        )
        val registry = EventRegistry.create()
        val registered = registry.register(creation)
        assertTrue(registered is dev.rubentxu.pipeline.v2.events.registry.RegistrationOutcome.Registered) {
            "a well-formed definition must be admitted: $registered"
        }

        // 2. EMIT through the store-authority bridge.
        val sink = ConsumerSink()
        val emitter = RegistryEventEmitter(registry, sink, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        val emitted = emitter.emit("fabric.contractvalidated", "run-rc2", ContractValidated("gate-green"))
        val assigned = (emitted as dev.rubentxu.pipeline.v2.events.registry.EmissionOutcome.Emitted).event
        assertEquals(1L, assigned.sequence, "the sequence came from the store authority")

        // 3. PAGE through the published single pagination authority.
        val slice = sink.readSlice("run-rc2", after = null, limit = 10)
        assertEquals(1, slice.events.size)
        val carried = slice.events.single() as PluginEventEmitted
        assertEquals("fabric.contractvalidated", carried.registryKind)

        // 4. RE-TYPE through the registry — and an unknown kind fails closed.
        assertEquals(
            PayloadDecode.Decoded(ContractValidated("gate-green")),
            emitter.decode(carried),
        )
        assertTrue(
            emitter.decode(carried.copy(registryKind = "fabric.unknown")) is PayloadDecode.Malformed,
        )
    }

    @Test
    fun `an unregistered kind cannot be emitted - the refusal is part of the published contract`() {
        val registry = EventRegistry.create()
        val outcome = RegistryEventEmitter(registry, ConsumerSink(), Clock.systemUTC())
            .emit("fabric.never.registered", "run-x", ContractValidated("x"))
        assertTrue(
            outcome is dev.rubentxu.pipeline.v2.events.registry.EmissionOutcome.UnregisteredKind,
        ) { "$outcome" }
    }
}
