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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * StepContractSuite — LFC-2E1 / S2-A2 / G6 for `core.sleep`.
 *
 * Certifies `core.sleep` end-to-end across the registry-driven, open-world Step seam following the
 * certified `core.echo` (MEMOIZED replay law) and `core.error` (typed-carrier) models.
 *
 * Coverage matrix:
 * ```
 *  1.  identity                                          REQUIRED
 *  2.  contract completeness                             REQUIRED
 *  3.  input codec round-trip                            REQUIRED
 *  4.  input codec rejection (foreign kind / negative)   REQUIRED
 *  5.  output codec round-trip                           REQUIRED
 *  6.  canonical envelope (byte-identical dsl-v1)        REQUIRED
 *  7.  production registry resolution                    REQUIRED
 *  8.  fresh factory consistency                         REQUIRED
 *  9.  capability declaration                            REQUIRED (empty)
 * 10.  capability admission (empty available succeeds)   REQUIRED
 * 11.  missing-capability rejection                      REQUIRED
 *      (admission-level, mirroring EchoStepContractSuiteTest: a hypothetical capability
 *       declared but absent MUST be rejected fail-closed — proves the seam, not a
 *       fabricated core.sleep contract)
 * 12.  success                                           REQUIRED
 * 13.  typed failure (handler exception)                 REQUIRED
 * 14.  fresh durable                                     REQUIRED
 * 15.  replay (MEMOIZED: reuse, no re-execution)         REQUIRED
 * 16.  divergence (typed failure on changed input)       REQUIRED
 * 17.  observability (StepStarted/StepFinished pair)     REQUIRED
 * 18.  real pipeline scenario (DSL `sleep(1)`)           REQUIRED
 * 19.  architecture fitness                              DELEGATED to
 *      S3SleepLegacyRemovedFitnessTest + CoreSleepRegistryPrimaryFitnessTest (G5)
 * ```
 *
 * Replay semantics follow the MEMOIZED law (NOT the core.error NEVER law):
 *
 * ```
 * fresh / no durable entry          → EXECUTE (handler suspends `seconds`, then SUCCESS)
 * existing SUCCEEDED durable entry  → REUSE (handler MUST NOT re-run; no second effect)
 * existing entry, different input   → typed divergence failure (fail closed)
 * ```
 */
@Timeout(30)
class SleepStepContractSuiteTest {

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { CoreSleepStep.registerInto(this) }

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
        workDir: java.nio.file.Path = Files.createTempDirectory("sleep-contract-"),
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
        return Harness(coord, journal, eventStore)
    }

    private data class Harness(
        val coord: CanonicalDurableRunCoordinator,
        val journal: InMemoryOperationJournal,
        val eventStore: InMemoryEventStore,
    )

    private fun sleepNode(seconds: Long, nodeId: String = "build/sleep") = OpaqueStepNode(
        id = StepId(nodeId),
        pluginStepId = CoreSleepStep.KEY,
        payload = VersionedStepPayload(
            schemaVersion = "dsl-v1",
            encoded = """{"kind":"sleep","seconds":$seconds}""",
        ),
    )

    private fun pipeline(vararg nodes: OpaqueStepNode) = CompiledPipeline(
        id = DefinitionId("sleep-contract-suite"),
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
    fun `identity — CoreSleepStep KEY is core dot sleep and duplicate registration fails`() {
        assertEquals(PluginStepId("core.sleep"), CoreSleepStep.KEY)
        assertEquals("core.sleep", CoreSleepStep.KEY.value)
        val r = registry()
        assertTrue(
            runCatching { CoreSleepStep.registerInto(r) }.isFailure,
            "duplicate registration of core.sleep must fail",
        )
    }

    // ===== 2. contract completeness =====

    @Test
    fun `contract completeness — key, descriptor, codecs, empty capabilities, MEMOIZED`() {
        val contract = CoreSleepStep.definition.contract
        assertEquals(CoreSleepStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "StepDescriptor must be present")
        assertEquals("sleep", contract.descriptor.name)
        assertEquals(
            setOf(Effect.READ_ONLY),
            contract.descriptor.effects.toSet(),
            "core.sleep effects MUST be READ_ONLY (temporal wait, no external mutation)",
        )
        assertEquals(
            ReplayPolicy.MEMOIZED,
            contract.descriptor.replayPolicy,
            "core.sleep replayPolicy MUST be MEMOIZED (idempotent temporal effect)",
        )
        assertEquals(
            emptySet<StepCapability>(),
            contract.requiredCapabilities,
            "core.sleep MUST declare empty requiredCapabilities (suspends, blocks nothing)",
        )
        assertNotNull(contract.inputCodec, "input codec must be present")
        assertNotNull(contract.outputCodec, "output codec must be present")
    }

    // ===== 3. input codec round-trip =====

    @Test
    fun `codec input — encode and round-trip preserve seconds and envelope shape`() {
        val input = CoreSleepInput(seconds = 3)
        val encoded = CoreSleepStep.definition.contract.inputCodec.encode(input)
        assertEquals(
            """{"kind":"sleep","seconds":3}""",
            encoded.value,
            "input codec must emit the canonical dsl-v1 envelope",
        )
        val decoded = CoreSleepStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(input, decoded, "round-trip encode -> decode must preserve seconds")
    }

    // ===== 4. input codec rejection =====

    @Test
    fun `codec input — decode rejects a non-sleep payload kind`() {
        val foreign = EncodedStepValue("""{"kind":"echo","text":"x"}""")
        assertTrue(
            runCatching { CoreSleepStep.definition.contract.inputCodec.decode(foreign) }.isFailure,
            "decode must fail closed on a non-sleep payload kind",
        )
    }

    @Test
    fun `codec input — decode rejects a missing seconds field`() {
        val malformed = EncodedStepValue("""{"kind":"sleep"}""")
        assertTrue(
            runCatching { CoreSleepStep.definition.contract.inputCodec.decode(malformed) }.isFailure,
            "decode must fail closed on a missing seconds field",
        )
    }

    @Test
    fun `codec input — decode rejects a negative seconds value`() {
        val negative = EncodedStepValue("""{"kind":"sleep","seconds":-1}""")
        assertTrue(
            runCatching { CoreSleepStep.definition.contract.inputCodec.decode(negative) }.isFailure,
            "decode must fail closed on seconds < 0 (CoreSleepInput invariant)",
        )
    }

    // ===== 5. output codec round-trip =====

    @Test
    fun `codec output — CoreSleepOutput round-trips byte-identically`() {
        val encoded = CoreSleepStep.definition.contract.outputCodec.encode(CoreSleepOutput)
        assertEquals(
            """{"kind":"sleep","outcome":"SUCCESS"}""",
            encoded.value,
            "output codec must emit the canonical success envelope",
        )
        assertEquals(
            CoreSleepOutput,
            CoreSleepStep.definition.contract.outputCodec.decode(encoded),
            "round-trip decode must reconstruct the singleton CoreSleepOutput",
        )
    }

    @Test
    fun `codec output — decode rejects a non-success outcome`() {
        val bad = EncodedStepValue("""{"kind":"sleep","outcome":"FAILURE"}""")
        assertTrue(
            runCatching { CoreSleepStep.definition.contract.outputCodec.decode(bad) }.isFailure,
            "output decode must fail closed on a non-SUCCESS outcome",
        )
    }

    // ===== 6. canonical envelope =====

    @Test
    fun `canonical envelope — input codec envelope is byte-identical to legacy dsl-v1 sleep envelope`() {
        // Continuous durable fingerprint/journal identity requires byte-identical envelopes.
        val registryEnvelope = CoreSleepStep.definition.contract.inputCodec.encode(CoreSleepInput(5)).value
        val legacyEnvelope = """{"kind":"sleep","seconds":5}"""
        assertEquals(
            legacyEnvelope,
            registryEnvelope,
            "registry input codec MUST emit a byte-identical dsl-v1 envelope",
        )
    }

    // ===== 7. production registry resolution =====

    @Test
    fun `registry resolution — production factory contains core dot sleep`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(registry.contains(CoreSleepStep.KEY), "production registry must contain core.sleep")
        val definition = registry.definition(CoreSleepStep.KEY)
        assertNotNull(definition, "production registry MUST resolve core.sleep to a StepDefinition")
        assertSame(
            CoreSleepStep.definition,
            definition,
            "production registry MUST return the canonical CoreSleepStep.definition instance",
        )
    }

    // ===== 8. fresh factory consistency =====

    @Test
    fun `registry resolution — production factory registry is fresh per call and consistent across calls`() {
        val r1 = CoreStepRegistryFactory.registry()
        val r2 = CoreStepRegistryFactory.registry()
        assertFalse(r1 === r2, "factory must produce fresh per-call registries")
        assertTrue(r1.contains(CoreSleepStep.KEY))
        assertTrue(r2.contains(CoreSleepStep.KEY))
        assertSame(r1.definition(CoreSleepStep.KEY), r2.definition(CoreSleepStep.KEY))
    }

    // ===== 9. capability declaration =====

    @Test
    fun `capability declaration — core sleep declares empty required capabilities`() {
        // The handler suspends via delay(); it reaches no coordinator, journal, event sink,
        // or process executor. No capability is required for admission.
        assertEquals(
            emptySet<StepCapability>(),
            CoreSleepStep.definition.contract.requiredCapabilities,
            "CoreSleepStep MUST declare empty requiredCapabilities (handler is pure suspension)",
        )
    }

    // ===== 10. capability admission =====

    @Test
    fun `capability admission — admission succeeds when the runtime exposes zero capabilities`() {
        // Empty-declared contract: regardless of available capabilities, admission succeeds.
        // Proves fail-closed admission does NOT over-reject a zero-capability Step.
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreSleepStep.KEY,
            encodedInput = CoreSleepStep.definition.contract.inputCodec.encode(CoreSleepInput(0)),
            availableCapabilities = emptySet(),
        )
        assertTrue(
            preparation is ExecutionPreparation.Ready,
            "admission must succeed with an empty available set (empty declared capabilities)",
        )
    }

    // ===== 11. missing capability =====

    @Test
    fun `missing capability — admission rejects when a declared capability is absent`() {
        // Admission-level proof mirroring EchoStepContractSuiteTest: a definition that declares
        // a capability the runtime does not expose MUST be rejected fail-closed before the
        // handler can run. core.sleep itself declares none; this pins the seam.
        val absent = StepCapability("missing.capability.never.declared")
        val altRegistry = InMemoryStepRegistry().apply {
            register(
                object : StepDefinition<CoreSleepInput, CoreSleepOutput> {
                    override val contract: StepContract<CoreSleepInput, CoreSleepOutput> = StepContract(
                        key = CoreSleepStep.KEY,
                        descriptor = CoreSleepStep.definition.contract.descriptor,
                        inputCodec = CoreSleepStep.definition.contract.inputCodec,
                        outputCodec = CoreSleepStep.definition.contract.outputCodec,
                        requiredCapabilities = setOf<StepCapability>(absent),
                    )
                    override val handler: StepHandler<CoreSleepInput, CoreSleepOutput> =
                        CoreSleepStep.definition.handler
                },
            )
        }
        val admission = RegistryExecutionPreparation.prepare(
            registry = altRegistry,
            key = CoreSleepStep.KEY,
            encodedInput = CoreSleepStep.definition.contract.inputCodec.encode(CoreSleepInput(1)),
            availableCapabilities = emptySet(),
        )
        assertTrue(
            admission is ExecutionPreparation.Rejected,
            "missing capability must surface as Rejected admission (fail-closed)",
        )
    }

    // ===== 12. success =====

    @Test
    fun `success — registry-routed sleep SUCCEEDS with one terminal SUCCEEDED operation`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            val outcome = h.coord.run(pipeline(sleepNode(0)), RunId("sleep-ok"))
            assertEquals(RunOutcome.Success, outcome, "a 0-second sleep must succeed")
            assertEquals(
                OperationStatus.SUCCEEDED,
                h.journal.listForRun("sleep-ok").single().status,
            )
        }
    }

    // ===== 13. typed failure =====

    @Test
    fun `typed failure — a registry-routed sleep whose handler throws surfaces as RunOutcome Failure`() {
        val throwingHandler: StepHandler<CoreSleepInput, CoreSleepOutput> =
            StepHandler { _: CoreSleepInput, _: StepHandlerContext ->
                throw IllegalStateException("core.sleep handler contract violated for test")
            }
        val throwingRegistry = InMemoryStepRegistry().apply {
            register(
                object : StepDefinition<CoreSleepInput, CoreSleepOutput> {
                    override val contract: StepContract<CoreSleepInput, CoreSleepOutput> = StepContract(
                        key = CoreSleepStep.KEY,
                        descriptor = CoreSleepStep.definition.contract.descriptor,
                        inputCodec = CoreSleepStep.definition.contract.inputCodec,
                        outputCodec = CoreSleepStep.definition.contract.outputCodec,
                        requiredCapabilities = emptySet(),
                    )
                    override val handler: StepHandler<CoreSleepInput, CoreSleepOutput> = throwingHandler
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
            controlDirRoot = Files.createTempDirectory("sleep-contract-fail-"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = throwingRegistry,
        )
        runBlocking {
            val outcome = coord.run(pipeline(sleepNode(1)), RunId("sleep-throw"))
            assertTrue(
                outcome is RunOutcome.Failure,
                "handler exceptions must surface as a typed RunOutcome.Failure, not silent success; got $outcome",
            )
        }
    }

    // ===== 14. fresh durable =====

    @Test
    fun `fresh durable — first execution of core dot sleep writes one terminal SUCCEEDED operation`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            h.coord.run(pipeline(sleepNode(0)), RunId("sleep-first"))
            val rows = h.journal.listForRun("sleep-first")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    // ===== 15. replay (MEMOIZED) =====

    @Test
    fun `replay — a previously SUCCEEDED sleep is reused without re-running the handler`() {
        // MEMOIZED law (NOT the core.error NEVER law):
        //   fresh / no durable entry  → EXECUTE
        //   existing SUCCEEDED entry  → REUSE; handler MUST NOT re-run.
        // Handler-invocation invariant is observed via the count of journaled terminal rows
        // and StepStarted events: both stay at 1 after the replay.
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            h.coord.run(pipeline(sleepNode(0)), RunId("sleep-replay"))
            val firstStarts = h.eventStore.eventsFor("sleep-replay").filterIsInstance<StepStarted>().count()
            assertEquals(1, firstStarts, "first execution emits exactly one StepStarted")

            // Second execution at the SAME runId: MEMOIZED reuse.
            h.coord.run(pipeline(sleepNode(0)), RunId("sleep-replay"))
            val secondStarts = h.eventStore.eventsFor("sleep-replay").filterIsInstance<StepStarted>().count()
            assertEquals(
                1,
                secondStarts,
                "replay must NOT re-run the handler (StepStarted count stays at 1)",
            )
            assertEquals(
                1,
                h.journal.listForRun("sleep-replay").count(),
                "replay must reuse the single terminal SUCCEEDED row",
            )
        }
    }

    // ===== 16. divergence =====

    @Test
    fun `divergence — replaying a SUCCEEDED sleep with different seconds fails closed as typed divergence`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            h.coord.run(pipeline(sleepNode(1)), RunId("sleep-div"))
            // Changed seconds => different durable fingerprint => divergence surface.
            val outcome = h.coord.run(pipeline(sleepNode(2)), RunId("sleep-div"))
            assertTrue(
                outcome is RunOutcome.Failure || outcome is RunOutcome.Unstable,
                "divergence must surface as a typed failure/unstable, not a silent success; got $outcome",
            )
        }
    }

    // ===== 17. observability =====

    @Test
    fun `observability — every core dot sleep run emits a StepStarted StepFinished pair`() {
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            h.coord.run(pipeline(sleepNode(0)), RunId("sleep-obs"))
            val events = h.eventStore.eventsFor("sleep-obs").toList()
            assertTrue(events.any { it is StepStarted }, "StepStarted must be emitted (lifecycle observability)")
            assertTrue(events.any { it is StepFinished }, "StepFinished must be emitted (lifecycle observability)")
        }
    }

    // ===== 18. real pipeline scenario (DSL → canonical) =====

    @Test
    fun `real pipeline scenario — public DSL pipeline stage sleep 1 echo woke runs end-to-end`() = runBlocking {
        // The full DSL pipeline scenario, end-to-end through the public seam:
        //   pipeline { stage("sleep") { steps { sleep(1); echo("woke") } } }
        val sourcePath = "Pipeline.kts"
        val sourceContent = """
            pipeline {
                stages {
                    stage("sleep") {
                        sleep(1)
                        echo("woke")
                    }
                }
            }
        """.trimIndent()
        val spec: PipelineSpec = pipeline {
            stages {
                stage("sleep") {
                    sleep(1)
                    echo("woke")
                }
            }
        }
        val workDir = Files.createTempDirectory("sleep-contract-real-pipeline-")
        try {
            val run = PipelineRule.run(
                spec = spec,
                sourcePath = sourcePath,
                sourceContent = sourceContent,
                runIdValue = "sleep-real-pipeline",
                workDir = workDir,
            )
            assertEquals(RunOutcome.Success, run.outcome, "real pipeline scenario must succeed")
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }
}
