package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * El dueño que devuelve lo que le confiaron.
 *
 * ## La garantía
 * Un scope que cierra devuelve TODO lo que le confiaron, en orden inverso de
 * adquisición, aunque una devolución falle. La garantía tiene dos mitades que
 * no se pueden separar: **el fallo no impide las otras devoluciones** (un
 * recurso cuyo release explota no autoriza a abandonar los demás) y **el fallo
 * no desaparece** (un release que explota es un recurso que quizá sigue
 * vivo — un lock con dueño escrito en disco, un fichero sin cerrar — y el
 * operador tiene que enterarse; tragárselo convierte una fuga en un silencio).
 *
 * ## Por qué esto no lo puede observar la capa ejecutable
 * La señal vive en la excepción que `close` emite, y el ejecutable mira un
 * código de salida. El consumidor real es Kotlin `use`, que encadena la
 * excepción del cierre como suppressed del outcome del bloque: lanzar aquí no
 * oculta nada, silenciar aquí lo pierde todo. La unidad de la garantía es el
 * scope solo, con liberaciones que son lambdas en memoria.
 */
class ResourceScopeTest {

    /**
     * La fila del defecto: una devolución explota y close vuelve en silencio.
     * La primera mitad de la garantía (el resto se devuelve) ya se cumplía; la
     * segunda (el fallo se señala) es la que falta.
     */
    @Test
    @DisplayName("un cierre que falla se señala sin impedir las otras devoluciones")
    fun aFailingReleaseIsSignalledWithoutBlockingTheRest() {
        val scope = ResourceScope()
        val givenBack = mutableListOf<String>()
        scope.own { givenBack += "first-acquired" }
        scope.own { error("el segundo recurso no se pudo devolver") }

        val thrown = assertThrows(IllegalStateException::class.java) { scope.close() }

        assertEquals(
            "el segundo recurso no se pudo devolver",
            thrown.message,
            "la excepción del release debe ser la señal, no un secreto",
        )
        assertEquals(
            listOf("first-acquired"),
            givenBack,
            "el fallo de una devolución no autoriza a abandonar las demás",
        )
    }

    /**
     * Los fallos se acumulan: el primero lanza y los demás viajan suppressed.
     * Perder el segundo sería contar una fuga de dos recursos como una.
     */
    @Test
    @DisplayName("dos fallos de liberación viajan juntos, el primero arriba y el otro suppressed")
    fun twoFailingReleasesTravelTogether() {
        val scope = ResourceScope()
        scope.own { error("el primero falló") }
        scope.own { error("el segundo falló") }

        val thrown = assertThrows(IllegalStateException::class.java) { scope.close() }

        assertEquals("el segundo falló", thrown.message, "el primer fallo en orden de liberación lanza")
        assertEquals(
            listOf("el primero falló"),
            thrown.suppressed.map { it.message },
            "el otro fallo viaja suppressed: dos recursos, dos fugas contadas",
        )
    }

    /**
     * El orden de liberación es el inverso de adquisición aunque una falle:
     * la fila que muere si alguien aplana el orden para simplificar el bucle.
     */
    @Test
    @DisplayName("el orden de devolución es el inverso de adquisición aunque una devolución falle")
    fun theOrderIsReverseAcquisitionEvenWhenOneFails() {
        val scope = ResourceScope()
        val order = mutableListOf<String>()
        scope.own { order += "A"; error("A falla") }
        scope.own { order += "B" }

        assertThrows(IllegalStateException::class.java) { scope.close() }

        assertEquals(
            listOf("B", "A"),
            order,
            "un fallo no altera el orden: el que se adquirió después se devuelve primero",
        )
    }

}
