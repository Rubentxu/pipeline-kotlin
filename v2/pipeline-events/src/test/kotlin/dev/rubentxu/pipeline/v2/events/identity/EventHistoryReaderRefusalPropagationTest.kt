package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.UndecodableEventRecordException
import dev.rubentxu.pipeline.v2.events.UndecodableReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * S5.4 — the OTHER half of the law: the refusal must actually arrive.
 *
 * ## Why this test exists, and why it is not a fitness test
 *
 * `FArchS54PageRefusalIsTheStoreRefusalTest` pins the SHAPE: that [EventPage] declares a field of
 * the store's refusal type. That half is checkable by reflection on the compiled artifact, so it
 * lives in the architecture module where it can see the class without touching it.
 *
 * This half is behaviour, and behaviour needs a subject. A field of the right type can be filled
 * with `emptyList()` forever and the shape law stays green — that is design D-M4, and it is the most
 * likely way to get this unit wrong. So this test runs [EventHistoryReader.readAfter] against a sink
 * that refuses and watches where the refusal ends up.
 *
 * ## What the double does and does not do
 *
 * [RefusingSink] overrides `readRecords` ONLY. It deliberately does **not** override `readSlice`,
 * and that omission is what makes the last test honest: `EventStore.readSlice` keeps its real
 * default, `readRecords(...).requireFullyDecoded()`, so the strict mode exercised here is the
 * production one rather than a stub that throws on purpose. An earlier draft overrode `readSlice`
 * to throw and then asserted that strict mode throws, which was the double agreeing with itself.
 *
 * The double names no SQLite, no filesystem and no connection, so the test says only what it means.
 * `SqliteEventStore` cannot be used here at all: `pipeline-events` does not depend on
 * `pipeline-events-store`, and by design a published source that names `events.durable` is a
 * compile error. The 40/41/42 case against the real store lives in the store's own module, where
 * this double is replaced by the real thing.
 */
@DisplayName("S5.4 — el rechazo llega a la pagina, y la pagina no lo convierte en evento")
class EventHistoryReaderRefusalPropagationTest {

    private val runId = "run-refusal"
    private val run: ResourceRef = ResourceRefs.run(runId)

    private class RefusingSink(private val records: List<EventRecordRead>) : EventSink {
        override fun append(event: DomainEvent) = Unit

        override fun eventsFor(runId: String): Sequence<DomainEvent> =
            records.mapNotNull { (it as? EventRecordRead.Decoded)?.event }.asSequence()

        override fun readRecords(runId: String, after: EventCursor?, limit: Int): EventRecordSlice {
            val afterSequence = after?.lastSequence ?: 0L
            val page = records.filter { it.sequence > afterSequence }
            return EventRecordSlice(
                records = page,
                nextCursor = EventCursor(runId, page.lastOrNull()?.sequence ?: afterSequence),
                hasMore = false,
            )
        }
    }

    private fun refusal(sequence: Long, kind: String?, reason: UndecodableReason) =
        EventRecordRead.Undecodable(
            sequence = sequence,
            kind = kind,
            eventId = "evt-$sequence",
            reason = reason,
        )

    private val fortyOneMalformed =
        refusal(41L, "some.Kind", UndecodableReason.MalformedPayload("no json here"))
    private val fortyOneUnknown =
        refusal(41L, "plugin.from.the.future", UndecodableReason.UnknownKind("plugin.from.the.future"))

    private fun decoded(sequence: Long) =
        EventRecordRead.Decoded(
            RunStarted(
                eventId = "evt-$sequence",
                runId = runId,
                sequence = sequence,
                occurredAt = Instant.parse("2026-10-06T12:00:00Z"),
                scriptPath = "/tmp/run-$sequence.pipeline.kts",
            ),
        )

    @Test
    fun `un rechazo alcanza la pagina en vez de tirar la pagina entera`() {
        val sink = RefusingSink(listOf(decoded(40L), fortyOneMalformed, decoded(42L)))

        val page = EventHistoryReader(sink).readAfter(run, null, limit = 10)

        assertEquals(
            listOf(41L),
            page.refusals.map { it.sequence },
            "la fila 41 existe y debe llegar a la pagina nombrada por su secuencia. Si no llega, el " +
                "consumidor solo puede afirmar que no habia mas, que es un falsehood.",
        )
    }

    @Test
    fun `el consumidor distingue payload malformado de kind desconocido`() {
        val malformed = EventHistoryReader(RefusingSink(listOf(fortyOneMalformed)))
            .readAfter(run, null, limit = 10)

        assertEquals(
            UndecodableReason.MalformedPayload("no json here"),
            malformed.refusals.single().reason,
            "un payload ilegible y un kind desconocido son hechos distintos y el consumidor puede " +
                "hacer algo con cada uno: uno es una fila corrupta, el otro es una version futura.",
        )

        val unknown = EventHistoryReader(RefusingSink(listOf(fortyOneUnknown)))
            .readAfter(run, null, limit = 10)

        assertEquals(
            UndecodableReason.UnknownKind("plugin.from.the.future"),
            unknown.refusals.single().reason,
            "un kind que este runtime no conoce no es una fila corrupta. Colapsar los dos casos " +
                "haría que un consumidor tirase la pagina entera por algo que puede solo saltarse.",
        )
    }

    @Test
    fun `el rechazo nunca se proyecta a envelope`() {
        val sink = RefusingSink(listOf(decoded(40L), fortyOneMalformed, decoded(42L)))

        val page = EventHistoryReader(sink).readAfter(run, null, limit = 10)

        assertEquals(
            listOf(40L, 42L),
            page.envelopes.map { it.sequence },
            "solo la 40 y la 42 son envelopes. Proyectar la 41 seria inventar un registro que el " +
                "store no pudo interpretar, que es la forma UnknownDomainEvent-como-valido.",
        )
        assertEquals(
            3,
            page.envelopes.size + page.refusals.size,
            "la pagina debe contabilizar las TRES filas: dos envelope y un rechazo. Una pagina que " +
                "solo lleva 2 de 3 ha hecho desaparecer la tercera.",
        )
    }

    @Test
    fun `el cursor avanza por filas y no por envelopes proyectados`() {
        val sink = RefusingSink(listOf(decoded(40L), fortyOneMalformed, decoded(42L)))

        val page = EventHistoryReader(sink).readAfter(run, null, limit = 10)

        assertEquals(
            42L,
            page.nextCursor?.lastSequence,
            "el cursor debe situarse despues de la 42, porque la 41 es una fila y ocupa su sitio. " +
                "Un cursor en 40 devolveria la misma pagina para siempre.",
        )
    }

    /**
     * The same law, on the page that actually breaks it.
     *
     * The case above cannot tell the two cursors apart: when the LAST row of a page is decodable,
     * `nextCursor.lastSequence` and `decoded.last().sequence` are the same number, so a reader that
     * computed the cursor from projections passes for the right-looking reason. That is what the
     * D-M3 mutation found, and it is why this case exists.
     *
     * Here the page ENDS on the refusal, which is the only shape where "advance by row" and "advance
     * by projection" disagree. Computing the cursor from decoded rows leaves it at 40, so the reader
     * asks again from 40, gets the 41 refusal again, and loops forever on a page it can never finish.
     */
    @Test
    fun `el cursor no se queda atras cuando la pagina termina en un rechazo`() {
        val sink = RefusingSink(listOf(decoded(40L), fortyOneMalformed))

        val page = EventHistoryReader(sink).readAfter(run, null, limit = 10)

        assertEquals(
            41L,
            page.nextCursor?.lastSequence,
            "la pagina termina en la 41, que es una fila aunque no sea un envelope. El cursor debe " +
                "quedarse en 41 y no en 40: en 40 el consumidor releeria el mismo rechazo para " +
                "siempre sin avanzar nunca.",
        )
    }

    @Test
    fun `el modo estricto sigue lanzando, y no por culpa de un doble`() {
        val sink = RefusingSink(listOf(decoded(40L), fortyOneMalformed, decoded(42L)))

        assertThrows(
            UndecodableEventRecordException::class.java,
        ) {
            // The INHERITED default: readRecords(...).requireFullyDecoded(). Nothing in this test
            // file overrides it, so a green result here means the production strict mode still
            // refuses, which is R5.
            sink.readSlice(runId, null, 10)
        }

        val page = EventHistoryReader(sink).readAfter(run, null, limit = 10)
        assertTrue(
            page.refusals.isNotEmpty(),
            "el modo observabilidad no puede depender de que el estricto lance: son dos variantes " +
                "del mismo puerto y las dos tienen que funcionar.",
        )
    }
}