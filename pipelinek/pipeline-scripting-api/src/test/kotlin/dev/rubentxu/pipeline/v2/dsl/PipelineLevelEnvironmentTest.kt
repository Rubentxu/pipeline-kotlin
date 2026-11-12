package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.MapRuntimeConfig
import dev.rubentxu.pipeline.v2.domain.RuntimeConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Lo que lee un script cuando el PIPELINE declara la variable.
 *
 * ## La garantía bajo prueba
 *
 * `environment { }` a nivel de pipeline declara variables para los stages que
 * no las declaran, y un stage que declara una variable PROPIA la conserva. La
 * lectura —`env("TARGET")`— ve la misma declaración que ven los pasos: la del
 * pipeline cuando el stage no declara ninguna, y la del stage cuando declara.
 *
 * ## Por qué la lectura se mide aparte del plan compilado
 *
 * Porque la lectura ocurre MIENTRE se construye el plan, y no cuando corre el
 * paso: el valor viaja dentro del plan y un replay lo reproduce. El plan
 * compilado —que es donde el motor recibe las variables— lo mide otra fila, en
 * el módulo que lo compila. Aquí lo que se mide es la LECTURA, que es la
 * única forma que tiene el autor de conocer el valor antes de que el paso
 * corra.
 *
 * ## El orden, que es una decisión y no un descuido
 *
 * La declaración del pipeline se lee mientras corre el script, así que una
 * lectura dentro de un stage sólo la ve si la declaración está ESCRITA antes
 * del bloque `stages { }`. Es la misma regla que el nivel de stage ya
 * establece —el bloque `environment { }` tiene que estar escrito antes del
 * paso que lee la variable— y la fila la fija: las variables del pipeline
 * llegan igualmente a los PASOS, porque la aplicación se decide al final del
 * bloque del pipeline, pero la LECTURA es de las que se hacen mientras corre.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PipelineLevelEnvironmentTest {

    /**
     * La lectura de un stage ve la variable que declara el pipeline.
     */
    @Test
    fun `el stage lee la variable que declara el pipeline`() {
        var read: String? = null

        pipeline(host(env = mapOf("TARGET" to "from-the-process"))) {
            environment { env("TARGET", "from-the-pipeline") }
            stages {
                stage("build") {
                    read = env("TARGET")
                }
            }
        }

        assertEquals(
            "from-the-pipeline",
            read,
            "el pipeline declara la variable y el stage la lee: la lectura ve la declaración " +
                "del pipeline y no la de la máquina",
        )
    }

    /**
     * La declaración del stage gana sobre la del pipeline, también en la lectura.
     */
    @Test
    fun `la declaracion del stage gana sobre la del pipeline en la lectura`() {
        var read: String? = null

        pipeline(host()) {
            environment { env("TARGET", "from-the-pipeline") }
            stages {
                stage("build") {
                    environment { env("TARGET", "from-the-stage") }
                    read = env("TARGET")
                }
            }
        }

        assertEquals(
            "from-the-stage",
            read,
            "el stage declara su propia variable y su declaración gana sobre la del pipeline",
        )
    }

    /**
     * El pipeline lee su propia variable.
     */
    @Test
    fun `el pipeline lee la variable que declara`() {
        var read: String? = null

        pipeline(host(env = mapOf("TARGET" to "from-the-process"))) {
            environment { env("TARGET", "from-the-pipeline") }
            read = env("TARGET")
        }

        assertEquals(
            "from-the-pipeline",
            read,
            "el pipeline declara la variable, así que la lee: la declaración es suya",
        )
    }

    /**
     * Una declaración escrita DESPUÉS de los stages llega a los pasos y no a la
     * lectura.
     *
     * La fila fija el orden como una DECISIÓN: la aplicación de las variables
     * del pipeline se decide al final del bloque del pipeline —y por eso llega
     * a los pasos—, mientras que la lectura se hace mientras el bloque corre.
     */
    @Test
    fun `una declaracion escrita despues de los stages llega a los pasos y no a la lectura`() {
        var read: String? = null

        val spec = pipeline(host(env = mapOf("TARGET" to "from-the-process"))) {
            stages {
                stage("build") {
                    read = env("TARGET")
                }
            }
            environment { env("TARGET", "from-the-pipeline") }
        }

        assertEquals(
            "from-the-process",
            read,
            "la lectura se hace mientras corre el script, así que una declaración escrita " +
                "después no la ve: la regla es la misma que la del nivel de stage",
        )
        assertEquals(
            mapOf("TARGET" to "from-the-pipeline"),
            spec.stages.single().environment,
            "las variables del pipeline llegan igualmente a los pasos: la aplicación se decide " +
                "al final del bloque del pipeline",
        )
    }

    private fun host(env: Map<String, String> = emptyMap()): RuntimeConfig =
        MapRuntimeConfig(env = env, properties = emptyMap())
}
