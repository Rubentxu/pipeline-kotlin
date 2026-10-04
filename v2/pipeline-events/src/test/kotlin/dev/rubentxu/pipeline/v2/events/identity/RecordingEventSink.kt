package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberFunctions

/**
 * Doble de test en memoria para [EventSink].
 *
 * Vive en las fuentes de test de este modulo a proposito: el adaptador real
 * (`InMemoryEventStore`) pertenece a `:pipeline-events-store`, y depender de el
 * desde aqui invertiria la frontera que BLOCK 2 acaba de fijar. El modulo
 * publicado no puede alcanzar al modulo no publicado, y un test que si lo hiciera
 * estaria probando una combinacion que ningun consumidor puede construir.
 *
 * Reproduce las dos semanticas que el Productor necesita de un store:
 * - [append] respeta la secuencia que ya trae el evento, que es lo que necesita
 *   un lector de historia ya escrito (el store es la autoridad de secuencia y el
 *   test entrega secuencias explicitas).
 * - [appendAssigned] decide la secuencia y la devuelve en el propio evento, que
 *   es el reconocimiento explicito de escritura que consume
 *   `EnvelopeProjectingEventSink`. Un evento con `sequence == 0` recibe la
 *   siguiente secuencia del contador de su run; uno que ya trae secuencia la
 *   respeta, igual que el adaptador real.
 *
 * [eventsFor] devuelve una copia materializada de la lista: el lector consume el
 * flujo una sola vez y un doble que se aliaseara permitiria que una segunda
 * iteracion dependiera del orden de la primera.
 */
internal class RecordingEventSink : EventSink {

    private val almacen = LinkedHashMap<String, MutableList<DomainEvent>>()
    private val contadores = HashMap<String, Long>()

    override fun append(event: DomainEvent) {
        agregar(event)
    }

    override fun appendAssigned(event: DomainEvent): DomainEvent {
        val siguiente = (contadores[event.runId] ?: 0L) + 1L
        contadores[event.runId] = siguiente
        val asignado = if (event.sequence == 0L) cambiarSecuencia(event, siguiente) else event
        agregar(asignado)
        return asignado
    }

    override fun eventsFor(runId: String): Sequence<DomainEvent> =
        almacen[runId]?.toList()?.asSequence() ?: emptySequence()

    private fun agregar(event: DomainEvent) {
        almacen.getOrPut(event.runId) { mutableListOf() }.add(event)
    }
}

/**
 * Devuelve el evento con `sequence` sustituido y todos los demas campos intactos.
 *
 * Es la operacion que los dos adaptadores de store ejecutan a mano, una rama por
 * tipo (`is StepStarted -> event.copy(sequence = n)`, y asi las 70). Aqui se hace
 * por reflexion para que un unico helper pueda aplicarla a toda la jerarquia y
 * para que el test del vocabulario pueda afirmar la misma propiedad sobre cada
 * subtipo sin 70 copias a mano.
 *
 * Solo cambia `sequence`: los demas parametros los resuelve el metodo `copy`
 * generado por el compilador con el valor que ya tiene la instancia.
 */
internal fun cambiarSecuencia(event: DomainEvent, secuencia: Long): DomainEvent {
    val copia = event::class.memberFunctions.single { it.name == "copy" }
    val instancia = copia.parameters.single { it.kind == KParameter.Kind.INSTANCE }
    val parametro = copia.parameters.single { it.name == "sequence" }
    return copia.callBy(mapOf(instancia to event, parametro to secuencia)) as DomainEvent
}
