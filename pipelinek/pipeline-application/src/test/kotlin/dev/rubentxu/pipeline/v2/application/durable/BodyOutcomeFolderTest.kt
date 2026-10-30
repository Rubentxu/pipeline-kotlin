package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CoreWorkflowControlInput
import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcomeFold
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * El fold de un scope de control: qué LE PASA al outcome del cuerpo.
 *
 * ## La garantía bajo prueba
 *
 * Un scope de control (`catchError`/`warnError`) no reescribe la ejecución: el cuerpo
 * corre una vez y el scope FOLDEA el outcome tipado que el cuerpo produjo. Esta fila
 * afirma la regla del fold como regla: una función pura, sin reloj, sin eventos, sin I/O.
 *
 * ## La capacidad que esta garantía sirve
 *
 * Quien declara un `catchError` decide qué pasa con el fallo que su cuerpo tuvo:
 *
 * - `buildResult = SUCCESS` — el fallo se absorbe y el scope termina limpio;
 * - `buildResult = UNSTABLE` — el fallo se absorbe y el scope deja el stage inestable;
 * - `buildResult = FAILURE` — el fallo NO se absorbe: el MISMO fallo sigue hacia fuera.
 *
 * Y quien declara un `warnError` obtiene una degradación del stage, la haya tenido el
 * cuerpo o no.
 *
 * ## El defecto que estas filas persiguen
 *
 * La semántica del scope vivía en tres sitios a la vez —la proyección del compilador, los
 * nodos-marcador que esa proyección emitía, y un recorrido sobre un overlay de contexto
 * que el stage hacía al recibir el fallo—, y en ninguno de ellos como una regla. Tres
 * autoridades sobre lo que significa un scope se separan en el primer caso que una de
 * ellas no cubre. Aquí hay UNA, y estas filas son su lectura completa.
 *
 * ## Por qué son filas y no una tabla
 *
 * Cada fila es una regla del fold. Las particiones de una misma regla (un cuerpo que
 * tuvo éxito y uno que quedó inestable: los dos pasan intactos) van en la MISMA fila,
 * porque no son dos garantías: son el mismo criterio con dos entradas. Y una mutación
 * focal que rompe el fold rompe la fila que afirma ese trozo, no todas.
 */
class BodyOutcomeFolderTest {

    /**
     * Un scope de contencion cuyo cuerpo NO fallo no dice nada.
     *
     * El cuerpo tuvo exito o quedo inestable: los dos outcomes pasan intactos, y el scope
     * no publica nada, porque no vio ningun fallo que anunciar. Es la regla que hace que
     * un `catchError` que no atrapa nada deje el run ESTABLE.
     */
    @Test
    fun `un scope de contencion cuyo cuerpo no fallo no publica nada`() {
        val exito = BodyOutcomeFolder.fold(
            fold = BodyOutcomeFold.CONTAINMENT,
            contract = contract(build = CatchErrorBuildResult.Failure),
            bodyOutcome = StepOutcome.Success,
        )
        assertEquals(
            StepOutcome.Success,
            exito.outcome,
            "el outcome del cuerpo pasa intacto: el scope no absorbe lo que no falló",
        )
        assertEquals(
            emptyList<BodyFoldEmission>(),
            exito.emissions,
            "un scope que no vio ningun fallo no publica nada: no hay nada que anunciar",
        )

        val inestable = BodyOutcomeFolder.fold(
            fold = BodyOutcomeFold.CONTAINMENT,
            contract = contract(build = CatchErrorBuildResult.Failure),
            bodyOutcome = StepOutcome.Unstable,
        )
        assertEquals(
            StepOutcome.Unstable,
            inestable.outcome,
            "un cuerpo inestable tampoco es un fallo: el scope lo deja ser inestable",
        )
        assertEquals(
            emptyList<BodyFoldEmission>(),
            inestable.emissions,
            "un cuerpo inestable no es un fallo capturado, asi que no se anuncia como tal",
        )
    }

    /**
     * El `buildResult` DECLARADO decide el outcome del scope.
     *
     * El fold no inventa un resultado: usa el que el autor declaro. Y publica el fallo
     * que vio con los MISMOS resultados declarados, que son los que viajan al evento
     * durable.
     */
    @Test
    fun `el buildResult declarado decide el outcome del scope`() {
        val absorbed = BodyOutcomeFolder.fold(
            fold = BodyOutcomeFold.CONTAINMENT,
            contract = contract(
                build = CatchErrorBuildResult.Success,
                stage = CatchErrorBuildResult.Success,
                message = "atrapado",
            ),
            bodyOutcome = failure(),
        )
        assertEquals(
            StepOutcome.Success,
            absorbed.outcome,
            "un buildResult SUCCESS absorbe el fallo y deja el scope limpio",
        )
        assertEquals(
            listOf(
                BodyFoldEmission.CaughtFailure(
                    buildResult = CatchErrorBuildResult.Success,
                    stageResult = CatchErrorBuildResult.Success,
                    message = "atrapado",
                ),
            ),
            absorbed.emissions,
            "el scope publica el fallo que vio con los resultados DECLARADOS: son la unica " +
                "fuente de verdad sobre lo que el scope hizo",
        )

        val degradado = BodyOutcomeFolder.fold(
            fold = BodyOutcomeFold.CONTAINMENT,
            contract = contract(
                build = CatchErrorBuildResult.Unstable,
                stage = CatchErrorBuildResult.Unstable,
                message = "atrapado",
            ),
            bodyOutcome = failure(),
        )
        assertEquals(
            StepOutcome.Unstable,
            degradado.outcome,
            "un buildResult UNSTABLE absorbe el fallo pero deja el scope inestable",
        )
        assertEquals(
            listOf(
                BodyFoldEmission.CaughtFailure(
                    buildResult = CatchErrorBuildResult.Unstable,
                    stageResult = CatchErrorBuildResult.Unstable,
                    message = "atrapado",
                ),
            ),
            degradado.emissions,
            "el fallo capturado se publica con los resultados declarados, no con el outcome " +
                "que el scope acaba tomando",
        )
    }

    /**
     * Un `buildResult` FAILURE no absorbe el fallo: el MISMO fallo sigue hacia fuera.
     *
     * No uno equivalente, ni uno reconstruido: la MISMA instancia. Quien recibe el fallo
     * mas arriba tiene que poder comparar identidad con el que el cuerpo produjo, y por
     * eso el fold no fabrica un fallo nuevo.
     */
    @Test
    fun `un buildResult FAILURE no absorbe el fallo y el mismo fallo sigue hacia fuera`() {
        val original = failure()
        val resultado = BodyOutcomeFolder.fold(
            fold = BodyOutcomeFold.CONTAINMENT,
            contract = contract(
                build = CatchErrorBuildResult.Failure,
                stage = CatchErrorBuildResult.Failure,
                message = "sin atrapar",
            ),
            bodyOutcome = original,
        )
        assertSame(
            original.failure,
            (resultado.outcome as StepOutcome.Failure).failure,
            "el fallo que no se absorbe sigue hacia fuera sin cambiar: su identidad es la " +
                "identidad del fallo que el cuerpo produjo",
        )
        assertEquals(
            listOf(
                BodyFoldEmission.CaughtFailure(
                    buildResult = CatchErrorBuildResult.Failure,
                    stageResult = CatchErrorBuildResult.Failure,
                    message = "sin atrapar",
                ),
            ),
            resultado.emissions,
            "el scope DECLARO no absorber: eso se publica, porque es lo que el autor pidio, " +
                "aunque el fallo siga su camino",
        )
    }

    /**
     * Una decoracion degrada su stage aunque el cuerpo haya tenido exito.
     *
     * El `warnError` no depende de lo que el cuerpo hiciera: su efecto es degradar el
     * stage. Por eso un `warnError` sobre un cuerpo limpio deja el stage inestable.
     */
    @Test
    fun `una decoracion degrada su stage aunque el cuerpo haya tenido exito`() {
        val resultado = BodyOutcomeFolder.fold(
            fold = BodyOutcomeFold.OUTPUT_DECORATION,
            contract = contract(
                fold = BodyOutcomeFold.OUTPUT_DECORATION,
                build = CatchErrorBuildResult.Unstable,
                stage = CatchErrorBuildResult.Unstable,
                message = "aviso",
            ),
            bodyOutcome = StepOutcome.Success,
        )
        assertEquals(
            StepOutcome.Unstable,
            resultado.outcome,
            "una decoracion siempre degrada su stage: por eso un warnError sobre un cuerpo " +
                "limpio deja el stage inestable",
        )
        assertEquals(
            listOf(BodyFoldEmission.StageDegraded("aviso")),
            resultado.emissions,
            "la decoracion publica el aviso que el autor declaro",
        )
    }

    /**
     * Una decoracion sobre un cuerpo fallido publica el fallo Y la degradacion, en ese orden.
     *
     * Dos emisiones, porque son dos hechos: el scope vio un fallo, y el scope degrada el
     * stage. El fallo se ABSORBE —una decoracion no relanza, y eso es lo que la separa de
     * una contencion— pero sigue siendo un hecho que el lane durable tiene que registrar.
     */
    @Test
    fun `una decoracion sobre un cuerpo fallido publica el fallo y la degradacion, en ese orden`() {
        val resultado = BodyOutcomeFolder.fold(
            fold = BodyOutcomeFold.OUTPUT_DECORATION,
            contract = contract(
                fold = BodyOutcomeFold.OUTPUT_DECORATION,
                build = CatchErrorBuildResult.Unstable,
                stage = CatchErrorBuildResult.Unstable,
                message = "aviso",
            ),
            bodyOutcome = failure(),
        )
        assertEquals(
            StepOutcome.Unstable,
            resultado.outcome,
            "una decoracion absorbe el fallo y degrada el stage: nunca relanza",
        )
        assertEquals(
            listOf(
                BodyFoldEmission.CaughtFailure(
                    buildResult = CatchErrorBuildResult.Unstable,
                    stageResult = CatchErrorBuildResult.Unstable,
                    message = "aviso",
                ),
                BodyFoldEmission.StageDegraded("aviso"),
            ),
            resultado.emissions,
            "el fallo que el scope vio se publica ANTES que la degradacion: primero lo que " +
                "ocurrio, despues lo que el scope hizo con ello",
        )
    }

    /**
     * Una decoracion sin mensaje declarado degrada con el texto vacio.
     *
     * El evento durable lleva un texto no nulable, asi que un scope que no declaro
     * mensaje publica la cadena vacia en vez de un `null` que el evento no puede
     * sostener. Es la propia ortografia del lane durable, y la que su lector ya espera.
     */
    @Test
    fun `una decoracion sin mensaje declarado degrada con el texto vacio`() {
        val resultado = BodyOutcomeFolder.fold(
            fold = BodyOutcomeFold.OUTPUT_DECORATION,
            contract = contract(
                fold = BodyOutcomeFold.OUTPUT_DECORATION,
                build = CatchErrorBuildResult.Unstable,
                stage = CatchErrorBuildResult.Unstable,
                message = null,
            ),
            bodyOutcome = StepOutcome.Success,
        )
        assertEquals(
            listOf(BodyFoldEmission.StageDegraded("")),
            resultado.emissions,
            "un scope que no declaro mensaje degrada con el texto vacio: el evento durable " +
                "lleva un texto no nulable y no hay nada que inventar",
        )
    }

    /**
     * El contrato declarado de un scope, con los resultados que el autor escribio.
     */
    private fun contract(
        fold: BodyOutcomeFold = BodyOutcomeFold.CONTAINMENT,
        build: CatchErrorBuildResult,
        stage: CatchErrorBuildResult = build,
        message: String? = null,
    ): CoreWorkflowControlInput = CoreWorkflowControlInput(
        fold = fold,
        buildResult = build,
        stageResult = stage,
        message = message,
        catchInterruptions = false,
    )

    /**
     * El fallo del cuerpo. Un valor, para que dos filas comparen el MISMO fallo.
     */
    private fun failure(): StepOutcome.Failure = StepOutcome.Failure(
        PipelineFailure(kind = FailureKind.SCRIPT, message = "el cuerpo fallo"),
    )
}
