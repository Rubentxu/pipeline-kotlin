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
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepBody
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepNode
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
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
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

/**
 * WU-RP-035 / slice A: the durable identity a body-bearing Step has TODAY, on HEAD 9521d256.
 *
 * This is a characterization, not a target. It records the shape RP-035 has to decide rather
 * than guessing it, and it is deliberately green at the commit that introduces it.
 *
 * The two facts it pins, both verified on 9521d256:
 *
 *  1. A `BlockStepNode` reaches the canonical body engine through `dispatchBody` and its
 *     body children are journaled normally.
 *  2. The block Step itself has NO operation of its own: it produces no journal row, so it
 *     has no fingerprint, no attempt and no replay/divergence behaviour of its own.
 *
 * Consequence for RP-035: a handler-driven body Step routed through the durable spine will
 * acquire an operation, and therefore a fingerprint, where today it has none. Whatever binds
 * those two states together must decide how the BODY STRUCTURE participates in that identity,
 * because a parent fingerprint computed from its payload alone would let a changed body reuse
 * silently. See `RP035_A_HANDLER_CONTINUATION_RED.md` for the demonstrated RED.
 */
@Timeout(30)
class ExternalBodyStepDurableIdentityTest {

    private val blockKey = PluginStepId("test.identityblock")

    data class IdentityBlockInput(val label: String)

    private val inputCodec = object : StepCodec<IdentityBlockInput> {
        override fun encode(value: IdentityBlockInput): EncodedStepValue =
            EncodedStepValue("{\"label\":\"${value.label}\"}")

        override fun decode(encoded: EncodedStepValue): IdentityBlockInput {
            val match = Regex("\"label\"\\s*:\\s*\"([^\"]*)\"").find(encoded.value)
                ?: throw IllegalArgumentException("invalid identityblock payload")
            return IdentityBlockInput(match.groupValues[1])
        }
    }

    private val outputCodec = object : StepCodec<String> {
        private val json = Json
        override fun encode(value: String): EncodedStepValue =
            EncodedStepValue(json.encodeToString(String.serializer(), value))

        override fun decode(encoded: EncodedStepValue): String =
            json.decodeFromString(String.serializer(), encoded.value)
    }

    /**
     * Declares the capability an open-world body Step would need. It is NEVER observed today,
     * because the block path bypasses the handler entirely: that absence is the defect
     * RP-035-C removes, and it is why this file only characterizes the durable identity.
     */
    private val definition = object : StepDefinition<IdentityBlockInput, String> {
        override val contract = StepContract(
            key = blockKey,
            descriptor = StepDescriptor(
                stepId = blockKey.value,
                name = "identityblock",
                configRef = "",
                pluginId = "test.identityblock",
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

        override val handler = StepHandler<IdentityBlockInput, String> { input, _ -> input.label.uppercase() }
    }

    private fun registry() = InMemoryStepRegistry().apply {
        register(definition)
        CoreEchoStep.registerInto(this)
    }

    private fun harness(): Triple<CanonicalDurableRunCoordinator, InMemoryOperationJournal, InMemoryEventStore> {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val events = InMemoryEventStore()
        val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = events,
            credentialScopePort = { _, _ ->
                CredentialScopeOutcome.Unavailable(
                    CredentialScopeFailure.StoreUnavailable("body identity characterization stub"),
                )
            },
            controlDirRoot = Files.createTempDirectory("identityblock-").resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry(),
        )
        return Triple(coordinator, journal, events)
    }

    private fun stage(name: String, vararg nodes: StepNode) = CompiledPipeline(
        id = DefinitionId("body-identity"),
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

    private fun block(vararg children: StepNode) = BlockStepNode(
        id = StepId("external/identityblock-body-1"),
        pluginStepId = blockKey,
        payload = VersionedStepPayload("dsl-v1", inputCodec.encode(IdentityBlockInput("hi")).value),
        body = children.toList(),
    )

    private fun echoChild(text: String) = OpaqueStepNode(
        id = StepId("external/identityblock-body-1/echo-1"),
        pluginStepId = PluginStepId("core.echo"),
        payload = VersionedStepPayload("dsl-v1", "{\"kind\":\"echo\",\"text\":\"$text\"}"),
    )

    @Test
    fun `body children are journaled through the canonical body engine`() = runBlocking {
        val (coordinator, journal, _) = harness()

        coordinator.run(stage("B", block(echoChild("hello"))), RunId("id1"))

        val operationIds = journal.listForRun("id1").map { it.id }
        assertTrue(
            operationIds.isNotEmpty(),
            "the body child must be journaled under the run's body path",
        )
    }

    @Test
    fun `the block step contributes no durable row of its own`() = runBlocking {
        val (coordinator, journal, _) = harness()

        coordinator.run(stage("B", block(echoChild("hello"))), RunId("id2"))

        val operationIds = journal.listForRun("id2").map { it.id }
        assertEquals(
            emptyList<String>(),
            operationIds.filter { it == "id2-s0-0" },
            "a BlockStepNode has no operation of its own today; recorded shape is $operationIds. " +
                "RP-035-C routes a HANDLER_CONTINUATION body Step through the durable spine, " +
                "which gives it a fingerprint it does not have now, so the body structure must " +
                "be made part of that identity.",
        )
    }
}
