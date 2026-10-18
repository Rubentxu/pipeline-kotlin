package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * La identidad de una compilación es lo que un cache decide reutilizar. Este test afirma la
 * propiedad que la hace utilizable — **dos asignaciones distintas de dimensiones producen
 * digest distintos** — y la que la hace reinterpretable — **la etiqueta de versión dice cómo
 * leer el valor**.
 *
 * HF0 puro: no invoca al compilador, no toca disco, no lee el reloj. Es la fidelidad mínima
 * suficiente para una función de valor puro.
 */
class CacheKeyIdentityTest {

    /**
     * Un separador dentro de una dimensión no es una frontera entre dos dimensiones.
     *
     * Sobre un pre-imagen sin encuadrar, estas dos identidades son la misma cadena
     * `a|b|c|d|e`: una lleva `|` dentro del classpath y la otra lo lleva en la frontera entre
     * el classpath y la primera opción del compilador.
     *
     * Hoy la colisión es **latente** en producción: la llamada real pasa siempre el mismo número
     * de dimensiones y ninguna contiene `|`. Se vuelve **real** en cuanto una dimensión posea ese
     * carácter —`pipelineSource` es texto arbitrario— y también en cuanto cambie el número de
     * dimensiones, porque entonces la misma lista se puede agrupar de dos maneras. Por eso se
     * cierra ahora y no cuando exista el cache que la aprovecharía.
     *
     * La fila se opone a la mutación "sustituir el encuadre por `joinToString("|")`".
     */
    @Test
    fun `a separator inside one dimension is not a boundary between two dimensions`() {
        val separatorInsideTheClasspath = CacheKey.Companion.v2.compute(
            identity(approvedClasspath = "d|e", compilerOptions = emptyList())
        )
        val separatorAtTheClasspathBoundary = CacheKey.Companion.v2.compute(
            identity(approvedClasspath = "d", compilerOptions = listOf("e"))
        )

        assertNotEquals(
            separatorInsideTheClasspath.value,
            separatorAtTheClasspathBoundary.value,
            "Distinct dimension assignments collapsed to one digest: the pre-image is not framed",
        )
    }

    /**
     * CONTROL — la pre-imagen de `v1` es la unión sin encuadrar, y debe seguir siéndolo.
     *
     * Esta fila pasa antes y después del cambio: su trabajo es impedir que alguien "arregle"
     * también el encuadre de `v1`. Un valor `v1` ya escrito en un log de eventos tiene que
     * seguir siendo interpretable con la misma recipe con la que se escribió.
     */
    @Test
    fun `v1 keeps the historical unframed pre-image so written values stay interpretable`() {
        assertEquals(
            Sha256.ofText("script|/cp.jar|2.4.10|1.2.0"),
            CacheKey.Companion.v1.compute(
                scriptText = "script",
                sortedClasspath = "/cp.jar",
                kotlinVersion = "2.4.10",
                hostVersion = "1.2.0",
            ).value,
            "v1's pre-image changed: values already written under the v1 label no longer decode",
        )
    }

    /**
     * Las opciones del compilador son una dimensión, no una disciplina.
     *
     * Antes de esto, `compilerFlags` estaba cubierta *de facto* por `hostVersion`: el KDoc del
     * host pedía que quien añadiera una opción subiera la versión a mano. Eso es exactamente la
     * clase que la Constitución Semántica prohíbe —"parámetro semántico muerto: metadato
     * aceptado pero nunca interpretado"— y la forma correcta de defenderla es el mecanismo, no
     * el comentario. Con la opción como dimensión, olvidarse de subir una versión ya no puede
     * servir un artefacto compilado con opciones distintas.
     *
     * La fila es la que se opone a la mutación "quitar `compilerOptions` de las partes".
     */
    @Test
    fun `the compiler options are part of the identity, not a manual bump discipline`() {
        val withoutTheFlag = identity(compilerOptions = listOf("-Xa"))
        val withTheFlag = identity(compilerOptions = listOf("-Xa", "-Xreturn-value-checker=check"))
        val withTheSameFlagInTheOtherPosition =
            identity(compilerOptions = listOf("-Xreturn-value-checker=check", "-Xa"))

        assertNotEquals(
            CacheKey.Companion.v2.compute(withoutTheFlag).value,
            CacheKey.Companion.v2.compute(withTheFlag).value,
            "Adding a compiler option did not change the key: a stale compiled artifact would be reused",
        )
        assertNotEquals(
            CacheKey.Companion.v2.compute(withTheFlag).value,
            CacheKey.Companion.v2.compute(withTheSameFlagInTheOtherPosition).value,
            "Compiler option order did not change the key: the identity is not the set passed",
        )
    }

    /**
     * CONTROL — la misma identidad produce la misma clave, y bajo la etiqueta `v2`.
     *
     * Sin esta fila, "cualquier cambio produce un digest nuevo" sería una forma de pasar la fila
     * anterior: basta con sembrar aleatoriedad. Passa antes y después del cambio en el host.
     */
    @Test
    fun `an identical identity yields an identical key under the v2 label`() {
        val key = CacheKey.Companion.v2.compute(identity())

        assertEquals(key.value, CacheKey.Companion.v2.compute(identity()).value)
        assertEquals("v2", key.version)
    }

    private fun identity(
        pipelineSource: String = "pipeline { stages { stage(\"s\") { echo(\"x\") } } }",
        approvedClasspath: String = "/a.jar,/b.jar",
        compilerOptions: List<String> = listOf("-Xa", "-Xb"),
    ) = CompilationIdentity(
        pipelineSource = pipelineSource,
        kotlinVersion = "2.4.10",
        hostVersion = "1.2.0",
        approvedClasspath = approvedClasspath,
        compilerOptions = compilerOptions,
    )
}