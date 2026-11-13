package dev.rubentxu.pipeline.v2.domain.post

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * El resultado del RUN, proyectado sobre el vocabulario del `post`.
 *
 * ## La garantía bajo prueba
 *
 * El `post` del PIPELINE se decide sobre el resultado del pipeline completo, y
 * ese resultado tiene cuatro palabras. La proyección es TOTAL: cada palabra
 * del run nombra exactamente un resultado del post, y ninguno se queda sin
 * nombre.
 *
 * ## Por qué `Aborted` NO es `Failed`
 *
 * Porque un run que fue DETENIDO no es un run que FALLÓ: no hubo un fallo que
 * nadie observara, y un bloque `failure` que se disparara para un run abortado
 * contaría una causa que no ocurrió. Es la misma razón por la que un stage
 * saltado no es un stage fallido.
 *
 * ## Lo que esta clase NO mide
 *
 * Qué bloques se escogen con cada resultado es cosa del planificador, y lo
 * mide su propia prueba. Aquí lo que se mide es la proyección: que cada
 * palabra del run tiene un sitio en el vocabulario del post.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RunOutcomePostOutcomeTest {

    /**
     * Cada palabra del run nombra un resultado del post.
     */
    @Test
    fun `cada palabra del run nombra un resultado del post`() {
        assertEquals(
            StageOutcome.Succeeded,
            RunOutcome.Success.postOutcome(),
            "un run que terminó bien es un post de éxito",
        )
        assertEquals(
            StageOutcome.Unstable,
            RunOutcome.Unstable.postOutcome(),
            "un run inestable es un post inestable, no un post fallido",
        )
        assertEquals(
            StageOutcome.Failed,
            RunOutcome.Failure(PipelineFailure(FailureKind.USER, "boom")).postOutcome(),
            "un run que falló es un post fallido",
        )
        assertEquals(
            StageOutcome.Aborted,
            RunOutcome.Aborted.postOutcome(),
            "un run que fue DETENIDO es un post abortado: no hubo un fallo que nadie observara",
        )
    }
}
