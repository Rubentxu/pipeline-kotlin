package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
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
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.application.MilestoneOperations
import dev.rubentxu.pipeline.v2.application.MilestoneOperationsAdapter
import dev.rubentxu.pipeline.v2.application.MilestoneStateStore
import dev.rubentxu.pipeline.v2.application.support.PipelineRule
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.MilestoneAborted
import dev.rubentxu.pipeline.v2.events.MilestoneReached
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files

/**
 * StepContractSuite — LFC-2E1 / S2-A9 / G3 for `core.milestone`.
 *
 * Certifies `core.milestone` end-to-end across the registry-driven, open-world Step seam
 * following the certified `CorePwdStepContractSuiteTest` model. The suite proves the
 * registration contract is complete: identity, codec, envelope, dispatch, capability admission,
 * durable identity (fresh + replay), observability, missing capability, and a real pipeline
 * scenario through the public DSL.
 *
 * Coverage matrix:
 * ```
 *  1.  identity                                              REQUIRED
 *  2.  contract completeness                                 REQUIRED (key + descriptor + 1 cap + MEMOIZED)
 *  3.  input codec round-trip                              REQUIRED
 *  4.  input codec rejection (foreign kind)                REQUIRED
 *  4b. input codec rejection (non-positive ordinal)        REQUIRED
 *  5.  output codec round-trip (Reached)                  REQUIRED
 *  5b. output codec round-trip (Aborted)                  REQUIRED
 *  6.  canonical envelope (well-formed JSON, kind=milestone) REQUIRED
 *  7.  registry resolution (production factory)            REQUIRED
 *  8.  fresh factory consistency                          REQUIRED
 *  9.  capability admission (EVENT_SINK present → Ready)    REQUIRED
 * 10.  success (registry path: MilestoneReached + Success)  REQUIRED
 * 11.  aborted (registry path: MilestoneAborted + Unstable) REQUIRED
 * 12.  fresh durable (1 terminal SUCCEEDED row)          REQUIRED
 * 13.  replay (MEMOIZED: reuse, no handler re-run)        REQUIRED
 * 14.  missing capability (EVENT_SINK absent → Rejected)  REQUIRED
 * 15.  real pipeline scenario (DSL milestone ordinals)   REQUIRED
 * ```
 *
 * Note: milestone has no typed failure case (handler never throws) and no divergence
 * case (input comparison is not part of the milestone contract). These are N/A
 * for this Step family.
 */
@Timeout(30)
class CoreMilestoneStepContractSuiteTest {

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { CoreMilestoneStep.registerInto(this) }

    private fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable("step-contract-suite stub"),
        )
    }

    private fun freshHarness(
        eventStore: InMemoryEventStore,
        workDir: java.nio.file.Path = Files.createTempDirectory("milestone-contract-"),
    ): Harness {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        // S2-A9 spike: create a MilestoneStateStore scoped to this harness instance.
        // The store is passed to the coordinator so it can provide MILESTONE_OPERATIONS_CAPABILITY.
        val milestoneStore = MilestoneStateStore()
        val registryForCoord = CoreStepRegistryFactory.registry()
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
            stepRegistry = registryForCoord,
            // S2-A9 spike: bind the milestone store to the coordinator.
            // The coordinator wires it through ExecutionBoundaryFactory to RegistryExecutionBoundary,
            // which populates MILESTONE_OPERATIONS_CAPABILITY when building CanonicalRuntimeCapabilityAccess.
            milestoneStateStore = milestoneStore,
        )
        return Harness(coord, journal, eventStore, registryForCoord, workDir, milestoneStore)
    }

    private data class Harness(
        val coord: CanonicalDurableRunCoordinator,
        val journal: InMemoryOperationJournal,
        val eventStore: InMemoryEventStore,
        val registry: dev.rubentxu.pipeline.v2.domain.step.StepRegistry,
        val workDir: java.nio.file.Path,
        // S2-A9 spike: milestone store for this harness instance.
        // Tests can create MilestoneOperations from it for direct handler testing.
        val milestoneStore: MilestoneStateStore,
    )

    private fun milestoneNode(ordinal: Int, label: String?, nodeId: String = "build/milestone"): OpaqueStepNode {
        val encoded = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", JsonPrimitive("milestone"))
            put("ordinal", JsonPrimitive(ordinal))
            label?.let { put("label", JsonPrimitive(it)) }
        })
        return OpaqueStepNode(
            id = StepId(nodeId),
            pluginStepId = CoreMilestoneStep.KEY,
            payload = VersionedStepPayload(
                schemaVersion = "dsl-v1",
                encoded = encoded,
            ),
        )
    }

    private fun pipeline(vararg nodes: OpaqueStepNode) = CompiledPipeline(
        id = DefinitionId("milestone-contract-suite"),
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

    // S2-A9 spike: fresh milestone store created per test — no resetState() needed.
    @BeforeEach
    fun setup() {
        // No longer calling CoreMilestoneStep.resetState()
        // Each test gets its own MilestoneStateStore via freshHarness
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 1. identity
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `identity — CoreMilestoneStep KEY is core dot milestone and unique`() {
        assertEquals(PluginStepId("core.milestone"), CoreMilestoneStep.KEY)
        assertEquals("core.milestone", CoreMilestoneStep.KEY.value)
        val r = registry()
        assertTrue(
            runCatching { CoreMilestoneStep.registerInto(r) }.isFailure,
            "duplicate registration of core.milestone must fail",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 2. contract completeness
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `contract completeness — key, descriptor, codecs, EVENT_SINK_CAPABILITY, MILESTONE_OPERATIONS_CAPABILITY, MEMOIZED`() {
        val contract = CoreMilestoneStep.definition.contract
        assertEquals(CoreMilestoneStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "StepDescriptor must be present")
        assertEquals("milestone", contract.descriptor.name)
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.Effect.READ_ONLY,
            contract.descriptor.effects.single(),
            "core.milestone effects MUST be READ_ONLY",
        )
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy.MEMOIZED,
            contract.descriptor.replayPolicy,
            "core.milestone replayPolicy MUST be MEMOIZED",
        )
        // S2-A9 spike: milestone now requires both EVENT_SINK and MILESTONE_OPERATIONS capabilities
        assertEquals(
            setOf<StepCapability>(EVENT_SINK_CAPABILITY, MILESTONE_OPERATIONS_CAPABILITY),
            contract.requiredCapabilities,
            "core.milestone MUST declare both EVENT_SINK_CAPABILITY and MILESTONE_OPERATIONS_CAPABILITY as required",
        )
        assertNotNull(contract.inputCodec, "input codec must be present")
        assertNotNull(contract.outputCodec, "output codec must be present")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3. input codec round-trip
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `codec input — round-trip preserves ordinal and label`() {
        val input = MilestoneInput(ordinal = 1, label = "contractual")
        val encoded = CoreMilestoneStep.definition.contract.inputCodec.encode(input)
        val decoded = CoreMilestoneStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(input, decoded, "round-trip encode -> decode must preserve ordinal and label")
    }

    @Test
    fun `codec input — round-trip preserves null label`() {
        val input = MilestoneInput(ordinal = 5, label = null)
        val encoded = CoreMilestoneStep.definition.contract.inputCodec.encode(input)
        val decoded = CoreMilestoneStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(input, decoded)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 4. input codec rejection
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `codec input — decode rejects a non-milestone payload kind`() {
        val foreign = EncodedStepValue(Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", JsonPrimitive("not-milestone"))
            put("ordinal", JsonPrimitive(1))
        }))
        assertTrue(
            runCatching { CoreMilestoneStep.definition.contract.inputCodec.decode(foreign) }.isFailure,
            "decode must fail closed on a non-milestone payload kind",
        )
    }

    @Test
    fun `codec input — decode rejects non-positive ordinal`() {
        val zeroOrdinal = EncodedStepValue(Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", JsonPrimitive("milestone"))
            put("ordinal", JsonPrimitive(0))
        }))
        assertTrue(
            runCatching { CoreMilestoneStep.definition.contract.inputCodec.decode(zeroOrdinal) }.isFailure,
            "decode must fail closed on ordinal=0",
        )

        val negativeOrdinal = EncodedStepValue(Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", JsonPrimitive("milestone"))
            put("ordinal", JsonPrimitive(-1))
        }))
        assertTrue(
            runCatching { CoreMilestoneStep.definition.contract.inputCodec.decode(negativeOrdinal) }.isFailure,
            "decode must fail closed on negative ordinal",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 5. output codec round-trip
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `codec output — MilestoneOutput(Reached) round-trips byte-identically`() {
        val output = MilestoneOutput(
            ordinal = 3,
            label = "deploy",
            status = MilestoneStatus.Reached,
        )
        val encoded = CoreMilestoneStep.definition.contract.outputCodec.encode(output)
        val decoded = CoreMilestoneStep.definition.contract.outputCodec.decode(encoded)
        assertEquals(output, decoded, "output codec round-trip must preserve Reached output")
    }

    @Test
    fun `codec output — MilestoneOutput(Aborted) round-trips byte-identically`() {
        val output = MilestoneOutput(
            ordinal = 1,
            label = "duplicate",
            status = MilestoneStatus.Aborted("ordinal-already-reached (previous=1)"),
        )
        val encoded = CoreMilestoneStep.definition.contract.outputCodec.encode(output)
        val decoded = CoreMilestoneStep.definition.contract.outputCodec.decode(encoded)
        assertInstanceOf(MilestoneStatus.Aborted::class.java, decoded.status)
        assertEquals(
            (output.status as MilestoneStatus.Aborted).reason,
            (decoded.status as MilestoneStatus.Aborted).reason,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 6. canonical envelope
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `canonical envelope — emitted envelope is a well-formed JSON object with kind=milestone`() {
        val encoded = CoreMilestoneStep.definition.contract.inputCodec.encode(
            MilestoneInput(ordinal = 2, label = "production"),
        )
        val parsed = Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is JsonObject, "canonical milestone envelope must be a JSON object for durable-spine eligibility")
        val obj = parsed as JsonObject
        assertEquals("milestone", obj["kind"]?.let { (it as JsonPrimitive).content })
        assertEquals(2, obj["ordinal"]?.let { (it as JsonPrimitive).content?.toInt() })
        assertEquals("production", obj["label"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `canonical envelope — null label is omitted from encoded payload`() {
        val encoded = CoreMilestoneStep.definition.contract.inputCodec.encode(
            MilestoneInput(ordinal = 1, label = null),
        )
        assertFalse(encoded.value.contains("\"label\""), "null label must be omitted")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 7. registry resolution
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `registry resolution — production factory contains core dot milestone`() {
        val r = CoreStepRegistryFactory.registry()
        assertTrue(r.contains(CoreMilestoneStep.KEY))
        assertEquals(CoreMilestoneStep.KEY, r.definition(CoreMilestoneStep.KEY)?.contract?.key)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 8. fresh factory consistency
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `registry resolution — production factory registry is fresh per call and consistent`() {
        val a = CoreStepRegistryFactory.registry()
        val b = CoreStepRegistryFactory.registry()
        assertFalse(a === b, "factory must produce fresh per-call registries")
        assertTrue(a.contains(CoreMilestoneStep.KEY))
        assertTrue(b.contains(CoreMilestoneStep.KEY))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 9. capability admission
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `capability admission — admission succeeds when both EVENT_SINK_CAPABILITY and MILESTONE_OPERATIONS_CAPABILITY are present`() {
        val encoded = CoreMilestoneStep.definition.contract.inputCodec.encode(
            MilestoneInput(ordinal = 1, label = "admitted"),
        )
        val preparation = RegistryExecutionPreparation.prepare(
            registry = registry(),
            key = CoreMilestoneStep.KEY,
            encodedInput = encoded,
            // S2-A9 spike: milestone now requires MILESTONE_OPERATIONS_CAPABILITY
            availableCapabilities = setOf<StepCapability>(
                EVENT_SINK_CAPABILITY,
                MILESTONE_OPERATIONS_CAPABILITY,
            ),
        )
        assertTrue(
            preparation is ExecutionPreparation.Ready,
            "admission must succeed when both EVENT_SINK and MILESTONE_OPERATIONS are available",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 10. success (registry path: MilestoneReached + Success)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `success — registry-routed milestone emits MilestoneReached and SUCCEEDS`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            val outcome = h.coord.run(
                pipeline(milestoneNode(ordinal = 1, label = "first")),
                RunId("milestone-success"),
            )
            assertEquals(RunOutcome.Success, outcome)
            val reached = h.eventStore.eventsFor("milestone-success").toList().filterIsInstance<MilestoneReached>()
            assertEquals(1, reached.size, "exactly one MilestoneReached emitted")
            assertEquals(1, reached.single().ordinal)
            assertEquals("first", reached.single().label)
            assertEquals(OperationStatus.SUCCEEDED, h.journal.listForRun("milestone-success").single().status)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 11. aborted (registry path: MilestoneAborted + Unstable)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `aborted — registry-routed milestone emits MilestoneAborted and returns Unstable`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            // Both milestones in the SAME pipeline so they share handler companion state.
            // First milestone (ordinal 2) → Reached.
            // Second milestone (ordinal 1 < 2) → Aborted.
            val outcome = h.coord.run(
                pipeline(
                    milestoneNode(ordinal = 2, label = "second", nodeId = "build/m2"),
                    milestoneNode(ordinal = 1, label = "first", nodeId = "build/m1"),
                ),
                RunId("milestone-aborted"),
            )
            assertEquals(RunOutcome.Unstable, outcome)
            val events = h.eventStore.eventsFor("milestone-aborted").toList()
            val reached = events.filterIsInstance<MilestoneReached>()
            val aborted = events.filterIsInstance<MilestoneAborted>()
            assertEquals(1, reached.size, "exactly one MilestoneReached emitted")
            assertEquals(2, reached.single().ordinal, "ordinal 2 must be reached")
            assertEquals(1, aborted.size, "exactly one MilestoneAborted emitted")
            assertEquals(1, aborted.single().ordinal, "ordinal 1 must be aborted")
            assertTrue(
                aborted.single().reason.contains("ordinal-already-reached"),
                "reason must mention ordinal-already-reached",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 12. fresh durable
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `fresh durable — first execution writes one terminal SUCCEEDED operation`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            h.coord.run(
                pipeline(milestoneNode(ordinal = 1, label = "fresh")),
                RunId("milestone-fresh"),
            )
            val rows = h.journal.listForRun("milestone-fresh")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 13. replay (MEMOIZED: reuse without re-running handler)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `replay — a previously SUCCEEDED milestone is reused without re-running the handler`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            // First execution: emits MilestoneReached.
            h.coord.run(
                pipeline(milestoneNode(ordinal = 1, label = "replay-me")),
                RunId("milestone-replay"),
            )
            val first = h.eventStore.eventsFor("milestone-replay").toList()
                .filterIsInstance<MilestoneReached>().count()
            assertEquals(1, first, "fresh execution must emit exactly one MilestoneReached")

            // Second execution with same runId: must REUSE the SUCCEEDED op
            // without re-running the handler. No duplicate MilestoneReached.
            h.coord.run(
                pipeline(milestoneNode(ordinal = 1, label = "replay-me")),
                RunId("milestone-replay"),
            )
            val second = h.eventStore.eventsFor("milestone-replay").toList()
                .filterIsInstance<MilestoneReached>().count()
            assertEquals(1, second, "replay must NOT re-emit MilestoneReached (handler did not run)")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 14. missing capability
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `missing capability — admission rejects when EVENT_SINK is absent`() {
        val absent = StepCapability("missing.capability.never.declared")
        val altRegistry = InMemoryStepRegistry().apply {
            register(object : StepDefinition<MilestoneInput, MilestoneOutput> {
                override val contract = StepContract(
                    key = CoreMilestoneStep.KEY,
                    descriptor = CoreMilestoneStep.definition.contract.descriptor,
                    inputCodec = CoreMilestoneStep.definition.contract.inputCodec,
                    outputCodec = CoreMilestoneStep.definition.contract.outputCodec,
                    // S2-A9 spike: milestone requires both capabilities
                    requiredCapabilities = setOf(absent, MILESTONE_OPERATIONS_CAPABILITY),
                )
                override val handler = CoreMilestoneStep.definition.handler
            })
        }
        val admission = RegistryExecutionPreparation.prepare(
            registry = altRegistry,
            key = CoreMilestoneStep.KEY,
            encodedInput = CoreMilestoneStep.definition.contract.inputCodec.encode(
                MilestoneInput(ordinal = 1, label = "orphan"),
            ),
            // Only MILESTONE_OPERATIONS present, not EVENT_SINK
            availableCapabilities = setOf(MILESTONE_OPERATIONS_CAPABILITY),
        )
        assertTrue(
            admission is ExecutionPreparation.Rejected,
            "missing EVENT_SINK capability must surface as Rejected admission (fail-closed)",
        )
    }

    // S2-A9 spike: test that missing MILESTONE_OPERATIONS also fails admission
    @Test
    fun `missing capability — admission rejects when MILESTONE_OPERATIONS is absent`() {
        val absent = StepCapability("missing.capability.never.declared")
        val altRegistry = InMemoryStepRegistry().apply {
            register(object : StepDefinition<MilestoneInput, MilestoneOutput> {
                override val contract = StepContract(
                    key = CoreMilestoneStep.KEY,
                    descriptor = CoreMilestoneStep.definition.contract.descriptor,
                    inputCodec = CoreMilestoneStep.definition.contract.inputCodec,
                    outputCodec = CoreMilestoneStep.definition.contract.outputCodec,
                    // S2-A9 spike: milestone requires both capabilities
                    requiredCapabilities = setOf(EVENT_SINK_CAPABILITY, absent),
                )
                override val handler = CoreMilestoneStep.definition.handler
            })
        }
        val admission = RegistryExecutionPreparation.prepare(
            registry = altRegistry,
            key = CoreMilestoneStep.KEY,
            encodedInput = CoreMilestoneStep.definition.contract.inputCodec.encode(
                MilestoneInput(ordinal = 1, label = "orphan"),
            ),
            // Only EVENT_SINK present, not MILESTONE_OPERATIONS
            availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
        )
        assertTrue(
            admission is ExecutionPreparation.Rejected,
            "missing MILESTONE_OPERATIONS capability must surface as Rejected admission (fail-closed)",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 15. real pipeline scenario (DSL → canonical)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `real pipeline scenario — DSL pipeline with milestone ordinals runs end-to-end`() =
        runBlocking {
            val sourcePath = "Pipeline.kts"
            val sourceContent = """
                pipeline {
                    stages {
                        stage("build") {
                            milestone(1, "start")
                            milestone(2, "build")
                            milestone(3, "deploy")
                        }
                    }
                }
            """.trimIndent()
            val spec: PipelineSpec = pipeline {
                stages {
                    stage("build") {
                        milestone(1, "start")
                        milestone(2, "build")
                        milestone(3, "deploy")
                    }
                }
            }
            val workDir = Files.createTempDirectory("milestone-contract-real-pipeline-")
            try {
                val run = PipelineRule.run(
                    spec = spec,
                    sourcePath = sourcePath,
                    sourceContent = sourceContent,
                    runIdValue = "real-pipeline",
                    workDir = workDir,
                )
                assertEquals(RunOutcome.Success, run.outcome, "pipeline must succeed")

                val reached = run.events.filterIsInstance<MilestoneReached>()
                assertEquals(3, reached.size, "must emit 3 MilestoneReached events")
                assertEquals(listOf(1, 2, 3), reached.map { it.ordinal }, "ordinals must be increasing")
                assertEquals(listOf("start", "build", "deploy"), reached.map { it.label }, "labels must match")
            } finally {
                workDir.toFile().deleteRecursively()
            }
        }
}
