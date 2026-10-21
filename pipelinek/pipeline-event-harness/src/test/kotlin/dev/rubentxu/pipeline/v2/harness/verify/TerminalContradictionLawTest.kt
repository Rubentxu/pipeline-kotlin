package dev.rubentxu.pipeline.v2.harness.verify

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.identity.EnvelopeProjector
import dev.rubentxu.pipeline.v2.harness.model.EventContract
import dev.rubentxu.pipeline.v2.harness.model.ExpectedRunOutcome
import dev.rubentxu.pipeline.v2.harness.model.VerificationResult
import dev.rubentxu.pipeline.v2.harness.model.ViolationRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * La contradicción terminal: un step no puede haber fallado y haber salido bien a la vez.
 *
 * ## La garantía
 * En un intento de un step, el plano no puede decir a la vez «este step falló» y «este step
 * salió bien». El fallo lleva la evidencia —el tipo y el mensaje—; el terminal lleva el
 * veredicto. Si los dos están para el mismo intento, el plano se contradice.
 *
 * ## Por qué hace falta (medido, no supuesto)
 * El `StepFailed` declaraba el step y no el stage. Sin el stage no había forma de saber **de qué
 * step** era el fallo, así que la ley no se podía correlacionar: el índice de step significa un
 * step distinto en cada stage del run, y comparar por índice comparaba steps distintos.
 *
 * ## Las tres correcciones que la ley necesita, y por qué
 * La ley se enfoca sobre el plano real del producto, medido con el binario, no sobre el plano
 * que uno se imagina:
 *
 *  - **la identidad de un step es (stage, índice, nombre)**: el stage porque el índice significa
 *    otra cosa en cada stage; el nombre porque un step anidado comparte el índice del step que
 *    lo contiene, y solo el nombre los distingue;
 *  - **un intento va de un `StepStarted` al siguiente `StepStarted` del mismo step**: un reintento
 *    vuelve a ejecutar el step y lo dice empezando de nuevo, así que el fallo del primer intento
 *    y el éxito del segundo son hechos de dos intentos y no una contradicción;
 *  - **el veredicto se lee del terminal**: el fallo no dice si el step salió bien, y preguntar
 *    «¿falló?» sobre el fallo sería preguntarlo sobre el evento equivocado.
 *
 * Los planos de este test son los que el producto produce de verdad: el del reintento, el del
 * `catchError` y el del fallo simple, medidos con el binario sobre un run real.
 */
class TerminalContradictionLawTest {

    private val runId = "run-under-law"

    @Test
    @DisplayName("un step que falló y salió bien en el mismo intento es una contradicción")
    fun `a step that failed and succeeded in the same attempt is a contradiction`() {
        val plane = listOf(
            stageStarted(2, 0),
            stepStarted(3, 0, 0, "ship"),
            stepFailed(4, 0, 0, "ship"),
            stepFinished(5, 0, 0, "ship", StepOutcome.SUCCESS_TOKEN),
        )

        val result = verify(plane, runOutcome = "success", expect = ExpectedRunOutcome.SUCCESS)

        assertEquals(ViolationRule.LAW_CONTRADICTORY_TERMINAL, onlyRule(result))
    }

    @Test
    @DisplayName("un reintento que acaba bien no es una contradicción: son dos intentos")
    fun `a retry that ends well is not a contradiction`() {
        // El plano medido: el primer intento falla, el segundo sale bien. Los dos intentos
        // comparten step, stage y nombre, y solo el segundo `StepStarted` los separa.
        val plane = listOf(
            stageStarted(2, 0),
            stepStarted(3, 0, 0, "flaky/retry-body-0/sh-0"),
            stepFailed(4, 0, 0, "flaky/retry-body-0/sh-0"),
            stepFinished(5, 0, 0, "flaky/retry-body-0/sh-0", StepOutcome.FAILURE_TOKEN),
            stepStarted(6, 0, 0, "flaky/retry-body-0/sh-0"),
            stepFinished(7, 0, 0, "flaky/retry-body-0/sh-0", StepOutcome.SUCCESS_TOKEN),
        )

        assertEquals(
            VerificationResult.Valid,
            verify(plane, runOutcome = "success", expect = ExpectedRunOutcome.SUCCESS),
        )
    }

    @Test
    @DisplayName("un fallo atrapado por catchError no es una contradicción")
    fun `a failure caught by catchError is not a contradiction`() {
        // El plano medido: el bloque que falla es un step propio, y el que sale bien es otro.
        val plane = listOf(
            stageStarted(2, 1),
            stepStarted(3, 1, 0, "swallowed/catch-error-enter-0"),
            stepFinished(4, 1, 0, "swallowed/catch-error-enter-0", StepOutcome.SUCCESS_TOKEN),
            stepStarted(5, 1, 1, "swallowed/catch-error-body-0"),
            stepFailed(6, 1, 1, "swallowed/catch-error-body-0"),
            stepFinished(7, 1, 1, "swallowed/catch-error-body-0", StepOutcome.FAILURE_TOKEN),
            stepStarted(8, 1, 2, "swallowed/catch-error-trigger-0"),
            stepFinished(9, 1, 2, "swallowed/catch-error-trigger-0", StepOutcome.SUCCESS_TOKEN),
        )

        assertEquals(
            VerificationResult.Valid,
            verify(plane, runOutcome = "success", expect = ExpectedRunOutcome.SUCCESS),
        )
    }

    @Test
    @DisplayName("un step que solo falla no es una contradicción")
    fun `a step that only fails is not a contradiction`() {
        val plane = listOf(
            stageStarted(2, 2),
            stepStarted(3, 2, 0, "plain/sh-0"),
            stepFailed(4, 2, 0, "plain/sh-0"),
            stepFinished(5, 2, 0, "plain/sh-0", StepOutcome.FAILURE_TOKEN),
        )

        assertEquals(
            VerificationResult.Valid,
            verify(plane, runOutcome = "failure", expect = ExpectedRunOutcome.FAILURE),
        )
    }

    @Test
    @DisplayName("el fallo de un step anidado no es el fallo de su contenedor")
    fun `the failure of a nested step is not the failure of its container`() {
        // Un step anidado comparte el índice del step que lo contiene: solo el nombre los
        // distingue. Sin el nombre, el fallo del anidado sería el fallo del contenedor, y un
        // contenedor que sale bien se leería como contradictorio.
        val plane = listOf(
            stageStarted(2, 0),
            stepStarted(3, 0, 0, "parent"),
            stepStarted(4, 0, 0, "parent/child"),
            stepFailed(5, 0, 0, "parent/child"),
            stepFinished(6, 0, 0, "parent", StepOutcome.SUCCESS_TOKEN),
        )

        assertEquals(
            VerificationResult.Valid,
            verify(plane, runOutcome = "success", expect = ExpectedRunOutcome.SUCCESS),
        )
    }

    @Test
    @DisplayName("el mismo nombre de step en dos stages son dos steps")
    fun `the same step name in two stages is two steps`() {
        // El índice de step significa un step distinto en cada stage, así que un step con el
        // mismo nombre en dos stages son dos steps: el fallo del stage 0 y el éxito del stage 1
        // no se contradicen. La identidad de un step lleva el stage para que no dependa del
        // orden: aquí los separa también el intento, y eso es lo que hace la ley robusta en
        // un plano donde las stages se solapan.
        val plane = listOf(
            stageStarted(2, 0),
            stepStarted(3, 0, 0, "ship"),
            stepFailed(4, 0, 0, "ship"),
            stageStarted(5, 1),
            stepStarted(6, 1, 0, "ship"),
            stepFinished(7, 1, 0, "ship", StepOutcome.SUCCESS_TOKEN),
        )

        assertEquals(
            VerificationResult.Valid,
            verify(plane, runOutcome = "failure", expect = ExpectedRunOutcome.FAILURE),
        )
    }

    @Test
    @DisplayName("un fallo que no dice su stage no se atribuye al stage 0")
    fun `a failure that names no stage is not attributed to stage 0`() {
        // El step del stage 0 sale bien. El fallo no dice su stage: un lector que lo atribuyera
        // al stage 0 leería una contradicción donde no la hay, y además ataría el fallo a un
        // stage al que nadie dijo que perteneciera. Un stage ausente NO es el stage 0.
        val plane = listOf(
            stageStarted(2, 0),
            stepStarted(3, 0, 0, "ship"),
            stepFinished(4, 0, 0, "ship", StepOutcome.SUCCESS_TOKEN),
            stepFailedWithoutStage(5, 0, "ship"),
        )

        assertEquals(
            VerificationResult.Valid,
            verify(plane, runOutcome = "success", expect = ExpectedRunOutcome.SUCCESS),
        )
    }

    // --- the fixture ----------------------------------------------------------------

    private fun verify(
        plane: List<TypedEvent>,
        runOutcome: String,
        expect: ExpectedRunOutcome,
    ): VerificationResult = EventHarness.verify(
        listOf(runStarted(1)) + plane + runFinished(99, runOutcome),
        EventContract(
            version = EventContract.CURRENT_VERSION,
            name = "contract-under-law",
            expect = expect,
            constraints = emptyList(),
        ),
    )

    private fun onlyRule(result: VerificationResult): ViolationRule {
        val violations = (result as? VerificationResult.Invalid)?.violations
            ?: error("expected a violation, got $result")
        assertEquals(1, violations.size, "expected one violation, got $violations")
        return violations.first().rule
    }

    private fun typed(event: DomainEvent): TypedEvent = TypedEvent(EnvelopeProjector.project(event), event)

    private fun runStarted(sequence: Long) = typed(
        RunStarted("e$sequence", runId, sequence, Instant.ofEpochMilli(sequence), "pipeline.kts"),
    )

    private fun stageStarted(sequence: Long, stage: Int) = typed(
        StageStarted("e$sequence", runId, sequence, Instant.ofEpochMilli(sequence), stage, "stage$stage"),
    )

    private fun stepStarted(sequence: Long, stage: Int, step: Int, name: String) = typed(
        StepStarted("e$sequence", runId, sequence, Instant.ofEpochMilli(sequence), stage, step, name, "sh"),
    )

    private fun stepFinished(sequence: Long, stage: Int, step: Int, name: String, outcome: String) = typed(
        StepFinished("e$sequence", runId, sequence, Instant.ofEpochMilli(sequence), stage, step, name, "sh", outcome),
    )

    private fun stepFailed(sequence: Long, stage: Int, step: Int, name: String) = typed(
        StepFailed(
            "e$sequence", runId, sequence, Instant.ofEpochMilli(sequence),
            stage, step, name, "sh", FailureKind.SCRIPT, "shell exited with code 1",
        ),
    )

    /** Un fallo de una fila que no dice su stage: una fila de un build anterior, o un productor que no lo sabe. */
    private fun stepFailedWithoutStage(sequence: Long, step: Int, name: String) = typed(
        StepFailed(
            "e$sequence", runId, sequence, Instant.ofEpochMilli(sequence),
            null, step, name, "sh", FailureKind.SCRIPT, "shell exited with code 1",
        ),
    )

    private fun runFinished(sequence: Long, outcome: String) = typed(
        RunFinished("e$sequence", runId, sequence, Instant.ofEpochMilli(sequence), outcome, emptyList()),
    )
}
