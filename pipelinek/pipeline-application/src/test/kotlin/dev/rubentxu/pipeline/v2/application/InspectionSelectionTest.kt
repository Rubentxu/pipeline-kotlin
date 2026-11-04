package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.durable.OpId
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * La SELECCIÓN de una inspección es una decisión, y cada una de sus decisiones se comprueba por
 * separado.
 *
 * ## La garantía
 *
 * [InspectionSelection] contesta tres preguntas y ninguna más:
 *
 *  1. **de qué operación es cada fila** — para que la transcripción que la acompaña sea la de ESA
 *     fila y no la de otra;
 *  2. **qué filas conserva el pedido** — los filtros y la ventana de contexto;
 *  3. **qué dice la columna del veredicto** — cómo se traduce el evento a la palabra que el
 *     operador lee.
 *
 * Lo que la garantía NO promete es que las respuestas encajen entre sí: la fila de una etapa
 * lleva el nombre del PASO (que es lo que la columna `STAGE` ha mostrado siempre), y el ordinal
 * del paso va en su propia columna. Eso no se corrige aquí — el verbo debe conservar su salida
 * exacta — pero se fija, para que un cambio futuro en el nombre no pueda colarse sin que una
 * fila se ponga roja.
 *
 * ## Por qué una fila por decisión
 *
 * Cada fila aísla UNA decisión, y ninguna comparte eje con otra. Esa disciplina es la que hace
 * posible una **mutación focal**: al invertir el criterio de un eje, la fila que depende de él se
 * pone roja y ninguna otra, porque ninguna otra fila comparte ese eje. Una tabla con un
 * escenario por fila —"filtro por etapa y tipo a la vez"— no podría distinguir el fallo de un eje
 * del fallo del otro.
 *
 * ## Puro
 *
 * Nada aquí abre un almacén, no toca un reloj ni un sistema de ficheros: son listas en memoria y
 * comparaciones. Esa es la razón por la que esta decisión tiene una comprobación permanente y las
 * lecturas que la sostienen no la tienen.
 */
class InspectionSelectionTest {

    /**
     * Cada fila responde por una decisión de ATRIBUCIÓN, y ninguna comparte eje con otra.
     */
    @TestFactory
    fun `each attribution decision answers exactly one thing`(): List<DynamicTest> {
        val cases = listOf(
            Case(
                "una etapa que abre no es una operación",
            ) {
                assertNull(ops()[0], "una etapa no es una operación: su fila no lleva transcripción")
            },
            Case(
                "un paso es dueño de su operación",
            ) {
                assertEquals(OpId(RUN_ID, 0, 0), ops()[1], "el paso es dueño de su operación")
            },
            Case(
                "un evento que no es de un paso hereda la operación abierta",
            ) {
                assertEquals(
                    OpId(RUN_ID, 0, 0),
                    ops()[2],
                    "la salida capturada pertenece al paso abierto",
                )
            },
            Case(
                "el cierre de un paso es dueño de su operación",
            ) {
                assertEquals(OpId(RUN_ID, 0, 0), ops()[3], "el cierre nombra su propio paso")
            },
            Case(
                "el cierre de una etapa no hereda la operación del último paso",
            ) {
                assertNull(
                    ops()[6],
                    "el cierre de una etapa no es una operación: la etapa no pertenece a ningún paso",
                )
            },
            Case(
                "una etapa cerrada no deja abierta la operación para lo que viene después",
            ) {
                assertNull(
                    ops()[11],
                    "tras cerrar la etapa no queda ninguna operación abierta",
                )
            },
            Case(
                "un fallo de paso hereda la operación abierta",
            ) {
                assertEquals(
                    OpId(RUN_ID, 0, 1),
                    ops()[5],
                    "el fallo pertenece al paso que estaba abierto",
                )
            },
        )
        return cases.asDynamicTests()
    }

    /**
     * Cada fila responde por una decisión de FILTRO, y ninguna comparte eje con otra.
     */
    @TestFactory
    fun `each filter decision answers exactly one thing`(): List<DynamicTest> {
        val cases = listOf(
            Case(
                "sin filtros se muestran todas las filas",
            ) {
                val all = records()
                assertEquals(
                    all,
                    InspectionSelection.applyFilters(all, CompiledInspectionQuery()),
                    "un pedido sin filtros tiene una respuesta: todas las filas",
                )
            },
            Case(
                "--failed muestra las filas cuyo veredicto es un fallo",
            ) {
                val all = records()
                assertEquals(
                    listOf(all[1], all[3]),
                    InspectionSelection.applyFilters(all, CompiledInspectionQuery(failed = true)),
                    "--failed conserva las filas de veredicto failure",
                )
            },
            Case(
                "--kind muestra las filas de ese veredicto",
            ) {
                val all = records()
                assertEquals(
                    listOf(all[0], all[2], all[4]),
                    InspectionSelection.applyFilters(all, CompiledInspectionQuery(kind = "success")),
                    "--kind conserva las filas de ese veredicto",
                )
            },
            Case(
                "--step muestra las filas de ese ordinal",
            ) {
                val all = records()
                assertEquals(
                    listOf(all[1], all[2]),
                    InspectionSelection.applyFilters(all, CompiledInspectionQuery(step = 2)),
                    "--step conserva las filas de ese ordinal",
                )
            },
            Case(
                "los filtros de ejes distintos se unen",
            ) {
                val all = records()
                assertEquals(
                    listOf(all[1], all[2], all[3]),
                    InspectionSelection.applyFilters(
                        all,
                        CompiledInspectionQuery(failed = true, stage = "alpha-two"),
                    ),
                    "los filtros estrechan por dimensiones distintas de la misma fila, así que se unen",
                )
            },
        )
        return cases.asDynamicTests()
    }

    /**
     * Cada fila responde por una decisión de VENTANA, y ninguna comparte eje con otra.
     */
    @TestFactory
    fun `each window decision answers exactly one thing`(): List<DynamicTest> {
        val cases = listOf(
            Case(
                "sin ventana se muestran sólo las filas que coinciden",
            ) {
                val all = records()
                assertEquals(
                    listOf(all[2]),
                    InspectionSelection.applyContextWindow(
                        all,
                        listOf(all[2]),
                        CompiledInspectionQuery(context = 0),
                    ),
                    "una ventana de cero filas es la coincidencia y nada más",
                )
            },
            Case(
                "la ventana trae la fila de antes y la de después",
            ) {
                val all = records()
                assertEquals(
                    listOf(all[1], all[2], all[3]),
                    InspectionSelection.applyContextWindow(
                        all,
                        listOf(all[2]),
                        CompiledInspectionQuery(context = 1),
                    ),
                    "la ventana se mide en filas de la corrida entera",
                )
            },
            Case(
                "la ventana se recorta en el borde",
            ) {
                val all = records()
                assertEquals(
                    listOf(all[0], all[1], all[2]),
                    InspectionSelection.applyContextWindow(
                        all,
                        listOf(all[0]),
                        CompiledInspectionQuery(context = 2),
                    ),
                    "la ventana no se sale de la corrida",
                )
            },
            Case(
                "sin coincidencias la ventana no trae nada",
            ) {
                val all = records()
                assertEquals(
                    emptyList<InspectionRecord>(),
                    InspectionSelection.applyContextWindow(
                        all,
                        emptyList(),
                        CompiledInspectionQuery(context = 3),
                    ),
                    "una ventana sin coincidencias no inventa filas",
                )
            },
        )
        return cases.asDynamicTests()
    }

    /**
     * Cada fila responde por una decisión de VEREDICTO, y ninguna comparte eje con otra.
     */
    @TestFactory
    fun `each verdict decision answers exactly one thing`(): List<DynamicTest> {
        val cases = listOf(
            Case(
                "un run que terminó con éxito dice success",
            ) {
                assertEquals("success", outcomeOf(runFinished("success")), "success")
            },
            Case(
                "un run inestable dice unstable",
            ) {
                assertEquals("unstable", outcomeOf(runFinished("unstable")), "unstable")
            },
            Case(
                "un run que falló dice failure",
            ) {
                assertEquals("failure", outcomeOf(runFinished("failure")), "failure")
            },
            Case(
                "un run abortado dice failure",
            ) {
                assertEquals("failure", outcomeOf(runFinished("aborted")), "aborted se lee como failure")
            },
            Case(
                "un veredicto que la fila no conoce se dice tal cual",
            ) {
                assertEquals(
                    "cancelled",
                    outcomeOf(runFinished("cancelled")),
                    "una palabra desconocida no se traduce",
                )
            },
            Case(
                "un fallo de paso dice failure y lleva su mensaje",
            ) {
                val record = InspectionSelection.toRecord(stepFailed(), null, emptyMap())
                assertEquals("failure", record.outcome, "un fallo de paso es un fallo")
                assertEquals("boom", record.message, "el fallo lleva su mensaje")
            },
            Case(
                "un paso cerrado con veredicto dice el veredicto",
            ) {
                assertEquals(
                    "success",
                    outcomeOf(stepFinished(outcome = "success")),
                    "el veredicto del paso es el resultado, no el nombre del evento",
                )
            },
            Case(
                "un paso cerrado sin veredicto dice el nombre del evento",
            ) {
                assertEquals(
                    "StepFinished",
                    outcomeOf(stepFinished(outcome = null)),
                    "sin veredicto la fila dice lo que siempre ha dicho",
                )
            },
        )
        return cases.asDynamicTests()
    }

    /**
     * Una fila: el rótulo de la decisión y la comprobación que la ancla.
     */
    private class Case(
        val label: String,
        val check: () -> Unit,
    )

    private companion object {

        const val RUN_ID = "run-7"

        val EPOCH: Instant = Instant.EPOCH

        /**
         * Una corrida de dos etapas: la primera con un paso que sale bien y otro que falla, la
         * segunda con un paso que sale bien. Entre el primer paso y su cierre hay una captura de
         * salida, que es el evento que NO es de un paso y por lo tanto hereda la operación
         * abierta.
         */
        fun events(): List<DomainEvent> = listOf(
            StageStarted("evt-1", RUN_ID, 1, EPOCH, stageIndex = 0, stageName = "build"),
            StepStarted(
                "evt-2", RUN_ID, 2, EPOCH,
                stageIndex = 0, stepIndex = 0, stepName = "alpha-one", stepType = "shell",
            ),
            EchoOutputCaptured("evt-3", RUN_ID, 3, EPOCH, stepIndex = 0, content = "hola"),
            StepFinished(
                "evt-4", RUN_ID, 4, EPOCH,
                stageIndex = 0, stepIndex = 0, stepName = "alpha-one", stepType = "shell",
                outcome = "success",
            ),
            StepStarted(
                "evt-5", RUN_ID, 5, EPOCH,
                stageIndex = 0, stepIndex = 1, stepName = "alpha-two", stepType = "shell",
            ),
            StepFailed(
                "evt-6", RUN_ID, 6, EPOCH,
                stageIndex = 0, stepIndex = 1, stepName = "alpha-two", stepType = "shell",
                failureKind = FailureKind.SCRIPT, message = "boom",
            ),
            StageFinished("evt-7", RUN_ID, 7, EPOCH, stageIndex = 0, stageName = "build", outcome = "failure"),
            StageStarted("evt-8", RUN_ID, 8, EPOCH, stageIndex = 1, stageName = "verify"),
            StepStarted(
                "evt-9", RUN_ID, 9, EPOCH,
                stageIndex = 1, stepIndex = 0, stepName = "beta-one", stepType = "shell",
            ),
            StepFinished(
                "evt-10", RUN_ID, 10, EPOCH,
                stageIndex = 1, stepIndex = 0, stepName = "beta-one", stepType = "shell",
                outcome = "success",
            ),
            StageFinished("evt-11", RUN_ID, 11, EPOCH, stageIndex = 1, stageName = "verify", outcome = "success"),
            RunFinished("evt-12", RUN_ID, 12, EPOCH, outcome = "failure", diagnostics = emptyList()),
        )

        /** La operación de cada evento de la corrida, en el orden de los eventos. */
        fun ops(): List<OpId?> = InspectionSelection.operationIdPerEvent(events(), RUN_ID)

        /**
         * Las filas de los filtros, escritas a mano y no derivadas de los eventos: la decisión de
         * "qué filas conserva el pedido" no depende de "qué dice el evento", y una fila de la
         * tabla que dependiera de las dos no podría localizar un fallo en ninguna de las dos.
         *
         * Los ordinales se repiten a propósito: dos filas del mismo ordinal y dos filas del mismo
         * veredicto son lo que permite distinguir "filtró por este eje" de "devolvió las filas
         * que casualmente coinciden".
         */
        fun records(): List<InspectionRecord> = listOf(
            InspectionRecord(stage = "alpha-one", stepOrdinal = 1, outcome = "success"),
            InspectionRecord(stage = "alpha-two", stepOrdinal = 2, outcome = "failure"),
            InspectionRecord(stage = "alpha-two", stepOrdinal = 2, outcome = "success"),
            InspectionRecord(stage = "beta-one", stepOrdinal = 3, outcome = "failure"),
            InspectionRecord(stage = "beta-one", stepOrdinal = 3, outcome = "success"),
        )

        fun runFinished(outcome: String): DomainEvent =
            RunFinished("evt-r", RUN_ID, 99, EPOCH, outcome = outcome, diagnostics = emptyList())

        fun stepFailed(): DomainEvent =
            StepFailed(
                "evt-f", RUN_ID, 99, EPOCH,
                stageIndex = 0, stepIndex = 1, stepName = "alpha-two", stepType = "shell",
                failureKind = FailureKind.SCRIPT, message = "boom",
            )

        fun stepFinished(outcome: String?): DomainEvent =
            StepFinished(
                "evt-s", RUN_ID, 99, EPOCH,
                stageIndex = 0, stepIndex = 0, stepName = "alpha-one", stepType = "shell",
                outcome = outcome,
            )

        /** El veredicto que la fila de un evento dice. */
        fun outcomeOf(event: DomainEvent): String =
            InspectionSelection.toRecord(event, null, emptyMap()).outcome

        /** Convierte la tabla de decisiones en las filas dinámicas que JUnit ejecuta. */
        fun List<Case>.asDynamicTests(): List<DynamicTest> = map { case ->
            dynamicTest(case.label) { case.check() }
        }
    }
}
