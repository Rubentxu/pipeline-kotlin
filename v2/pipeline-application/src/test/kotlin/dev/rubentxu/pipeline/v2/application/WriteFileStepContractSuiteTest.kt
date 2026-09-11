package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
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
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
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
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * StepContractSuite — LFC-2E1 / S2-A3 / G6 for `core.file.writeFile`.
 *
 * Certifies `core.file.writeFile` end-to-end across the registry-driven, open-world Step
 * seam, following the certified `core.sleep` (MEMOIZED) and `core.sh` (capability-routed,
 * effectful/recoverable) models.
 *
 * Coverage matrix:
 * ```
 *  1.  identity                                          REQUIRED
 *  2.  contract completeness                             REQUIRED
 *  3.  input codec round-trip                            REQUIRED
 *  4.  input codec rejection (foreign kind / blank file) REQUIRED
 *  5.  output codec round-trip                           REQUIRED
 *  6.  canonical envelope (byte-identical dsl-v1)        REQUIRED
 *  7.  production registry resolution                    REQUIRED
 *  8.  fresh factory consistency                         REQUIRED
 *  9.  capability declaration (WORKSPACE_OPERATIONS)     REQUIRED
 * 10.  capability admission (available succeeds)         REQUIRED
 * 11.  missing-capability rejection                      REQUIRED
 * 12.  success                                           REQUIRED
 * 13.  typed failure (handler exception)                 REQUIRED
 * 14.  fresh durable                                     REQUIRED
 * 15.  replay (effectful rerun law, like core.sh)        REQUIRED
 * 16.  divergence (typed failure on changed input)       REQUIRED
 * 17.  observability (StepStarted/StepFinished pair)     REQUIRED
 * 18.  real pipeline scenario (DSL `writeFile(...)`)     REQUIRED
 * 19.  architecture fitness                              DELEGATED to
 *      S3WriteFileLegacyRemovedFitnessTest + CoreWriteFileRegistryPrimaryFitnessTest (G5/G4)
 * ```
 *
 * Replay semantics follow the MEMOIZED law:
 *
 * ```
 * fresh / no durable entry          → EXECUTE (handler writes via the typed capability seam)
 * existing SUCCEEDED durable entry  → RERUN (WRITES_WORKSPACE re-executes idempotently;
 *                                     reuse is reserved for READ_ONLY, e.g. core.sleep)
 * existing entry, different input   → typed divergence failure (fail closed)
 * ```
 */
@Timeout(30)
class WriteFileStepContractSuiteTest {

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { CoreWriteFileStep.registerInto(this) }

    private fun noOpCredentialScopePort(): dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort =
        dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort { _, _ ->
            dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome.Unavailable(
                dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure.StoreUnavailable(
                    "step-contract-suite stub",
                ),
            )
        }

    private fun freshHarness(
        eventStore: InMemoryEventStore,
        workDir: java.nio.file.Path = Files.createTempDirectory("writefile-contract-"),
    ): Harness {
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
        return Harness(coord, journal, eventStore, workDir)
    }

    private data class Harness(
        val coord: CanonicalDurableRunCoordinator,
        val journal: InMemoryOperationJournal,
        val eventStore: InMemoryEventStore,
        val workDir: java.nio.file.Path,
    )

    private fun writeFileNode(file: String, text: String, encoding: String = "UTF-8", nodeId: String = "build/write") =
        OpaqueStepNode(
            id = StepId(nodeId),
            pluginStepId = CoreWriteFileStep.KEY,
            payload = VersionedStepPayload(
                schemaVersion = "dsl-v1",
                encoded = """{"kind":"writeFile","file":"$file","text":"$text","encoding":"$encoding"}""",
            ),
        )

    private fun pipeline(vararg nodes: OpaqueStepNode) = CompiledPipeline(
        id = DefinitionId("writefile-contract-suite"),
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

    // ===== 1. identity =====

    @Test
    fun `identity — CoreWriteFileStep KEY is core dot file dot writeFile and duplicate registration fails`() {
        assertEquals(PluginStepId("core.file.writeFile"), CoreWriteFileStep.KEY)
        assertEquals("core.file.writeFile", CoreWriteFileStep.KEY.value)
        val r = registry()
        assertTrue(
            runCatching { CoreWriteFileStep.registerInto(r) }.isFailure,
            "duplicate registration of core.file.writeFile must fail",
        )
    }

    // ===== 2. contract completeness =====

    @Test
    fun `contract completeness — key, descriptor, codecs, workspace capability, MEMOIZED`() {
        val contract = CoreWriteFileStep.definition.contract
        assertEquals(CoreWriteFileStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "StepDescriptor must be present")
        assertEquals("writeFile", contract.descriptor.name)
        assertEquals(
            setOf(Effect.WRITES_WORKSPACE),
            contract.descriptor.effects.toSet(),
            "core.file.writeFile effects MUST be WRITES_WORKSPACE",
        )
        assertEquals(
            ReplayPolicy.MEMOIZED,
            contract.descriptor.replayPolicy,
            "core.file.writeFile replayPolicy MUST be MEMOIZED (idempotent single-writer atomic write)",
        )
        assertEquals(
            setOf(WORKSPACE_OPERATIONS_CAPABILITY),
            contract.requiredCapabilities,
            "core.file.writeFile MUST declare exactly the WORKSPACE_OPERATIONS capability",
        )
        assertNotNull(contract.inputCodec, "input codec must be present")
        assertNotNull(contract.outputCodec, "output codec must be present")
    }

    // ===== 3. input codec round-trip =====

    @Test
    fun `codec input — encode and round-trip preserve file text encoding and envelope shape`() {
        val input = CoreWriteFileInput(file = "out.txt", text = "hello", encoding = "UTF-8")
        val encoded = CoreWriteFileStep.definition.contract.inputCodec.encode(input)
        assertEquals(
            """{"kind":"writeFile","file":"out.txt","text":"hello","encoding":"UTF-8"}""",
            encoded.value,
            "input codec must emit the canonical dsl-v1 envelope",
        )
        val decoded = CoreWriteFileStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(input, decoded, "round-trip encode -> decode must preserve all fields")
    }

    // ===== 4. input codec rejection =====

    @Test
    fun `codec input — decode rejects a non-writeFile payload kind`() {
        val foreign = EncodedStepValue("""{"kind":"echo","text":"x"}""")
        assertTrue(
            runCatching { CoreWriteFileStep.definition.contract.inputCodec.decode(foreign) }.isFailure,
            "decode must fail closed on a non-writeFile payload kind",
        )
    }

    @Test
    fun `codec input — decode rejects a missing file field`() {
        val malformed = EncodedStepValue("""{"kind":"writeFile","text":"x","encoding":"UTF-8"}""")
        assertTrue(
            runCatching { CoreWriteFileStep.definition.contract.inputCodec.decode(malformed) }.isFailure,
            "decode must fail closed on a missing file field",
        )
    }

    @Test
    fun `codec input — decode rejects a blank file path`() {
        val blank = EncodedStepValue("""{"kind":"writeFile","file":"  ","text":"x","encoding":"UTF-8"}""")
        assertTrue(
            runCatching { CoreWriteFileStep.definition.contract.inputCodec.decode(blank) }.isFailure,
            "decode must fail closed on a blank file path (CoreWriteFileInput invariant)",
        )
    }

    // ===== 5. output codec round-trip =====

    @Test
    fun `codec output — CoreWriteFileOutput round-trips byte-identically`() {
        val encoded = CoreWriteFileStep.definition.contract.outputCodec.encode(CoreWriteFileOutput)
        assertEquals(
            """{"kind":"writeFile","outcome":"SUCCESS"}""",
            encoded.value,
            "output codec must emit the canonical success envelope",
        )
        assertEquals(
            CoreWriteFileOutput,
            CoreWriteFileStep.definition.contract.outputCodec.decode(encoded),
            "round-trip decode must reconstruct the singleton CoreWriteFileOutput",
        )
    }

    @Test
    fun `codec output — decode rejects a non-success outcome`() {
        val bad = EncodedStepValue("""{"kind":"writeFile","outcome":"FAILURE"}""")
        assertTrue(
            runCatching { CoreWriteFileStep.definition.contract.outputCodec.decode(bad) }.isFailure,
            "output decode must fail closed on a non-SUCCESS outcome",
        )
    }

    // ===== 6. canonical envelope =====

    @Test
    fun `canonical envelope — input codec envelope is byte-identical to legacy dsl-v1 writeFile envelope`() {
        // Continuous durable fingerprint/journal identity requires byte-identical envelopes.
        val registryEnvelope =
            CoreWriteFileStep.definition.contract.inputCodec.encode(CoreWriteFileInput("o.txt", "t", "UTF-8")).value
        val legacyEnvelope = """{"kind":"writeFile","file":"o.txt","text":"t","encoding":"UTF-8"}"""
        assertEquals(
            legacyEnvelope,
            registryEnvelope,
            "registry input codec MUST emit a byte-identical dsl-v1 envelope",
        )
    }

    // ===== 7. production registry resolution =====

    @Test
    fun `registry resolution — production factory contains core dot file dot writeFile`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(registry.contains(CoreWriteFileStep.KEY), "production registry must contain core.file.writeFile")
        val definition = registry.definition(CoreWriteFileStep.KEY)
        assertNotNull(definition, "production registry MUST resolve core.file.writeFile to a StepDefinition")
        assertSame(
            CoreWriteFileStep.definition,
            definition,
            "production registry MUST return the canonical CoreWriteFileStep.definition instance",
        )
    }

    // ===== 8. fresh factory consistency =====

    @Test
    fun `registry resolution — production factory registry is fresh per call and consistent across calls`() {
        val r1 = CoreStepRegistryFactory.registry()
        val r2 = CoreStepRegistryFactory.registry()
        assertFalse(r1 === r2, "factory must produce fresh per-call registries")
        assertTrue(r1.contains(CoreWriteFileStep.KEY))
        assertTrue(r2.contains(CoreWriteFileStep.KEY))
        assertSame(r1.definition(CoreWriteFileStep.KEY), r2.definition(CoreWriteFileStep.KEY))
    }

    // ===== 9. capability declaration =====

    @Test
    fun `capability declaration — core file writeFile declares exactly the workspace operations capability`() {
        assertEquals(
            setOf(WORKSPACE_OPERATIONS_CAPABILITY),
            CoreWriteFileStep.definition.contract.requiredCapabilities,
            "declared capability MUST equal the used capability (handler reaches only WorkspaceOperations)",
        )
    }

    // ===== 10. capability admission =====

    @Test
    fun `capability admission — admission succeeds when the runtime exposes the workspace capability`() {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreWriteFileStep.KEY,
            encodedInput = CoreWriteFileStep.definition.contract.inputCodec
                .encode(CoreWriteFileInput("a.txt", "x", "UTF-8")),
            availableCapabilities = setOf(WORKSPACE_OPERATIONS_CAPABILITY),
        )
        assertTrue(
            preparation is ExecutionPreparation.Ready,
            "admission must succeed when the declared capability is available",
        )
    }

    // ===== 11. missing capability =====

    @Test
    fun `missing capability — admission rejects when the workspace capability is absent`() {
        val admission = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreWriteFileStep.KEY,
            encodedInput = CoreWriteFileStep.definition.contract.inputCodec
                .encode(CoreWriteFileInput("a.txt", "x", "UTF-8")),
            availableCapabilities = emptySet(),
        )
        assertTrue(
            admission is ExecutionPreparation.Rejected,
            "missing declared capability must surface as Rejected admission (fail-closed)",
        )
    }

    // ===== 12. success =====

    @Test
    fun `success — registry-routed writeFile SUCCEEDS and writes the file via the capability seam`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            val outcome = h.coord.run(pipeline(writeFileNode("out.txt", "hello")), RunId("wf-ok"))
            assertEquals(RunOutcome.Success, outcome, "writeFile must succeed")
            assertEquals(
                OperationStatus.SUCCEEDED,
                h.journal.listForRun("wf-ok").single().status,
            )
        }
    }

    // ===== 13. typed failure =====

    @Test
    fun `typed failure — a registry-routed writeFile whose handler throws surfaces as RunOutcome Failure`() {
        val throwingHandler: StepHandler<CoreWriteFileInput, CoreWriteFileOutput> =
            StepHandler { _: CoreWriteFileInput, _: StepHandlerContext ->
                throw IllegalStateException("core.file.writeFile handler contract violated for test")
            }
        val throwingRegistry = InMemoryStepRegistry().apply {
            register(
                object : StepDefinition<CoreWriteFileInput, CoreWriteFileOutput> {
                    override val contract: StepContract<CoreWriteFileInput, CoreWriteFileOutput> = StepContract(
                        key = CoreWriteFileStep.KEY,
                        descriptor = CoreWriteFileStep.definition.contract.descriptor,
                        inputCodec = CoreWriteFileStep.definition.contract.inputCodec,
                        outputCodec = CoreWriteFileStep.definition.contract.outputCodec,
                        requiredCapabilities = setOf(WORKSPACE_OPERATIONS_CAPABILITY),
                    )
                    override val handler: StepHandler<CoreWriteFileInput, CoreWriteFileOutput> = throwingHandler
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
            controlDirRoot = Files.createTempDirectory("writefile-contract-fail-"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = throwingRegistry,
        )
        runBlocking {
            val outcome = coord.run(pipeline(writeFileNode("f.txt", "t")), RunId("wf-throw"))
            assertTrue(
                outcome is RunOutcome.Failure,
                "handler exceptions must surface as a typed RunOutcome.Failure, not silent success; got $outcome",
            )
        }
    }

    // ===== 14. fresh durable =====

    @Test
    fun `fresh durable — first execution of core file writeFile writes one terminal SUCCEEDED operation`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            h.coord.run(pipeline(writeFileNode("out.txt", "hello")), RunId("wf-first"))
            val rows = h.journal.listForRun("wf-first")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    // ===== 15. replay (MEMOIZED + WRITES_WORKSPACE) =====

    @Test
    fun `replay — a previously SUCCEEDED writeFile re-executes idempotently per the effectful rerun law`() {
        // Effect-aware replay law (DefaultEffectReplayPolicy, mirroring core.sh):
        //   MEMOIZED + WRITES_WORKSPACE + existing SUCCEEDED entry → RERUN.
        // WRITES_WORKSPACE is an effectful family: the durable protocol re-executes the
        // handler on replay so the workspace is (idempotently) re-materialized; reuse is
        // reserved for READ_ONLY (core.sleep). The atomic-write substrate keeps the
        // re-execution single-writer safe.
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            h.coord.run(pipeline(writeFileNode("out.txt", "hello")), RunId("wf-replay"))
            val firstStarts = h.eventStore.eventsFor("wf-replay").filterIsInstance<StepStarted>().count()
            assertEquals(1, firstStarts, "first execution emits exactly one StepStarted")

            // Second execution at the SAME runId: effectful re-execution.
            val secondOutcome = h.coord.run(pipeline(writeFileNode("out.txt", "hello")), RunId("wf-replay"))
            assertEquals(RunOutcome.Success, secondOutcome, "re-executed writeFile must still succeed")
            val totalStarts = h.eventStore.eventsFor("wf-replay").filterIsInstance<StepStarted>().count()
            assertEquals(
                2,
                totalStarts,
                "WRITES_WORKSPACE replay MUST re-execute the handler (effectful rerun law, like core.sh)",
            )
            val terminalRows = h.journal.listForRun("wf-replay").filter {
                it.status == OperationStatus.SUCCEEDED
            }
            assertTrue(
                terminalRows.isNotEmpty(),
                "the replayed execution must leave a terminal SUCCEEDED row",
            )
        }
    }

    // ===== 16. divergence =====

    @Test
    fun `divergence — replaying a SUCCEEDED writeFile with different text fails closed as typed divergence`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            h.coord.run(pipeline(writeFileNode("out.txt", "hello")), RunId("wf-div"))
            // Changed text => different durable fingerprint => divergence surface.
            val outcome = h.coord.run(pipeline(writeFileNode("out.txt", "changed")), RunId("wf-div"))
            assertTrue(
                outcome is RunOutcome.Failure || outcome is RunOutcome.Unstable,
                "divergence must surface as a typed failure/unstable, not a silent success; got $outcome",
            )
        }
    }

    // ===== 17. observability =====

    @Test
    fun `observability — every core file writeFile run emits a StepStarted StepFinished pair`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            h.coord.run(pipeline(writeFileNode("out.txt", "hello")), RunId("wf-obs"))
            val events = h.eventStore.eventsFor("wf-obs").toList()
            assertTrue(events.any { it is StepStarted }, "StepStarted must be emitted (lifecycle observability)")
            assertTrue(events.any { it is StepFinished }, "StepFinished must be emitted (lifecycle observability)")
        }
    }

    // ===== 18. real pipeline scenario (DSL → canonical) =====

    @Test
    fun `real pipeline scenario — public DSL pipeline stage writeFile runs end-to-end`() = runBlocking {
        // The full DSL pipeline scenario, end-to-end through the public seam:
        //   pipeline { stage("write") { steps { writeFile(file="out.txt", text="hello") } } }
        val sourcePath = "Pipeline.kts"
        val sourceContent = """
            pipeline {
                stages {
                    stage("write") {
                        writeFile(file = "out.txt", text = "hello")
                    }
                }
            }
        """.trimIndent()
        val spec: PipelineSpec = pipeline {
            stages {
                stage("write") {
                    writeFile(file = "out.txt", text = "hello")
                }
            }
        }
        val workDir = Files.createTempDirectory("writefile-contract-real-pipeline-")
        try {
            val run = PipelineRule.run(
                spec = spec,
                sourcePath = sourcePath,
                sourceContent = sourceContent,
                runIdValue = "writefile-real-pipeline",
                workDir = workDir,
            )
            assertEquals(RunOutcome.Success, run.outcome, "real pipeline scenario must succeed")
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }
}
