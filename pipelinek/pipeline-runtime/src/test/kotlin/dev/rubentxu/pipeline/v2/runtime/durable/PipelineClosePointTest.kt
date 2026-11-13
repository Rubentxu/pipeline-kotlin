package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.IdGenerator
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.PostSpec
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.post.PostCondition
import dev.rubentxu.pipeline.v2.domain.post.StageOutcome
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.PostConditionSelected
import dev.rubentxu.pipeline.v2.runtime.NoopPipelineTelemetry
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * El CIERRE DEL PIPELINE: el punto donde el motor ejecuta el `post` que declara
 * el pipeline, sobre el resultado del RUN completo.
 *
 * ## La garantía bajo prueba
 *
 * El motor tiene un cierre de pipeline, y el `post` del pipeline se ejecuta
 * ahí. El post del pipeline NO es un stage más: no es un stage del bucle, no
 * tiene su propio `StageStarted`, y su resultado no es el de un stage sino el
 * del run entero.
 *
 * ## Por qué la IDENTIDAD y no el efecto
 *
 * La identidad es lo que esta fila mide, y lo que la garantía de «una sola
 * vez» se apoya: el finalizador del pipeline se despacha con el índice UNO MÁS
 * ALLÁ del último stage, con el índice de paso del post de un stage y con el
 * body path que dice qué bloque es. Esa identidad es la MISMA en cada
 * intento, así que el segundo intento de un run se encuentra con la fila del
 * journal que el primero escribió — y el modelo durable (no esta clase)
 * decide si se sirve esa fila o se ejecuta el paso de nuevo.
 *
 * Lo que la fila NO mide es el efecto: que el `sh` del post no se ejecute dos
 * veces lo decide la tabla de reejecución del modelo durable, que ya está
 * fijada por su propia prueba.
 *
 * ## Por qué el motor y no el DSL
 *
 * El DSL sólo recoge lo que el autor escribió. Dónde corre el post, con qué
 * identidad y sobre qué resultado son decisiones del MOTOR, y una fila que
 * las midiera en el DSL mediría una copia.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PipelineClosePointTest {

    /**
     * El post del pipeline se ejecuta en el CIERRE, con la identidad del cierre.
     */
    @Test
    fun `el post del pipeline se ejecuta en el cierre con la identidad del cierre`() {
        val dispatched = mutableListOf<StepDispatchRequest>()
        val events = mutableListOf<DomainEvent>()
        val engine = engine(events, dispatched)

        val failure = runBlocking {
            engine.runPipelinePostBlock(
                post = PostSpec(conditions = mapOf(PostCondition.SUCCESS to listOf(echoNode()))),
                closeIndex = CLOSE_INDEX,
                pipelineName = "pipeline.kts",
                outcome = StageOutcome.Succeeded,
                runId = RUN_ID,
                shOptions = shOptions(),
                ambient = ExecutionContext.EMPTY,
            )
        }

        assertNull(
            failure,
            "el finalizador terminó bien, así que el cierre del pipeline no falla el run",
        )
        val request = dispatched.single()
        assertEquals(
            CLOSE_INDEX,
            request.stageIndex,
            "el cierre del pipeline va más allá del último stage: el índice es lo que impide " +
                "que sus finalizers compartan identidad con los de un stage",
        )
        assertEquals(
            StageExecutionEngine.POST_BASE_STEP_INDEX,
            request.stepIndex,
            "el finalizador del pipeline se despacha en la misma base de índices que el de un stage",
        )
        assertEquals(
            listOf(BlockSegment("post:SUCCESS:0")),
            request.bodyPath,
            "el body path dice qué bloque es, y es el mismo que usa el post de un stage",
        )
        val selected = events.filterIsInstance<PostConditionSelected>().single()
        assertEquals(
            CLOSE_INDEX,
            selected.stageIndex,
            "el evento de la selección lleva el índice del cierre, que es donde se escogió",
        )
        assertEquals(
            listOf("SUCCESS"),
            selected.selectedConditions,
            "la condición escogida es la que declara el post del pipeline",
        )
    }

    /**
     * Un pipeline que NO declara post no ejecuta ningún finalizador.
     *
     * La fila es la contrapartida de la anterior: el cierre del pipeline existe
     * siempre, y lo que cambia es si hay algo que ejecutar en él.
     */
    @Test
    fun `un pipeline que no declara post no ejecuta ningun finalizador`() {
        val dispatched = mutableListOf<StepDispatchRequest>()
        val events = mutableListOf<DomainEvent>()
        val engine = engine(events, dispatched)

        val failure = runBlocking {
            engine.runPipelinePostBlock(
                post = null,
                closeIndex = CLOSE_INDEX,
                pipelineName = "pipeline.kts",
                outcome = StageOutcome.Succeeded,
                runId = RUN_ID,
                shOptions = shOptions(),
                ambient = ExecutionContext.EMPTY,
            )
        }

        assertNull(failure, "no hay finalizador que pueda fallar")
        assertTrue(
            dispatched.isEmpty(),
            "un pipeline que no declara post no despacha nada en su cierre",
        )
        assertTrue(
            events.none { it is PostConditionSelected },
            "y no anuncia una selección de condiciones que nadie declaró",
        )
    }

    /**
     * La identidad del post del pipeline es la MISMA en cada intento.
     *
     * Es lo que hace posible la garantía de «una sola vez»: el segundo intento
     * de un run despacha los mismos finalizers con la misma identidad, así que
     * el journal del primero es lo que el segundo se encuentra.
     */
    @Test
    fun `la identidad del post del pipeline es la misma en cada intento`() {
        val first = mutableListOf<StepDispatchRequest>()
        val second = mutableListOf<StepDispatchRequest>()

        runBlocking {
            engine(mutableListOf(), first).runPipelinePostBlock(
                post = PostSpec(conditions = mapOf(PostCondition.SUCCESS to listOf(echoNode()))),
                closeIndex = CLOSE_INDEX,
                pipelineName = "pipeline.kts",
                outcome = StageOutcome.Succeeded,
                runId = RUN_ID,
                shOptions = shOptions(),
                ambient = ExecutionContext.EMPTY,
            )
            engine(mutableListOf(), second).runPipelinePostBlock(
                post = PostSpec(conditions = mapOf(PostCondition.SUCCESS to listOf(echoNode()))),
                closeIndex = CLOSE_INDEX,
                pipelineName = "pipeline.kts",
                outcome = StageOutcome.Succeeded,
                runId = RUN_ID,
                shOptions = shOptions(),
                ambient = ExecutionContext.EMPTY,
            )
        }

        assertEquals(
            identityOf(first.single()),
            identityOf(second.single()),
            "el segundo intento despacha el mismo finalizador con la misma identidad: " +
                "es lo que permite que el journal lo sirva en vez de ejecutarlo otra vez",
        )
    }

    /**
     * La identidad del finalizador: lo que la hace falta para reconocerlo.
     */
    private fun identityOf(request: StepDispatchRequest): String =
        "${request.runId.value}/s${request.stageIndex}/p${request.stepIndex}" +
            request.bodyPath.joinToString("/") { it.encoded }

    /**
     * El motor, con un despachador que RECUERDA lo que se le despacha y un sink
     * que recuerda lo que se publica.
     */
    private fun engine(
        events: MutableList<DomainEvent>,
        dispatched: MutableList<StepDispatchRequest>,
    ): StageExecutionEngine {
        val sink = object : EventSink {
            override fun append(event: DomainEvent) {
                events += event
            }

            override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
        }
        var counter = 0
        val ids = IdGenerator { "id-${counter++}" }
        val lifecycle = RunLifecycleEngine(
            eventSink = sink,
            telemetry = NoopPipelineTelemetry,
            clock = FixedClock,
            idGenerator = ids,
        )
        return StageExecutionEngine(
            stepDispatch = StepDispatcher { request ->
                dispatched += request
                DispatchedStep(
                    result = CommonExecutionResult(outcome = StepOutcome.Success),
                    context = ExecutionContext.EMPTY,
                )
            },
            runLifecycle = lifecycle,
            eventSink = sink,
            clock = FixedClock,
            idGenerator = ids,
        )
    }

    /**
     * Un paso cualquiera: al cierre lo que se mira es DÓNDE se despacha, no
     * qué dice el paso.
     */
    private fun echoNode(): OpaqueStepNode = OpaqueStepNode(
        id = StepId("pipeline-post-SUCCESS/echo-0"),
        pluginStepId = PluginStepId("core.echo"),
        payload = VersionedStepPayload(schemaVersion = "dsl-v1", encoded = "\"hello\""),
    )

    private fun shOptions(): ShOptions = ShOptions(
        workspaceRoot = Path.of("/tmp/pipeline-close-point"),
        captureStdout = false,
        timeoutMs = null,
        env = emptyMap(),
    )

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        /** El índice del cierre: uno más allá del último stage. */
        const val CLOSE_INDEX = 2

        /** El run al que pertenecen los despachos. */
        val RUN_ID = RunId("run-1")
    }
}
