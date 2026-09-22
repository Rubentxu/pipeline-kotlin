package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.BodyExecution
import dev.rubentxu.pipeline.v2.domain.BodyInvocationPolicy
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepBody
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * WU-RP-041 / S1 — paridad de cancelación por deadline para hijos de cuerpo de un
 * Step EXTERNO declarado (cierra la deuda #1 del RP-3 EXIT REVIEW).
 *
 * Un cuerpo externo (`StepBody.Declared`, política `Sequential`, propietario
 * CANONICAL_ENGINE) que contiene un bloque `core.timeout` con un hijo `core.sh`
 * dormido DEBE:
 *  - recibir el deadline proyectado por el MISMO camino canónico que los bloques
 *    core (`decodeDeadline` -> `BlockShellScope.Timeout` -> `TimeoutScheduled`
 *    -> childShOptions.timeoutMs -> watchdog del DurableShellExecutor),
 *  - terminar con `RunOutcome.Failure(FailureKind.TIMEOUT)` dentro del presupuesto,
 *  - dejar el estado durable del hijo como FAILED_TIMEOUT (kill, no shell huérfana),
 *  - y completar normalmente cuando el cuerpo termina antes del deadline.
 *
 * Sin ramificación concreta: el coordinator no sabe nada de `test.upperblock`;
 * sólo lee la política declarada del descriptor vía el resolver compuesto.
 */
@Timeout(120)
class ExternalBodyDeadlineCancellationProofTest {

    private val upperBlockKey = PluginStepId("test.upperblock")

    data class UpperBlockInput(val prefix: String)

    private val inputCodec = object : StepCodec<UpperBlockInput> {
        override fun encode(value: UpperBlockInput): EncodedStepValue =
            EncodedStepValue("""{"prefix":"${value.prefix}"}""")
        override fun decode(encoded: EncodedStepValue): UpperBlockInput {
            val m = Regex("\"prefix\"\\s*:\\s*\"([^\"]*)\"").find(encoded.value)
                ?: throw IllegalArgumentException("invalid upperblock payload")
            return UpperBlockInput(m.groupValues[1])
        }
    }

    private val outputCodec = object : StepCodec<String> {
        private val json = Json
        override fun encode(value: String): EncodedStepValue =
            EncodedStepValue(json.encodeToString(String.serializer(), value))
        override fun decode(encoded: EncodedStepValue): String =
            json.decodeFromString(String.serializer(), encoded.value)
    }

    private val definition = object : StepDefinition<UpperBlockInput, String> {
        override val contract = StepContract(
            key = upperBlockKey,
            descriptor = StepDescriptor(
                stepId = upperBlockKey.value,
                name = "upperblock",
                configRef = "",
                pluginId = "test.upperblock",
                pluginVersion = "0.1.0",
                executionLocation = ExecutionLocation.CONTROLLER,
                effects = listOf(Effect.READ_ONLY),
                replayPolicy = ReplayPolicy.MEMOIZED,
                body = StepBody.Declared(
                    invocation = BodyInvocationPolicy.ONCE,
                    execution = BodyExecution(
                        owner = BodyExecutionOwner.CANONICAL_ENGINE,
                        policy = BodyExecutionPolicy.Sequential,
                    ),
                    introduces = null,
                ),
            ),
            inputCodec = inputCodec,
            outputCodec = outputCodec,
            requiredCapabilities = emptySet(),
        )
        override val handler = StepHandler<UpperBlockInput, String> { input, _ -> input.prefix }
    }

    private fun registry() = InMemoryStepRegistry().apply {
        register(definition)
        CoreEchoStep.registerInto(this)
        CoreShellStep.registerInto(this)
    }

    private fun harness(tempDir: java.nio.file.Path): Triple<CanonicalDurableRunCoordinator, InMemoryOperationJournal, InMemoryEventStore> {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val events = InMemoryEventStore()
        val coord = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = events,
            credentialScopePort = { _, _ ->
                CredentialScopeOutcome.Unavailable(
                    CredentialScopeFailure.StoreUnavailable("deadline-proof stub"),
                )
            },
            controlDirRoot = tempDir.resolve("control"),
            shOptions = ShOptions(tempDir.resolve("workspace"), false, null, emptyMap()),
            stepRegistry = registry(),
        )
        return Triple(coord, journal, events)
    }

    private fun stage(name: String, vararg nodes: dev.rubentxu.pipeline.v2.domain.StepNode) = CompiledPipeline(
        id = DefinitionId("upperblock-deadline-proof"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId(name),
                name = name,
                body = StageBody.Steps(nodes.toList()),
            ),
        ),
    )

    private fun externalBlock(vararg children: dev.rubentxu.pipeline.v2.domain.StepNode) = BlockStepNode(
        id = StepId("proof/upperblock-body-1"),
        pluginStepId = upperBlockKey,
        payload = VersionedStepPayload("dsl-v1", inputCodec.encode(UpperBlockInput("p")).value),
        body = children.toList(),
    )

    /** `core.timeout(seconds)` node — canonical payload contract (`decodeDeadline`). */
    private fun timeoutBlock(seconds: Long, vararg children: dev.rubentxu.pipeline.v2.domain.StepNode) =
        BlockStepNode(
            id = StepId("proof/upperblock-body-1/timeout-1"),
            pluginStepId = PluginStepId("core.timeout"),
            payload = VersionedStepPayload("dsl-v1", """{"kind":"timeout","seconds":"$seconds"}"""),
            body = children.toList(),
        )

    private fun shChild(id: String, script: String) = OpaqueStepNode(
        id = StepId("proof/upperblock-body-1/$id"),
        pluginStepId = PluginStepId("core.sh"),
        payload = VersionedStepPayload("dsl-v1", """{"kind":"sh","command":"${script.replace("\"", "\\\"")}","isScriptBlock":false,"returnStdout":false}"""),
    )

    @Test
    fun `deadline expires - external body child is cancelled through the canonical path within budget`(
        @TempDir tempDir: java.nio.file.Path,
    ) = runBlocking {
        val marker = tempDir.resolve("started.txt")
        val (coord, journal, events) = harness(tempDir)
        val pipeline = stage(
            "B",
            externalBlock(
                timeoutBlock(
                    1L,
                    shChild("sleep-1", "echo started > '$marker' && sleep 30"),
                ),
            ),
        )

        val started = System.nanoTime()
        val outcome = coord.run(pipeline, RunId("wb-deadline-expired"))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // Typed terminal: TIMEOUT failure, never a hang nor an infrastructure error.
        assertTrue(outcome is RunOutcome.Failure, "expected Failure, got $outcome")
        assertEquals(FailureKind.TIMEOUT, (outcome as RunOutcome.Failure).failure.kind)

        // Bounded: the watchdog killed the child; the run returned well under sleep(30)s.
        assertTrue(elapsedMs < 25_000, "run must not hang on the killed child; took ${elapsedMs}ms")

        // Cancellation happened through the canonical scheduling transition:
        // TimeoutScheduled BEFORE any child StepStarted of the sleep child.
        val runEvents = events.eventsFor("wb-deadline-expired").toList()
        val timeoutIdx = runEvents.indexOfFirst { it is dev.rubentxu.pipeline.v2.events.TimeoutScheduled }
        val sleepStartIdx = runEvents.indexOfFirst {
            it is dev.rubentxu.pipeline.v2.events.StepStarted && it.stepName.contains("sleep-1")
        }
        assertTrue(timeoutIdx >= 0, "TimeoutScheduled must be emitted; events: $runEvents")
        assertTrue(
            sleepStartIdx < 0 || timeoutIdx < sleepStartIdx,
            "TimeoutScheduled must precede the sleep child StepStarted",
        )

        // Durable truth: the sh child row is FAILED_TIMEOUT (kill), not SUCCEEDED/FAILED.
        val statuses = journal.listForRun("wb-deadline-expired").map { it.status }
        assertTrue(
            statuses.contains(OperationStatus.FAILED_TIMEOUT),
            "sh child must be durably FAILED_TIMEOUT; rows: $statuses",
        )
    }

    @Test
    fun `body finishing before deadline succeeds through the same external body path`(
        @TempDir tempDir: java.nio.file.Path,
    ) = runBlocking {
        val (coord, _, _) = harness(tempDir)
        val pipeline = stage(
            "B",
            externalBlock(
                timeoutBlock(
                    30L,
                    shChild("ok-1", "echo done"),
                ),
            ),
        )
        val outcome = coord.run(pipeline, RunId("wb-deadline-ok"))
        assertEquals(RunOutcome.Success, outcome)
    }
}
