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

    private fun registryWithValidated(): EventRegistry = EventRegistry.builder().also {
        it.register(validatedDefinition())
    }.build()

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
            val builder = EventRegistry.builder()
            val outcome = builder.register(validatedDefinition())
            assertEquals(RegistrationOutcome.Registered("acme.validated"), outcome)
            val registry = builder.build()
            assertTrue(registry.isRegistered("acme.validated"))
            assertEquals(1, registry.size())
        }

        @Test
        fun `a duplicate kind is REJECTED, never overwritten, and names the owner`() {
            val builder = EventRegistry.builder().also { it.register(validatedDefinition()) }
            val registry = builder.build()
            val outcome = builder.register(
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

        /**
         * The version is not a negotiation axis, and this pins that.
         *
         * It is tempting to read "a different schemaVersion" as an upgrade of an existing kind —
         * the registry already owns that kind, so replacing its codec looks like version management
         * rather than a collision. It is not. The payloads already on disk were written under the
         * FIRST version, and swapping the codec re-types every one of them with a shape they do
         * not have. So the second registration is refused exactly as any duplicate is, and the
         * registry keeps serving the version it admitted first.
         *
         * Evolving an existing kind is a real need and it is NOT this: it is a new kind, or a
         * deliberate migration. Both are core decisions, and neither may arrive as a side effect
         * of one plugin shipping a new jar on a shared classpath.
         */
        @Test
        fun `a known kind re-declared under a different schema version is refused, not upgraded`() {
            val builder = EventRegistry.builder().also { it.register(validatedDefinition()) }
            val registry = builder.build()
            val outcome = builder.register(
                validatedDefinition(schemaVersion = 2, emittedBy = "a-different-plugin"),
            )
            assertEquals(
                RegistrationOutcome.DuplicateKind("acme.validated", "acme-plugin"),
                outcome,
                "a new schemaVersion on an owned kind is a collision, not an upgrade: the payloads " +
                    "already on disk were written under version 1 and a swapped codec would re-type " +
                    "every one of them with a shape they do not have",
            )
            assertEquals(1, registry.size(), "the refused redefinition must not have changed anything")
            assertEquals(
                1,
                registry.definition("acme.validated")?.schemaVersion,
                "the registry must still serve the version it admitted first, not the one that lost",
            )
        }

        @Test
        fun `an invalid definition cannot be registered at all - no acceptance with warnings`() {            val builder = EventRegistry.builder()
            val outcome = builder.register(validatedDefinition(schemaVersion = 0))
            assertEquals(
                RegistrationOutcome.RejectedDefinition::class,
                outcome::class,
            )
            assertEquals(0, builder.build().size(), "a rejected definition must leave nothing behind")
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

    // ---- S6/F: the laws the Builder split introduces --------------------------------

    /**
     * The class KDoc claimed "read-only after registration" while `EventRegistry` itself
     * carried a public `register`. The claim was a promise; it is now a property of the type,
     * and these rows are the enforcement.
     */
    @Nested
    @DisplayName("S6/F the registry cannot be extended after build()")
    inner class FrozenAfterBuild {
        /**
         * MUTATION THAT KILLS THIS: `build()` implemented as `EventRegistry(byKind)` without the
         * copy. A builder kept alive after building would then keep growing the registry an
         * emitter and a read-back are already consulting — a new event kind would appear mid-run
         * for one reader and not the other.
         */
        @Test
        fun `build snapshots - a later registration never reaches the frozen registry`() {
            val builder = EventRegistry.builder()
            builder.register(validatedDefinition(kind = "acme.first"))
            val frozen = builder.build()

            builder.register(validatedDefinition(kind = "acme.later"))

            assertEquals(
                listOf("acme.first"),
                frozen.registeredKinds(),
                "the frozen registry must not observe a registration made after build()",
            )
            assertEquals(1, frozen.size())
            assertTrue(
                !frozen.isRegistered("acme.later"),
                "a kind added after build() must not be visible to a reader",
            )
            assertEquals(
                listOf("acme.first", "acme.later"),
                builder.build().registeredKinds(),
                "the builder is still free to grow; it just no longer reaches what was handed out",
            )
        }

        /**
         * MUTATION THAT KILLS THIS: re-adding `fun register(...)` to the `EventRegistry`
         * interface. The builder, the copy and every admission row above would still pass while
         * the runtime regained a mutation path. This row is the one that notices.
         */
        @Test
        fun `EventRegistry exposes no registration method`() {
            // Match on the Kotlin name and on the exact mutation verbs. A prefix test on
            // "register" would flag `registeredKinds`, which is a read and is part of the
            // declared surface below.
            val mutationMethods = EventRegistry::class.java.declaredMethods.map { it.name.substringBefore('-') }
                .filter { n ->
                    n == "register" || n == "unregister" || n.startsWith("add") ||
                        n == "put" || n == "remove" || n == "clear"
                }

            assertTrue(
                mutationMethods.isEmpty(),
                "EventRegistry must not expose any registration method, but found: " +
                    mutationMethods.joinToString(),
            )
            // Inline value classes mangle the JVM name of any parameter they appear in
            // (`definition-bQvmloc`), so compare on the Kotlin name.
            assertEquals(
                setOf("definition", "isRegistered", "registeredKinds", "size"),
                EventRegistry::class.java.declaredMethods.map { it.name.substringBefore('-') }.toSet(),
                "EventRegistry's read surface must stay exactly these four operations",
            )
        }
    }

    // ---- determinism / no defaults ------------------------------------------------

    @Nested
    @DisplayName("snapshots are deterministic and unknown kinds stay unknown")
    inner class Determinism {
        @Test
        fun `same registrations in same order produce the same registry`() {
            fun build(): EventRegistry = EventRegistry.builder().also { r ->
                r.register(validatedDefinition(kind = "acme.first"))
                r.register(validatedDefinition(kind = "acme.second"))
            }.build()
            assertEquals(build().registeredKinds(), build().registeredKinds())
        }

        @Test
        fun `registration order is preserved in the snapshot`() {
            val builder = EventRegistry.builder().also { it.register(validatedDefinition()) }
            val registry = builder.build()
            builder.register(validatedDefinition(kind = "acme.later"))
            val withBoth = builder.build()
            assertEquals(
                listOf("acme.validated", "acme.later"),
                withBoth.registeredKinds(),
                "order is a property of the composition, and it must survive the freeze",
            )
            assertEquals(
                listOf("acme.validated"),
                registry.registeredKinds(),
                "the registry frozen before the second registration must not observe it",
            )
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
