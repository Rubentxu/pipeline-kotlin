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
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * WU-RP-033 / RP-3 exit criterion: an EXTERNAL Step WITH a body reaches the same
 * generic registry (open StepRegistry + declared BodyExecutionPolicy) and runs
 * through the canonical body engine, with ZERO new concrete branching in the
 * coordinator.
 *
 * Proven rows:
 *  - with-body: an external key whose descriptor declares `StepBody.Declared` with
 *    `Sequential` policy executes its BlockStepNode children through the shared
 *    body-child loop (same durable path as core blocks).
 *  - fail-closed: a BlockStepNode whose key is unknown is a typed rejection
 *    before any child runs.
 *  - parity: the atomic form (opaque node, no body) of the SAME plugin still works.
 */
@Timeout(30)
class ExternalStepWithBodyRegistryProofTest {

    private val upperBlockKey = PluginStepId("test.upperblock")

    data class UpperBlockInput(val prefix: String)

    private val inputCodec = object : StepCodec<UpperBlockInput> {
        override fun encode(value: UpperBlockInput): EncodedStepValue =
            EncodedStepValue("{\"prefix\":\"${value.prefix}\"}")
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
    }

    private fun harness(): Triple<CanonicalDurableRunCoordinator, InMemoryOperationJournal, InMemoryEventStore> {
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
                    CredentialScopeFailure.StoreUnavailable("upper-block proof stub"),
                )
            },
            controlDirRoot = Files.createTempDirectory("upperblock-proof-").resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry(),
        )
        return Triple(coord, journal, events)
    }

    private fun stage(name: String, vararg nodes: dev.rubentxu.pipeline.v2.domain.StepNode) = CompiledPipeline(
        id = DefinitionId("upperblock-proof"),
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
        id = StepId("external/upperblock-body-1"),
        pluginStepId = upperBlockKey,
        payload = VersionedStepPayload("dsl-v1", inputCodec.encode(UpperBlockInput("p")).value),
        body = children.toList(),
    )

    private fun echoChild(text: String) = OpaqueStepNode(
        id = StepId("external/upperblock-body-1/echo-1"),
        pluginStepId = PluginStepId("core.echo"),
        payload = VersionedStepPayload("dsl-v1", "{\"kind\":\"echo\",\"text\":\"$text\"}"),
    )

    @Test
    fun `with-body - external declared body executes children through the canonical body engine`() = runBlocking {
        val (coord, journal, _) = harness()
        val outcome = coord.run(stage("B", externalBlock(echoChild("hello"))), RunId("wb1"))
        assertEquals(RunOutcome.Success, outcome)
        // Body children were dispatched through the shared loop: the echo child produced
        // its durable row under the SAME run — proof the canonical body engine re-entered
        // the engine for an external plugin key.
        val rows = journal.listForRun("wb1")
        assertTrue(rows.isNotEmpty(), "body child must produce durable journal rows")
    }

    @Test
    fun `fail-closed - block node for an unknown key is rejected before any child runs`() = runBlocking {
        val (coord, journal, _) = harness()
        val unknownBlock = BlockStepNode(
            id = StepId("external/unknown-body-1"),
            pluginStepId = PluginStepId("test.never.registered"),
            payload = VersionedStepPayload("dsl-v1", "{}"),
            body = listOf(echoChild("must-not-run")),
        )
        val outcome = coord.run(stage("B", unknownBlock), RunId("wb2"))
        assertTrue(outcome is RunOutcome.Failure, "unknown block key must fail closed")
        val rows = journal.listForRun("wb2")
        assertTrue(rows.isEmpty(), "no child may run for a rejected body policy")
    }

    @Test
    fun `parity - the SAME plugin key executes as an atomic opaque node via the registry seam`() = runBlocking {
        val (coord, _, _) = harness()
        val atomic = OpaqueStepNode(
            id = StepId("external/upperblock-atomic-1"),
            pluginStepId = upperBlockKey,
            payload = VersionedStepPayload("dsl-v1", inputCodec.encode(UpperBlockInput("p")).value),
        )
        val outcome = coord.run(stage("A", atomic), RunId("wb3"))
        assertEquals(RunOutcome.Success, outcome)
    }
}
