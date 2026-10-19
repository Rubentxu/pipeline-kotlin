package dev.rubentxu.pipeline.v2.events.registry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The admission laws of [EventRegistry]: which declarations get in, and what a reader can
 * therefore rely on once they are in.
 *
 * The class KDoc above promises these laws are "each pinned by [EventRegistryTest]". That
 * sentence was a lie until this file existed: `2aa071aab` deleted the 21 test files of this
 * module and the promises stayed in the source, so a reader concluded a guardian existed where
 * none did. This file is the guardian that sentence was already claiming.
 *
 * Why the laws matter beyond tidiness: a kind is the identity a recorded event travels under.
 * If admission is ever loosened, a second contributor claiming one kind silently replaces the
 * first, and every payload already recorded under that kind becomes ambiguous FOREVER -- there
 * is no timestamp that says which definition was in force, and no decoder that can tell. The
 * damage is retroactive, which is why the failure mode has to be a refusal rather than a
 * resolution order.
 *
 * What this file does NOT cover: whether an admitted definition's codec actually round-trips a
 * payload, and whether any of these kinds reach production. Both need a store and a consumer
 * respectively, and stay with the executable layer.
 *
 * No filesystem, no clock, no network, no event store: every input here is a value the compiler
 * already checked, and every output is a value a reader can inspect.
 */
class EventRegistryTest {

    private val codec = StringCodec

    private fun definition(
        kind: String,
        emittedBy: String = "plugin-a",
        schemaVersion: Int = 1,
    ): EventDefinitionCreation<String> =
        EventDefinition.create(
            kind = kind,
            schemaVersion = schemaVersion,
            payloadClass = String::class.java,
            codec = codec,
            emittedBy = emittedBy,
        )

    private fun registryWith(vararg kinds: String): EventRegistry =
        EventRegistry.builder().also { b -> kinds.forEach { b.register(definition(it)) } }
            .build()

    // --- admission: fail closed ----------------------------------------------------------

    @Test
    fun `a duplicate kind is refused and names the owner that already holds it`() {
        // THE LOAD-BEARING ROW. Replace the `DuplicateKind` return with an overwrite and this
        // goes red -- and nothing else in the repository would, because no E2E reads this module:
        // `examples/run.sh` observes the process exit code and at most a stderr substring, and
        // `stdout` is captured to a file that is never opened.
        val builder = EventRegistry.builder()
        assertEquals(RegistrationOutcome.Registered("acme.thing"), builder.register(definition("acme.thing", emittedBy = "plugin-a")))

        val conflict = builder.register(definition("acme.thing", emittedBy = "plugin-b"))

        assertEquals(
            RegistrationOutcome.DuplicateKind(kind = "acme.thing", registeredBy = "plugin-a"),
            conflict,
            "a second claim on one kind must name the FIRST owner, not the challenger",
        )
    }

    @Test
    fun `a refused duplicate does not replace the definition already registered`() {
        // The other half of the row above, and the one that would actually corrupt history:
        // reporting `DuplicateKind` while still overwriting the map entry satisfies the previous
        // assertion perfectly. The observable proof is that the FIRST contributor still owns it.
        val builder = EventRegistry.builder()
        builder.register(definition("acme.thing", emittedBy = "plugin-a"))
        builder.register(definition("acme.thing", emittedBy = "plugin-b"))

        val registry = builder.build()

        assertEquals("plugin-a", registry.definition("acme.thing")?.emittedBy)
        assertEquals(listOf("acme.thing"), registry.registeredKinds(), "the duplicate was admitted after all")
        assertEquals(1, registry.size())
    }

    @Test
    fun `an invalid declaration is refused with every reason found, and registers nothing`() {
        // Stated as a refusal on both halves. A registry that rejected the definition but still
        // grew would pass a test that only inspected the outcome value.
        val outcome = EventRegistry.builder().register(
            definition(kind = "", schemaVersion = 0, emittedBy = ""),
        )

        assertEquals(
            RegistrationOutcome.RejectedDefinition(
                listOf(
                    "kind must not be blank",
                    "schemaVersion must be >= 1, got 0",
                    "emittedBy must not be blank",
                ),
            ),
            outcome,
            "the refusal must carry EVERY reason, not just the first",
        )
        assertEquals(0, EventRegistry.builder().also { it.register(definition(kind = "", schemaVersion = 0, emittedBy = "")) }.build().size())
    }

    @Test
    fun `a kind that is not namespaced is refused`() {
        // The namespacing rule is what makes an accidental collision between two plugins
        // impossible in practice, and its refusal is a VALUE rather than a throw so a contributor
        // can report it. Without a dot, `thing` and `acme.thing` are the same string, and nothing
        // downstream could tell two owners apart.
        val outcome = EventRegistry.builder().register(definition(kind = "thing"))

        assertEquals(
            RegistrationOutcome.RejectedDefinition(listOf("kind 'thing' must be namespaced as <plugin-id>.<EventName>")),
            outcome,
        )
    }

    // --- no silent defaults --------------------------------------------------------------

    @Test
    fun `an unknown kind is absent rather than a placeholder`() {
        // A placeholder definition would let an unregistered payload decode as "something"
        // instead of failing as "unknown". The whole point of a sealed registry is that absence
        // is representable, and a test that only checked `isRegistered` would pass on a registry
        // that answered `definition()` with a fabricated entry.
        val registry = registryWith("acme.thing")

        assertNull(registry.definition("acme.other"), "an unknown kind fabricated a definition")
        assertFalse(registry.isRegistered("acme.other"))
    }

    // --- read-only after registration ------------------------------------------------------

    @Test
    fun `registering into the same builder after build does not change the registry already handed out`() {
        // The composition/observation split: composition lives on `Builder`, so a reader holding
        // the first registry cannot see declarations made after it was frozen. Before the split
        // this class had a public `register` and this was only true by convention. The proof is
        // that the FIRST snapshot is unchanged, not that the second grew.
        val builder = EventRegistry.builder()
        builder.register(definition("acme.first"))
        val first = builder.build()

        builder.register(definition("acme.second"))
        val second = builder.build()

        assertEquals(listOf("acme.first"), first.registeredKinds(), "a frozen registry changed shape under its reader")
        assertEquals(listOf("acme.first", "acme.second"), second.registeredKinds())
    }

    @Test
    fun `registration order is the order of declaration`() {
        // `registeredKinds()` is the only order a reader can observe, and it is what a consumer
        // gets when it lists what this engine speaks. A registry backed by an unordered map would
        // return a different list per run for the same declarations.
        val registry = registryWith("acme.charlie", "acme.alpha", "acme.bravo")

        assertEquals(
            listOf("acme.charlie", "acme.alpha", "acme.bravo"),
            registry.registeredKinds(),
            "declaration order was not preserved; it is alphabetical, or unordered",
        )
    }

    @Test
    fun `the same declarations in the same order produce the same registry`() {
        // Determinism is the property the class KDoc claims ("the registered snapshot is
        // deterministic"). Asserted by identity of the visible facts, not by `equals` on the
        // registry, which would pin down an implementation detail that is free to change.
        val first = registryWith("acme.alpha", "acme.bravo")
        val second = registryWith("acme.alpha", "acme.bravo")

        assertEquals(first.registeredKinds(), second.registeredKinds())
        assertEquals(first.size(), second.size())
        assertSame(first.definition("acme.alpha")?.kind, second.definition("acme.alpha")?.kind)
        assertTrue(first.definition("acme.alpha") !== second.definition("acme.alpha"), "the two builders share one definition instance")
    }
}

/**
 * A codec that only has to satisfy the type; nothing in these laws decodes a payload.
 *
 * Encoding is the identity so that a future assertion about payload content has something stable
 * to assert against, and decoding reports the one case these tests never ask for.
 */
private object StringCodec : EventPayloadCodec<String> {
    override fun encode(payload: String): String = payload

    override fun decode(raw: String, schemaVersion: Int): PayloadDecode<String> =
        PayloadDecode.Decoded(raw)
}
