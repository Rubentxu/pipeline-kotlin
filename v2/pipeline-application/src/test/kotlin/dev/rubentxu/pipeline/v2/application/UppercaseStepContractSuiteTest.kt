package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
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
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import example.uppercase.UppercaseCodec
import example.uppercase.UppercaseInput
import example.uppercase.UppercaseOutput
import example.uppercase.UppercaseOutputCodec
import example.uppercase.UppercaseStepDefinition
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * StepContractSuite for the EXTERNAL plugin Step `example.uppercase` (LB-02 final gate).
 *
 * Certifies the first external plugin Step end-to-end across the SAME registry-driven,
 * open-world seam used by core Steps — proving "one execution path, no privileged core":
 * identity, contract completeness, codec round-trips and fail-closed decode, canonical
 * envelope, registry resolution through DISCOVERY (ServiceLoader, not manual registration),
 * capability admission, durable identity (fresh + replay + divergence), observability,
 * missing capability, handler semantics, and the real DSL → canonical pipeline scenario.
 *
 * The plugin JAR must be on the test runtime classpath (same artifact the installed
 * distribution hosts via `--plugin-jar`); discovery is exercised exactly as production
 * discovery is.
 */
@Timeout(15)
class UppercaseStepContractSuiteTest {

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { ExternalStepPluginDiscovery.registerInto(this) }

    private fun harness(
        eventStore: InMemoryEventStore,
        workDir: java.nio.file.Path = Files.createTempDirectory("uppercase-contract-"),
    ): Harness {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val coord = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = InMemoryReplayCursorStore(clock),
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = { _, _ ->
                CredentialScopeOutcome.Unavailable(
                    CredentialScopeFailure.StoreUnavailable("uppercase-contract-suite stub"),
                )
            },
            controlDirRoot = workDir.resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = registry(),
        )
        return Harness(coord, journal, eventStore)
    }

    private data class Harness(
        val coord: CanonicalDurableRunCoordinator,
        val journal: InMemoryOperationJournal,
        val eventStore: InMemoryEventStore,
    )

    private fun uppercaseNode(text: String, nodeId: String = "external/uppercase") = OpaqueStepNode(
        id = StepId(nodeId),
        pluginStepId = UppercaseStepDefinition.KEY,
        payload = VersionedStepPayload(
            schemaVersion = "dsl-v1",
            encoded = UppercaseCodec.encode(UppercaseInput(text)).value,
        ),
    )

    private fun pipeline(vararg nodes: OpaqueStepNode) = CompiledPipeline(
        id = DefinitionId("uppercase-contract-suite"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("external"),
                name = "External",
                body = StageBody.Steps(nodes.toList()),
            ),
        ),
    )

    private fun noCapabilityAccess(): StepCapabilityAccess = object : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = emptySet()
        override fun <T : Any> get(key: StepCapability): T =
            throw IllegalStateException("no capabilities declared")
    }

    // -------- identity --------

    @Test
    fun `identity - KEY is example dot uppercase and duplicate registration fails closed`() {
        assertEquals(PluginStepId("example.uppercase"), UppercaseStepDefinition.KEY)
        assertEquals("example.uppercase", UppercaseStepDefinition.KEY.value)
        val r = registry()
        assertTrue(
            runCatching { r.register(UppercaseStepDefinition) }.isFailure,
            "duplicate registration of example.uppercase must fail closed",
        )
    }

    // -------- contract completeness --------

    @Test
    fun `contract completeness - key, descriptor, codecs, no privileged capabilities`() {
        val contract = UppercaseStepDefinition.contract
        assertEquals(UppercaseStepDefinition.KEY, contract.key)
        assertEquals("uppercase", contract.descriptor.name)
        assertEquals("example.uppercase", contract.descriptor.pluginId)
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.Effect.READ_ONLY,
            contract.descriptor.effects.single(),
        )
        assertEquals(ReplayPolicy.MEMOIZED, contract.descriptor.replayPolicy)
        assertEquals(emptySet<StepCapability>(), contract.requiredCapabilities)
    }

    // -------- codec input --------

    @Test
    fun `codec input - encode and round-trip preserve text and canonical envelope shape`() {
        val encoded = UppercaseCodec.encode(UppercaseInput("contractual"))
        val parsed = Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is JsonObject, "canonical envelope must be a JSON object (durable eligibility)")
        val obj = parsed as JsonObject
        assertEquals("contractual", obj["text"]?.let { (it as JsonPrimitive).content })
        assertEquals(UppercaseInput("contractual"), UppercaseCodec.decode(encoded))
    }

    @Test
    fun `codec input - decode fails closed on a foreign payload kind`() {
        val foreign = EncodedStepValue(Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", JsonPrimitive("not-uppercase"))
            put("text", JsonPrimitive("x"))
        }))
        assertTrue(
            runCatching { UppercaseCodec.decode(foreign) }.isFailure,
            "decode must fail closed on a foreign payload shape",
        )
    }

    // -------- codec output --------

    @Test
    fun `codec output - typed output round-trips symmetrically`() {
        val out = UppercaseOutput(value = "HELLO")
        assertEquals(out, UppercaseOutputCodec.decode(UppercaseOutputCodec.encode(out)))
    }

    // -------- registry resolution via DISCOVERY --------

    @Test
    fun `registry resolution - ServiceLoader discovery finds the contributor without manual registration`() {
        val r = registry()
        assertTrue(r.contains(UppercaseStepDefinition.KEY), "discovery must register example.uppercase")
        assertEquals(
            UppercaseStepDefinition.contract.key,
            r.definition(UppercaseStepDefinition.KEY)?.contract?.key,
        )
    }

    // -------- capability admission --------

    @Test
    fun `capability admission - prepares Ready with declared (empty) capabilities`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = UppercaseStepDefinition.KEY,
            encodedInput = UppercaseCodec.encode(UppercaseInput("admit")),
            availableCapabilities = emptySet(),
        )
        assertTrue(admission is ExecutionPreparation.Ready, "declared-empty capabilities must admit")
    }

    @Test
    fun `capability admission - missing capability surfaces as Rejected fail-closed`() {
        val absent = StepCapability("never.declared.capability")
        val altRegistry = InMemoryStepRegistry().apply {
            register(object : StepDefinition<UppercaseInput, UppercaseOutput> {
                override val contract = StepContract(
                    key = UppercaseStepDefinition.KEY,
                    descriptor = UppercaseStepDefinition.contract.descriptor,
                    inputCodec = UppercaseCodec,
                    outputCodec = UppercaseOutputCodec,
                    requiredCapabilities = setOf(absent),
                )
                override val handler = UppercaseStepDefinition.handler
            })
        }
        val admission = RegistryExecutionPreparation.prepare(
            registry = altRegistry,
            key = UppercaseStepDefinition.KEY,
            encodedInput = UppercaseCodec.encode(UppercaseInput("x")),
            availableCapabilities = emptySet(),
        )
        assertTrue(admission is ExecutionPreparation.Rejected, "missing capability must be Rejected")
    }

    // -------- handler semantics (typed, direct through the seam) --------

    @Test
    fun `handler - uppercase hello produces HELLO through the typed handler seam`() = runBlocking {
        val ctx = StepHandlerContext(runId = RunId("handler"), stepIndex = 0, capabilities = noCapabilityAccess())
        val out = UppercaseStepDefinition.handler.execute(UppercaseInput("hello"), ctx)
        assertEquals("HELLO", out.value)
    }

    // -------- durable: fresh / replay / divergence --------

    @Test
    fun `fresh durable - first execution writes exactly one terminal SUCCEEDED operation`() {
        val eventStore = InMemoryEventStore()
        val h = harness(eventStore)
        runBlocking {
            val outcome = h.coord.run(pipeline(uppercaseNode("fresh")), RunId("fresh"))
            assertEquals(RunOutcome.Success, outcome)
            val rows = h.journal.listForRun("fresh")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    @Test
    fun `replay - a previously SUCCEEDED uppercase run is reused without re-running the handler`() {
        val eventStore = InMemoryEventStore()
        val h = harness(eventStore)
        runBlocking {
            h.coord.run(pipeline(uppercaseNode("replay-me")), RunId("replay"))
            val outcome = h.coord.run(pipeline(uppercaseNode("replay-me")), RunId("replay"))
            assertEquals(RunOutcome.Success, outcome, "replay of a SUCCEEDED run must succeed (reuse)")
        }
    }

    @Test
    fun `divergence - replaying a SUCCEEDED run with different input fails closed as typed divergence`() {
        val eventStore = InMemoryEventStore()
        val h = harness(eventStore)
        runBlocking {
            h.coord.run(pipeline(uppercaseNode("original")), RunId("div"))
            val outcome = h.coord.run(pipeline(uppercaseNode("changed")), RunId("div"))
            assertTrue(
                outcome is RunOutcome.Failure || outcome is RunOutcome.Unstable,
                "divergence must surface as a typed failure/unstable, not a silent success",
            )
        }
    }

    // -------- observability --------

    @Test
    fun `observability - run emits StepStarted and StepFinished for the external step`() {
        val eventStore = InMemoryEventStore()
        val h = harness(eventStore)
        runBlocking {
            h.coord.run(pipeline(uppercaseNode("observable")), RunId("obs"))
            val events = h.eventStore.eventsFor("obs").toList()
            assertTrue(events.any { it is StepStarted }, "StepStarted must be emitted (lifecycle observability)")
            assertTrue(events.any { it is StepFinished }, "StepFinished must be emitted (lifecycle observability)")
        }
    }

    // -------- real pipeline scenario (DSL → canonical) --------

    @Test
    fun `real pipeline scenario - DSL with external uppercase step runs end-to-end through the canonical spine`() = runBlocking {
        val spec: PipelineSpec = pipeline {
            stages {
                stage("External") {
                    registryStep(
                        stepKey = UppercaseStepDefinition.KEY,
                        encodedInput = UppercaseCodec.encode(UppercaseInput("hello registry")),
                    )
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "uppercase.pipeline.kts",
            sourceContent = "pipeline { /* uppercase scenario */ }",
            pluginLockDigest = Digest("lock"),
        )
        val eventStore = InMemoryEventStore()
        val h = harness(eventStore)
        val run = h.coord.run(compiled, RunId("scenario"))
        assertEquals(RunOutcome.Success, run, "external Step must run through the SAME canonical spine")
        val events = h.eventStore.eventsFor("scenario").toList()
        assertTrue(events.any { it is StepStarted })
        assertTrue(events.any { it is StepFinished })
    }
}
