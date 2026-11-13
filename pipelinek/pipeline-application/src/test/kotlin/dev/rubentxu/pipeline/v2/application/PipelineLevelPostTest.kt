package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.post.PostCondition
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * El `post` del PIPELINE en el plan compilado.
 *
 * ## La garantía bajo prueba
 *
 * El plan compilado lleva el `post` que declara el pipeline, con las mismas
 * condiciones que declara el de un stage, y un pipeline que no declara post
 * no lleva ninguno en el plan.
 *
 * ## Por qué se mide el PLAN y no el `PipelineSpec`
 *
 * Porque el plan es lo que el motor ejecuta: es donde el post del pipeline
 * llega al punto de cierre del run. Un `PipelineSpec` que llevara el post
 * probaría que el DSL lo guardó, no que el motor lo recibe. Y el plan es
 * también donde se ve la IDENTIDAD del finalizador, que es lo que impide que
 * el del pipeline comparta fila de journal con el de un stage.
 *
 * ## Lo que esta clase NO mide
 *
 * Que el post se EJECUTE en el cierre es cosa del motor, y lo mide el módulo
 * que lo ejecuta. Aquí lo que se mide es que el plan lo lleva y que su
 * identidad no puede chocar con la de un stage.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PipelineLevelPostTest {

    /**
     * El plan compilado lleva el post del pipeline, y sus finalizers minan una
     * identidad que ningún stage puede alcanzar.
     */
    @Test
    fun `el plan compilado lleva el post del pipeline`() {
        val compiled = compiledOf(
            pipeline {
                post {
                    success { echo("done") }
                }
            },
        )

        val post = compiled.post ?: error("el plan compilado no lleva el post del pipeline")
        assertEquals(
            listOf(PostCondition.SUCCESS),
            post.conditions.keys.toList(),
            "el plan lleva el post del pipeline con la condición que el autor declaró",
        )
        assertEquals(
            "pipeline-post-SUCCESS/echo-0",
            post.conditions.getValue(PostCondition.SUCCESS).single().id.value,
            "el finalizador del pipeline mina su identidad en un espacio que ningún stage " +
                "puede alcanzar: el nombre de la condición va en MAYÚSCULAS y los tokens de " +
                "los stages van en minúsculas",
        )
    }

    /**
     * Un pipeline que NO declara post no lleva ninguno en el plan.
     *
     * Un plan con un post vacío sería un plan que cierra con algo que nadie
     * escribió, y el motor no tiene por qué distinguir ese caso del otro.
     */
    @Test
    fun `un pipeline que no declara post no lleva ninguno en el plan`() {
        val compiled = compiledOf(
            pipeline {
                stages {
                    stage("build") { echo("hello") }
                }
            },
        )

        assertNull(
            compiled.post,
            "un pipeline que no declara post no lleva ninguno en el plan: el cierre del pipeline " +
                "no tiene nada que ejecutar",
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
