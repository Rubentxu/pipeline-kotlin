package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Dos planos de posición que se encuentran en el cable, y la promesa de que no se cruzan.
 *
 * ## La garantía
 * Una posición que viaja como token vuelve al programa que la emitió, y quien la recibe tiene
 * que poder distinguir de qué plano venía ANTES de mover un byte o un hecho. El plano de hechos
 * numera con una secuencia que asigna el almacén; el plano de bytes numera con un desfase
 * comprometido dentro de un flujo de salida. Son dos numeraciones distintas, y una lectura que
 * las mezclara avanzaría a una posición plausible y equivocada — que es mucho más difícil de
 * detectar que un rechazo.
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
 * Cada fila de este fichero usa el token que el productor real emite, no uno escrito a mano para
 * la prueba. Un token inventado puede tener una forma que ningún productor produce, y entonces la
 * fila pasa por un motivo equivocado: porque el token es raro, no porque el plano se separa. Por
 * eso la fila del plano de bytes comprueba además que el token tiene la MISMA forma de tres
 * partes que uno de hechos, y que lo único que los separa es el prefijo.
 *
 * ## Lo que este fichero NO cubre, y por qué
 * El plano de replay no tiene forma de cable: su posición es una fila durable direccionada por la
 * ejecución, así que su separación no se puede observar en un token sino en el fichero donde
 * conviven las dos autoridades, y eso lo demuestra otra clase.
 *
 * El plano de marcos —el ordinal de marco que el verbo de consola imprime para reanudar— es una
 * tercera numeración, y su invariante viva no es "que no se confunda" sino "que nombre el último
 * marco entregado y no la cuenta": esa la guardan las filas del verbo de consola, que leen del
 * plano real y reanudan con lo que el verbo imprimió. Aquí no se repite.
 */
class CursorPlaneSeparationTest {

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
    @DisplayName("el verbo de consola rechaza un token de hechos con un rechazo tipado")
    fun theConsoleVerbRefusesAnEventTokenWithATypedRefusal() {
        val eventToken = EventCursor("run-1", 7L).encode()

        val result = CliConsoleParser.parse(
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
}
