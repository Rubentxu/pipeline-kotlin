package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.post.PostCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * El `post` que declara el PIPELINE, y lo que se guarda cuando no declara
 * ninguno.
 *
 * ## La garantía bajo prueba
 *
 * Un `pipeline { }` declara su `post { }` con la MISMA forma que un stage: el
 * mismo [PostScope] y las mismas siete condiciones. Un pipeline que no declara
 * `post` no lleva ninguno: el campo existe y está vacío, que es una
 * declaración distinta de una que declara bloques vacíos.
 *
 * ## Por qué el DSL y no el plan
 *
 * El DSL solo RECOGE lo que el autor escribió: qué bloques hay y con qué
 * condición. Cuál de ellos se ejecuta lo decide el planificador puro, en el
 * momento del cierre, sobre el resultado del run. Una fila que decidiera aquí
 * qué bloque corre estaría midiendo una copia de esa decisión.
 *
 * ## Lo que esta clase NO mide
 *
 * Que el post del pipeline se ejecute en el CIERRE DEL PIPELINE es cosa del
 * motor, y lo mide el módulo que lo ejecuta. Aquí lo que se mide es la regla
 * del DSL: el pipeline declara un post con las siete condiciones, o no declara
 * ninguno.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PipelineLevelPostTest {

    /**
     * Un pipeline declara su `post` con las SIETE condiciones.
     *
     * La fila compara el conjunto de condiciones con la lista cerrada del
     * dominio, y no con una lista escrita a mano: la lista de las siete
     * condiciones vive en el dominio, y una copia escrita aquí se quedaría
     * vieja en silencio.
     */
    @Test
    fun `un pipeline declara su post con las siete condiciones`() {
        val spec = pipeline {
            post {
                always { echo("always") }
                success { echo("success") }
                failure { echo("failure") }
                unstable { echo("unstable") }
                aborted { echo("aborted") }
                unsuccessful { echo("unsuccessful") }
                cleanup { echo("cleanup") }
            }
        }

        assertEquals(
            PostCondition.EXECUTION_ORDER.toSet(),
            spec.post.conditions.keys,
            "el post del pipeline declara las mismas siete condiciones que declara el de un stage",
        )
    }

    /**
     * Un pipeline que NO declara `post` no lleva ninguno.
     */
    @Test
    fun `un pipeline que no declara post no lleva ninguno`() {
        val spec = pipeline {
            stages {
                stage("build") { echo("hello") }
            }
        }

        assertTrue(
            spec.post.isEmpty,
            "un pipeline que no declara post no lleva ninguno: el motor no tiene nada que cerrar",
        )
    }
}
