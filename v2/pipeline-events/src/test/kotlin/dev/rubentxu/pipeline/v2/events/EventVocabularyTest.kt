package dev.rubentxu.pipeline.v2.events

import dev.rubentxu.pipeline.v2.events.identity.cambiarSecuencia
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/**
 * Leyes del vocabulario de [DomainEvent]: una instancia por cada subtipo concreto,
 * `kind` como discriminante de cable, semantica de valor y `copy` fiel.
 *
 * El fixture de [vocabulario] es la parte que mas merece este test: 70 subtipos
 * declarados en un solo fichero son 70 constructores que nadie exercise de forma
 * sistematica, y casi todas las lineas de ese fichero son miembros generados por
 * el compilador (`componentN`, `copy`, `equals`, `hashCode`, `toString`) que solo
 * quedan cubiertos si alguien los invoca.
 *
 * La guarda de completitud se deriva de la jerarquia COMPILADA
 * (`DomainEvent::class.sealedSubclasses`), no de una lista de nombres escrita a
 * mano. Un evento nuevo sin entrada en el fixture rompe el build en vez de
 * colarse sin comprobar; una lista mantenida a mano solo detectaria el olvido de
 * quien la escribio, que es justo lo que no se quiere.
 */
class EventVocabularyTest {

    @Test
    fun `el fixture cubre exactamente las subclases declaradas de DomainEvent`() {
        val declaradas = subtiposDeclarados(DomainEvent::class).toSet()
        val enFixture = vocabulario().map { it::class }.toSet()

        assertEquals(
            declaradas,
            enFixture,
            "el fixture debe cubrir cada subtipo declarado de DomainEvent y ninguno mas",
        )
        assertEquals(
            declaradas.size,
            vocabulario().size,
            "el fixture debe traer exactamente una instancia por subtipo",
        )
    }

    @Test
    fun `kind es el nombre simple del tipo y es unico en todo el vocabulario`() {
        val kinds = vocabulario().map { it.kind }

        vocabulario().forEach { event ->
            assertEquals(
                event::class.simpleName,
                event.kind,
                "kind debe ser el nombre simple del tipo (discriminante de cable)",
            )
        }
        assertEquals(
            kinds.size,
            kinds.toSet().size,
            "kind debe ser unico: dos tipos con el mismo kind harian el stream ambiguan para un consumidor externo",
        )
    }

    @Test
    fun `dos instancias construidas por separado son iguales y con el mismo hash`() {
        val uno = vocabulario()
        val otro = vocabulario()

        uno.forEachIndexed { posicion, evento ->
            val gemelo = otro[posicion]
            assertEquals(evento::class, gemelo::class, "el fixture debe ser estable en el orden")
            assertEquals(evento, gemelo, "dos eventos con los mismos valores deben ser iguales")
            assertEquals(evento.hashCode(), gemelo.hashCode(), "igualdad implica mismo hashCode")
            assertTrue(
                evento.toString().contains(checkNotNull(evento::class.simpleName)),
                "toString debe nombrar el tipo: ${evento.toString()}",
            )
        }
    }

    @Test
    fun `copy cambia solo la secuencia y conserva el resto de campos`() {
        // 999999 queda fuera del rango del fixture (n * 10000 + k, con n <= 70),
        // asi que una copia que no cambiara nada seria detectada.
        val nueva = 999_999L

        vocabulario().forEach { original ->
            val copia = cambiarSecuencia(original, nueva)

            assertEquals(original::class, copia::class, "copy debe devolver el mismo tipo")
            assertEquals(nueva, copia.sequence, "copy(sequence = n) debe fijar la secuencia")
            assertEquals(original.kind, copia.kind, "kind es una constante derivada: copy no puede alterarla")
            assertNotEquals(original, copia, "una secuencia distinta produce un valor distinto")

            val antes = propiedades(original)
            val despues = propiedades(copia)
            assertEquals(antes.keys, despues.keys, "copy no puede cambiar el conjunto de propiedades")
            (antes.keys - "sequence").forEach { nombre ->
                assertEquals(
                    antes[nombre],
                    despues[nombre],
                    "copy(sequence = n) no puede cambiar la propiedad $nombre de ${original::class.simpleName}",
                )
            }
        }
    }

    @Test
    fun `el helper reflectivo de copy coincide con la copia escrita a mano`() {
        // Ancla del helper que usa el test anterior: si la reflexion se desviara de
        // lo que hace el compilador, el test de `copy` estaria certificandose a si
        // mismo y no la propiedad real de la clase.
        val original = StepStarted(
            eventId = "evt-8", runId = RUN_ID, sequence = l(8, 0), occurredAt = AT,
            stageIndex = i(8, 1), stepIndex = i(8, 2), stepName = v(8, 3), stepType = v(8, 4),
        )

        assertEquals(original.copy(sequence = 999_999L), cambiarSecuencia(original, 999_999L))
    }

    @Test
    fun `los campos base viajan desde el fixture y son unicos por evento`() {
        val eventos = vocabulario()

        eventos.forEach { evento ->
            assertEquals(RUN_ID, evento.runId, "runId debe venir del fixture")
            assertEquals(AT, evento.occurredAt, "occurredAt debe venir del fixture")
        }

        val ids = eventos.map { it.eventId }
        assertEquals(ids.size, ids.toSet().size, "cada evento necesita un eventId propio")

        val secuencias = eventos.map { it.sequence }
        assertEquals(secuencias.size, secuencias.toSet().size, "cada evento necesita una secuencia propia")

        eventos.forEach { evento ->
            val numero = checkNotNull(evento.eventId.removePrefix("evt-").toIntOrNull())
            assertEquals(
                l(numero, 0),
                evento.sequence,
                "la secuencia del fixture debe ser la derivada del eventId que le asigno",
            )
        }
    }
}

/** Todas las subclases concretas de una jerarquia sellada, en profundidad. */
private fun subtiposDeclarados(tipo: KClass<*>): List<KClass<*>> =
    tipo.sealedSubclasses.flatMap { subclase -> listOf(subclase) + subtiposDeclarados(subclase) }

/** Propiedades declaradas de un evento, por nombre, con su valor actual. */
@Suppress("UNCHECKED_CAST")
private fun propiedades(evento: DomainEvent): Map<String, Any?> =
    evento::class.memberProperties.associate { propiedad ->
        val legible = propiedad as KProperty1<Any?, *>
        propiedad.name to legible.get(evento)
    }
