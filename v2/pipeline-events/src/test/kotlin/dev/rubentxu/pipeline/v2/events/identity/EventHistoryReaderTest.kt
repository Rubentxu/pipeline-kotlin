package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Lectura de historia y de cola sobre cualquier [dev.rubentxu.pipeline.v2.events.EventSink].
 *
 * El doble de test es un `EventSink` propio de este modulo, no el
 * `InMemoryEventStore` de `:pipeline-events-store`: depender de el aqui
 * invertiria la frontera entre el contrato publicado y su adaptador.
 *
 * El store de test entrega secuencias explicitas, que es lo que hace la ley del
 * store como autoridad: el lector proyecta `sequence`, no la inventa.
 */
class EventHistoryReaderTest {

    private companion object {
        /** Digest con la forma que exige `Digest`; la proyeccion no lo valida. */
        private const val DIGEST_DE_PRUEBA = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }

    private val runId = "run-historia"
    private val run = ResourceRefs.run(runId)
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    /** Ocho eventos de una ejecucion, con secuencia 1..8 declarada por el store. */
    private fun historia(): List<DomainEvent> = listOf(
        StepStarted(id(1), runId, 1L, at, 0, 0, "compila", "core.sh"),
        StageStarted(id(2), runId, 2L, at, 0, "build"),
        StepStarted(id(3), runId, 3L, at, 0, 1, "prueba", "core.sh"),
        EchoOutputCaptured(id(4), runId, 4L, at, 1, "hola"),
        StepFinished(id(5), runId, 5L, at, 0, 1, "prueba", "core.sh"),
        StageStarted(id(6), runId, 6L, at, 1, "publica"),
        RunStarted(id(7), runId, 7L, at, "/ws/x.pipeline.kts"),
        StepFinished(id(8), runId, 8L, at, 0, 0, "compila", "core.sh"),
    )

    private fun id(n: Int) = "evt-$n"

    private fun lector(vararg eventos: DomainEvent): EventHistoryReader {
        val sink = RecordingEventSink()
        eventos.forEach(sink::append)
        return EventHistoryReader(sink)
    }

    private fun EventHistoryReader.kinds() = history(run).map { it.kind }.toList()

    private fun EventHistoryReader.sequences() = history(run).map { it.sequence }.toList()

    @Test
    fun `All devuelve la historia completa en orden de secuencia`() {
        val kinds = lector(*historia().toTypedArray()).kinds()

        assertEquals(8, kinds.size)
        assertEquals(
            listOf(
                "StepStarted", "StageStarted", "StepStarted", "EchoOutputCaptured",
                "StepFinished", "StageStarted", "RunStarted", "StepFinished",
            ),
            kinds,
        )
    }

    @Test
    fun `ByKind devuelve solo losCoincidentes y nada cuando no hay ninguno`() {
        val reader = lector(*historia().toTypedArray())

        assertEquals(listOf(1L, 3L), reader.history(run, EventQuery.ByKind("StepStarted")).map { it.sequence }.toList())
        assertEquals(listOf(5L, 8L), reader.history(run, EventQuery.ByKind("StepFinished")).map { it.sequence }.toList())
        assertEquals(emptyList<Long>(), reader.history(run, EventQuery.ByKind("StageSkipped")).map { it.sequence }.toList())
    }

    @Test
    fun `BySource compara el texto canonico de la referencia fuente`() {
        val reader = lector(*historia().toTypedArray())

        assertEquals(8, reader.history(run, EventQuery.BySource(run)).count())
        assertEquals(
            emptyList<PipelineEventEnvelope>(),
            reader.history(run, EventQuery.BySource(ResourceRefs.run("otra-run"))).toList(),
        )
    }

    @Test
    fun `BySubject filtra por el sujeto proyectado y no por el indice del paso`() {
        val reader = lector(*historia().toTypedArray())

        // Los dos StepStarted comparten stageIndex/stepIndex 0/0, asi que comparten sujeto.
        assertEquals(listOf(1L, 8L), reader.history(run, EventQuery.BySubject(ResourceRefs.step(runId, 0, 0))).map { it.sequence }.toList())
        assertEquals(listOf(3L, 5L), reader.history(run, EventQuery.BySubject(ResourceRefs.step(runId, 0, 1))).map { it.sequence }.toList())
        assertEquals(listOf(2L), reader.history(run, EventQuery.BySubject(ResourceRefs.stage(runId, 0))).map { it.sequence }.toList())
        assertEquals(listOf(6L), reader.history(run, EventQuery.BySubject(ResourceRefs.stage(runId, 1))).map { it.sequence }.toList())
        assertEquals(
            emptyList<PipelineEventEnvelope>(),
            reader.history(run, EventQuery.BySubject(ResourceRefs.step(runId, 9, 9))).toList(),
        )
    }

    @Test
    fun `BySequenceRange incluye los dos extremos`() {
        val reader = lector(*historia().toTypedArray())

        assertEquals(listOf(3L, 4L, 5L), reader.history(run, EventQuery.BySequenceRange(3L, 5L)).map { it.sequence }.toList())
        assertEquals(listOf(8L), reader.history(run, EventQuery.BySequenceRange(8L, 40L)).map { it.sequence }.toList())
        assertEquals(emptyList<Long>(), reader.history(run, EventQuery.BySequenceRange(100L, 200L)).map { it.sequence }.toList())
    }

    @Test
    fun `la historia de una ejecucion no mezcla eventos de otra`() {
        val otro = StepStarted("evt-9", "otra-run", 1L, at, 0, 0, "compila", "core.sh")
        val reader = lector(*(historia() + otro).toTypedArray())

        assertEquals(8, reader.history(run).count())
        assertEquals(1, reader.history(ResourceRefs.run("otra-run")).count())
    }

    @Test
    fun `readAfter con limite uno recorre todas las paginas sin huecos ni repeticiones`() {
        val reader = lector(*historia().toTypedArray())
        val recogidas = mutableListOf<Long>()
        val estadosHasMore = mutableListOf<Boolean>()
        var paginas = 0
        var pagina = reader.readAfter(run, null, 1)

        while (true) {
            paginas++
            recogidas.addAll(pagina.envelopes.map { it.sequence })
            estadosHasMore.add(pagina.hasMore)
            if (!pagina.hasMore) break
            pagina = reader.readAfter(run, pagina.nextCursor, 1)
        }

        assertEquals(8, paginas, "ocho eventos con limite uno son ocho paginas")
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), recogidas, "sin huecos ni repeticiones")
        assertEquals(List(7) { true } + listOf(false), estadosHasMore, "solo la ultima pagina cierra")
    }

    @Test
    fun `readAfter sin cursor empieza por el principio y la pagina nunca excede el limite`() {
        val reader = lector(*historia().toTypedArray())

        val pagina = reader.readAfter(run, null, 3)

        assertEquals(listOf(1L, 2L, 3L), pagina.envelopes.map { it.sequence })
        assertTrue(pagina.hasMore)
        assertEquals(EventCursor(runId, 3L), pagina.nextCursor)
    }

    @Test
    fun `readAfter con un cursor mas alla del final devuelve pagina vacia y cerrada`() {
        val reader = lector(*historia().toTypedArray())
        val cursor = EventCursor(runId, 8L)

        val pagina = reader.readAfter(run, cursor, 5)

        assertEquals(emptyList<PipelineEventEnvelope>(), pagina.envelopes)
        assertFalse(pagina.hasMore)
        assertEquals(cursor, pagina.nextCursor, "sin eventos novos, el cursor sigue siendo el de entrada")
    }

    @Test
    fun `readAfter rechaza un limite no positivo`() {
        val reader = lector(*historia().toTypedArray())

        listOf(0, -1, Int.MIN_VALUE).forEach { limite ->
            val fallo = assertThrows(IllegalArgumentException::class.java) {
                reader.readAfter(run, null, limite)
            }
            assertTrue(
                fallo.message.orEmpty().contains("limit must be positive"),
                "el fallo debe explicar el limite, no fallar en silencio: ${fallo.message}",
            )
        }
    }

    @Test
    fun `con costura de proveedor los eventos de Step llevan procedencia y sin ella no`() {
        val metadata = metadataDePrueba()
        val sink = RecordingEventSink()
        listaDePrueba().forEach(sink::append)
        val conCostura = EventHistoryReader(sink) { clave: PluginStepId ->
            if (clave.value == "core.sh") metadata else null
        }
        val sinCostura = EventHistoryReader(sink)

        val proyectados = conCostura.history(run).toList()
        val conProveniencia = proyectados.associate { it.eventRef.id.value to it.provenance }
        val publicada = requireNotNull(
            proyectados.single { it.eventRef.id.value == "p1" }.provenance,
        ) {
            "un Step registrado con metadata debe proyectar procedencia en su StepStarted"
        }
        assertEquals("pipeline-kotlin", publicada.pluginPublisher)
        assertEquals("pipeline.scm-git", publicada.pluginNamespace)
        assertEquals("scm-git", publicada.pluginIdentity)
        assertEquals("0.36.0", publicada.releaseVersion)
        assertEquals(DIGEST_DE_PRUEBA, publicada.releaseDigest)
        assertEquals(setOf("NETWORK", "SCM"), publicada.families)

        assertNull(
            conProveniencia["p3"],
            "un evento que no emite ningun Step no puede llevar procedencia",
        )
        assertEquals(
            emptyList<String>(),
            sinCostura.history(run).toList().mapNotNull { it.provenance?.pluginPublisher },
            "sin costura de proveedor la compatibilidad C10 exige procedencia nula",
        )
    }

    @Test
    fun `una costura de proveedor que no resuelve la clave deja la procedencia nula`() {
        val sink = RecordingEventSink()
        listaDePrueba().forEach(sink::append)
        val lector = EventHistoryReader(sink) { null }

        assertEquals(
            emptyList<String>(),
            lector.history(run).toList().mapNotNull { it.provenance?.pluginPublisher },
            "una clave sin metadata registrada no puede proyectar procedencia",
        )
    }

    // ------------------------------------------------------------------

    private fun listaDePrueba(): List<DomainEvent> = listOf(
        StepStarted("p1", runId, 1L, at, 0, 0, "compila", "core.sh"),
        StepStarted("p2", runId, 2L, at, 0, 1, "desconocido", "otro.paso"),
        StageStarted("p3", runId, 3L, at, 0, "build"),
    )

    private fun metadataDePrueba(): StepProviderMetadata = StepProviderMetadata.create(
        plugin = ResourceRefs.plugin("pipeline.scm-git", "scm-git"),
        release = PluginReleaseRef(
            plugin = ResourceRefs.plugin("pipeline.scm-git", "scm-git"),
            version = SemVer(0, 36, 0),
            digest = Digest(DIGEST_DE_PRUEBA),
        ),
        publisher = "pipeline-kotlin",
        families = setOf(PluginFamily.SCM, PluginFamily.NETWORK),
        delivery = Delivery.OFFICIAL_PLUGIN,
        trust = TrustMetadata.Unverified,
    )
}
