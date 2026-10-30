package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
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
 * tercera numeración. Las filas del verbo de consola vigilan su invariante de POSICIÓN ("que
 * nombre el último marco entregado y no la cuenta"); aquí se vigila la otra mitad, que es la
 * SEPARACIÓN: que un ordinal de marco no se pueda leer como cursor de bytes, ni al revés, y que
 * el camino real del verbo lo diga con un rechazo tipado en lugar de con un desfase plausible.
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

    @Test
    @DisplayName("el ordinal de marco y el cursor de bytes no se cruzan en ninguna dirección")
    fun theFrameOrdinalTokenIsNotAByteCursorAndTheByteCursorIsNotAFrameOrdinal() {
        // Los dos tokens los produce el productor real: el ordinal lo imprime el verbo de
        // consola y el cursor lo emite el plano de salida. Un token escrito a mano podría tener
        // una forma que ningún productor genera, y la fila pasaría por un motivo equivocado.
        val frameToken = ConsoleFramePlane.encodeFrameOrdinal(4L)
        val byteToken = OutputCursor(OutputStreamId("run-1/op-1/stdout"), 42L).encode()

        assertNotEquals(
            byteToken.substringBefore(':'),
            frameToken.substringBefore(':'),
            "los dos planos de posición tienen prefijos distintos: si compartieran prefijo, un " +
                "token de un plano pegado en el comando del otro se leería como una posición " +
                "válida. Token de bytes: $byteToken; token de marco: $frameToken",
        )
        assertNull(
            OutputCursor.decode(frameToken),
            "una posición de marco no es una posición de bytes: si se admitiera, el lector " +
                "avanzaría un desfase de bytes a partir de un número de marco. Token emitido: " +
                "$frameToken",
        )
        assertNull(
            ConsoleFramePlane.decodeFrameOrdinal(byteToken),
            "una posición de bytes no es una posición de marco: si se admitiera, el verbo " +
                "reanudaría por marcos desde un desfase de bytes. Token emitido: $byteToken",
        )

        // Y el camino real del verbo, no sólo los decodificadores: un ordinal de marco pegado
        // en `--after-cursor` tiene que ser un rechazo tipado que nombre el token, porque un
        // rechazo mudo deja al operador pegando el mismo token otra vez.
        val result = CliConsoleParser.parse(
            arrayOf("--control-dir", "/control", "run-1", "op-1", "--after-cursor", frameToken),
        )
        val rejected = assertInstanceOf(
            ConsoleCliParseResult.Rejected::class.java,
            result,
            "el verbo de consola no puede admitir una posición de marco en el camino de bytes: " +
                "empezaría a leer bytes desde un número de marco. Resultado emitido: $result",
        )
        assertEquals(
            ConsoleCliRefusal.NotACursorToken(frameToken),
            rejected.refusal,
            "el rechazo tiene que nombrar el token que no pudo leer, o el operador no sabe " +
                "qué pegar",
        )
    }
}
