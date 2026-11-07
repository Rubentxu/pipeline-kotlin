package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Una variante del DSL sin lowering conocido se RECHAZA, y no se degrada a un payload.
 *
 * ## LA GARANTÍA BAJO PRUEBA
 *
 * El compilador no INVENTA un payload para una forma que no sabe bajar. Cuando una
 * variante llega a la tabla de payloads sin brazo propio, la compilación se rechaza
 * con un mensaje que nombra el TIPO de la variante, el PASO en el que está escrita y
 * la RAZÓN por la que no puede bajarse.
 *
 * ## POR QUÉ ESTA FORMA Y NO OTRA
 *
 * Un `parallel` a nivel de ETAPA no es una variante sin lowering: el compilador lo
 * intercepta ahí y lo baja a un `StageBody.Parallel`. La forma que llega a la tabla
 * de payloads es un `parallel` ANIDADO dentro del cuerpo de otro paso (`dir { parallel
 * { ... } }`), que el DSL permite porque el cuerpo de `dir` es un `StageScope`, y que
 * el compilador sólo intercepta a nivel de etapa.
 *
 * Esa forma es la que se degradaba: el payload llevaba un `toString()` del spec y el
 * run fallaba DESPUÉS, en el análisis de canonicalidad, con un mensaje sobre un
 * plugin id que nadie registró. El autor nunca llegó a saber que su forma no tenía
 * lowering.
 *
 * ## LO QUE ESTE FICHERO NO AFIRMA
 *
 * No afirma nada sobre qué variantes DEBEN bajarse: eso lo afirma la tabla de
 * payloads. Tampoco afirma nada sobre el run: las filas miran la compilación, que es
 * donde el rechazo ocurre y donde el autor lo lee.
 */
class UnknownDslVariantRefusalTest {

    /**
     * Un `parallel` anidado en un cuerpo de bloque no se degrada: la compilación se
     * rechaza y el rechazo dice qué variante es, dónde está y por qué.
     */
    @Test
    fun `un parallel anidado en un cuerpo de bloque se rechaza con tipo step y razon`() {
        val rejection = assertThrows(IllegalStateException::class.java) {
            compileSingle(nestedParallelInsideDir())
        }

        val message = rejection.message.orEmpty()
        assertTrue(
            message.contains("Parallel"),
            "el rechazo nombra el TIPO de la variante, para que el autor sepa qué forma " +
                "es la que no se baja: el mensaje fue '$message'",
        )
        assertTrue(
            message.contains("'parallel'"),
            "el rechazo nombra el tipo del DSL, que es la palabra que el autor escribió " +
                "en el script: el mensaje fue '$message'",
        )
        assertTrue(
            message.contains("s/dir-body-0/parallel-0"),
            "el rechazo nombra el PASO concreto, y no «algún paso»: un pipeline que usa " +
                "la misma forma dos veces no puede dejar al autor adivinar cuál se " +
                "rechazó. El mensaje fue '$message'",
        )
        assertTrue(
            message.contains("no canonical payload"),
            "el rechazo dice la RAZÓN: la variante no tiene forma canónica que el " +
                "runtime pueda leer. El mensaje fue '$message'",
        )
    }

    /**
     * El CONTROL de la fila anterior: una forma que SÍ tiene lowering sigue bajando.
     *
     * Un rechazo que se comiera la tabla entera dejaría el producto sin pasos, y
     * esta fila es la que lo dice. El byte que se afirma es el histórico: un
     * `echo` declara su texto y nada más.
     */
    @Test
    fun `una forma que si tiene lowering sigue bajando a su payload`() {
        val node = compileSingle(StepSpec.Echo("hola"))

        assertEquals(
            """{"kind":"echo","text":"hola"}""",
            node.payload.encoded,
            "el rechazo de las formas sin lowering no se ha comido la tabla de las que " +
                "sí lo tienen: un `echo` sigue bajando al payload que siempre escribió",
        )
    }

    /**
     * Compila UN paso y devuelve el nodo que produjo.
     *
     * La compilación de una etapa con un solo paso devuelve un `StageBody.Steps` con
     * un solo nodo; el refusal ocurre antes, en la construcción del payload.
     */
    private fun compileSingle(step: StepSpec): StepNode {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = PipelineSpec(stages = listOf(StageSpec(name = "s", steps = listOf(step)))),
            sourcePath = "pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = Digest("lock"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        return body.steps.single()
    }

    /**
     * La forma degradada: un `parallel` dentro del cuerpo de un `dir`.
     *
     * El cuerpo de `dir` es un `StageScope`, así que el DSL permite el `parallel`
     * ahí; el compilador sólo intercepta el `parallel` a nivel de etapa.
     */
    private fun nestedParallelInsideDir(): StepSpec.Dir =
        StepSpec.Dir(
            path = "sub",
            steps = listOf(
                StepSpec.Parallel(
                    branches = listOf(
                        StepSpec.BranchSpec(
                            name = "izquierda",
                            steps = listOf(StepSpec.Echo("hola")),
                        ),
                    ),
                ),
            ),
        )
}
