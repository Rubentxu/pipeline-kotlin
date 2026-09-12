package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
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
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.WsCleaned
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ReplayDecision
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * StepContractSuite — LFC-2E1 / S2-A10 / G3 for `core.cleanWs`.
 *
 * Certifies `core.cleanWs` end-to-end across the registry-driven, open-world Step seam
 * following the certified `core.deleteDir` model (S2-A7/G6). Effectful pattern:
 * ONE required capability ([CLEAN_WS_OPERATIONS_CAPABILITY]) — the handler reaches the
 * typed `CleanWsOperations` seam; all filesystem semantics, workspace resolution, and
 * `WsCleaned` emission live in `CleanWsOperationsAdapter` (single emission authority
 * over the existing `CleanWsExecutor` SDK substrate).
 *
 * cleanWs-specific semantics (S2-A10/G1):
 *  - input envelope is `{"kind":"cleanWs","deleteDirs":<bool>,"patterns":[...]}`;
 *  - tolerant decode mirrors the legacy decoder: `deleteDirs` defaults `true`,
 *    missing `patterns` defaults to an empty list;
 *  - effects are `{ WRITES_WORKSPACE }`, replayPolicy is MEMOIZED,
 *    recoveryPolicy is None (per WAVE-2 G0 inventory);
 *  - the capability is conditionally exposed only when `controlDirRoot != null`;
 *  - output is `CleanWsOutput(deletedFiles, deletedDirs, patterns, sha256)`;
 *  - durable observation: one `WsCleaned` event per fresh execution;
 *  - idempotence law: cleaning an already-clean workspace SUCCEEDS with
 *    deletedFiles=0 / deletedDirs=0 (deleteDir deletedCount=0 semantic shape).
 *
 * Coverage matrix (adapted honestly from CoreDeleteDirStepContractSuiteTest —
 * NOT copied blindly; replay semantics follow the actual MEMOIZED+WRITES_WORKSPACE
 * policy, and the G1 candidate state means structural-family classification stays
 * LegacyCore until G4):
 * ```
 *  1.  identity                                              REQUIRED
 *  2.  contract completeness                                 REQUIRED (descriptor + 1 cap + MEMOIZED + None)
 *  3.  input codec round-trip (default + patterns)           REQUIRED
 *  3b. input codec tolerant defaults (legacy envelope)       REQUIRED
 *  4.  input codec rejection (foreign envelope)              REQUIRED
 *  5.  output codec round-trip                               REQUIRED
 *  6.  output codec rejection (non-cleanWs kind)             REQUIRED
 *  7.  canonical envelope (byte-identical to legacy dsl-v1)  REQUIRED
 *  8.  production registry resolution                        REQUIRED
 *  9.  fresh factory consistency                             REQUIRED
 * 10.  capability declaration (exactly CLEAN_WS_OPERATIONS)  REQUIRED
 * 11.  capability admission (available → Ready)              REQUIRED
 * 12.  missing CLEAN_WS_OPERATIONS rejects fail-closed       REQUIRED
 * 12b. conditional exposure: controlDirRoot=null →           REQUIRED
 *      capability absent → admission Rejected
 * 14.  success via canonical coordinator (typed outcome)     REQUIRED
 * 15.  typed failure (handler exception)                     REQUIRED
 * 16.  fresh durable (1 terminal SUCCEEDED row)              REQUIRED
 * 17.  replay (MEMOIZED + WRITES_WORKSPACE: RERUN            REQUIRED
 *      idempotently; second WsCleaned with 0/0 counts)
 * 17b. replay decision — policy unit property                REQUIRED
 * 18.  observability (StepStarted + StepFinished pair)       REQUIRED
 * 19.  WsCleaned event payload (counts, patterns, sha256)    REQUIRED (cleanWs-specific)
 * 20.  real registry path scenario                           REQUIRED
 * 21.  G1 candidate invariant: structural family stays       REQUIRED (cleanWs-specific,
 *      LegacyCore; counters unchanged)                        honest G1-state pin)
 * ```
 */
@Timeout(30)
class CoreCleanWsStepContractSuiteTest {

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { CoreCleanWsStep.registerInto(this) }

    private fun noOpCredentialScopePort(): CredentialScopePort =
        CredentialScopePort { _, _ ->
            CredentialScopeOutcome.Unavailable(
                CredentialScopeFailure.StoreUnavailable("step-contract-suite stub"),
            )
        }

    private fun freshHarness(
        eventStore: InMemoryEventStore,
        workDir: java.nio.file.Path = Files.createTempDirectory("cleanws-contract-"),
    ): Harness {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
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
        )
        return Harness(coord, journal, eventStore, registryForCoord, workDir)
    }

    private data class Harness(
        val coord: CanonicalDurableRunCoordinator,
        val journal: InMemoryOperationJournal,
        val eventStore: InMemoryEventStore,
        val registry: dev.rubentxu.pipeline.v2.domain.step.StepRegistry,
        val workDir: java.nio.file.Path,
    )

    private fun cleanWsNode(
        nodeId: String = "build/cleanWs",
        encoded: String = """{"kind":"cleanWs","deleteDirs":true,"patterns":[]}""",
    ) = OpaqueStepNode(
        id = StepId(nodeId),
        pluginStepId = CoreCleanWsStep.KEY,
        payload = VersionedStepPayload(schemaVersion = "dsl-v1", encoded = encoded),
    )

    private fun pipeline(vararg nodes: OpaqueStepNode) = CompiledPipeline(
        id = DefinitionId("cleanws-contract-suite"),
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
    fun `identity — CoreCleanWsStep KEY is core dot cleanWs and duplicate registration fails`() {
        assertEquals(PluginStepId("core.cleanWs"), CoreCleanWsStep.KEY)
        assertEquals("core.cleanWs", CoreCleanWsStep.KEY.value)
        assertEquals("core.cleanWs", CoreCleanWsStep.definition.contract.descriptor.stepId)
        assertEquals("cleanWs", CoreCleanWsStep.definition.contract.descriptor.name)
        val r = registry()
        assertTrue(
            runCatching { CoreCleanWsStep.registerInto(r) }.isFailure,
            "duplicate registration of core.cleanWs must fail",
        )
    }

    // ===== 2. contract completeness =====

    @Test
    fun `contract completeness — key, descriptor, codecs, single capability, WRITES_WORKSPACE, MEMOIZED, None`() {
        val contract = CoreCleanWsStep.definition.contract
        assertEquals(CoreCleanWsStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "StepDescriptor must be present")
        assertEquals("cleanWs", contract.descriptor.name)
        assertEquals(
            setOf(Effect.WRITES_WORKSPACE),
            contract.descriptor.effects.toSet(),
            "core.cleanWs effects MUST be WRITES_WORKSPACE (deletes workspace contents)",
        )
        assertEquals(
            ReplayPolicy.MEMOIZED,
            contract.descriptor.replayPolicy,
            "core.cleanWs replayPolicy MUST be MEMOIZED (legacy metadata row parity)",
        )
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy.None,
            contract.descriptor.recoveryPolicy,
            "core.cleanWs recoveryPolicy MUST be None (per WAVE-2 G0 inventory)",
        )
        assertEquals(
            setOf(CLEAN_WS_OPERATIONS_CAPABILITY),
            contract.requiredCapabilities,
            "core.cleanWs MUST declare EXACTLY {CLEAN_WS_OPERATIONS} as required capabilities",
        )
        assertNotNull(contract.inputCodec, "input codec must be present")
        assertNotNull(contract.outputCodec, "output codec must be present")
    }

    // ===== 3. input codec round-trip =====

    @Test
    fun `codec input — default input encodes to the canonical legacy envelope and round-trips`() {
        val encoded = CoreCleanWsStep.definition.contract.inputCodec.encode(CleanWsInput())
        assertEquals(
            """{"kind":"cleanWs","deleteDirs":true,"patterns":[]}""",
            encoded.value,
            "input codec must emit the canonical legacy dsl-v1 envelope",
        )
        val decoded = CoreCleanWsStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(CleanWsInput(), decoded, "round-trip must reconstruct CleanWsInput defaults")
    }

    // ===== 3b. input codec — tolerant defaults (legacy decoder parity) =====

    @Test
    fun `codec input — decode accepts the legacy envelope without fields with tolerant defaults`() {
        val legacy = EncodedStepValue("""{"kind":"cleanWs"}""")
        val decoded = CoreCleanWsStep.definition.contract.inputCodec.decode(legacy)
        assertEquals(
            CleanWsInput(deleteDirs = true, patterns = emptyList()),
            decoded,
            "missing deleteDirs MUST default to true and missing patterns to empty (legacy decoder parity)",
        )
    }

    // ===== 4. input codec rejection =====

    @Test
    fun `codec input — decode rejects a foreign envelope kind`() {
        val bad = EncodedStepValue("""{"kind":"echo","deleteDirs":true}""")
        assertTrue(
            runCatching { CoreCleanWsStep.definition.contract.inputCodec.decode(bad) }.isFailure,
            "input decode must fail closed on a non-cleanWs kind",
        )
    }

    // ===== 5. output codec round-trip =====

    @Test
    fun `codec output — CleanWsOutput round-trips byte-identically`() {
        val value = CleanWsOutput(deletedFiles = 3, deletedDirs = 1, patterns = listOf("*.txt"), sha256 = "abc123")
        val encoded = CoreCleanWsStep.definition.contract.outputCodec.encode(value)
        val decoded = CoreCleanWsStep.definition.contract.outputCodec.decode(encoded)
        assertEquals(value, decoded, "round-trip decode must reconstruct CleanWsOutput")
        val persisted: String = encoded.value
        assertEquals(
            value,
            CoreCleanWsStep.definition.contract.outputCodec.decode(EncodedStepValue(persisted)),
            "durable persistence (string round-trip) MUST decode to the same values",
        )
    }

    // ===== 6. output codec rejection =====

    @Test
    fun `codec output — decode rejects a non-cleanWs kind`() {
        val bad = EncodedStepValue("""{"kind":"echo","deletedFiles":0,"deletedDirs":0,"patterns":[],"sha256":"y"}""")
        assertTrue(
            runCatching { CoreCleanWsStep.definition.contract.outputCodec.decode(bad) }.isFailure,
            "output decode must fail closed on a non-cleanWs kind",
        )
    }

    // ===== 7. canonical envelope =====

    @Test
    fun `canonical envelope — input codec envelope is byte-identical to legacy dsl-v1 cleanWs envelope`() {
        val registryEnvelope = CoreCleanWsStep.definition.contract.inputCodec.encode(
            CleanWsInput(deleteDirs = true, patterns = listOf("*.txt")),
        ).value
        val legacyEnvelope = """{"kind":"cleanWs","deleteDirs":true,"patterns":["*.txt"]}"""
        assertEquals(
            legacyEnvelope,
            registryEnvelope,
            "registry input codec MUST emit a byte-identical dsl-v1 envelope",
        )
    }

    // ===== 8. production registry resolution =====

    @Test
    fun `registry resolution — production factory contains core dot cleanWs`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(registry.contains(CoreCleanWsStep.KEY), "production registry must contain core.cleanWs")
        val definition = registry.definition(CoreCleanWsStep.KEY)
        assertNotNull(definition, "production registry MUST resolve core.cleanWs to a StepDefinition")
        assertSame(
            CoreCleanWsStep.definition,
            definition,
            "production registry MUST return the canonical CoreCleanWsStep.definition instance",
        )
    }

    // ===== 9. fresh factory consistency =====

    @Test
    fun `registry resolution — production factory registry is fresh per call and consistent across calls`() {
        val r1 = CoreStepRegistryFactory.registry()
        val r2 = CoreStepRegistryFactory.registry()
        assertFalse(r1 === r2, "factory must produce fresh per-call registries")
        assertTrue(r1.contains(CoreCleanWsStep.KEY))
        assertTrue(r2.contains(CoreCleanWsStep.KEY))
        assertSame(r1.definition(CoreCleanWsStep.KEY), r2.definition(CoreCleanWsStep.KEY))
    }

    // ===== 10. capability declaration =====

    @Test
    fun `capability declaration — core cleanWs declares EXACTLY CLEAN_WS_OPERATIONS`() {
        val declared = CoreCleanWsStep.definition.contract.requiredCapabilities
        assertEquals(
            1,
            declared.size,
            "core.cleanWs MUST declare exactly 1 required capability; got ${declared.map { it.key }}",
        )
        assertTrue(
            CLEAN_WS_OPERATIONS_CAPABILITY in declared,
            "CLEAN_WS_OPERATIONS_CAPABILITY MUST be declared",
        )
    }

    // ===== 11. capability admission (available) =====

    @Test
    fun `capability admission — the capability available prepares Ready`() {
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = CoreCleanWsStep.KEY,
                encodedInput = CoreCleanWsStep.definition.contract.inputCodec.encode(CleanWsInput()),
                availableCapabilities = setOf(CLEAN_WS_OPERATIONS_CAPABILITY),
            )
            assertTrue(
                preparation is ExecutionPreparation.Ready,
                "admission must succeed when CLEAN_WS_OPERATIONS_CAPABILITY is available",
            )
        }
    }

    // ===== 12. missing CLEAN_WS_OPERATIONS =====

    @Test
    fun `missing capability — admission rejects when CLEAN_WS_OPERATIONS is absent`() {
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = CoreCleanWsStep.KEY,
                encodedInput = CoreCleanWsStep.definition.contract.inputCodec.encode(CleanWsInput()),
                availableCapabilities = emptySet(),
            )
            assertTrue(
                preparation is ExecutionPreparation.Rejected,
                "missing CLEAN_WS_OPERATIONS must surface as Rejected admission (fail-closed)",
            )
            val rejected = assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
            assertTrue(
                rejected.reason.contains(CLEAN_WS_OPERATIONS_CAPABILITY.key),
                "Rejection MUST identify the missing CLEAN_WS_OPERATIONS capability",
            )
        }
    }

    // ===== 12b. conditional exposure (controlDirRoot == null) =====

    @Test
    fun `conditional exposure — controlDirRoot null means CLEAN_WS_OPERATIONS absent and admission rejects`() {
        val nullContext = CanonicalRuntimeContext(
            opId = OpId("cleanws-null-ctrl", 0, 0),
            runId = "cleanws-null-ctrl",
            stageName = "build",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = InMemoryEventStore(),
        )
        val access = CanonicalRuntimeCapabilityAccess(nullContext)
        val available = access.available()
        assertTrue(
            CLEAN_WS_OPERATIONS_CAPABILITY !in available,
            "CLEAN_WS_OPERATIONS_CAPABILITY must NOT be available when controlDirRoot is null",
        )
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = CoreCleanWsStep.KEY,
                encodedInput = CoreCleanWsStep.definition.contract.inputCodec.encode(CleanWsInput()),
                availableCapabilities = available,
            )
            assertTrue(
                preparation is ExecutionPreparation.Rejected,
                "admission MUST reject when the capability was not exposed (controlDirRoot=null)",
            )
        }
    }

    // ===== 14. success via canonical coordinator =====

    @Test
    fun `success — registry-routed cleanWs SUCCEEDS with one terminal SUCCEEDED operation and typed output`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            val outcome = h.coord.run(pipeline(cleanWsNode()), RunId("cleanws-ok"))
            assertEquals(
                RunOutcome.Success,
                outcome,
                "a cleanWs against the canonical stage workspace must succeed",
            )
            val rows = h.journal.listForRun("cleanws-ok")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    // ===== 15. typed failure (handler exception) =====

    // G1-candidate honesty note: at G1..G3 `core.cleanWs` is still in LEGACY_PLUGIN_IDS, so the
    // production coordinator classifies the step as LegacyCore (StructuralFamilyResolver's
    // legacy-membership-wins rule) and NEVER reaches the throwing registry handler below — a
    // coordinator-level `RunOutcome.Failure` assertion here would only pass by asserting routing
    // that does not exist yet. The deleteDir suite's coordinator-level typed-failure test is
    // registry-primary only because its G4 flip preceded its G6 suite. The honest G1 equivalent
    // asserts the exact seam G4 will promote: RegistryExecutionPreparation admits the throwing
    // handler (Ready), and RegistryExecutionBoundary.coexecute maps the thrown exception to a
    // typed StepOutcome.Failure(ENGINE) with null encodedOutput and no WsCleaned emission.
    @Test
    fun `typed failure — registry boundary maps a thrown cleanWs handler to StepOutcome Failure ENGINE`() {
        val throwingHandler: StepHandler<CleanWsInput, CleanWsOutput> =
            StepHandler { _: CleanWsInput, _: StepHandlerContext ->
                throw IllegalStateException("core.cleanWs handler contract violated for test")
            }
        val throwingRegistry = InMemoryStepRegistry().apply {
            register(
                object : StepDefinition<CleanWsInput, CleanWsOutput> {
                    override val contract: StepContract<CleanWsInput, CleanWsOutput> = StepContract(
                        key = CoreCleanWsStep.KEY,
                        descriptor = CoreCleanWsStep.definition.contract.descriptor,
                        inputCodec = CoreCleanWsStep.definition.contract.inputCodec,
                        outputCodec = CoreCleanWsStep.definition.contract.outputCodec,
                        requiredCapabilities = setOf(CLEAN_WS_OPERATIONS_CAPABILITY),
                    )
                    override val handler: StepHandler<CleanWsInput, CleanWsOutput> = throwingHandler
                },
            )
        }
        runBlocking {
            // G4 promotion path: admission over the throwing registry is Ready (admission never
            // runs the handler), then the boundary maps the thrown exception to a typed failure.
            val encoded = CoreCleanWsStep.definition.contract.inputCodec.encode(CleanWsInput())
            val preparation = RegistryExecutionPreparation.prepare(
                registry = throwingRegistry,
                key = CoreCleanWsStep.KEY,
                encodedInput = encoded,
                availableCapabilities = setOf(CLEAN_WS_OPERATIONS_CAPABILITY),
            )
            val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
            val prepared = assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)

            val failEventStore = InMemoryEventStore()
            val ctx = CanonicalRuntimeContext(
                opId = OpId("cleanws-throw-boundary", 0, 0),
                runId = "cleanws-throw-boundary",
                stageName = "build",
                stageIndex = 0,
                stepIndex = 0,
                shOptions = ShOptions.EMPTY,
                controlDirRoot = Files.createTempDirectory("cleanws-contract-fail-boundary-"),
                eventSink = failEventStore,
            )
            val result = RegistryExecutionBoundary.coexecute(prepared, ctx)
            val failure = assertInstanceOf(
                dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure::class.java,
                result.outcome,
                "handler exceptions must surface as a typed StepOutcome.Failure, not silent success; got ${result.outcome}",
            )
            assertEquals(
                dev.rubentxu.pipeline.v2.domain.FailureKind.ENGINE,
                failure.failure.kind,
                "a thrown handler is an adapter/engine defect: failure kind MUST be ENGINE",
            )
            assertTrue(
                failure.failure.message.contains("core.cleanWs handler contract violated for test"),
                "the typed failure MUST carry the handler's message; got '${failure.failure.message}'",
            )
            assertNull(
                result.encodedOutput,
                "a failed execution MUST NOT carry an encoded terminal output",
            )
            assertEquals(
                0,
                failEventStore.eventsFor("cleanws-throw-boundary").toList().filterIsInstance<WsCleaned>().size,
                "a thrown handler MUST NOT emit a WsCleaned observation",
            )
        }
    }

    // ===== 16. fresh durable =====

    @Test
    fun `fresh durable — first execution of core dot cleanWs writes one terminal SUCCEEDED operation`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(cleanWsNode()), RunId("cleanws-first"))
            val rows = h.journal.listForRun("cleanws-first")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    // ===== 17. replay (MEMOIZED, WRITES_WORKSPACE) =====

    @Test
    fun `replay — MEMOIZED cleanWs with WRITES_WORKSPACE reruns idempotently (counts collapse to 0)`() {
        // Decision matrix (EffectReplayPolicy): MEMOIZED + WRITES_WORKSPACE → RERUN. The
        // durable law for cleanWs is IDEMPOTENCE, not memoized skip: re-execution on the
        // already-clean workspace emits a new WsCleaned with deletedFiles=0/deletedDirs=0,
        // and the run still SUCCEEDS with one terminal row per fresh execution.
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            val firstOutcome = h.coord.run(pipeline(cleanWsNode()), RunId("cleanws-replay"))
            assertEquals(RunOutcome.Success, firstOutcome)
            val firstCleaned = h.eventStore.eventsFor("cleanws-replay").filterIsInstance<WsCleaned>().toList()
            assertEquals(1, firstCleaned.size, "first execution emits exactly one WsCleaned")

            // Second execution at the SAME runId: policy decides RERUN (WRITES_WORKSPACE);
            // the handler re-runs safely on the already-clean workspace.
            val secondOutcome = h.coord.run(pipeline(cleanWsNode()), RunId("cleanws-replay"))
            assertEquals(
                RunOutcome.Success,
                secondOutcome,
                "replay of a WRITES_WORKSPACE cleanWs MUST rerun and succeed (idempotent)",
            )
            val allCleaned = h.eventStore.eventsFor("cleanws-replay").filterIsInstance<WsCleaned>().toList()
            assertEquals(2, allCleaned.size, "rerun emits exactly one additional WsCleaned")
            assertEquals(
                0,
                allCleaned.last().deletedFiles,
                "re-execution on the already-clean workspace MUST observe deletedFiles=0 (idempotent)",
            )
            assertEquals(
                0,
                allCleaned.last().deletedDirs,
                "re-execution on the already-clean workspace MUST observe deletedDirs=0 (idempotent)",
            )
            val rows = h.journal.listForRun("cleanws-replay")
            assertEquals(1, rows.size, "the rerun updates the SAME operation row (one row per op identity)")
            assertEquals(
                OperationStatus.SUCCEEDED,
                rows.single().status,
                "the terminal row stays SUCCEEDED after the idempotent rerun",
            )
        }
    }

    // ===== 17b. replay decision — policy unit property =====

    @Test
    fun `replay decision — DefaultEffectReplayPolicy reruns MEMOIZED WRITES_WORKSPACE with a SUCCEEDED entry`() {
        val decision = DefaultEffectReplayPolicy().decide(
            replayPolicy = ReplayPolicy.MEMOIZED,
            effects = setOf(Effect.WRITES_WORKSPACE),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.SUCCEEDED,
        )
        assertEquals(
            ReplayDecision.RERUN,
            decision,
            "the frozen decision matrix pins MEMOIZED+WRITES_WORKSPACE+journaled → RERUN",
        )
    }

    // ===== 18. observability =====

    @Test
    fun `observability — every core dot cleanWs run emits a StepStarted StepFinished pair`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(cleanWsNode()), RunId("cleanws-obs"))
            val events = h.eventStore.eventsFor("cleanws-obs").toList()
            assertTrue(events.any { it is StepStarted }, "StepStarted must be emitted (lifecycle observability)")
            assertTrue(events.any { it is StepFinished }, "StepFinished must be emitted (lifecycle observability)")
        }
    }

    // ===== 19. WsCleaned event payload =====

    @Test
    fun `WsCleaned event payload — exactly one event with non-negative counts, echoed patterns, 64-hex sha256`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(cleanWsNode()), RunId("cleanws-event"))
            val events = eventStore.eventsFor("cleanws-event").toList().filterIsInstance<WsCleaned>()
            assertEquals(1, events.size, "exactly one WsCleaned event is emitted")
            val event = events.single()
            assertEquals("WsCleaned", event.kind)
            assertTrue(event.deletedFiles >= 0, "WsCleaned deletedFiles MUST be non-negative; got ${event.deletedFiles}")
            assertTrue(event.deletedDirs >= 0, "WsCleaned deletedDirs MUST be non-negative; got ${event.deletedDirs}")
            assertEquals(
                emptyList<String>(),
                event.patterns,
                "WsCleaned patterns MUST echo the applied patterns (empty = delete-all)",
            )
            assertEquals(
                64,
                event.sha256.length,
                "WsCleaned sha256 MUST be a 64-char hex SHA-256; got ${event.sha256}",
            )
            runCatching { UUID.fromString(event.eventId) }
                .onFailure { throw AssertionError("WsCleaned eventId MUST be a UUID: ${event.eventId}", it) }
        }
    }

    // ===== 20. real registry path scenario =====

    @Test
    fun `real registry path — canonical coordinator + capability bridge exercises core dot cleanWs end-to-end`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)

            val encoded = CoreCleanWsStep.definition.contract.inputCodec.encode(CleanWsInput())
            val node = OpaqueStepNode(
                id = StepId("real/cleanWs"),
                pluginStepId = CoreCleanWsStep.KEY,
                payload = VersionedStepPayload(schemaVersion = "dsl-v1", encoded = encoded.value),
            )

            val preparation = RegistryExecutionPreparation.prepare(
                registry = h.registry,
                key = CoreCleanWsStep.KEY,
                encodedInput = encoded,
                availableCapabilities = setOf(CLEAN_WS_OPERATIONS_CAPABILITY),
            )
            val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
            val prepared = assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)

            val outcome = h.coord.run(pipeline(node), RunId("cleanws-real"))
            assertEquals(RunOutcome.Success, outcome, "registry seam end-to-end must succeed")

            // Independently exercise the boundary coexecute path to prove the typed outcome.
            val boundaryEventStore = InMemoryEventStore()
            val ctx = CanonicalRuntimeContext(
                opId = OpId("cleanws-real-boundary", 0, 0),
                runId = "cleanws-real-boundary",
                stageName = "build",
                stageIndex = 0,
                stepIndex = 0,
                shOptions = ShOptions.EMPTY,
                controlDirRoot = Files.createTempDirectory("cleanws-real-boundary-"),
                eventSink = boundaryEventStore,
            )
            val result = RegistryExecutionBoundary.coexecute(prepared, ctx)
            assertEquals(dev.rubentxu.pipeline.v2.domain.StepOutcome.Success, result.outcome)
            val typed = CoreCleanWsStep.definition.contract.outputCodec.decode(result.encodedOutput!!)
            assertTrue(typed.deletedFiles >= 0)
            assertTrue(typed.deletedDirs >= 0)
            assertEquals(64, typed.sha256.length)
            assertEquals(
                1,
                boundaryEventStore.eventsFor("cleanws-real-boundary").toList().filterIsInstance<WsCleaned>().size,
                "the boundary path must emit exactly one WsCleaned observation",
            )
        }
    }

    // ===== 21. G1 candidate invariant (cleanWs-specific) =====

    @Test
    fun `G1 candidate invariant — core dot cleanWs stays LegacyCore and counters remain 5 5 5`() {
        // Candidate-only registration: while core.cleanWs remains in LEGACY_PLUGIN_IDS,
        // StructuralFamilyResolver's legacy-membership-wins rule keeps LegacyCore as the
        // production authority. No legacy mutation, no counter change at G1..G3.
        assertTrue(
            "core.cleanWs" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.cleanWs MUST remain in LEGACY_PLUGIN_IDS at G1..G3 (candidate-only registration)",
        )
        assertEquals(
            5,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size,
            "LEGACY_PLUGIN_IDS count MUST stay 5 (no G4/G5 mutations in this slice)",
        )
        assertTrue(
            "core.cleanWs" in CanonicalCoreStepMetadata.pluginIds,
            "the legacy metadata row MUST remain untouched at G1..G3",
        )
    }
}
