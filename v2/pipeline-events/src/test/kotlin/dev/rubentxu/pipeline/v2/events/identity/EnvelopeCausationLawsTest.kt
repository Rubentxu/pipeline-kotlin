package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * `causation` y `correlation` tienen que viajar de verdad, no solo existir en el tipo.
 *
 * El defecto que estas filas fijan: `PipelineEventEnvelopeSerializer.Wire` declaraba cuatro campos
 * de texto plano para ellos, el KDoc del envelope anunciaba ambos en la forma de cable, y
 * `serialize` no rellenaba ninguno mientras `deserialize` no leia ninguno. El envelope aparentaba
 * llevar contexto causal y no llevaba ninguno. Un campo que se acepta, se declara y se documenta,
 * y luego se descarta en silencio al codificar, es un parametro semantico muerto: el
 * `Semantic Constitution` lo prohibe, y un consumidor que parseara el JSON no tenia con que
 * reconstruir el orden ni la relacion entre eventos.
 *
 * Estas pruebas vivian fuera del modulo. El unico sitio que ejercitaba el serializador era el
 * consumidor independiente de `examples/fabric-contract-consumer`, que es un build separado y no
 * corre en el gate de este modulo. Un defecto en el contrato publicado puede asi llegar verde
 * mientras su modulo propio no tiene ni una sola prueba del serializador: por eso estan aqui.
 *
 * La forma de cable tambien se fija de forma literal. Un round trip que solo comprobase que
 * `original == decodificado` pasaria igual si los dos campos se renombraran, se anidaran o se
 * movieran de sitio, y un consumidor que ya leia ese JSON dejaria de funcionar sin que ninguna
 * prueba de este modulo lo notara.
 */
class EnvelopeCausationLawsTest {

    private val runId = "01987654-3210-fedc-ba98-76543210fedc"
    private val at: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun envelope(
        sequence: Long = 3L,
        causation: EventRef? = null,
        correlation: EventRef? = null,
    ): PipelineEventEnvelope = PipelineEventEnvelope(
        version = PipelineEventEnvelope.VERSION,
        eventRef = EventRef(ResourceRefs.run(runId), EventId("evt-$sequence")),
        kind = "StepFinished",
        occurredAt = at,
        sequence = sequence,
        subject = ResourceRefs.run(runId),
        causation = causation,
        correlation = correlation,
    )

    private fun ref(id: String): EventRef = EventRef(ResourceRefs.run(runId), EventId(id))

    @Test
    fun `causation y correlation sobreviven a un viaje de ida y vuelta`() {
        // La fila de regresion. Con el serializador anterior este `original == decodificado`
        // fallaba en cuanto los dos campos eran distintos de null.
        val original = envelope(causation = ref("evt-2"), correlation = ref("evt-1"))

        val decoded = EnvelopeCodec.decode(EnvelopeCodec.encode(original))

        assertEquals(original, decoded, "el envelope debe sobrevivir a su propia forma de cable")
        assertEquals(ref("evt-2"), decoded.causation, "causation debe volver con su referencia completa")
        assertEquals(ref("evt-1"), decoded.correlation, "correlation debe volver con su referencia completa")
    }

    @Test
    fun `los dos campos aparecen en el JSON y no solo en el objeto`() {
        // La mitad que un round trip no ve: que los campos esten en el TEXTO. Un envelope puede
        // conservarlos en memoria y perderlos al codificar, que es exactamente lo que pasaba.
        val json = EnvelopeCodec.encode(envelope(causation = ref("evt-2"), correlation = ref("evt-1")))

        assertTrue(json.contains("\"causation\""), "causation no aparece en el JSON: $json")
        assertTrue(json.contains("\"correlation\""), "correlation no aparece en el JSON: $json")
        assertTrue(json.contains("\"evt-2\""), "el id de causation no aparece en el JSON: $json")
        assertTrue(json.contains("\"evt-1\""), "el id de correlation no aparece en el JSON: $json")
    }

    @Test
    fun `la forma de cable de los dos campos queda fijada de forma literal`() {
        // Freeze del shape, no del contenido. Un rename, un anidamiento o un cambio de posicion
        // rompe aqui aunque el round trip siga siendo correcto.
        val json = EnvelopeCodec.encode(envelope(causation = ref("evt-2"), correlation = ref("evt-1")))

        // `ResourceRefs.run(id)` produce los segmentos `pipeline`/`run`/`id`, no solo el
        // identificador: los tres van al cable y por eso la asercion los escribe enteros.
        val source = """{"kind":"RUN","segments":["pipeline","run","$runId"]}"""

        assertEquals(
            """{"version":${PipelineEventEnvelope.VERSION},""" +
                """"eventRefSource":$source,""" +
                """"eventRefId":"evt-3","kind":"StepFinished",""" +
                """"occurredAt":"2026-01-01T00:00:00Z","sequence":3,""" +
                """"subject":$source,""" +
                """"causation":{"source":$source,"id":"evt-2"},""" +
                """"correlation":{"source":$source,"id":"evt-1"},""" +
                """"provenance":null}""",
            json,
            "la forma de cable del envelope cambia: un consumidor que ya lea este JSON dejaria de " +
                "entenderlo, y el fallo no lo veria ninguna prueba de round trip",
        )
    }

    @Test
    fun `un envelope sin contexto causal lo dice de forma explicita y no por omision`() {
        // `null` declarado, no ausente: la ausencia y el null se distinguen al leer el JSON, y un
        // consumidor que diferencie "no se sabe" de "no aplica" no puede hacerlo sobre una clave
        // que a veces esta y a veces no.
        val json = EnvelopeCodec.encode(envelope())
        val decoded = EnvelopeCodec.decode(json)

        assertTrue(json.contains("\"causation\":null"), "causation ausente en vez de null explicito: $json")
        assertTrue(json.contains("\"correlation\":null"), "correlation ausente en vez de null explicito: $json")
        assertNull(decoded.causation)
        assertNull(decoded.correlation)
    }

    @Test
    fun `causation y correlation son independientes entre si`() {
        // Los dos se rellenan a la vez en el camino normal, y por eso un bug que copiara uno en el
        // otro pasaria el round trip del caso completo. Estas filas son las que lo detectan.
        val soloCausation = EnvelopeCodec.decode(EnvelopeCodec.encode(envelope(causation = ref("evt-2"))))
        assertEquals(ref("evt-2"), soloCausation.causation)
        assertNull(soloCausation.correlation, "correlation debe seguir siendo null si no se relleno")

        val soloCorrelation = EnvelopeCodec.decode(EnvelopeCodec.encode(envelope(correlation = ref("evt-9"))))
        assertNull(soloCorrelation.causation, "causation debe seguir siendo null si no se relleno")
        assertEquals(ref("evt-9"), soloCorrelation.correlation)
    }

    @Test
    fun `una referencia de origen distinto sobrevive porque el par source e id es la identidad`() {
        // La identidad de un EventRef es (source, eventId): el mismo id bajo otro origen es otro
        // evento. Un serializador que escribiera solo el id perderia esa distincion, y el fallo se
        // veria al decodificar, no al codificar.
        val otro = EventRef(ResourceRefs.run("otra-ejecucion"), EventId("evt-2"))
        val original = envelope(causation = otro)

        val decoded = EnvelopeCodec.decode(EnvelopeCodec.encode(original))

        assertEquals(otro, decoded.causation, "una referencia de otro origen es OTRO evento, no el mismo")
        assertEquals(ResourceRefs.run("otra-ejecucion").canonicalText(), decoded.causation?.source?.canonicalText())
        assertEquals("evt-2", decoded.causation?.id?.value, "el id se conserva aunque cambie el origen")
    }
}
