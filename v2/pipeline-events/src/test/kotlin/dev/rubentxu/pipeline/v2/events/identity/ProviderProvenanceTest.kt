package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.events.AT
import dev.rubentxu.pipeline.v2.events.RUN_ID
import dev.rubentxu.pipeline.v2.events.StepStarted
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ProviderProvenance] es la proyeccion de auditoria que viaja dentro del sobre:
 * responde "que codigo produjo este evento" sin obligar al consumidor a
 * reconstruir segmentos de texto ni a conocer clases de plugin.
 *
 * Aqui se fija su forma observable:equality de valor, tolerancia a campos
 * ausentes al decodificar una forma V1 antigua, y el hecho de que el sobre
 * completo sobrevive al viaje por JSON. La parte que decide QUE valores lleva
 * ([EnvelopeProjector]) tiene su propio test.
 */
class ProviderProvenanceTest {

    private val procedencia = ProviderProvenance(
        pluginPublisher = "pipeline-kotlin",
        pluginNamespace = "pipeline.scm-git",
        pluginIdentity = "scm-git",
        releaseVersion = "0.36.0",
        releaseDigest = "sha256:" + "a".repeat(64),
        families = setOf("NETWORK", "SCM"),
    )

    @Test
    fun `dos instancias con los mismos valores son iguales`() {
        val gemelo = ProviderProvenance(
            pluginPublisher = "pipeline-kotlin",
            pluginNamespace = "pipeline.scm-git",
            pluginIdentity = "scm-git",
            releaseVersion = "0.36.0",
            releaseDigest = "sha256:" + "a".repeat(64),
            families = setOf("SCM", "NETWORK"),
        )

        assertEquals(procedencia, gemelo, "el orden de entrada de un conjunto no cambia el valor")
        assertEquals(procedencia.hashCode(), gemelo.hashCode())
    }

    @Test
    fun `cambiar el publicador cambia el valor`() {
        val otro = procedencia.copy(pluginPublisher = "otro")

        assertEquals("otro", otro.pluginPublisher)
        assertTrue(procedencia != otro, "la procedencia identifica al publicador, no lo decorativa")
    }

    @Test
    fun `viaja dentro del sobre y sobrevive al viaje por JSON`() {
        val env = PipelineEventEnvelope(
            version = PipelineEventEnvelope.VERSION,
            eventRef = EventRef(source = dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs.run(RUN_ID), id = EventId("e1")),
            kind = "StepStarted",
            occurredAt = AT,
            sequence = 3L,
            subject = dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs.step(RUN_ID, 0, 1),
            provenance = procedencia,
        )

        val ida = EnvelopeCodec.encode(env)
        val vuelta = EnvelopeCodec.decode(ida)

        assertEquals(env, vuelta, "el sobre con procedencia tiene que volver igual")
        assertTrue(ida.contains("\"provenance\""), "la procedencia tiene que estar en la forma de cable")
        assertEquals(ida, EnvelopeCodec.encode(vuelta), "la codificacion es estable")
    }

    @Test
    fun `una forma V1 sin el campo provenance decodifica con procedencia nula`() {
        // El versionado NO se sube por un campo aditivo: un emisor que no conoce
        // `provenance` sigue produciendo una forma V1 valida, y un lector nuevo la
        // acepta como C10 (compatibilidad hacia atras).
        val sinProveniencia = """
            {"version":1,"eventRefSource":{"kind":"RUN","segments":["pipeline","run","$RUN_ID"]},
             "eventRefId":"e1","kind":"StepStarted","occurredAt":"$AT","sequence":3,
             "subject":{"kind":"STEP","segments":["pipeline","run","$RUN_ID","stage","0","step","1"]}}
        """.trimIndent().replace("\n", "")

        val env = EnvelopeCodec.decode(sinProveniencia)

        assertNull(env.provenance, "una forma V1 sin provenance no puede inventar una procedencia")
        assertEquals("StepStarted", env.kind)
    }

    @Test
    fun `el campo families se serializa como una lista ordenada`() {
        val json = Json.encodeToString(ProviderProvenance.serializer(), procedencia)

        assertTrue(json.contains("NETWORK"), "las familias declaradas tienen que estar en la forma de cable")
        assertTrue(
            json.indexOf("NETWORK") < json.indexOf("SCM"),
            "el orden de las familias es determinista: el mismo valor produce el mismo cable",
        )
    }
}
