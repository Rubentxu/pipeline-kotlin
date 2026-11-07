package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.CoreStepRegistryFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * El diagnóstico de pasos canónicos dice lo que el registro dice.
 *
 * ## LA GARANTÍA BAJO PRUEBA
 *
 * El mensaje que la CLI imprime cuando un script usa un plugin que no es canónico
 * ENUMERA los pasos que el puente canónico ofrece, y esa lista es la del registro
 * efectivo: ni uno de más, ni uno de menos.
 *
 * ## POR QUÉ ESTA LISTA ERA UNA DEUDA Y NO UN DETALLE
 *
 * La lista estaba escrita a mano y era falsa en las dos direcciones. Anunciaba
 * `core.load`, un paso que ningún registro ha tenido nunca (el compilador lo declara
 * diferido y la admisión canónica lo rechaza), y omitía doce ids que el registro SÍ
 * tiene. Un diagnóstico que responde «qué puedo usar» con una lista que no es la del
 * registro responde a otra pregunta, y el autor que la lee decide mal.
 *
 * ## EL INSTRUMENTO
 *
 * Las dos sides de la comparación se derivan del mismo sitio: el mensaje se parsesa
 * y la lista esperada sale del registro. Una lista escrita a mano en la prueba sería
 * una segunda autoridad: la que la propia tarjeta denuncia.
 *
 * ## LO QUE ESTE FICHERO NO AFIRMA
 *
 * No afirma nada sobre el texto del mensaje más allá de la lista que enumera: el
 * resto del texto es legible y no se compara aquí. Tampoco afirma nada sobre la
 * ejecución: esto es lo que el operador LEE cuando el rechazo ocurre.
 */
class RegistryStepsDiagnosticTest {

    /**
     * La lista del diagnóstico es EXACTAMENTE la del registro efectivo.
     */
    @Test
    fun `el diagnostico enumera exactamente los pasos del registro efectivo`() {
        assertEquals(
            CoreStepRegistryFactory.canonicalStepIds().sorted(),
            diagnosticStepIds(),
            "el diagnóstico enumera los pasos canónicos, y esa lista es la del registro: " +
                "una lista escrita a mano nombra pasos que no existen y omite pasos que " +
                "sí, y el autor que la lee decide mal",
        )
    }

    /**
     * `core.load` no está en el diagnóstico.
     *
     * Es la fila que nombra el defecto concreto: la lista antigua anunciaba un paso
     * diferido como si el puente pudiera ejecutarlo.
     */
    @Test
    fun `el diagnostico no anuncia core load`() {
        assertFalse(
            diagnosticStepIds().contains("core.load"),
            "`core.load` no lo registra ningún registro y la admisión canónica lo " +
                "rechaza: anunciarlo como paso del puente afirma una capacidad que " +
                "no se puede ejecutar",
        )
    }

    /**
     * La lista NO está vacía.
     *
     * Una comparación contra el registro vale de poco si el mensaje dejara de
     * enumerar pasos: las dos listas serían vacías y coincidirían. Esta fila dice que
     * hay algo que comparar.
     */
    @Test
    fun `el diagnostico enumera pasos`() {
        assertTrue(
            diagnosticStepIds().isNotEmpty(),
            "un diagnóstico que no enumera ningún paso no responde a la pregunta que " +
                "hace: dos listas vacías también coinciden",
        )
    }

    /**
     * Los ids que el diagnóstico enumera, leídos del propio mensaje.
     */
    private fun diagnosticStepIds(): List<String> {
        val announced = NON_CANONICAL_CANONICAL_BRIDGE_ERROR
            .substringAfter("canonical bridge requires ")
            .removeSuffix(".")
        return announced.split("/").filter { it.isNotBlank() }
    }
}
