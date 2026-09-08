package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.SystemClock
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
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * CDE.3-e5: durable end-to-end registry laws on the REAL spine, through a neutral `test.identity` step
 * (NOT core.echo). Proves the registry MECHANISM traverses the same durable spine as legacy, sharing
 * journal / replay / fingerprint semantics, without the durable engine knowing I/O/handler.
 *
 * Typed decode runs ONLY inside registry prepare (on Execute); replay reuse and divergence never call
 * the codec or handler. A step requiring a capability the runtime does not supply fails closed at
 * prepare admission (handler 0). Output normalization reduces the typed handler `O` to a durable
 * [StepOutcome] with no generic output slot; a reused outcome is never re-produced by the handler.
 */
@Timeout(10)
class RegistryDurableSpineTest {

    private data class IdentityInput(val value: String)

    private class Counters {
        var codecDecode = 0
        var handler = 0
    }

    private val IDENTITY_KEY = PluginStepId("test.identity")
    private val NEEDS_KEY = PluginStepId("test.needs-unavailable")
    private val UNAVAILABLE = StepCapability("not.supplied.by.runtime")

    /** A registered neutral step whose input codec emits a well-formed JSON OBJECT (durable-spine shape). */
    private fun registerIdentity(registry: InMemoryStepRegistry, counters: Counters) {
        val definition: StepDefinition<IdentityInput, String> = object : StepDefinition<IdentityInput, String> {
            override val contract: StepContract<IdentityInput, String> = StepContract(
                key = IDENTITY_KEY,
                descriptor = StepDescriptor(
                    stepId = "test.identity",
                    name = "identity",
                    configRef = "",
                    executionLocation = ExecutionLocation.CONTROLLER,
                    effects = listOf(Effect.READ_ONLY),
                    replayPolicy = ReplayPolicy.MEMOIZED,
                ),
                inputCodec = object : StepCodec<IdentityInput> {
                    override fun encode(value: IdentityInput): EncodedStepValue =
                        EncodedStepValue(JsonObject(mapOf("value" to JsonPrimitive(value.value))).toString())

                    override fun decode(encoded: EncodedStepValue): IdentityInput {
                        counters.codecDecode++
                        return IdentityInput(Json.parseToJsonElement(encoded.value).jsonObject.getValue("value").jsonPrimitive.content)
                    }
                },
                outputCodec = object : StepCodec<String> {
                    override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
                    override fun decode(encoded: EncodedStepValue): String = encoded.value
                },
                requiredCapabilities = emptySet(),
            )
            override val handler: StepHandler<IdentityInput, String> = StepHandler { input, _ ->
                counters.handler++
                "identity:${input.value}"
            }
        }
        registry.register(definition)
    }

    /** A registered step that DECLARES a capability the runtime never supplies (fail-closed DREG-5). */
    private fun registerNeedsUnavailable(registry: InMemoryStepRegistry, counters: Counters) {
        val definition: StepDefinition<IdentityInput, String> = object : StepDefinition<IdentityInput, String> {
            override val contract: StepContract<IdentityInput, String> = StepContract(
                key = NEEDS_KEY,
                descriptor = StepDescriptor(
                    stepId = "test.needs-unavailable",
                    name = "needs-unavailable",
                    configRef = "",
                    executionLocation = ExecutionLocation.CONTROLLER,
                    effects = listOf(Effect.READ_ONLY),
                    replayPolicy = ReplayPolicy.MEMOIZED,
                ),
                inputCodec = object : StepCodec<IdentityInput> {
                    override fun encode(value: IdentityInput): EncodedStepValue =
                        EncodedStepValue(JsonObject(mapOf("value" to JsonPrimitive(value.value))).toString())

                    override fun decode(encoded: EncodedStepValue): IdentityInput {
                        counters.codecDecode++
                        return IdentityInput(Json.parseToJsonElement(encoded.value).jsonObject.getValue("value").jsonPrimitive.content)
                    }
                },
                outputCodec = object : StepCodec<String> {
                    override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
                    override fun decode(encoded: EncodedStepValue): String = encoded.value
                },
                requiredCapabilities = setOf(UNAVAILABLE),
            )
            override val handler: StepHandler<IdentityInput, String> = StepHandler { input, _ ->
                counters.handler++
                "identity:${input.value}"
            }
        }
        registry.register(definition)
    }

    private fun pipeline(key: PluginStepId, encoded: String) = CompiledPipeline(
        id = DefinitionId("registry-spine-pipeline"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(
                    listOf(
                        OpaqueStepNode(
                            id = StepId("build/identity"),
                            pluginStepId = key,
                            payload = VersionedStepPayload("dsl-v1", encoded),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun identityPayload(text: String): String =
        JsonObject(mapOf("value" to JsonPrimitive(text))).toString()

    private fun coordinator(registry: InMemoryStepRegistry, clock: SystemClock, journal: InMemoryOperationJournal): CanonicalDurableRunCoordinator =
        CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = InMemoryEventStore(),
            credentialScopePort = noOpCredentialScopePort(),
            stepRegistry = registry,
        )

    private fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable("No credential store in this coordinator unit test"),
        )
    }

    @Test
    fun `DREG-1 fresh registry valid executes codec and handler once on the durable spine`() = runBlocking {
        val counters = Counters()
        val registry = InMemoryStepRegistry()
        registerIdentity(registry, counters)
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val outcome = coordinator(registry, clock, journal).run(pipeline(IDENTITY_KEY, identityPayload("hi")), RunId("dreg-1"))

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(1, counters.codecDecode, "fresh registry step must decode exactly once")
        assertEquals(1, counters.handler, "fresh registry step must run its handler once")
        assertEquals(OperationStatus.SUCCEEDED, journal.listForRun("dreg-1").single().status)
    }

    @Test
    fun `DREG-2 replayed registry outcome is reused without codec or handler`() = runBlocking {
        val counters = Counters()
        val registry = InMemoryStepRegistry()
        registerIdentity(registry, counters)
        val clock = SystemClock()
        val runId = RunId("dreg-2")
        val journal = InMemoryOperationJournal(clock)
        val payload = identityPayload("hi")
        val input = OperationInput(
            stepId = IDENTITY_KEY.value,
            params = mapOf("payload" to JsonPrimitive(payload)),
            runId = runId.value,
            attempt = 1,
        )
        // Seed a completed MEMOIZED operation with a MATCHING fingerprint: reuse must NOT re-execute.
        journal.append(
            RerunOperation(
                id = "${runId.value}-s0-0",
                fingerprint = Fingerprint.compute(input, IDENTITY_KEY.value, ReplayPolicy.MEMOIZED, 1),
                input = input,
                output = null,
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            ),
        )
        val outcome = coordinator(registry, clock, journal).run(pipeline(IDENTITY_KEY, payload), runId)

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(0, counters.codecDecode, "reuse must NOT run the codec")
        assertEquals(0, counters.handler, "reuse must NOT run the handler")
    }

    @Test
    fun `DREG-3 divergent registry input fails closed without codec or handler`() = runBlocking {
        val counters = Counters()
        val registry = InMemoryStepRegistry()
        registerIdentity(registry, counters)
        val clock = SystemClock()
        val runId = RunId("dreg-3")
        val journal = InMemoryOperationJournal(clock)
        val seededInput = OperationInput(
            stepId = IDENTITY_KEY.value,
            params = mapOf("payload" to JsonPrimitive(identityPayload("original"))),
            runId = runId.value,
            attempt = 1,
        )
        // Journal holds a completed operation whose fingerprint does NOT match the incoming payload.
        journal.append(
            RerunOperation(
                id = "${runId.value}-s0-0",
                fingerprint = Fingerprint.compute(seededInput, IDENTITY_KEY.value, ReplayPolicy.MEMOIZED, 1),
                input = seededInput,
                output = null,
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            ),
        )
        val outcome = coordinator(registry, clock, journal).run(
            pipeline(IDENTITY_KEY, identityPayload("changed")),
            runId,
        )

        assertTrue(outcome is RunOutcome.Failure, "a divergent registry invocation must fail closed")
        assertEquals(0, counters.codecDecode, "divergence must NOT run the codec")
        assertEquals(0, counters.handler, "divergence must NOT run the handler")
    }

    @Test
    fun `DREG-4 typed-invalid registry input rejects as schema without common execution or handler`() = runBlocking {
        val counters = Counters()
        val registry = InMemoryStepRegistry()
        registerIdentity(registry, counters)
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        // A well-formed JSON object the identity codec cannot decode (missing the "value" field) is
        // SCHEMA (typed-invalid): the structural gate passes, registry prepare decode fails, the common
        // executor never runs.
        val badPayload = JsonObject(mapOf("unexpected" to JsonPrimitive("x"))).toString()
        val outcome = coordinator(registry, clock, journal).run(pipeline(IDENTITY_KEY, badPayload), RunId("dreg-4"))

        assertTrue(outcome is RunOutcome.Failure, "typed-invalid registry input must fail closed as schema")
        assertEquals(1, counters.codecDecode, "typed-invalid decode runs once at prepare")
        assertEquals(0, counters.handler, "a decode-failed input must never reach the handler")
    }

    @Test
    fun `DREG-5 missing capability fails closed at prepare admission with handler 0`() = runBlocking {
        val counters = Counters()
        val registry = InMemoryStepRegistry()
        registerNeedsUnavailable(registry, counters)
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val outcome = coordinator(registry, clock, journal).run(pipeline(NEEDS_KEY, identityPayload("hi")), RunId("dreg-5"))

        assertTrue(outcome is RunOutcome.Failure, "a registry step with an unavailable capability must fail closed")
        assertEquals(0, counters.handler, "handler must never run without capability admission")
    }
}
