package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata
import dev.rubentxu.pipeline.v2.events.AT
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import dev.rubentxu.pipeline.v2.events.RUN_ID
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.TimeoutScheduled
import dev.rubentxu.pipeline.v2.events.vocabulario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Proyeccion de un evento de dominio a su sobre de identidad.
 *
 * La tabla de sujetos es una `when` exhaustiva sobre la familia cerrada de
 * eventos: anadir un evento sin decidir su sujeto rompe la compilacion, y este
 * test fija la decision para las 70 ramas. La ley congelada de EVT-1 es que el
 * sujeto es la identidad MAS FINA que el evento lleva de forma estable: con
 * `stageIndex` y `stepIndex` es un STEP, con solo el indice de etapa (o el de la
 * etapa padre de una rama paralela) es un STAGE, y el ciclo de vida de la
 * ejecucion es la RUN.
 *
 * El resto del sobre es proyeccion pura: version, referencia del evento, kind,
 * instante y secuencia salen del evento, y `causation`/`correlation` no se inventan
 * porque el evento no trae esa informacion.
 */
class EnvelopeProjectorTest {

    /**
     * Eventos cuyo sujeto es un STEP segun la tabla de la proyeccion.
     *
     * `TimeoutScheduled` entra aqui porque la instancia del fixture trae los dos
     * indices; el caso sin indices, que cae a la RUN, tiene su propio test. La
     * proyeccion es condicional en ese evento y la lista tiene que reflejarlo.
     */
    private val conSujetoStep = setOf(
        "StepStarted", "StepFinished", "RetryAttemptStarted", "RetryAttemptFinished",
        "StepAdmissionObserved", "TimeoutScheduled",
    )

    /** Eventos cuyo sujeto es un STAGE segun la tabla de la proyeccion. */
    private val conSujetoStage = setOf(
        "StageStarted", "StageSkipped", "StageFinished", "PostConditionSelected",
        "ParallelBranchStarted", "ParallelBranchFinished",
    )

    /**
     * Eventos cuya procedencia se decide a partir de una clave de paso registrada.
     *
     * No es el mismo conjunto que los de sujeto STEP: lo que decide la procedencia
     * es si el evento nombra un tipo de paso, no si su sujeto es un STEP.
     */
    private val conClaveDePaso = setOf(
        "StepStarted", "StepFinished", "StepFailed", "RetryAttemptStarted", "RetryAttemptFinished",
        "StepAdmissionObserved", "TimeoutScheduled",
    )

    @Test
    fun `cada evento se proyecta al sujeto que le corresponde en la tabla`() {
        vocabulario().forEach { evento ->
            val env = EnvelopeProjector.project(evento)
            val esperado = when (evento.kind) {
                in conSujetoStep -> ResourceKind.STEP
                in conSujetoStage -> ResourceKind.STAGE
                else -> ResourceKind.RUN
            }
            assertEquals(
                esperado,
                env.subject.kind,
                "sujeto inesperado para ${evento.kind}: ${env.subject.canonicalText()}",
            )
        }
    }

    @Test
    fun `el sujeto STEP lleva la identidad de etapa y paso del evento`() {
        val env = EnvelopeProjector.project(
            StepStarted(
                eventId = "e1", runId = RUN_ID, sequence = 1L, occurredAt = AT,
                stageIndex = 2, stepIndex = 5, stepName = "compila", stepType = "core.sh",
            ),
        )

        assertEquals(ResourceRefs.step(RUN_ID, 2, 5), env.subject)
        assertEquals("v1:step:pipeline/run/$RUN_ID/stage/2/step/5", env.subject.canonicalText())
    }

    @Test
    fun `el sujeto STAGE de una rama paralela es la etapa padre`() {
        val env = EnvelopeProjector.project(
            ParallelBranchStarted(
                eventId = "e2", runId = RUN_ID, sequence = 2L, occurredAt = AT,
                branchIndex = 0, branchName = "rama", parentStageIndex = 3,
            ),
        )

        assertEquals(
            ResourceRefs.stage(RUN_ID, 3),
            env.subject,
            "la rama se ancla a su etapa padre, no a si misma",
        )
    }

    @Test
    fun `un TimeoutScheduled con indices se ancla al paso y sin indices a la ejecucion`() {
        val conIndices = EnvelopeProjector.project(
            TimeoutScheduled(
                eventId = "e3", runId = RUN_ID, sequence = 3L, occurredAt = AT,
                timeoutSeconds = 60L, timeoutAction = "ABORT", stepName = "compila", stepType = "core.sh",
                stageIndex = 1, stepIndex = 4,
            ),
        )
        val sinIndices = EnvelopeProjector.project(
            TimeoutScheduled(
                eventId = "e4", runId = RUN_ID, sequence = 4L, occurredAt = AT,
                timeoutSeconds = 60L, timeoutAction = "ABORT", stepName = null, stepType = null,
                stageIndex = null, stepIndex = null,
            ),
        )

        assertEquals(ResourceRefs.step(RUN_ID, 1, 4), conIndices.subject)
        assertEquals(
            ResourceRefs.run(RUN_ID),
            sinIndices.subject,
            "sin indices no hay identidad de paso que afirmar",
        )
    }

    @Test
    fun `el resto del sobre es proyeccion del evento y nada mas`() {
        val env = EnvelopeProjector.project(
            RunStarted(
                eventId = "e5", runId = RUN_ID, sequence = 77L, occurredAt = AT, scriptPath = "/ws/x.pipeline.kts",
            ),
        )

        assertEquals(PipelineEventEnvelope.VERSION, env.version)
        assertEquals(ResourceRefs.run(RUN_ID), env.eventRef.source)
        assertEquals("e5", env.eventRef.id.value)
        assertEquals("RunStarted", env.kind)
        assertEquals(AT, env.occurredAt)
        assertEquals(77L, env.sequence, "la secuencia se proyecta, no se reasigna")
        assertNull(env.causation, "un evento de dominio no trae causalidad propia")
        assertNull(env.correlation)
    }

    @Test
    fun `la proyeccion es determinista y la via heredada coincide con la costura nula`() {
        vocabulario().forEach { evento ->
            val directa = EnvelopeProjector.project(evento)
            assertEquals(directa, EnvelopeProjector.project(evento, null), "project(event) es project(event, null)")
            assertEquals(directa, EnvelopeProjector.project(evento), "proyectar dos veces da el mismo sobre")
        }
    }

    @Test
    fun `la costura de proveedor solo proyecta los eventos que emiten un Step`() {
        val metadata = metadataDePrueba()
        val conProveniencia = vocabulario().associate { evento ->
            evento.kind to EnvelopeProjector.project(evento) { metadata }.provenance
        }

        conClaveDePaso.forEach { kind ->
            assertEquals(
                metadata.publisher,
                conProveniencia[kind]?.pluginPublisher,
                "un evento emitido por un Step registrado debe llevar procedencia ($kind)",
            )
        }
        assertEquals(
            emptySet<String>(),
            conProveniencia.filterKeys { it !in conClaveDePaso }
                .values.filterNotNull().map { it.pluginPublisher }.toSet(),
            "un evento que no emite ningun Step no puede llevar procedencia",
        )
    }

    @Test
    fun `la costura consulta el tipo de paso del evento y no su nombre de instancia`() {
        val consultadas = mutableListOf<String>()
        val env = EnvelopeProjector.project(
            StepFailed(
                eventId = "e6", runId = RUN_ID, sequence = 6L, occurredAt = AT,
                stepIndex = 3, stepName = "compila", stepType = "core.sh",
                failureKind = FailureKind.SCRIPT, message = "boom",
            ),
        ) { clave: PluginStepId ->
            consultadas.add(clave.value)
            metadataDePrueba()
        }

        assertEquals(listOf("core.sh"), consultadas, "la clave consultada es el tipo de paso, no stepName")
        assertEquals("acme", env.provenance?.pluginPublisher)
    }

    @Test
    fun `una costura que no resuelve la clave deja la procedencia nula`() {
        val env = EnvelopeProjector.project(
            StepStarted(
                eventId = "e7", runId = RUN_ID, sequence = 7L, occurredAt = AT,
                stageIndex = 0, stepIndex = 1, stepName = "compila", stepType = "desconocido",
            ),
        ) { null }

        assertNull(env.provenance, "una clave sin metadata registrada no puede proyectar procedencia")
    }

    @Test
    fun `un TimeoutScheduled sin stepType no puede proyectar procedencia`() {
        val conTipo = EnvelopeProjector.project(
            TimeoutScheduled(
                eventId = "e8", runId = RUN_ID, sequence = 8L, occurredAt = AT,
                timeoutSeconds = 1L, timeoutAction = "ABORT", stepName = "compila", stepType = "core.sh",
                stageIndex = 1, stepIndex = 1,
            ),
        ) { metadataDePrueba() }
        val sinTipo = EnvelopeProjector.project(
            TimeoutScheduled(
                eventId = "e9", runId = RUN_ID, sequence = 9L, occurredAt = AT,
                timeoutSeconds = 1L, timeoutAction = "ABORT", stepName = "compila", stepType = null,
                stageIndex = 1, stepIndex = 1,
            ),
        ) { metadataDePrueba() }

        assertEquals("acme", conTipo.provenance?.pluginPublisher)
        assertNull(sinTipo.provenance, "sin stepType no hay clave de registro que consultar")
    }

    @Test
    fun `una identidad de plugin corta proyecta namespace y deja la identidad vacia`() {
        // `ResourceRefs.plugin` produce tres segmentos. Una referencia de plugin con
        // menos no puede nombrar una identidad, y la proyeccion dice "" en vez de
        // adivinar un segmento que no existe.
        val corto = ResourceRef(ResourceKind.PLUGIN, listOf("mi.ns"))
        val env = EnvelopeProjector.project(
            StepStarted(
                eventId = "e10", runId = RUN_ID, sequence = 10L, occurredAt = AT,
                stageIndex = 0, stepIndex = 1, stepName = "compila", stepType = "core.sh",
            ),
        ) {
            StepProviderMetadata.create(
                plugin = corto,
                release = PluginReleaseRef(plugin = corto, version = SemVer(1, 0, 0), digest = Digest(DIGEST)),
                publisher = "acme",
                families = setOf(PluginFamily.UTILITIES),
                delivery = Delivery.OFFICIAL_PLUGIN,
                trust = TrustMetadata.Unverified,
            )
        }

        assertEquals("mi.ns", env.provenance?.pluginNamespace)
        assertEquals(
            "",
            env.provenance?.pluginIdentity,
            "sin el tercer segmento no hay identidad que proyectar",
        )
    }

    @Test
    fun `la procedencia ordena las familias para que el sobre sea determinista`() {
        val env = EnvelopeProjector.project(
            StepStarted(
                eventId = "e11", runId = RUN_ID, sequence = 11L, occurredAt = AT,
                stageIndex = 0, stepIndex = 1, stepName = "compila", stepType = "core.sh",
            ),
        ) { metadataDePrueba() }

        assertEquals(
            listOf("NETWORK", "SCM"),
            env.provenance?.families?.toList(),
            "families es un conjunto ordenado: el sobre no puede depender del orden de entrada",
        )
    }

    @Test
    fun `el fallo de proyeccion es tipado y conserva el diagnostico`() {
        val fallo = UnprojectableEventException("sin regla de derivacion")

        assertEquals("sin regla de derivacion", fallo.message, "el mensaje nombra el evento sin identidad")
        assertEquals(
            IllegalStateException::class.java,
            fallo.javaClass.superclass,
            "el contrato declarado es IllegalStateException: fallar cerrado, no degradar la identidad",
        )
    }

    private companion object {
        private const val DIGEST =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        private fun metadataDePrueba(): StepProviderMetadata {
            val plugin = ResourceRefs.plugin("pipeline.scm-git", "scm-git")
            return StepProviderMetadata.create(
                plugin = plugin,
                release = PluginReleaseRef(plugin = plugin, version = SemVer(0, 36, 0), digest = Digest(DIGEST)),
                publisher = "acme",
                families = setOf(PluginFamily.SCM, PluginFamily.NETWORK),
                delivery = Delivery.OFFICIAL_PLUGIN,
                trust = TrustMetadata.Unverified,
            )
        }
    }
}
