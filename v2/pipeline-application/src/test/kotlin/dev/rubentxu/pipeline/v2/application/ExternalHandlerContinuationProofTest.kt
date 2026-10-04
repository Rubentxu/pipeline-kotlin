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
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.BODY_CONTINUATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BodyContinuation
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * WU-RP-035: an EXTERNAL body Step whose registered handler actually runs and drives its
 * own body through the bound continuation.
 *
 * Companion of [ExternalStepWithBodyRegistryProofTest] (RP-033, untouched: it proves
 * generic structural routing) and of [ExternalBodyStepDurableIdentityTest] (slice A, which
 * characterizes what a block Step durably is). This file is where the corrected ADR-0081
 * claim is proven:
 *
 * ```text
 * external plugin handler
 *   -> receives BODY_CONTINUATION_CAPABILITY, already bound to its own body
 *   -> BodyContinuation.invoke()
 *   -> canonical body engine
 *   -> children
 * ```
 *
 * Rows:
 *  - [handler_is_invoked] the RED from slice A, now green: the handler runs exactly once
 *    and observes exactly the capability it declared.
 *  - [the body runs only when the handler invokes it] the negative that matters most. If the
 *    engine still substituted its own semantics, this would report child effects for a
 *    handler that never invoked anything.
 *  - [a changed body is not silently reused] the durable-identity row: same parent payload,
 *    different body, same runId must NOT come back as a memoized success.
 *  - [invoking the continuation twice does not duplicate effects] the exactly-once row for a
 *    handler that calls its body more than once.
 */
@Timeout(30)
class ExternalHandlerContinuationProofTest {

    private val blockKey = PluginStepId("test.handlerblock")

    data class HandlerBlockInput(val label: String)

    private val invocations = AtomicInteger(0)
    private val observedCapabilities = mutableListOf<Set<StepCapability>>()

    /** How many times the handler drives its own body. 0 is the load-bearing negative. */
    private var bodyInvocations: Int = 1

    private val inputCodec = object : StepCodec<HandlerBlockInput> {
        override fun encode(value: HandlerBlockInput): EncodedStepValue =
            EncodedStepValue("{\"label\":\"${value.label}\"}")

        override fun decode(encoded: EncodedStepValue): HandlerBlockInput {
            val match = Regex("\"label\"\\s*:\\s*\"([^\"]*)\"").find(encoded.value)
                ?: throw IllegalArgumentException("invalid handlerblock payload")
            return HandlerBlockInput(match.groupValues[1])
        }
    }

    private val outputCodec = object : StepCodec<String> {
        private val json = Json
        override fun encode(value: String): EncodedStepValue =
            EncodedStepValue(json.encodeToString(String.serializer(), value))

        override fun decode(encoded: EncodedStepValue): String =
            json.decodeFromString(String.serializer(), encoded.value)
    }

    private fun definition() = object : StepDefinition<HandlerBlockInput, String> {
        override val contract = StepContract(
            key = blockKey,
            descriptor = StepDescriptor(
                stepId = blockKey.value,
                name = "handlerblock",
                configRef = "",
                pluginId = "test.handlerblock",
                pluginVersion = "0.1.0",
                executionLocation = ExecutionLocation.CONTROLLER,
                effects = listOf(Effect.READ_ONLY),
                replayPolicy = ReplayPolicy.MEMOIZED,
                body = StepBody.Declared(
                    invocation = BodyInvocationPolicy.ONCE,
                    execution = BodyExecution(
                        owner = BodyExecutionOwner.HANDLER_CONTINUATION,
                        policy = BodyExecutionPolicy.Sequential,
                    ),
                    introduces = null,
                ),
            ),
            inputCodec = inputCodec,
            outputCodec = outputCodec,
            requiredCapabilities = setOf(BODY_CONTINUATION_CAPABILITY),
        )

        override val handler = StepHandler<HandlerBlockInput, String> { input, context ->
            invocations.incrementAndGet()
            observedCapabilities += context.capabilities.available()
            val continuation: BodyContinuation = context.capabilities.get(BODY_CONTINUATION_CAPABILITY)
            var bodyFailures = 0
            repeat(bodyInvocations) {
                val outcome = continuation.invoke(BodyInvocationContext())
                if (outcome is dev.rubentxu.pipeline.v2.domain.step.BodyOutcome.Completed &&
                    outcome.outcome is dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure
                ) {
                    bodyFailures++
                }
            }
            "${input.label.uppercase()}:$bodyFailures"
        }
    }

    private fun registry() = InMemoryStepRegistry().apply {
        register(definition())
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
                    CredentialScopeFailure.StoreUnavailable("handler-continuation proof stub"),
                )
            },
            controlDirRoot = Files.createTempDirectory("handlerblock-proof-").resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry(),
        )
        return Triple(coordinator, journal, events)
    }

    private fun stage(vararg nodes: StepNode) = CompiledPipeline(
        id = DefinitionId("handlerblock-proof"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("B"),
                name = "B",
                body = StageBody.Steps(nodes.toList()),
            ),
        ),
    )

    private fun block(vararg children: StepNode) = BlockStepNode(
        id = StepId("external/handlerblock-body-1"),
        pluginStepId = blockKey,
        payload = VersionedStepPayload("dsl-v1", inputCodec.encode(HandlerBlockInput("hi")).value),
        body = children.toList(),
    )

    private fun echoChild(text: String) = OpaqueStepNode(
        id = StepId("external/handlerblock-body-1/echo-1"),
        pluginStepId = PluginStepId("core.echo"),
        payload = VersionedStepPayload("dsl-v1", "{\"kind\":\"echo\",\"text\":\"$text\"}"),
    )

    @Test
    fun `handler_is_invoked`() = runBlocking {
        val (coordinator, journal, _) = harness()

        val outcome = coordinator.run(stage(block(echoChild("hello"))), RunId("hc1"))

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(
            1,
            invocations.get(),
            "an external HANDLER_CONTINUATION Step must run its registered handler exactly once, " +
                "observed ${invocations.get()} with capabilities ${observedCapabilities.toList()}",
        )
        assertTrue(
            observedCapabilities.firstOrNull()?.contains(BODY_CONTINUATION_CAPABILITY) == true,
            "the handler must observe the continuation it declared, observed " +
                "${observedCapabilities.firstOrNull()}",
        )
        assertTrue(
            journal.listForRun("hc1").isNotEmpty(),
            "the body child invoked BY THE HANDLER must be journaled",
        )
    }

    @Test
    fun `the body runs only when the handler invokes it`() = runBlocking {
        bodyInvocations = 0
        val (coordinator, journal, _) = harness()

        val outcome = coordinator.run(stage(block(echoChild("must-not-run"))), RunId("hc2"))

        assertEquals(1, invocations.get(), "the handler must still run; only the body is skipped")
        assertEquals(RunOutcome.Success, outcome)
        // The parent now journals its OWN operation (that is the point of the durable spine);
        // a child would carry a body path in its operation id. Counting the parent's row as a
        // child effect would be the same confusion the old routing produced.
        val childRows = journal.listForRun("hc2").map { it.id }.filter { it.contains("-bp") }
        assertEquals(
            emptyList<String>(),
            childRows,
            "a handler that never invokes its continuation produces ZERO child effects; the " +
                "engine must not substitute its own body semantics for the plugin's",
        )
    }

    @Test
    fun `invoking the continuation twice does not duplicate effects`() = runBlocking {
        bodyInvocations = 2
        val (coordinator, journal, _) = harness()

        val outcome = coordinator.run(stage(block(echoChild("once"))), RunId("hc3"))

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(1, invocations.get(), "the handler runs once; it is the BODY it drives twice")
        val childRows = journal.listForRun("hc3").count { it.id.contains("-bp") }
        assertEquals(
            1,
            childRows,
            "child operation ids are deterministic, so a second invocation reuses the journal " +
                "row instead of duplicating the effect",
        )
    }

    @Test
    fun `a changed body is not silently reused`() = runBlocking {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val events = InMemoryEventStore()
        fun coordinator() = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = events,
            credentialScopePort = { _, _ ->
                CredentialScopeOutcome.Unavailable(
                    CredentialScopeFailure.StoreUnavailable("divergence stub"),
                )
            },
            controlDirRoot = Files.createTempDirectory("handlerblock-diverge-").resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry(),
        )

        val first = coordinator().run(stage(block(echoChild("A"))), RunId("hc4"))
        assertEquals(RunOutcome.Success, first, "the first run establishes the durable row")

        val second = coordinator().run(stage(block(echoChild("B"))), RunId("hc4"))

        assertTrue(
            second !is RunOutcome.Success,
            "same parent payload with a CHANGED body must not come back as a silent reuse; " +
                "observed $second",
        )
        assertTrue(
            invocations.get() == 1,
            "the handler of a diverged run must not have run a second time, observed " +
                "${invocations.get()}",
        )
    }
}
