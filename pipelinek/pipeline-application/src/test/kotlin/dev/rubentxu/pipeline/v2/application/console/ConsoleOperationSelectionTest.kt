package dev.rubentxu.pipeline.v2.application.console

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepStarted
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * La resolución de un filtro de consola es UNA decisión, y cada una de sus decisiones se
 * comprueba por separado.
 *
 * ## La garantía
 *
 * [ConsoleOperationSelection.resolve] contesta una sola pregunta —"¿qué operaciones quiere este
 * pedido?"— y la contesta de una de estas cinco maneras: todas las operaciones, un conjunto de
 * identificadores de operación, un par de índices, o uno de los cuatro rechazos. Lo que la
 * garantía promete es que **cada uno de esos caminos decide una sola cosa** y que ninguno decide
 * de más.
 *
 * La razón de que la promesa sea por decisión y no por resultado es que el defecto que esta
 * decisión existe para evitar no era un resultado equivocado: era un resultado que no decidía
 * nada. `--kind` era `val kindOk = true`, es decir, un conjunto de tipos no vacío aportaba un
 * conjuncto VERDADERO, y `--kind nonsense` seleccionaba todos los frames del run. El fallo era
 * indistinguible de un filtro correcto porque la forma de la respuesta era la misma: un conjunto
 * de operaciones. Sólo la decisión interior distingue "filtró por el tipo" de "no filtró".
 *
 * ## Por qué una fila por decisión, y no un caso por escenario
 *
 * Cada fila de esta tabla aísla UNA decisión: la fila del tipo se resuelve con `--kind` y nada
 * más, la fila de la etapa con `--stage` y nada más. Esa disciplina es lo que hace posible una
 * **mutación focal**: al invertir el criterio de un eje, la fila que depende de él se pone roja y
 * ninguna otra, porque ninguna otra fila comparte ese eje. Una tabla con un escenario por fila
 * —"filtro por etapa y tipo a la vez"— no podría distinguir el fallo de un eje del fallo del otro.
 *
 * La consecuencia es deliberada y hay que aceptarla: el eje del tipo se ejercita aislado, y su
 * combinación con otro eje no se comprueba aquí. Ese hueco no es un descuido: es el precio de que
 * la mutación del eje del tipo sea localizable, y la fila aislada es la que lo ancla.
 *
 * ## Puro
 *
 * [ConsoleOperationSelection.resolve] lee una secuencia de hechos y devuelve un valor. No abre un
 * almacén, no toca un reloj ni un sistema de ficheros, así que la resolución entera —incluidos
 * todos sus rechazos— se puede comprobar sin journal, sin run y sin raíz de control. Decidir está
 * separado de interpretar: quien llama abre el journal y le entrega los hechos.
 *
 * El journal de esta tabla es una lista en memoria, y una de sus filas lo entrega como una
 * secuencia de un solo uso. [ConsoleOperationSelection.resolve] la recorre UNA vez: la versión
 * anterior hacía dos `filterIsInstance` sobre la misma secuencia, y con el almacén real —cuya
 * secuencia sólo se puede consumir una vez— eso era una excepción en producción que ningún test
 * podía ver, porque `list.asSequence()` no está restringida.
 */
class ConsoleOperationSelectionTest {

    /**
     * Cada fila responde por una decisión, y ninguna comparte eje con otra.
     *
     * La tabla es la instrumento completo de este fichero: catorce filas, catorce decisiones.
     */
    @TestFactory
    fun `each selection decision answers exactly one thing`(): List<DynamicTest> {
        val cases = listOf(
            Case(
                "un filtro vacío admite todas las operaciones",
                Filter(),
                { journal().asSequence() },
                SelectionOutcome.Selected(OperationSelection.AllOperations),
            ),
            Case(
                "una etapa por nombre selecciona las operaciones de esa etapa",
                Filter(stages = setOf("alpha")),
                { journal().asSequence() },
                SelectionOutcome.Selected(OperationSelection.Operations(setOf("run-42-s0-0", "run-42-s0-1"))),
            ),
            Case(
                "un paso por nombre selecciona esa operación",
                Filter(steps = setOf("beta-one")),
                { journal().asSequence() },
                SelectionOutcome.Selected(OperationSelection.Operations(setOf("run-42-s1-0"))),
            ),
            Case(
                "un tipo por nombre selecciona las operaciones de ese tipo y no las demás",
                Filter(kinds = setOf("core")),
                { journal().asSequence() },
                SelectionOutcome.Selected(OperationSelection.Operations(setOf("run-42-s1-0"))),
            ),
            Case(
                "varias etapas del mismo eje se unen",
                Filter(stages = setOf("alpha", "beta")),
                { journal().asSequence() },
                SelectionOutcome.Selected(
                    OperationSelection.Operations(setOf("run-42-s0-0", "run-42-s0-1", "run-42-s1-0")),
                ),
            ),
            Case(
                "los ejes distintos se cortan entre sí",
                Filter(stages = setOf("alpha"), steps = setOf("alpha-two")),
                { journal().asSequence() },
                SelectionOutcome.Selected(OperationSelection.Operations(setOf("run-42-s0-1"))),
            ),
            Case(
                "una etapa que el journal no conoce se rechaza nombrando las que sí",
                Filter(stages = setOf("gamma")),
                { journal().asSequence() },
                SelectionOutcome.Refused(SelectionRefusal.UnknownStage("gamma", listOf("alpha", "beta"))),
            ),
            Case(
                "un paso que el journal no conoce se rechaza nombrando los que sí",
                Filter(steps = setOf("gamma")),
                { journal().asSequence() },
                SelectionOutcome.Refused(
                    SelectionRefusal.UnknownStep("gamma", listOf("alpha-one", "alpha-two", "beta-one")),
                ),
            ),
            Case(
                "un tipo que el journal no conoce se rechaza nombrando los que sí",
                Filter(kinds = setOf("python")),
                { journal().asSequence() },
                SelectionOutcome.Refused(SelectionRefusal.UnknownKind("python", listOf("core", "shell"))),
            ),
            Case(
                "sin journal un nombre de etapa no se puede resolver",
                Filter(stages = setOf("alpha")),
                { null },
                SelectionOutcome.Refused(SelectionRefusal.NoEventsAuthority(listOf("--stage"))),
            ),
            Case(
                "sin journal un tipo no se puede resolver",
                Filter(kinds = setOf("core")),
                { null },
                SelectionOutcome.Refused(SelectionRefusal.NoEventsAuthority(listOf("--kind"))),
            ),
            Case(
                "sin journal los índices sí bastan",
                Filter(stages = setOf("0"), steps = setOf("1")),
                { null },
                SelectionOutcome.Selected(OperationSelection.IndexOnly(requiredStage = 0, requiredStep = 1)),
            ),
            Case(
                "sin journal dos índices no se pueden enumerar",
                Filter(stages = setOf("0", "1")),
                { null },
                SelectionOutcome.Refused(SelectionRefusal.NoEventsAuthority(listOf("--stage", "--step"))),
            ),
            Case(
                "el journal se recorre una sola vez",
                Filter(stages = setOf("alpha")),
                { sequence { yieldAll(journal()) } },
                SelectionOutcome.Selected(OperationSelection.Operations(setOf("run-42-s0-0", "run-42-s0-1"))),
            ),
        )

        return cases.map { case ->
            dynamicTest(case.label) {
                assertEquals(
                    case.expected,
                    ConsoleOperationSelection.resolve(
                        RUN_ID,
                        case.filter.stages,
                        case.filter.steps,
                        case.filter.kinds,
                        case.events(),
                    ),
                    "the row '${case.label}' decided differently",
                )
            }
        }
    }

    /**
     * Una fila: un rótulo, un filtro, los hechos que el filtro ve, y lo que se espera de la
     * decisión. Los hechos llegan por una fábrica porque una de las filas los entrega como una
     * secuencia de un solo uso, que es lo que el almacén real entrega.
     */
    private class Case(
        val label: String,
        val filter: Filter,
        val events: () -> Sequence<DomainEvent>?,
        val expected: SelectionOutcome,
    )

    /** Los tres ejes del filtro, con el valor de cada uno. */
    private data class Filter(
        val stages: Set<String> = emptySet(),
        val steps: Set<String> = emptySet(),
        val kinds: Set<String> = emptySet(),
    )

    private companion object {
        const val RUN_ID = "run-42"

        val EPOCH: Instant = Instant.EPOCH

        /**
         * Un journal de dos etapas y tres pasos: dos pasos `shell` en `alpha` y un paso `core` en
         * `beta`. El tipo `core` sólo existe en la segunda etapa, que es lo que permite distinguir
         * "filtró por el tipo" de "filtró por el nombre de la etapa".
         */
        fun journal(): List<DomainEvent> = listOf(
            StageStarted("evt-1", RUN_ID, 1, EPOCH, stageIndex = 0, stageName = "alpha"),
            StepStarted("evt-2", RUN_ID, 2, EPOCH, stageIndex = 0, stepIndex = 0, stepName = "alpha-one", stepType = "shell"),
            StepStarted("evt-3", RUN_ID, 3, EPOCH, stageIndex = 0, stepIndex = 1, stepName = "alpha-two", stepType = "shell"),
            StageStarted("evt-4", RUN_ID, 4, EPOCH, stageIndex = 1, stageName = "beta"),
            StepStarted("evt-5", RUN_ID, 5, EPOCH, stageIndex = 1, stepIndex = 0, stepName = "beta-one", stepType = "core"),
        )
    }
}
