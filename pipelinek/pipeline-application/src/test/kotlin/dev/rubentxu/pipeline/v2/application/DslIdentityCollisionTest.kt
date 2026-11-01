package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Dos nombres DISTINTOS no pueden compartir una identidad.
 *
 * ## La garantía bajo prueba
 *
 * El compilador es quien ACUÑA las identidades (`StageId`, `StepId`). La normalización que
 * convierte un nombre del DSL en un token de identidad es deliberadamente laxa —minúsculas y
 * todo lo que no sea alfanumérico pasa a un guion— y por eso NO es inyectiva: `build+test`,
 * `build test` y `build_test` normalizan los tres a `build-test`. Cuando dos nombres
 * DISTINTOS caían en el mismo token, el compilador acuñaba la MISMA identidad para los dos,
 * y el journal durable registra esa identidad como el nombre del paso: dos pasos que
 * comparten nombre no se distinguen después de los hechos.
 *
 * La garantía es que esa colisión se NIEGA en el momento de acuñar la identidad, diciendo
 * QUIÉNES colisionan (los dos nombres originales) y DÓNDE (la trayectoria), y que todo lo
 * demás compila exactamente igual que antes.
 *
 * ## La capacidad que esta garantía sirve
 *
 * Quien escribe un pipeline espera que sus etapas y sus pasos sean distinguibles por
 * nombre en el historial, y que un nombre mal escrito se le diga en el mensaje de error, no
 * que dos piezas del pipeline se fundan en una sola sin que nadie se entere.
 *
 * ## El defecto que esta fila persigue
 *
 * El compilador normalizaba el nombre sin comprobar que el token normalizado fuese único
 * entre hermanos. La colisión de ETAPAS y de RAMAS la cogía más tarde el validador del IR
 * (`Duplicate stage id`), con un mensaje que sólo nombraba el token —no los dos nombres— y
 * como una excepción sin limpiar; la colisión de PASOS no la cogía NADIE, porque el
 * validador sólo comprueba la unicidad dentro de un bloque y no entre los pasos directos
 * de un stage.
 *
 * ## Por qué es UNA fila y no tres
 *
 * Los tres ámbitos —etapas hermanas, ramas hermanas de un `parallel` y pasos hermanos de un
 * cuerpo— son la MISMA pieza ([IdentityScope]) usada en tres cableados. Una fila por
 * ámbito haría que una mutación focal de la pieza compartida pusiera tres filas rojas en
 * vez de una, y entonces el veredicto no diría QUÉ se rompió: sólo cuánto se rompió. Por
 * eso los tres ámbitos viven DENTRO de la fila que afirma su causa, y la fila de los falsos
 * positivos vive aparte porque afirma la OTRA cara de la misma pieza: lo que NO se puede
 * rechazar.
 *
 * ## El orden de las afirmaciones
 *
 * La primera afirmación es la que DEMUESTRA la colisión anterior —los dos nombres, por
 * separado, acuñan el mismo token— porque ésa es la que distingue el defecto del resto:
 * sin ella, el rechazo sería una excusa sin causa observada. Después vienen los tres
 * ámbitos, y al final la dirección que no puede fallar: el mismo nombre repetido.
 */
class DslIdentityCollisionTest {

    /**
     * Dos nombres que normalizan al mismo token no pueden compartir una identidad, y el
     * compilador lo dice nombrando a los dos y diciendo dónde.
     */
    @Test
    fun `dos nombres que normalizan al mismo token no pueden compartir una identidad`() {
        // --- LA COLISIÓN ANTERIOR: por separado, los dos nombres acuñan el MISMO token ---
        val idOfPlus = stageIdOf("build+test")
        val idOfSpace = stageIdOf("build test")
        assertEquals(
            idOfPlus,
            idOfSpace,
            "la normalización NO es inyectiva: 'build+test' y 'build test' acuñan el mismo " +
                "token de identidad, y por eso podían compartir una identidad",
        )

        // --- ÁMBITO DE ETAPA: dos etapas hermanas ---
        val stageRefusal = assertThrows(IllegalStateException::class.java) {
            compile(stage("build+test"), stage("build test"))
        }
        assertRefusalNames(
            stageRefusal,
            first = "build+test",
            second = "build test",
            token = "build-test",
            trajectory = "stage scope",
            why = "dos etapas hermanas que normalizan al mismo token compartirían un StageId",
        )

        // --- ÁMBITO DE RAMA: dos ramas hermanas de un parallel ---
        val branchRefusal = assertThrows(IllegalStateException::class.java) {
            compile(
                stage(
                    "s",
                    StepSpec.Parallel(
                        branches = listOf(
                            StepSpec.BranchSpec("left+1", listOf(StepSpec.Shell(command = "echo a"))),
                            StepSpec.BranchSpec("left 1", listOf(StepSpec.Shell(command = "echo b"))),
                        ),
                    ),
                ),
            )
        }
        assertRefusalNames(
            branchRefusal,
            first = "left+1",
            second = "left 1",
            token = "left-1",
            trajectory = "parallel branch scope",
            why = "dos ramas hermanas que normalizan al mismo token compartirían un StageId",
        )

        // --- ÁMBITO DE PASO: dos pasos hermanos de un cuerpo ---
        val stepRefusal = assertThrows(IllegalStateException::class.java) {
            compile(
                stage(
                    "s",
                    StepSpec.ArtifactQuery(artifactName = "a", name = "build+test"),
                    StepSpec.ArtifactQuery(artifactName = "b", name = "build test"),
                ),
            )
        }
        assertRefusalNames(
            stepRefusal,
            first = "build+test",
            second = "build test",
            token = "build-test",
            trajectory = "body of",
            why = "dos pasos hermanos que normalizan al mismo token compartirían un StepId, y " +
                "el contador de ocurrencias —indexado por el nombre CRUDO— no los separaría",
        )
    }

    /**
     * Un nombre que NO colisiona conserva exactamente la identidad que tenía, y el mismo
     * nombre repetido sigue siendo legítimo: el contador de ocurrencias separa a esos
     * hermanos y el rechazo no los confunde con una colisión.
     */
    @Test
    fun `un nombre que no colisiona conserva su identidad y el mismo nombre repetido sigue siendo legitimo`() {
        assertEquals(
            "build-test",
            stageIdOf("build+test"),
            "el token de identidad de un nombre que no colisiona no se mueve: el esquema de " +
                "ids y el digest de definición siguen siendo los de siempre, así que un " +
                "journal existente se reanuda igual",
        )
        assertEquals(
            listOf("s/query-0", "s/query-1"),
            stepIdsOf(
                StepSpec.ArtifactQuery(artifactName = "a", name = "query"),
                StepSpec.ArtifactQuery(artifactName = "b", name = "query"),
            ),
            "el MISMO nombre repetido en un cuerpo no es una colisión: el contador de " +
                "ocurrencias los separa y sus ids son los de siempre",
        )
        assertEquals(
            "build",
            stageIdOf("build"),
            "nombres que NO normalizan al mismo token siguen siendo identidades distintas",
        )
    }

    /**
     * El rechazo dice QUIÉN colisiona y DÓNDE: los dos nombres originales, el token que
     * comparten y la trayectoria donde viven.
     */
    private fun assertRefusalNames(
        refusal: IllegalStateException,
        first: String,
        second: String,
        token: String,
        trajectory: String,
        why: String,
    ) {
        val message = refusal.message.orEmpty()
        assertTrue(
            message.contains(first),
            "el rechazo nombra el primer nombre original ('$first'): $why — el mensaje dice " +
                "'$message'",
        )
        assertTrue(
            message.contains(second),
            "el rechazo nombra el segundo nombre original ('$second'): $why — el mensaje dice " +
                "'$message'",
        )
        assertTrue(
            message.contains(token),
            "el rechazo nombra el token en el que chocan ('$token'): $why — el mensaje dice " +
                "'$message'",
        )
        assertTrue(
            message.contains(trajectory),
            "el rechazo dice la TRAYECTORIA donde chocan ('$trajectory'): $why — el mensaje " +
                "dice '$message'",
        )
    }

    private fun compile(vararg stages: StageSpec): CompiledPipeline =
        DslCompiledPipelineCompiler.compile(
            spec = PipelineSpec(stages = stages.toList()),
            sourcePath = "pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("lock"),
        )

    private fun stage(name: String, vararg steps: StepSpec): StageSpec =
        StageSpec(name = name, steps = steps.toList())

    /**
     * El `StageId` que el compilador acuña para una etapa sola.
     */
    private fun stageIdOf(name: String): String = compile(stage(name)).stages.single().id.value

    /**
     * Los `StepId` que el compilador acuña para los pasos de un stage.
     */
    private fun stepIdsOf(vararg steps: StepSpec): List<String> {
        val body = compile(stage("s", *steps)).stages.single().body as StageBody.Steps
        return body.steps.map { it.id.value }
    }
}
