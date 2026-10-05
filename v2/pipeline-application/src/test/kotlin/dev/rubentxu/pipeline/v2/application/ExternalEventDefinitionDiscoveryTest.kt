package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.registry.EventDefinition
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionCreation
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionContributor
import dev.rubentxu.pipeline.v2.events.registry.EventPayloadCodec
import dev.rubentxu.pipeline.v2.events.registry.PayloadDecode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P3-B / S6.4 — the composition laws for plugin-contributed event kinds.
 *
 * The seam under test is [ExternalEventDefinitionDiscovery.compose], which is the ONE place where
 * a classpath becomes an open event plane. Everything it refuses, it refuses by ABORTING, and that
 * choice is what these tests exist to defend: a registry that admitted three of a plugin's four
 * event kinds is a runtime where a working feature silently drops one of its own observations, and
 * the only symptom is a missing line in somebody else's cursor.
 */
class ExternalEventDefinitionDiscoveryTest {

    private fun validCreation(
        kind: String = "acme.validated",
        schemaVersion: Int = 1,
        emittedBy: String = "acme-plugin",
    ): EventDefinitionCreation<String> = EventDefinition.create(
        kind = kind,
        schemaVersion = schemaVersion,
        payloadClass = String::class.java,
        emittedBy = emittedBy,
        codec = stringCodec,
    )

    private class StubContributor(
        override val id: String,
        private val declarations: () -> Iterable<EventDefinitionCreation<*>>,
    ) : EventDefinitionContributor {
        override fun definitions(): Iterable<EventDefinitionCreation<*>> = declarations()
    }

    @Test
    fun `a well-formed contributor is admitted and its kinds become the open authority`() {
        val registry = ExternalEventDefinitionDiscovery.compose(
            listOf(StubContributor("acme") { listOf(validCreation()) }),
        )

        assertEquals(1, registry.size())
        assertTrue(registry.isRegistered("acme.validated"))
        assertEquals("acme-plugin", registry.definition("acme.validated")?.emittedBy)
    }

    /**
     * Two plugins, one kind. The second one aborts the WHOLE composition rather than losing a
     * registration race, because first-wins would make the outcome depend on classpath order and
     * last-wins would silently re-point every payload already on disk.
     */
    @Test
    fun `a colliding kind aborts composition naming BOTH owners`() {
        val error = assertThrows(IllegalStateException::class.java) {
            ExternalEventDefinitionDiscovery.compose(
                listOf(
                    StubContributor("acme") { listOf(validCreation(emittedBy = "acme-plugin")) },
                    StubContributor("other") { listOf(validCreation(emittedBy = "other-plugin")) },
                ),
            )
        }
        assertTrue(
            error.message!!.contains("acme.validated") && error.message!!.contains("acme-plugin"),
            "the refusal must name the kind and the owner already holding it: ${error.message}",
        )
    }

    @Test
    fun `a contributor that throws while declaring aborts composition`() {
        val error = assertThrows(IllegalStateException::class.java) {
            ExternalEventDefinitionDiscovery.compose(
                listOf(
                    StubContributor("broken") { error("jar is not loadable") },
                ),
            )
        }
        assertTrue(
            error.message!!.contains("broken"),
            "a broken contributor must be named, not swallowed: ${error.message}",
        )
    }

    @Test
    fun `an invalid declaration aborts composition with its reasons intact`() {
        val error = assertThrows(IllegalStateException::class.java) {
            ExternalEventDefinitionDiscovery.compose(
                listOf(StubContributor("acme") { listOf(validCreation(schemaVersion = 0)) }),
            )
        }
        assertTrue(
            error.message!!.contains("acme"),
            "the contributor that declared the invalid definition must be named: ${error.message}",
        )
    }

    /**
     * A contributor declaring NOTHING is not a broken one. It composes to an empty registry, which
     * answers every kind with `UnregisteredKind` — the same typed refusal as "the plugin is
     * absent", so the caller does not have to distinguish the two.
     */
    @Test
    fun `no contributors compose to an empty authority that refuses every kind`() {
        val registry = ExternalEventDefinitionDiscovery.compose(emptyList())

        assertEquals(0, registry.size())
        assertEquals(null, registry.definition("acme.validated"), "nothing is fabricated")
        assertTrue(!registry.isRegistered("acme.validated"))
    }

    private companion object {
        /**
         * A typed codec, never raw JSON as the plugin-facing semantic API — and it refuses an
         * unknown version rather than guessing, which is what makes a versioned read possible.
         */
        val stringCodec = object : EventPayloadCodec<String> {
            override fun encode(payload: String): String = "s:$payload"
            override fun decode(raw: String, schemaVersion: Int): PayloadDecode<String> =
                when {
                    schemaVersion != 1 -> PayloadDecode.UnknownSchemaVersion(schemaVersion)
                    raw.startsWith("s:") -> PayloadDecode.Decoded(raw.removePrefix("s:"))
                    else -> PayloadDecode.Malformed("payload does not conform to schema v1: $raw")
                }
        }
    }
}
