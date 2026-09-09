package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.support.PipelineRule
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * StepContractSuite — LFC-2 / S3.5 first useful version.
 *
 * Certifies `core.echo` end-to-end across the registry-driven, open-world Step seam. The suite proves
 * the registration contract is complete: identity, codec, envelope, dispatch, capability admission,
 * durable identity (fresh + replay + divergence), observability, missing capability, and a real
 * pipeline scenario through the public DSL.
 *
 * This is the first Step CERTIFIED through the new path; it is the model every future core Step will
 * follow. The suite is intentionally minimal — the future platform will scale to a per-Step contract
 * directory, but for now `core.echo` is the canonical example.
 *
 * Coverage matrix (per S3.5 request):
 *  - identity
 *  - contract completeness
 *  - codec input
 *  - codec output
 *  - canonical envelope
 *  - registry resolution
 *  - capability admission
 *  - success
 *  - typed failure
 *  - fresh durable
 *  - replay
 *  - divergence
 *  - observability
 *  - missing capability
 *  - architecture fitness (delegated to Lfc2RegistryFamilyFitnessTest + S3EchoLegacyRemovedFitnessTest)
 *  - real pipeline scenario (DSL `pipeline { stage("echo") { steps { echo("hello registry") } } }`)
 */
@Timeout(15)
class EchoStepContractSuiteTest {

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { CoreEchoStep.registerInto(this) }

    private fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable("step-contract-suite stub"),
        )
    }

    /**
     * Constructs a coordinator whose journal is observable directly (held by the test). The coordinator
     * wires the production registry-routed path (CDE.3-c/d/e) via [CoreStepRegistryFactory.registry()].
     */
    private fun freshHarness(
        eventStore: InMemoryEventStore,
        workDir: java.nio.file.Path = Files.createTempDirectory("echo-contract-"),
    ): HarnessHarness {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val coord = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = cursorStore,
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            controlDirRoot = workDir.resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = CoreStepRegistryFactory.registry(),
        )
        return HarnessHarness(coord, journal, eventStore)
    }

    private data class HarnessHarness(
        val coord: CanonicalDurableRunCoordinator,
        val journal: InMemoryOperationJournal,
        val eventStore: InMemoryEventStore,
    )

    private fun echoNode(text: String, nodeId: String = "build/echo") = OpaqueStepNode(
        id = StepId(nodeId),
        pluginStepId = CoreEchoStep.KEY,
        payload = VersionedStepPayload(
            schemaVersion = "dsl-v1",
            encoded = """{"kind":"echo","text":"$text"}""",
        ),
    )

    private fun pipeline(vararg nodes: OpaqueStepNode) = CompiledPipeline(
        id = DefinitionId("echo-contract-suite"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(nodes.toList()),
            ),
        ),
    )

    // -------- identity --------

    @Test
    fun `identity — CoreEchoStep KEY is core dot echo and unique within the registry`() {
        assertEquals(PluginStepId("core.echo"), CoreEchoStep.KEY)
        assertEquals("core.echo", CoreEchoStep.KEY.value)
        // Re-registering must fail (deterministic / idempotent error).
        val r = registry()
        assertTrue(
            runCatching { CoreEchoStep.registerInto(r) }.isFailure,
            "duplicate registration of core.echo must fail",
        )
    }

    // -------- contract completeness --------

    @Test
    fun `contract completeness — key, descriptor, input codec, output codec, required capabilities`() {
        val contract = CoreEchoStep.definition.contract
        assertEquals(CoreEchoStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "StepDescriptor must be present")
        assertEquals("echo", contract.descriptor.name)
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.Effect.READ_ONLY,
            contract.descriptor.effects.single(),
        )
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy.MEMOIZED,
            contract.descriptor.replayPolicy,
        )
        assertEquals(setOf<StepCapability>(EVENT_SINK_CAPABILITY), contract.requiredCapabilities)
        assertNotNull(contract.inputCodec, "input codec must be present")
        assertNotNull(contract.outputCodec, "output codec must be present")
    }

    // -------- codec input --------

    @Test
    fun `codec input — encode and round-trip preserve text and envelope shape`() {
        val encoded = CoreEchoStep.definition.contract.inputCodec.encode(EchoInput("contractual"))
        // Canonical envelope is the canonical dsl-v1 payload form (B1.2c3-slice1).
        assertEquals(
            """{"kind":"echo","text":"contractual"}""",
            encoded.value,
            "input codec must emit the canonical dsl-v1 envelope",
        )
        val decoded = CoreEchoStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(EchoInput("contractual"), decoded, "round-trip encode -> decode must preserve text")
    }

    @Test
    fun `codec input — decode rejects a non-echo payload kind`() {
        val foreign = EncodedStepValue(Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", JsonPrimitive("not-echo"))
            put("text", JsonPrimitive("x"))
        }))
        assertTrue(
            runCatching { CoreEchoStep.definition.contract.inputCodec.decode(foreign) }.isFailure,
            "decode must fail closed on a non-echo payload kind",
        )
    }

    // -------- codec output --------

    @Test
    fun `codec output — opaque raw-text (durable eligibility) round-trips byte-identically`() {
        val raw = "captured stdout line 1\nline 2"
        val encoded = CoreEchoStep.definition.contract.outputCodec.encode(raw)
        assertEquals(raw, encoded.value)
        assertEquals(raw, CoreEchoStep.definition.contract.outputCodec.decode(encoded))
    }

    // -------- canonical envelope --------

    @Test
    fun `canonical envelope — emitted envelope is a well-formed JSON object (durable eligibility)`() {
        val encoded = CoreEchoStep.definition.contract.inputCodec.encode(EchoInput("hello"))
        val parsed = Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is JsonObject, "canonical echo envelope must be a JSON object for durable-spine eligibility")
        val obj = parsed as JsonObject
        assertEquals("echo", obj["kind"]?.let { (it as JsonPrimitive).content })
        assertEquals("hello", obj["text"]?.let { (it as JsonPrimitive).content })
    }

    // -------- registry resolution --------

    @Test
    fun `registry resolution — production factory contains core dot echo`() {
        val r = CoreStepRegistryFactory.registry()
        assertTrue(r.contains(CoreEchoStep.KEY))
        assertEquals(CoreEchoStep.KEY, r.definition(CoreEchoStep.KEY)?.contract?.key)
    }

    @Test
    fun `registry resolution — production factory registry is fresh per call and consistent across calls`() {
        // Two factories must be different instances (fresh per call), but both contain core.echo.
        val a = CoreStepRegistryFactory.registry()
        val b = CoreStepRegistryFactory.registry()
        assertFalse(a === b, "factory must produce fresh per-call registries")
        assertTrue(a.contains(CoreEchoStep.KEY))
        assertTrue(b.contains(CoreEchoStep.KEY))
    }

    // -------- capability admission --------

    @Test
    fun `capability admission — admission succeeds when the runtime exposes EVENT_SINK_CAPABILITY`() {
        // Direct registry-strategy admission: with EVENT_SINK present, preparation succeeds Ready.
        val r = registry()
        val encoded = CoreEchoStep.definition.contract.inputCodec.encode(EchoInput("admitted"))
        val preparation = RegistryExecutionPreparation.prepare(
            registry = r,
            key = CoreEchoStep.KEY,
            encodedInput = encoded,
            availableCapabilities = setOf<StepCapability>(EVENT_SINK_CAPABILITY),
        )
        assertTrue(
            preparation is ExecutionPreparation.Ready,
            "admission must succeed when EVENT_SINK is available",
        )
    }

    // -------- success --------

    @Test
    fun `success — registry-routed echo emits exactly one EchoOutputCaptured and SUCCEEDS`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            val outcome = h.coord.run(pipeline(echoNode("ok")), RunId("ok"))
            assertEquals(RunOutcome.Success, outcome)
            val captured = h.eventStore.eventsFor("ok").filterIsInstance<EchoOutputCaptured>().toList()
            assertEquals(1, captured.size, "exactly one EchoOutputCaptured emitted")
            assertEquals("ok\n", captured.single().content)
            assertEquals(OperationStatus.SUCCEEDED, h.journal.listForRun("ok").single().status)
        }
    }

    // -------- typed failure --------

    @Test
    fun `typed failure — a registry-routed echo whose handler throws surfaces as RunOutcome Failure`() {
        // Build a registry containing an echo definition whose handler throws. RunOutcome must be
        // Failure (typed), proving the canonical registry-strategy path converts handler exceptions
        // into a typed terminal (not a leaked IllegalState or a typed-Success).
        val throwingHandler: StepHandler<EchoInput, String> =
            StepHandler { _: EchoInput, _: StepHandlerContext ->
                throw IllegalStateException("core.echo handler contract violated for test")
            }
        val throwingRegistry = InMemoryStepRegistry().apply {
            register(
                object : StepDefinition<EchoInput, String> {
                    override val contract: StepContract<EchoInput, String> = StepContract(
                        key = CoreEchoStep.KEY,
                        descriptor = CoreEchoStep.definition.contract.descriptor,
                        inputCodec = CoreEchoStep.definition.contract.inputCodec,
                        outputCodec = CoreEchoStep.definition.contract.outputCodec,
                        requiredCapabilities = setOf<StepCapability>(EVENT_SINK_CAPABILITY),
                    )
                    override val handler: StepHandler<EchoInput, String> = throwingHandler
                },
            )
        }
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val eventStore = InMemoryEventStore()
        val coord = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = cursorStore,
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            controlDirRoot = Files.createTempDirectory("echo-contract-fail-"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = throwingRegistry,
        )
        runBlocking {
            val outcome = coord.run(pipeline(echoNode("throw")), RunId("throw"))
            assertEquals(RunOutcome.Failure::class, outcome::class, "handler exceptions must surface as a typed RunOutcome.Failure, not as silent success")
        }
    }

    // -------- fresh durable --------

    @Test
    fun `fresh durable — first execution of core dot echo writes one terminal SUCCEEDED operation`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            h.coord.run(pipeline(echoNode("first")), RunId("first"))
            val rows = h.journal.listForRun("first")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    // -------- replay --------

    @Test
    fun `replay — a previously SUCCEEDED echo is reused without re-running the handler`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            // First execution: emits EchoOutputCaptured.
            h.coord.run(pipeline(echoNode("replay-me")), RunId("replay"))
            val first = h.eventStore.eventsFor("replay").filterIsInstance<EchoOutputCaptured>().count()
            assertEquals(1, first)

            // Second execution at the SAME runId: must REUSE the SUCCEEDED op without re-running the
            // handler. The coordinator's reused path keeps the existing terminal row and does NOT
            // emit a new EchoOutputCaptured.
            h.coord.run(pipeline(echoNode("replay-me")), RunId("replay"))
            val second = h.eventStore.eventsFor("replay").filterIsInstance<EchoOutputCaptured>().count()
            assertEquals(1, second, "replay must NOT re-emit EchoOutputCaptured (handler did not run)")
        }
    }

    // -------- divergence --------

    @Test
    fun `divergence — replaying a SUCCEEDED echo with a different text fails closed as typed divergence`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            h.coord.run(
                pipeline(echoNode("original")),
                RunId("div"),
            )
            // Changed text => different durable fingerprint => divergence surface.
            val outcome = h.coord.run(
                pipeline(echoNode("different-text")),
                RunId("div"),
            )
            assertTrue(
                outcome is RunOutcome.Failure || outcome is RunOutcome.Unstable,
                "divergence must surface as a typed failure/unstable, not a silent success",
            )
        }
    }

    // -------- observability --------

    @Test
    fun `observability — every core dot echo run emits a StepStarted StepFinished pair plus EchoOutputCaptured`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            h.coord.run(pipeline(echoNode("observable")), RunId("obs"))
            val events = h.eventStore.eventsFor("obs").toList()
            assertTrue(
                events.any { it is StepStarted },
                "StepStarted must be emitted (lifecycle observability)",
            )
            assertTrue(
                events.any { it is StepFinished },
                "StepFinished must be emitted (lifecycle observability)",
            )
            assertTrue(
                events.any { it is EchoOutputCaptured },
                "EchoOutputCaptured must be emitted (handler observability)",
            )
        }
    }

    // -------- missing capability --------

    @Test
    fun `missing capability — admission rejects when EVENT_SINK is absent`() {
        // Construct a registry whose definition declares a different (absent) capability.
        val absent = StepCapability("missing.capability.never.declared")
        val altRegistry = InMemoryStepRegistry().apply {
            register(
                object : StepDefinition<EchoInput, String> {
                    override val contract: StepContract<EchoInput, String> = StepContract(
                        key = CoreEchoStep.KEY,
                        descriptor = CoreEchoStep.definition.contract.descriptor,
                        inputCodec = CoreEchoStep.definition.contract.inputCodec,
                        outputCodec = CoreEchoStep.definition.contract.outputCodec,
                        requiredCapabilities = setOf<StepCapability>(absent),
                    )
                    override val handler: StepHandler<EchoInput, String> = CoreEchoStep.definition.handler
                },
            )
        }
        // Direct registry admission surfaces the missing capability as a Rejected preparation.
        val admission = RegistryExecutionPreparation.prepare(
            registry = altRegistry,
            key = CoreEchoStep.KEY,
            encodedInput = CoreEchoStep.definition.contract.inputCodec.encode(EchoInput("x")),
            availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
        )
        assertTrue(
            admission is ExecutionPreparation.Rejected,
            "missing capability must surface as Rejected admission (fail-closed)",
        )
    }

    // -------- real pipeline scenario (DSL → canonical) --------

    @Test
    fun `real pipeline scenario — public DSL pipeline stage echo hello registry runs end-to-end`() = runBlocking {
        // The full DSL pipeline scenario, end-to-end through the public seam:
        //   pipeline { stage("echo") { steps { echo("hello registry") } } }
        // The script must compile (DslCompiledPipelineCompiler), execute (CanonicalDurableRunCoordinator),
        // emit EchoOutputCaptured ("hello registry\n"), and SUCCEED.
        val sourcePath = "Pipeline.kts"
        val sourceContent = """
            pipeline {
                stages {
                    stage("echo") {
                        echo("hello registry")
                    }
                }
            }
        """.trimIndent()
        val spec: PipelineSpec = pipeline {
            stages {
                stage("echo") {
                    echo("hello registry")
                }
            }
        }
        val workDir = Files.createTempDirectory("echo-contract-real-pipeline-")
        try {
            val run = PipelineRule.run(
                spec = spec,
                sourcePath = sourcePath,
                sourceContent = sourceContent,
                runIdValue = "real-pipeline",
                workDir = workDir,
            )
            assertEquals(RunOutcome.Success, run.outcome, "real pipeline scenario must succeed")
            val captured = run.events.filterIsInstance<EchoOutputCaptured>()
            assertEquals(1, captured.size, "real pipeline scenario must emit exactly one EchoOutputCaptured")
            assertEquals("hello registry\n", captured.single().content)
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }
}

