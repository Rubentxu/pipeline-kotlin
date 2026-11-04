package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.SequentialIdGenerator
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PipelineStepException
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Un fallo de paso conserva SU kind a lo largo de toda la cadena de ejecución.
 *
 * ## La garantía
 * Cuando un handler falla de una forma TIPADA —el usuario rechazó el paso, la red
 * no llegó, el plugin no pudo cumplir su contrato— el `FailureKind` que el fallo
 * transporta es el que se publica y el que seournala. Ni el motor ni el evento lo
 * reescriben.
 *
 * ## Por qué hace falta (medido, no supuesto)
 * El límite del registro envolvía la llamada al handler en un `catch (e: Exception)`
 * que labelledaba TODO como `FailureKind.ENGINE`. Ese `catch` se comía la
 * `PipelineStepException` —que ya transportaba el kind correcto en su campo
 * `failure`— antes de que la capa de lifecycle llegara a verla. La capa de
 * lifecycle SÍ preservaba el kind; el interior lo había borrado ya.
 *
 * El efecto no era cosmético: `ENGINE` dice "el motor falló", y un usuario que ve
 * eso en su paso no puede distinguir "mi paso falló" de "el producto está roto".
 * La clasificación es lo que separa un error del usuario de un defecto del motor.
 *
 * ## Lo que este test mira
 * Mira la cadena COMPLETA, no una capa suelta: la ejecución del registro y luego
 * el límite de lifecycle que emite los eventos. Un test que mirara solo la capa
 * interior vería el kind ya perdido, y uno que mirara solo la exterior pasaría
 * hoy sin ver nada — el defecto estaba exactamente en el encuentro de las dos.
 */
class StepFailureKindPreservationTest {

    /**
     * El sink que graba lo que se publica. En memoria y sin disco: la garantía
     * es sobre lo que se publica, no sobre dónde se guarda.
     */
    private class RecordingSink : EventSink {
        val events: MutableList<DomainEvent> = mutableListOf()

        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
    }

    private fun context() = StepLifecycleContext(
        runId = RUN_ID,
        stageIndex = 2,
        stepIndex = 0,
        stepName = "probe",
        stepType = STEP_ID,
    )

    /**
     * Un handler que falla con un [PipelineStepException] de un kind dado.
     *
     * El fallo se lanza, no se devuelve: es la forma en que un handler de
     * production comunica un fallo tipado, y la forma que el `catch` interior
     * interceptaba.
     */
    private fun failingHandler(kind: FailureKind): StepHandler<String, String> = StepHandler { _, _ ->
        throw PipelineStepException(PipelineFailure(kind, "the step refused"))
    }

    /** Un handler que revienta sin clasificar el fallo. */
    private fun explodingHandler(): StepHandler<String, String> = StepHandler { _, _ ->
        throw IllegalStateException("the handler blew up")
    }

    /** La definición del paso que va a fallar. */
    private fun definition(handler: StepHandler<String, String>): StepDefinition<String, String> =
        object : StepDefinition<String, String> {
            override val contract: StepContract<String, String> = StepContract(
                key = PluginStepId(STEP_ID),
                descriptor = StepDescriptor(stepId = STEP_ID, name = "probe", configRef = "probe"),
                inputCodec = StringCodec,
                outputCodec = StringCodec,
            )

            override val handler: StepHandler<String, String> = handler
        }

    /** La ejecución preparada del paso que va a fallar. */
    private fun prepared(handler: StepHandler<String, String>): PreparedRegistryExecution =
        PreparedRegistryExecution(
            key = PluginStepId(STEP_ID),
            definition = definition(handler),
            decodedInput = "value",
        )

    /** El contexto de runtime, apuntando a rutas que no existen. */
    private fun runtime(): RuntimeContext = RuntimeContext(
        opId = OpId(RUN_ID, 0, 0),
        runId = RUN_ID,
        stageName = "stage-0",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = ShOptions(
            workspaceRoot = Path.of("/nonexistent-workspace"),
            captureStdout = false,
            timeoutMs = null,
            env = emptyMap(),
        ),
        controlDirRoot = Path.of("/nonexistent-control-root"),
        eventSink = NullEventSink,
        clock = FixedClock,
        idGenerator = SequentialIdGenerator(),
    )

    /**
     * Ejecuta el paso por la cadena completa y devuelve el outcome que el
     * consumidor recibe.
     */
    private suspend fun outcomeThroughTheWholeChain(kind: FailureKind): StepOutcome {
        val boundary = RegistryExecutionBoundary(CapabilityBindings())
        val result = StepExecutionBoundary(RecordingSink(), clock = FixedClock, idGenerator = SequentialIdGenerator()).execute(context()) {
            boundary.execute(prepared(failingHandler(kind)), runtime())
        }
        return result.outcome
    }

    @Test
    @DisplayName("un fallo de usuario conserva su kind a lo largo de toda la cadena")
    fun `a user failure keeps its kind through the whole chain`() = runBlocking {
        val outcome = outcomeThroughTheWholeChain(FailureKind.USER)

        assertEquals(
            FailureKind.USER,
            (outcome as StepOutcome.Failure).failure.kind,
            "el kind del fallo debe llegar intacto al consumidor",
        )
    }

    @Test
    @DisplayName("un fallo de red conserva su kind a lo largo de toda la cadena")
    fun `a network failure keeps its kind through the whole chain`() = runBlocking {
        val outcome = outcomeThroughTheWholeChain(FailureKind.NETWORK)

        assertEquals(
            FailureKind.NETWORK,
            (outcome as StepOutcome.Failure).failure.kind,
            "el kind del fallo debe llegar intacto al consumidor",
        )
    }

    @Test
    @DisplayName("un fallo de plugin conserva su kind a lo largo de toda la cadena")
    fun `a plugin failure keeps its kind through the whole chain`() = runBlocking {
        val outcome = outcomeThroughTheWholeChain(FailureKind.PLUGIN)

        assertEquals(
            FailureKind.PLUGIN,
            (outcome as StepOutcome.Failure).failure.kind,
            "el kind del fallo debe llegar intacto al consumidor",
        )
    }

    @Test
    @DisplayName("un fallo de tiempo conserva su kind a lo largo de toda la cadena")
    fun `a timeout failure keeps its kind through the whole chain`() = runBlocking {
        val outcome = outcomeThroughTheWholeChain(FailureKind.TIMEOUT)

        assertEquals(
            FailureKind.TIMEOUT,
            (outcome as StepOutcome.Failure).failure.kind,
            "el kind del fallo debe llegar intacto al consumidor",
        )
    }

    @Test
    @DisplayName("el evento de fallo publica el kind del fallo, no ENGINE")
    fun `the failure event publishes the failure's own kind`() = runBlocking {
        val sink = RecordingSink()
        val boundary = RegistryExecutionBoundary(CapabilityBindings())

        StepExecutionBoundary(sink, clock = FixedClock, idGenerator = SequentialIdGenerator()).execute(context()) {
            boundary.execute(prepared(failingHandler(FailureKind.USER)), runtime())
        }

        val failed = sink.events.filterIsInstance<StepFailed>().last()
        assertEquals(
            FailureKind.USER,
            failed.failureKind,
            "el evento de fallo debe llevar el kind del fallo",
        )
    }

    @Test
    @DisplayName("un fallo que NO es de paso sigue siendo ENGINE")
    fun `a failure that is not a step failure stays ENGINE`() = runBlocking {
        val boundary = RegistryExecutionBoundary(CapabilityBindings())

        val result = StepExecutionBoundary(RecordingSink(), clock = FixedClock, idGenerator = SequentialIdGenerator()).execute(context()) {
            boundary.execute(prepared(explodingHandler()), runtime())
        }

        assertEquals(
            FailureKind.ENGINE,
            (result.outcome as StepOutcome.Failure).failure.kind,
            "una avería del motor es ENGINE y debe seguir siéndolo",
        )
    }

    @Test
    @DisplayName("el límite del registro no relabela un fallo tipado: lo deja subir")
    fun `the registry boundary does not relabel a typed failure`() = runBlocking {
        val boundary = RegistryExecutionBoundary(CapabilityBindings())

        val thrown = runCatching {
            boundary.execute(prepared(failingHandler(FailureKind.USER)), runtime())
        }.exceptionOrNull()

        // El fallo tipado conserva su propia clasificación al llegar al límite de lifecycle.
        // Si el límite del registro lo convirtiese en ENGINE, el kind se habría perdido aquí,
        // antes de que nadie publicara nada.
        val stepFailure = thrown as? PipelineStepException
        assertSame(PipelineStepException::class.java, stepFailure?.let { it::class.java })
        assertEquals(
            FailureKind.USER,
            stepFailure?.failure?.kind,
            "el fallo tipado debe conservar su kind al salir del límite del registro",
        )
    }

    /** Un codec que transporta una cadena, y nada más. */
    private object StringCodec : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)

        override fun decode(encoded: EncodedStepValue): String = encoded.value
    }

    private companion object {
        const val STEP_ID = "probe"
        const val RUN_ID = "run-1"
    }
    /** A clock that does not move, so nothing in these rows depends on the wall clock. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(2_000L)
    }
}
