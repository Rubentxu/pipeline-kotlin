package dev.rubentxu.pipeline.v2.events.registry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Conformance suite for the open event registry (P3 slice 1, Semantic Constitution §8.4/§9).
 *
 * Every row is a LAW of the registry, not a characterisation of today's implementation:
 * fail-closed admission, read-only after registration, deterministic snapshots, no silent
 * defaults, and versioned decode that never guesses. A change here is a change of the event
 * extensibility contract, not a refactor.
 */
class EventRegistryTest {

    private data class Validated(val note: String)

    /** A codec that only knows schema version 1 — the minimal honest contributor. */
    private val v1Codec = object : EventPayloadCodec<Validated> {
        override fun encode(payload: Validated): String = "v1:${payload.note}"
        override fun decode(raw: String, schemaVersion: Int): PayloadDecode<Validated> =
            when {
                schemaVersion != 1 -> PayloadDecode.UnknownSchemaVersion(schemaVersion)
                raw.startsWith("v1:") -> PayloadDecode.Decoded(Validated(raw.removePrefix("v1:")))
                else -> PayloadDecode.Malformed("payload does not conform to schema v1: $raw")
            }
    }

    private fun validatedDefinition(
        kind: String = "acme.validated",
        schemaVersion: Int = 1,
        emittedBy: String = "acme-plugin",
    ) = EventDefinition.create(
        kind = kind,
        schemaVersion = schemaVersion,
        payloadClass = Validated::class.java,
        codec = v1Codec,
        emittedBy = emittedBy,
    )

    private fun registryWithValidated(): EventRegistry = EventRegistry.create().also {
        it.register(validatedDefinition())
    }

    // ---- declaration validation -------------------------------------------------

    @Nested
    @DisplayName("declaration is validated totally, before any registry exists")
    inner class DeclarationValidation {
        @Test
        fun `a well-formed definition is Valid and carries itself`() {
            val creation = validatedDefinition()
            assertTrue(creation is EventDefinitionCreation.Valid) { "$creation" }
            assertEquals("acme.validated", (creation as EventDefinitionCreation.Valid).definition.kind)
        }

        @Test
        fun `every problem is reported, not just the first`() {
            val creation = EventDefinition.create(
                kind = "  ", // blank AND whitespace-only; also unnamespaced
                schemaVersion = 0,
                payloadClass = Validated::class.java,
                codec = v1Codec,
                emittedBy = "",
            )
            val problems = (creation as EventDefinitionCreation.Invalid).problems
            assertTrue(problems.size >= 3) {
                "blank kind, version 0 and blank emittedBy are three problems: $problems"
            }
        }

        @Test
        fun `an unnamespaced kind is rejected - two plugins must not collide by accident`() {
            val creation = validatedDefinition(kind = "validated")
            assertTrue(creation is EventDefinitionCreation.Invalid) { "$creation" }
            assertTrue(
                (creation as EventDefinitionCreation.Invalid).problems.any { it.contains("namespaced") },
            )
        }
    }

    // ---- admission laws ----------------------------------------------------------

    @Nested
    @DisplayName("admission is fail-closed and read-only")
    inner class Admission {
        @Test
        fun `registration succeeds once and admits the declared kind`() {
            val registry = EventRegistry.create()
            val outcome = registry.register(validatedDefinition())
            assertEquals(RegistrationOutcome.Registered("acme.validated"), outcome)
            assertTrue(registry.isRegistered("acme.validated"))
            assertEquals(1, registry.size())
        }

        @Test
        fun `a duplicate kind is REJECTED, never overwritten, and names the owner`() {
            val registry = registryWithValidated()
            val outcome = registry.register(
                validatedDefinition(emittedBy = "a-different-plugin"),
            )
            assertEquals(
                RegistrationOutcome.DuplicateKind("acme.validated", "acme-plugin"),
                outcome,
                "two plugins owning one kind would make every recorded payload of that kind " +
                    "ambiguous forever; the second registration must fail with the first owner named",
            )
            assertEquals(1, registry.size(), "the duplicate must not have changed the registry")
        }

        @Test
        fun `an invalid definition cannot be registered at all - no acceptance with warnings`() {
            val registry = EventRegistry.create()
            val outcome = registry.register(validatedDefinition(schemaVersion = 0))
            assertEquals(
                RegistrationOutcome.RejectedDefinition::class,
                outcome::class,
            )
            assertEquals(0, registry.size(), "a rejected definition must leave nothing behind")
        }

        @Test
        fun `the registry never mutates through reads`() {
            val registry = registryWithValidated()
            val before = registry.registeredKinds()
            registry.definition("acme.validated")
            registry.isRegistered("acme.validated")
            registry.registeredKinds()
            registry.size()
            assertEquals(before, registry.registeredKinds(), "reads are read-only")
        }
    }

    // ---- determinism / no defaults ------------------------------------------------

    @Nested
    @DisplayName("snapshots are deterministic and unknown kinds stay unknown")
    inner class Determinism {
        @Test
        fun `same registrations in same order produce the same registry`() {
            fun build(): EventRegistry = EventRegistry.create().also { r ->
                r.register(validatedDefinition(kind = "acme.first"))
                r.register(validatedDefinition(kind = "acme.second"))
            }
            assertEquals(build().registeredKinds(), build().registeredKinds())
        }

        @Test
        fun `registration order is preserved in the snapshot`() {
            val registry = registryWithValidated()
            registry.register(validatedDefinition(kind = "acme.later"))
            assertEquals(listOf("acme.validated", "acme.later"), registry.registeredKinds())
        }

        @Test
        fun `an unknown kind is null - nothing fabricates a placeholder`() {
            val registry = registryWithValidated()
            assertNull(
                registry.definition("acme.unknown"),
                "a placeholder definition would let an unregistered payload decode as " +
                    "'something' instead of failing as unknown",
            )
        }
    }

    // ---- versioned decode ----------------------------------------------------------

    @Nested
    @DisplayName("decode is versioned and never guesses")
    inner class Decode {
        @Test
        fun `round trip preserves the typed payload`() {
            val codec = validatedDefinition().let { (it as EventDefinitionCreation.Valid<Validated>).definition.codec }
            val encoded = codec.encode(Validated("hello"))
            assertEquals(PayloadDecode.Decoded(Validated("hello")), codec.decode(encoded, 1))
        }

        @Test
        fun `an unknown schema version fails closed`() {
            val codec = validatedDefinition().let { (it as EventDefinitionCreation.Valid<Validated>).definition.codec }
            assertEquals(
                PayloadDecode.UnknownSchemaVersion<Validated>(99),
                codec.decode("v1:hello", 99),
                "reading an unknown shape as 'probably fine' is the semantic drop the " +
                    "Constitution forbids",
            )
        }

        @Test
        fun `a malformed payload of a known version fails named`() {
            val codec = validatedDefinition().let { (it as EventDefinitionCreation.Valid<Validated>).definition.codec }
            val outcome = codec.decode("not-a-v1-payload", 1)
            assertTrue(outcome is PayloadDecode.Malformed) { "$outcome" }
        }
    }
}
