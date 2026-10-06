package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure

/**
 * S5.4 — the refusal declared by the page must BE the store's refusal.
 *
 * ## What this exists to make impossible
 *
 * The mistake is specific and it is cheap. Someone adds
 * `val refusals: List<EventRecordRead.Undecodable> = emptyList()` to [EventPage], it compiles, the
 * pipeline goes green, and they stop. The field is there. The field is **always empty**.
 *
 * It is empty because the reader still calls `EventStore.readSlice`, which is
 * `readRecords(...).requireFullyDecoded()` and throws the refusal away by refusing the whole page.
 * The contract then advertises a capability it does not have, and every consumer that trusts the
 * capability observes nothing at all. Design document D-M4 names this because it is the most likely
 * way to get this unit wrong.
 *
 * A contract that lies about what it carries is worse than one that lacks the field: the missing
 * field is discoverable by reading the type, the lie is not.
 *
 * ## Why reflection, and not a source scan
 *
 * This module compiles against `:pipeline-events`, so the shape can be read from the COMPILED
 * class. The assertion is then about the artifact that actually ships, not about how its source
 * happens to be written today: a comment quoting the right word cannot turn it green, and a rename
 * that keeps the contract will not turn it red.
 *
 * The repository forbids vacuous source-scan laws. This is the positive case of the opposite habit:
 * the law counts no occurrences and can only be satisfied by a property of the right type existing.
 *
 * ## The other half, and why it is not here
 *
 * A field of the right type can still be filled with `emptyList()` forever, so this half alone is
 * NOT the law — it is the half that is checkable structurally. The propagation half is behavioural
 * and lives beside the code that propagates, in `EventHistoryReaderRefusalPropagationTest`, because
 * it needs a sink that refuses.
 *
 * The split is deliberate. It is also why the unit's plan insists the fitness be written FIRST and
 * observed failing: the propagation test ships in the same commit that changes `readAfter`, and
 * without this structural half nobody would notice that the structural half alone is satisfied by
 * an empty list.
 */
@DisplayName("S5.4 — el rechazo declarado por la pagina es el del store, y no un tipo proprio")
class FArchS54PageRefusalIsTheStoreRefusalTest {

    private val refusalProperty = EventPage::class.memberProperties.firstOrNull { it.name == "refusals" }

    @Test
    fun `EventPage declara el rechazo con el tipo que produce la autoridad`() {
        assertNotNull(
            refusalProperty,
            "EventPage no declara ningun campo 'refusals'. Sin el, quien pide una pagina no tiene " +
                "forma de declarar donde apunta un rechazo, y el store produce uno por cada fila que " +
                "no decodifica: lo que se pierde no es un detalle de la pagina, es la existencia de la fila.",
        )

        val elementType = refusalProperty!!.returnType.arguments.firstOrNull()?.type?.jvmErasure

        assertEquals(
            EventRecordRead.Undecodable::class,
            elementType,
            "el campo 'refusals' de EventPage debe ser List<EventRecordRead.Undecodable>, el mismo " +
                "tipo que EventRecordSlice.refusals produce. Un tipo propio, aunque se llame igual, " +
                "seria una segunda autoridad de rechazo: el store nombraria uno y la pagina otro, y " +
                "nadie podria probar que se corresponden.",
        )
    }

    @Test
    fun `el campo tiene default para no romper a quien hoy construye la pagina`() {
        assertNotNull(
            refusalProperty,
            "sin el campo 'refusals' esta ley no tiene nada que comprobar",
        )

        val withDefault = EventPage::class.constructors.firstOrNull { ctor ->
            ctor.parameters.size == 4 && ctor.parameters.last().isOptional
        }

        assertNotNull(
            withDefault,
            "el campo 'refusals' debe tener default. Sin el, todo consumidor que hoy construye una " +
                "EventPage de tres campos deja de compilar, y una ruptura de fuente evitable se " +
                "convierte en una ruptura de ABI que habria que registrar.",
        )
    }

    @Test
    fun `la pagina gana exactamente un campo y ningun cursor propio`() {
        val names = EventPage::class.memberProperties.map { it.name }.sorted()

        assertEquals(
            listOf("envelopes", "hasMore", "nextCursor", "refusals"),
            names,
            "EventPage debe conservar envelopes, nextCursor y hasMore y ganar exactamente un campo. " +
                "Un cursor propio, una secuencia propia o un contador propio aqui serian la segunda " +
                "autoridad que P3-E y E4c acaban de eliminar, y E4c ya documento como se habia roto " +
                "una pagina antes de arreglarlo.",
        )
    }

    @Test
    fun `una pagina sin rechazos reporta ausencia y no un null`() {
        assertNotNull(
            refusalProperty,
            "sin el campo 'refusals' esta ley no tiene nada que comprobar",
        )

        val page = EventPage(
            envelopes = emptyList(),
            nextCursor = EventCursor("run-x", 0L),
            hasMore = false,
        )

        @Suppress("UNCHECKED_CAST")
        val value = refusalProperty!!.get(page) as List<EventRecordRead.Undecodable>

        assertTrue(
            value.isEmpty(),
            "una pagina sin rechazos debe reportar una lista vacia, no un null que obligue a cada " +
                "consumidor a decidir que hacer con la ausencia. Aqui la ausencia es un hecho " +
                "legitimo: la mayoria de las paginas no tienen ninguna fila ilegible.",
        )
    }
}