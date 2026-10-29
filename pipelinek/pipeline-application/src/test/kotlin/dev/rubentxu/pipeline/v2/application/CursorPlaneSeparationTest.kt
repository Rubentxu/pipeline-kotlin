package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tres planos de posición, y la promesa de que no se cruzan.
 *
 * ## La garantía
 * Cuando un lector vuelve con la posición que le devolvieron antes, esa posición es de UN plano,
 * y quien la recibe tiene que saber de cuál antes de mover un byte o un hecho. El plano
 * de hechos se numera con una secuencia que asigna el almacén; el plano de bytes se numera con un
 * desfase comprometido dentro de un flujo de salida; el plano de marcos se numera con un ordinal;
 * y el plano de replay no se numera con nada, porque su posición es una fila durable direccionada
 * por el identificador de la ejecución.
 *
 * La garantía es que una posición de un plano nunca se admite en otro. No es una cuestión de
 * estilo: el hecho número ciento cuarenta y tres y el byte ciento cuarenta y tres del log son
 * números distintos, y una lectura que mezcla los dos avanza a una posición plausible y
 * equivocada — que es mucho más difícil de detectar que un rechazo.
 *
 * ## Por qué esto NO lo puede observar la capa ejecutable
 * El verbo de consola escribe los bytes en stdout y el motivo del rechazo en stderr, y el
 * lanzador de ejemplos lee el código de salida y, como mucho, una subcadena de stderr. Lo que
 * esa lectura muestra es que el verbo FALLÓ, no de qué plano venía la posición: un token de bytes
 * rechazado y un token de hechos rechazado son exactamente la misma línea y el mismo código. Para
 * distinguir los planos hay que mirar el decodificador y el consumidor, que son donde la mezcla
 * ocurre.
 *
 * ## Los tokens que se cruzan son tokens de verdad
 * Cada fila de este fichero usa el token que el productor real emite, no uno escrito a mano para la
 * prueba. Un token inventado puede tener una forma que ningún productor produce, y entonces la
 * fila pasa por un motivo equivocado: porque el token es raro, no porque el plano se separa. Por
 * eso la fila del plano de bytes comprueba además que el token tiene la MISMA forma de tres
 * partes que uno de hechos, y que lo único que los separa es el prefijo.
 */
class CursorPlaneSeparationTest {

    private val run = ResourceRefs.run("run-1")

    @Test
    @DisplayName("el cursor de hechos rechaza el token de bytes que emite el plano de salida")
    fun theEventCursorRefusesTheRealByteToken() {
        val byteToken = OutputCursor(OutputStreamId("run-1/stdout"), 42L).encode()

        assertEquals(
            3,
            byteToken.split(':').size,
            "el token de bytes tiene la MISMA forma que uno de hechos: tres partes y un número " +
                "al final. Lo único que los separa es el prefijo, así que el prefijo es lo que esta " +
                "fila vigila. Token emitido: $byteToken",
        )
        assertNull(
            EventCursor.decode(byteToken),
            "una posición de bytes no es una posición de hechos: si se admitiera, el lector " +
                "seguiría la historia desde un desfase de bytes como si fuera una secuencia",
        )
    }

    @Test
    @DisplayName("el cursor de hechos rechaza el token de ordinal de marco")
    fun theEventCursorRefusesTheRealFrameOrdinalToken() {
        val frameToken = MainConsoleCli.encodeFrameOrdinal(4L)

        assertNull(
            EventCursor.decode(frameToken),
            "un ordinal de marco no es una secuencia de hechos: son dos planos con dos " +
                "numeraciones distintas",
        )
    }

    @Test
    @DisplayName("el verbo de consola rechaza un token de hechos con un rechazo tipado")
    fun theConsoleVerbRefusesAnEventTokenWithATypedRefusal() {
        val eventToken = EventCursor("run-1", 7L).encode()

        val result = CliParser.parseConsole(
            arrayOf("--control-dir", "/control", "run-1", "op-1", "--after-cursor", eventToken),
        )

        val rejected = assertInstanceOf(
            ConsoleCliParseResult.Rejected::class.java,
            result,
            "el verbo de consola no puede admitir una posición de hechos: empezaría a leer " +
                "bytes desde una secuencia. Resultado emitido: $result",
        )
        assertEquals(
            ConsoleCliRefusal.NotACursorToken(eventToken),
            rejected.refusal,
            "el rechazo tiene que nombrar el token que no pudo leer, o el operador no sabe " +
                "qué pegar",
        )
    }

    @Test
    @DisplayName("el decodificador de ordinales de marco rechaza el token de bytes")
    fun theFrameOrdinalDecoderRefusesTheRealByteToken() {
        val byteToken = OutputCursor(OutputStreamId("run-1/stdout"), 42L).encode()

        assertNull(
            MainConsoleCli.decodeFrameOrdinal(byteToken),
            "una posición de bytes no es un ordinal de marco",
        )
    }

    @Test
    @DisplayName("la posición de replay y la continuación de hechos son ejes que no se mueven juntos")
    fun theReplayPositionAndTheEventContinuationAreIndependentAxes() {
        val clock = FixedClock(Instant.EPOCH)
        val replayStore = InMemoryReplayCursorStore(clock)
        val eventStore = InMemoryEventStore()
        eventStore.append(RunStarted("evt-1", "run-1", 0L, Instant.EPOCH, "p.pipeline.kts"))
        eventStore.append(StageStarted("evt-2", "run-1", 0L, Instant.EPOCH, 0, "stage-0"))

        val read = EventPageDrain.drainTyped(eventStore, run, EventQuery.All, null, limit = 10)

        assertNull(
            replayStore.load("run-1"),
            "leer la historia hasta el final no crea una posición de replay: el plano de replay " +
                "no se deduce de la historia de hechos",
        )
        replayStore.advance("run-1", "op-1", 0)
        val reread = EventPageDrain.drainTyped(eventStore, run, EventQuery.All, null, limit = 10)

        assertEquals(
            read.page.nextCursor?.lastSequence,
            reread.page.nextCursor?.lastSequence,
            "avanzar la posición de replay no mueve la continuación de hechos",
        )
        assertEquals(
            listOf(1L, 2L),
            reread.page.events.map { it.sequence },
            "los hechos del plano de eventos son los mismos antes y después de mover el replay",
        )
        assertEquals(
            "op-1",
            replayStore.load("run-1")?.lastOpId,
            "la posición de replay se nombra con su propia coordenada, que no es una secuencia",
        )
        assertEquals(
            0,
            replayStore.load("run-1")?.stageIndex,
            "la posición de replay se nombra con su propia coordenada, que no es una secuencia",
        )
    }

    /**
     * Un reloj fijo: la posición de replay guarda una marca de tiempo y esta fila no quiere reloj.
     */
    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
