package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Un `writeFile` dentro de un scope de control es la MISMA capacidad que un `writeFile`
 * a nivel de stage.
 *
 * ## La garantía bajo prueba
 *
 * El compilador proyecta el scope de un bloque de control (`catchError`/`warnError`)
 * a un script de shell. Dentro de ese texto, `writeFile` se renderizaba como la LITERAL
 * `writeFile('fichero','texto','UTF-8')`: el shell no tiene ese comando, así que el
 * fichero NO se creaba y el run concluía UNSTABLE con un error de sintaxis de shell.
 *
 * Lo que se afirma aquí es una sola regla de proyección: dentro de un scope, un
 * `writeFile` se proyecta como el NODO TIPADO `core.file.writeFile`, con el MISMO
 * payload versionado que el `writeFile` de stage, en la POSICIÓN en que se declaró.
 *
 * ## La capacidad que esta garantía sirve
 *
 * Quien declara un fichero dentro de un bloque de control lo obtiene, con su contenido
 * y su codificación, y el run no se rompe por un error de sintaxis que no tiene nada
 * que ver con lo que pidió.
 *
 * ## El defecto que esta fila persigue
 *
 * La diferencia entre los dos sitios era EXACTAMENTE el defecto: a nivel de stage el
 * paso ya se sabía proyectar a un nodo tipado, y dentro del scope el mismo paso se
 * degradaba a texto de shell. No era un caso distinto: era la misma capacidad con dos
 * proyecciones, y la segunda era inválida.
 *
 * ## Por qué una sola fila, y por qué las dos variantes de scope
 *
 * `catchError` y `warnError` son dos escrituras de la MISMA proyección, así que se
 * preguntan en la misma fila: son particiones de equivalencia, no dos garantías. Y la
 * fila es una sola porque la regla es una sola —si el writeFile vuelve a ser embebible
 * en shell, TODAS las afirmaciones de esta fila caen a la vez, y ninguna otra fila
 * existe que pueda caer con ella: la mutación focal no necesita que un predicado
 * adivine cuál de ellas era la culpable.
 */
@Suppress("DEPRECATION")
class DslCompiledPipelineCompilerTest {

    /**
     * Un `writeFile` dentro de un scope de control se proyecta como el nodo tipado del
     * stage, en la posición declarada, y nunca como texto de shell.
     */
    @Test
    fun `un writeFile dentro de un scope de control se proyecta como el nodo tipado del stage`() {
        assertWriteFileIsProjectedTyped(
            nodes = nodesOf(listOf(StepSpec.CatchError(steps = scopeSteps()))),
        )
        assertWriteFileIsProjectedTyped(
            nodes = nodesOf(listOf(StepSpec.WarnError(message = "aviso", steps = scopeSteps()))),
        )
    }

    /**
     * La proyección del scope: el nodo tipado, en su posición, con el payload del stage.
     */
    private fun assertWriteFileIsProjectedTyped(nodes: List<StepNode>) {
        // Los marcadores de entrada y de salida del scope no son parte de lo que se
        // comprueba: lo que se comprueba es el ORDEN de los efectos dentro del scope.
        val scopeNodes = nodes.filter { it.pluginStepId != PluginStepId("core.emit.event") }

        // La guarda más específica va PRIMERA, y el orden de las afirmaciones es la
        // atribución: si el literal vuelve a colarse en el texto del shell —el defecto
        // exacto— esta es la afirmación que tiene que caer, y su mensaje lo nombra.
        val shellScripts = scopeNodes
            .filter { it.pluginStepId == PluginStepId("core.sh") }
            .map { it.payload.encoded }
        assertFalse(
            shellScripts.any { it.contains("writeFile(") },
            "ningún script de shell puede contener la literal de una capacidad de fichero: el " +
                "shell no tiene ese comando, y un fichero que no se crea se ve igual desde fuera " +
                "que un fichero que nadie pidió crear",
        )

        assertEquals(
            listOf("core.sh", "core.file.writeFile", "core.sh"),
            scopeNodes.map { it.pluginStepId.value },
            "el writeFile ocupa la posición en que se declaró —entre los dos tramos de shell— " +
                "y es un nodo tipado, no texto de shell",
        )

        val stageWriteFile = nodesOf(listOf(StepSpec.WriteFile(FILE, TEXT, ENCODING)))
            .single { it.pluginStepId == PluginStepId("core.file.writeFile") }
        val scopeWriteFile = scopeNodes.single { it.pluginStepId == PluginStepId("core.file.writeFile") }
        assertEquals(
            stageWriteFile.payload,
            scopeWriteFile.payload,
            "el writeFile del scope usa el MISMO paso de producto y el MISMO payload versionado " +
                "que el de stage: una sola autoridad de escritura, un solo vocabulario de ficheros",
        )
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
