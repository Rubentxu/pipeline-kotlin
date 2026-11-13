package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Lo que el pipeline declara, aplicado a los stages que no declaran lo suyo.
 *
 * ## La garantía bajo prueba
 *
 * Un `pipeline { }` declara sus variables con `environment { }`. Lo que declara
 * es el DEFAULT de cada stage: un stage que no declara una variable la hereda,
 * y un stage que declara una variable PROPIA la conserva. La precedencia es la
 * que el gate ya usa: una declaración gana sobre un valor que llega de fuera,
 * y se decide NOMBRE POR NOMBRE.
 *
 * ## Por qué se mide el PLAN COMPILADO y no el `PipelineSpec`
 *
 * Porque el plan compilado es lo que el motor ejecuta: es donde las variables
 * llegan al entorno del proceso del paso. Un `PipelineSpec` que llevara las
 * variables probaría que el DSL los guardó, no que el motor los recibe. El plan
 * es el ÚNICO sitio donde las dos cosas se juntan, así que es el único sitio
 * donde la garantía se puede medir.
 *
 * ## Lo que esta clase NO mide
 *
 * El agente. La autoría de agente se retiró del lenguaje, y lo que sobrevive
 * —la directriz `core.agent` y su resolutor— se mide donde el runtime la
 * decide, no donde el pipeline la declaraba.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PipelineLevelDefaultsTest {

    /**
     * El plan compilado lleva las variables del pipeline al stage que no
     * declara ninguna.
     */
    @Test
    fun `el plan compilado lleva las variables del pipeline`() {
        val compiled = compiledOf(
            pipeline {
                environment { env("TARGET", "staging") }
                stages {
                    stage("build") {
                        echo("hello")
                    }
                }
            },
        )

        val stage = compiled.stages.single()
        assertEquals(
            mapOf("TARGET" to "staging"),
            stage.environment.values,
            "el stage no declara ninguna variable, así que corre con las del pipeline",
        )
    }

    /**
     * La declaración de un stage gana sobre la del pipeline, nombre por nombre.
     */
    @Test
    fun `la declaracion de un stage gana sobre la del pipeline`() {
        val compiled = compiledOf(
            pipeline {
                environment {
                    env("TARGET", "from-the-pipeline")
                    env("REGISTRY", "from-the-pipeline")
                }
                stages {
                    stage("build") {
                        environment { env("TARGET", "from-the-stage") }
                        echo("hello")
                    }
                }
            },
        )

        val stage = compiled.stages.single()
        assertEquals(
            mapOf(
                "TARGET" to "from-the-stage",
                "REGISTRY" to "from-the-pipeline",
            ),
            stage.environment.values,
            "el stage gana en el nombre que declara y hereda el que no declara: " +
                "la precedencia se decide nombre por nombre",
        )
    }

    /**
     * Un SEGUNDO bloque de variables AÑADE al primero.
     *
     * Los dos llegan al stage: el nombre que sólo declara el primer bloque y
     * el que sólo declara el segundo. Y el nombre que los dos declaran es el
     * del bloque POSTERIOR, porque un pipeline que declara una variable dos
     * veces en dos bloques ha pedido dos cosas, y la que contesta es la última
     * que se dijo.
     */
    @Test
    fun `un segundo bloque de variables anade al primero`() {
        val compiled = compiledOf(
            pipeline {
                environment {
                    env("TARGET", "from-the-first-block")
                    env("REGISTRY", "from-the-first-block")
                }
                environment {
                    env("TARGET", "from-the-second-block")
                    env("EXTRA", "from-the-second-block")
                }
                stages {
                    stage("build") {
                        echo("hello")
                    }
                }
            },
        )

        val stage = compiled.stages.single()
        assertEquals(
            mapOf(
                "TARGET" to "from-the-second-block",
                "REGISTRY" to "from-the-first-block",
                "EXTRA" to "from-the-second-block",
            ),
            stage.environment.values,
            "el segundo bloque AÑADE al primero: los dos llegan, y el nombre que los dos " +
                "declaran es el del bloque posterior",
        )
    }

    /**
     * La variable declarada DESPUÉS de los stages llega a los stages.
     *
     * La fila fija el ORDEN de la regla: la aplicación de las variables se
     * decide al final del bloque del pipeline, así que el lugar donde se
     * declara no decide qué variable corre el stage. Es lo contrario de lo que
     * pasa con la LECTURA de una variable, que sí ve el orden, y las dos filas
     * lo dicen.
     */
    @Test
    fun `la variable declarada despues de los stages llega a los stages`() {
        val compiled = compiledOf(
            pipeline {
                stages {
                    stage("build") {
                        echo("hello")
                    }
                }
                environment { env("TARGET", "linux") }
            },
        )

        val stage = compiled.stages.single()
        assertEquals(
            mapOf("TARGET" to "linux"),
            stage.environment.values,
            "las variables del pipeline se aplican al final del bloque del pipeline: el lugar " +
                "donde se declaran no decide qué variables corre el stage",
        )
    }

    /**
     * El plan compilado de un `PipelineSpec`.
     */
    private fun compiledOf(spec: PipelineSpec) = DslCompiledPipelineCompiler.compile(
        spec = spec,
        sourcePath = "pipeline.kts",
        sourceContent = "pipeline { }",
        pluginLockDigest = Digest("lock"),
    )
}
