package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcomeFold
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * Un scope de control se EJECUTA por el motor canónico: no se traduce a un script.
 *
 * ## La garantía bajo prueba
 *
 * Un `catchError` / `warnError` es un Step que TIENE CUERPO, exactamente como lo son `dir`,
 * `retry` o `timeout`: sus hijos viajan en el CUERPO de ese Step, y lo que el scope
 * declara —qué hacer con un fallo— viaja en su PAYLOAD. Ese Step con cuerpo es lo que
 * permite que el motor canónico lo ejecute y aplique su fold; un scope aplanado en una
 * secuencia de pasos sueltos ejecuta lo mismo pero pierde el hecho de que son un cuerpo:
 * el motor ya no puede distinguirlos y la única forma de que el alcance sobreviva es
 * re-inventarlo en otro sitio.
 *
 * ## La capacidad que esta garantía sirve
 *
 * Quien declara un scope de control espera que sus hijos se ejecuten DENTRO de él, con el
 * alcance del scope, y no como hermanos del stage.
 *
 * ## El defecto que esta fila persigue
 *
 * El scope se proyectaba a un script de shell. Consecuencia medida: dentro de ese texto,
 * `writeFile` se renderizaba como la LITERAL `writeFile('fichero','texto','UTF-8')` —el
 * shell no tiene ese comando— así que el fichero NO se creaba y el run concluía UNSTABLE
 * con un error de sintaxis de shell. Y el contrato declarado del scope (`buildResult`,
 * `stageResult`) no viajaba a ningún sitio tipado: lo consumía la propia traducción.
 *
 * ## Por qué es UNA fila y no tres
 *
 * Las tres consecuencias —LA RUTA (un scope es un Step con cuerpo), el CONTRATO (lo
 * declarado viaja en el payload) y la CAPACIDAD (un `writeFile` del scope es el mismo
 * paso de producto que el de stage)— son la MISMA decisión de compilación vista desde tres
 * ángulos, y todas se ROMPEN a la vez por la misma rama. Separlarlas en tres filas haría
 * que una mutación focal de esa rama pusiera tres filas rojas en vez de una, y entonces el
 * veredicto no diría QUÉ se rompió: sólo cuánto se rompió. Por eso las tres consecuencias
 * viven DENTRO de la fila que afirma su causa.
 *
 * Y la fila cubre LAS DOS familias a propósito: la proyección legacy las re-ruteaba juntas,
 * así que una fila por familia dejaría a la mutación focal con dos filas rojas.
 *
 * ## El orden de las afirmaciones
 *
 * La primera afirmación es la MÁS ESPECÍFICA —el nodo compilado es un Step CON CUERPO y
 * no un nodo opaco de shell— porque ésa es la que distingue la ejecución canónica de la
 * proyección legacy. El orden de las afirmaciones es la atribución: la que cae primera
 * nombra la causa.
 */
@Suppress("DEPRECATION")
class DslCompiledPipelineCompilerTest {

    /**
     * Un scope de control se ejecuta por el motor canónico: compila a un Step con cuerpo,
     * y todo lo que ese Step declara y contiene viaja en el sitio que le corresponde.
     */
    @Test
    fun `un scope de control se ejecuta por el motor canonico y compila a un Step con cuerpo`() {
        // --- LA RUTA: las DOS familias bajan por la maquinaria de bloque ---
        val catchError = assertScopeCompilesToBodyBearingStep(
            nodes = nodesOf(
                listOf(
                    StepSpec.CatchError(
                        buildResult = CatchErrorBuildResult.Success,
                        stageResult = CatchErrorBuildResult.Success,
                        message = "atrapado",
                        steps = scopeSteps(),
                    ),
                ),
            ),
            expectedKey = "core.catchError",
        )
        val warnError = assertScopeCompilesToBodyBearingStep(
            nodes = nodesOf(listOf(StepSpec.WarnError(message = "aviso", steps = scopeSteps()))),
            expectedKey = "core.warnError",
        )

        // --- EL CONTRATO: lo declarado viaja en el payload, y se lee con su única autoridad ---
        val contratoCatch = CoreWorkflowControlWireCodec.decode(
            EncodedStepValue(catchError.payload.encoded),
        )
        assertEquals(
            BodyOutcomeFold.CONTAINMENT,
            contratoCatch.fold,
            "el fold del scope viaja en su payload: el motor no lo deduce del nombre del Step",
        )
        assertEquals(
            CatchErrorBuildResult.Success,
            contratoCatch.buildResult,
            "el buildResult DECLARADO viaja al payload, y no se sustituye por ningún valor " +
                "por defecto en el camino",
        )
        assertEquals(
            CatchErrorBuildResult.Success,
            contratoCatch.stageResult,
            "el stageResult DECLARADO viaja al payload",
        )
        assertEquals(
            "atrapado",
            contratoCatch.message,
            "el mensaje declarado viaja al payload",
        )

        val contratoWarn = CoreWorkflowControlWireCodec.decode(
            EncodedStepValue(warnError.payload.encoded),
        )
        assertEquals(
            BodyOutcomeFold.OUTPUT_DECORATION,
            contratoWarn.fold,
            "el fold del scope viaja en su payload: el motor no lo deduce del nombre del Step",
        )
        assertEquals(
            CatchErrorBuildResult.Unstable,
            contratoWarn.buildResult,
            "una decoracion de salida absorbe el fallo como UNSTABLE: es el resultado fijo de " +
                "la familia, no uno que el autor pueda cambiar",
        )

        // --- LA CAPACIDAD: un writeFile del scope es el MISMO paso de producto que el de stage ---
        // La guarda más específica va PRIMERA, y el orden de las afirmaciones es la
        // atribución: si la literal vuelve a colarse en el texto del shell —el defecto
        // exacto— esta es la afirmación que tiene que caer, y su mensaje lo nombra.
        val shellScripts = catchError.body
            .filter { it.pluginStepId == PluginStepId("core.sh") }
            .map { it.payload.encoded }
        assertFalse(
            shellScripts.any { it.contains("writeFile(") },
            "ningun script de shell puede contener la literal de una capacidad de fichero: el " +
                "shell no tiene ese comando, y un fichero que no se crea se ve igual desde fuera " +
                "que un fichero que nadie pidió crear",
        )

        val stageWriteFile = nodesOf(listOf(StepSpec.WriteFile(FILE, TEXT, ENCODING)))
            .single { it.pluginStepId == PluginStepId("core.file.writeFile") }
        val scopeWriteFile = catchError.body.single {
            it.pluginStepId == PluginStepId("core.file.writeFile")
        }
        assertEquals(
            stageWriteFile.payload,
            scopeWriteFile.payload,
            "el writeFile del scope usa el MISMO paso de producto y el MISMO payload versionado " +
                "que el de stage: una sola autoridad de escritura, un solo vocabulario de ficheros",
        )
    }

    /**
     * La ruta del scope: un Step, con cuerpo, y sus hijos en el cuerpo.
     */
    private fun assertScopeCompilesToBodyBearingStep(
        nodes: List<StepNode>,
        expectedKey: String,
    ): BlockStepNode {
        assertEquals(
            1,
            nodes.size,
            "un scope de control es UN Step: no se aplana en una secuencia de pasos sueltos, " +
                "porque un scope aplanado pierde el hecho de que sus hijos son un cuerpo",
        )
        val scope = assertInstanceOf(
            BlockStepNode::class.java,
            nodes.single(),
            "un scope de control se ejecuta por el motor canónico: compila a un Step CON CUERPO, " +
                "no a un nodo opaco que el shell interprete por su cuenta",
        )
        assertEquals(
            expectedKey,
            scope.pluginStepId.value,
            "el scope se identifica por su familia, y la familia decide su fold",
        )
        assertEquals(
            listOf("core.sh", "core.file.writeFile", "core.sh"),
            scope.body.map { it.pluginStepId.value },
            "los hijos del scope viajan en su cuerpo, en el orden en que se declararon",
        )
        return scope
    }

    /**
     * Los tres pasos del scope, en el orden en que se declaran.
     */
    private fun scopeSteps(): List<StepSpec> = listOf(
        StepSpec.Shell(command = "echo antes"),
        StepSpec.WriteFile(FILE, TEXT, ENCODING),
        StepSpec.Shell(command = "echo despues"),
    )

    /**
     * Los nodos que produce el compilador para un stage con estos pasos.
     *
     * El `Digest` va con el nombre COMPLETO porque el dominio publica DOS tipos con ese
     * nombre —`dev.rubentxu.pipeline.v2.domain.Digest` y
     * `dev.rubentxu.pipeline.v2.domain.step.Digest`— y un fichero que importa el primero
     * deja de resolver el paquete `domain.step`. El nombre completo mantiene este
     * fichero independiente de esa colisión y del orden de los imports, que un
     * formateador puede cambiar.
     */
    private fun nodesOf(steps: List<StepSpec>): List<StepNode> {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = PipelineSpec(stages = listOf(StageSpec(name = "s", steps = steps))),
            sourcePath = "pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("lock"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        return body.steps
    }

    private companion object {
        const val FILE = "artefacto.txt"
        const val TEXT = "contenido declarado"
        const val ENCODING = "UTF-8"
    }
}
