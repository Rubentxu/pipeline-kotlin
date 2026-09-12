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
import dev.rubentxu.pipeline.v2.events.DirDeleted
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * StepContractSuite — LFC-2E1 / S2-A7 / G6 for `core.deleteDir`.
 *
 * Certifies `core.deleteDir` end-to-end across the registry-driven, open-world Step seam
 * following the certified `core.pwd` model (S2-A6/G6). Effectful pattern (like `core.sh`):
 * ONE required capability ([DELETE_DIR_OPERATIONS_CAPABILITY]) — the handler reaches the
 * typed `DeleteDirOperations` seam; all filesystem semantics, workspace resolution, and
 * `DirDeleted` emission live in `DeleteDirOperationsAdapter`.
 *
 * deleteDir-specific semantics (S2-A7/G3-fix):
 *  - input envelope is `{"kind":"deleteDir","path":"."}` (path defaults to ".");
 *  - effects are `{ WRITES_WORKSPACE }`, replayPolicy is MEMOIZED;
 *  - the capability is conditionally exposed only when `controlDirRoot != null`;
 *  - output is `DeleteDirOutput(path, deletedCount, sha256)`;
 *  - durable observation: one `DirDeleted` event per fresh execution.
 *
 * Coverage matrix:
 * ```
 *  1.  identity                                              REQUIRED
 *  2.  contract completeness                                 REQUIRED (descriptor + 1 cap + MEMOIZED)
 *  3.  input codec round-trip (default path)                  REQUIRED
 *  3b. input codec legacy envelope without path field        REQUIRED
 *  4.  input codec rejection (foreign envelope)              REQUIRED
 *  5.  output codec round-trip                               REQUIRED
 *  6.  output codec rejection (non-deleteDir kind)           REQUIRED
 *  7.  canonical envelope (byte-identical to legacy dsl-v1)  REQUIRED
 *  8.  production registry resolution                        REQUIRED
 *  9.  fresh factory consistency                             REQUIRED
 * 10.  capability declaration (exactly DELETE_DIR_OPERATIONS) REQUIRED
 * 11.  capability admission (available → Ready)              REQUIRED
 * 12.  missing DELETE_DIR_OPERATIONS rejects fail-closed     REQUIRED
 * 12b. conditional exposure: controlDirRoot=null →           REQUIRED
 *      capability absent → admission Rejected
 * 14.  success via canonical coordinator (typed outcome)     REQUIRED
 * 15.  typed failure (handler exception)                     REQUIRED
 * 16.  fresh durable (1 terminal SUCCEEDED row)              REQUIRED
 * 17.  replay (MEMOIZED: reuse, no handler re-run,           REQUIRED
 *      no duplicate DirDeleted events)
 * 18.  observability (StepStarted + StepFinished pair)       REQUIRED
 * 19.  DirDeleted event payload (path, deletedCount, sha256) REQUIRED (deleteDir-specific)
 * 20.  real registry path scenario                           REQUIRED
 * ```
 */
@Timeout(30)
class CoreDeleteDirStepContractSuiteTest {

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { CoreDeleteDirStep.registerInto(this) }

    private fun noOpCredentialScopePort(): CredentialScopePort =
        CredentialScopePort { _, _ ->
            CredentialScopeOutcome.Unavailable(
                CredentialScopeFailure.StoreUnavailable("step-contract-suite stub"),
            )
        }

    private fun freshHarness(
        eventStore: InMemoryEventStore,
        workDir: java.nio.file.Path = Files.createTempDirectory("deletedir-contract-"),
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

    private fun deleteDirNode(nodeId: String = "build/deleteDir") = OpaqueStepNode(
        id = StepId(nodeId),
        pluginStepId = CoreDeleteDirStep.KEY,
        // The canonical legacy dsl-v1 envelope for deleteDir() is {"kind":"deleteDir","path":"."}.
        payload = VersionedStepPayload(
            schemaVersion = "dsl-v1",
            encoded = """{"kind":"deleteDir","path":"."}""",
        ),
    )

    private fun pipeline(vararg nodes: OpaqueStepNode) = CompiledPipeline(
        id = DefinitionId("deletedir-contract-suite"),
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
    fun `identity — CoreDeleteDirStep KEY is core dot deleteDir and duplicate registration fails`() {
        assertEquals(PluginStepId("core.deleteDir"), CoreDeleteDirStep.KEY)
        assertEquals("core.deleteDir", CoreDeleteDirStep.KEY.value)
        assertEquals("core.deleteDir", CoreDeleteDirStep.definition.contract.descriptor.stepId)
        assertEquals("deleteDir", CoreDeleteDirStep.definition.contract.descriptor.name)
        val r = registry()
        assertTrue(
            runCatching { CoreDeleteDirStep.registerInto(r) }.isFailure,
            "duplicate registration of core.deleteDir must fail",
        )
    }

    // ===== 2. contract completeness =====

    @Test
    fun `contract completeness — key, descriptor, codecs, single capability, WRITES_WORKSPACE, MEMOIZED`() {
        val contract = CoreDeleteDirStep.definition.contract
        assertEquals(CoreDeleteDirStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "StepDescriptor must be present")
        assertEquals("deleteDir", contract.descriptor.name)
        assertEquals(
            setOf(Effect.WRITES_WORKSPACE),
            contract.descriptor.effects.toSet(),
            "core.deleteDir effects MUST be WRITES_WORKSPACE (deletes workspace contents)",
        )
        assertEquals(
            ReplayPolicy.MEMOIZED,
            contract.descriptor.replayPolicy,
            "core.deleteDir replayPolicy MUST be MEMOIZED (replay reproduces the persisted observation)",
        )
        assertEquals(
            setOf(DELETE_DIR_OPERATIONS_CAPABILITY),
            contract.requiredCapabilities,
            "core.deleteDir MUST declare EXACTLY {DELETE_DIR_OPERATIONS} as required capabilities",
        )
        assertNotNull(contract.inputCodec, "input codec must be present")
        assertNotNull(contract.outputCodec, "output codec must be present")
    }

    // ===== 3. input codec round-trip =====

    @Test
    fun `codec input — default-path input encodes to the canonical legacy envelope and round-trips`() {
        val encoded = CoreDeleteDirStep.definition.contract.inputCodec.encode(DeleteDirInput(path = "."))
        assertEquals(
            """{"kind":"deleteDir","path":"."}""",
            encoded.value,
            "input codec must emit the canonical legacy dsl-v1 envelope",
        )
        val decoded = CoreDeleteDirStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(DeleteDirInput(path = "."), decoded, "round-trip must reconstruct DeleteDirInput(path=.)")
    }

    // ===== 3b. input codec — legacy envelope without path =====

    @Test
    fun `codec input — decode accepts the legacy envelope without path and defaults to dot`() {
        val legacy = EncodedStepValue("""{"kind":"deleteDir"}""")
        val decoded = CoreDeleteDirStep.definition.contract.inputCodec.decode(legacy)
        assertEquals(DeleteDirInput(path = "."), decoded, "missing path MUST default to '.'")
    }

    // ===== 4. input codec rejection =====

    @Test
    fun `codec input — decode rejects a foreign envelope kind`() {
        val bad = EncodedStepValue("""{"kind":"echo","path":"."}""")
        assertTrue(
            runCatching { CoreDeleteDirStep.definition.contract.inputCodec.decode(bad) }.isFailure,
            "input decode must fail closed on a non-deleteDir kind",
        )
    }

    // ===== 5. output codec round-trip =====

    @Test
    fun `codec output — DeleteDirOutput round-trips byte-identically`() {
        val value = DeleteDirOutput(path = "/tmp/ws-a/workspace/test-0", deletedCount = 7, sha256 = "abc123")
        val encoded = CoreDeleteDirStep.definition.contract.outputCodec.encode(value)
        val decoded = CoreDeleteDirStep.definition.contract.outputCodec.decode(encoded)
        assertEquals(value, decoded, "round-trip decode must reconstruct DeleteDirOutput")
        // Field-level: durable persistence round-trips path, deletedCount, sha256.
        val persisted: String = encoded.value
        assertEquals(
            value,
            CoreDeleteDirStep.definition.contract.outputCodec.decode(EncodedStepValue(persisted)),
            "durable persistence (string round-trip) MUST decode to the same values",
        )
    }

    // ===== 6. output codec rejection =====

    @Test
    fun `codec output — decode rejects a non-deleteDir kind`() {
        val bad = EncodedStepValue("""{"kind":"echo","path":"/x","deletedCount":0,"sha256":"y"}""")
        assertTrue(
            runCatching { CoreDeleteDirStep.definition.contract.outputCodec.decode(bad) }.isFailure,
            "output decode must fail closed on a non-deleteDir kind",
        )
    }

    // ===== 7. canonical envelope =====

    @Test
    fun `canonical envelope — input codec envelope is byte-identical to legacy dsl-v1 deleteDir envelope`() {
        // Continuous durable fingerprint/journal identity requires byte-identical envelopes.
        val registryEnvelope = CoreDeleteDirStep.definition.contract.inputCodec.encode(DeleteDirInput(path = ".")).value
        val legacyEnvelope = """{"kind":"deleteDir","path":"."}"""
        assertEquals(
            legacyEnvelope,
            registryEnvelope,
            "registry input codec MUST emit a byte-identical dsl-v1 envelope",
        )
    }

    // ===== 8. production registry resolution =====

    @Test
    fun `registry resolution — production factory contains core dot deleteDir`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(registry.contains(CoreDeleteDirStep.KEY), "production registry must contain core.deleteDir")
        val definition = registry.definition(CoreDeleteDirStep.KEY)
        assertNotNull(definition, "production registry MUST resolve core.deleteDir to a StepDefinition")
        assertSame(
            CoreDeleteDirStep.definition,
            definition,
            "production registry MUST return the canonical CoreDeleteDirStep.definition instance",
        )
    }

    // ===== 9. fresh factory consistency =====

    @Test
    fun `registry resolution — production factory registry is fresh per call and consistent across calls`() {
        val r1 = CoreStepRegistryFactory.registry()
        val r2 = CoreStepRegistryFactory.registry()
        assertFalse(r1 === r2, "factory must produce fresh per-call registries")
        assertTrue(r1.contains(CoreDeleteDirStep.KEY))
        assertTrue(r2.contains(CoreDeleteDirStep.KEY))
        assertSame(r1.definition(CoreDeleteDirStep.KEY), r2.definition(CoreDeleteDirStep.KEY))
    }

    // ===== 10. capability declaration =====

    @Test
    fun `capability declaration — core deleteDir declares EXACTLY DELETE_DIR_OPERATIONS`() {
        // The handler reaches ONE capability: DeleteDirOperations. This pins the typed boundary —
        // the handler MUST never touch the filesystem, the EventSink, sha256, or controlDirRoot
        // directly; all of that is bound by DeleteDirOperationsAdapter.
        val declared = CoreDeleteDirStep.definition.contract.requiredCapabilities
        assertEquals(
            1,
            declared.size,
            "core.deleteDir MUST declare exactly 1 required capability; got ${declared.map { it.key }}",
        )
        assertTrue(
            DELETE_DIR_OPERATIONS_CAPABILITY in declared,
            "DELETE_DIR_OPERATIONS_CAPABILITY MUST be declared",
        )
    }

    // ===== 11. capability admission (available) =====

    @Test
    fun `capability admission — the capability available prepares Ready`() {
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = CoreDeleteDirStep.KEY,
                encodedInput = CoreDeleteDirStep.definition.contract.inputCodec.encode(DeleteDirInput(path = ".")),
                availableCapabilities = setOf(DELETE_DIR_OPERATIONS_CAPABILITY),
            )
            assertTrue(
                preparation is ExecutionPreparation.Ready,
                "admission must succeed when DELETE_DIR_OPERATIONS_CAPABILITY is available",
            )
        }
    }

    // ===== 12. missing DELETE_DIR_OPERATIONS =====

    @Test
    fun `missing capability — admission rejects when DELETE_DIR_OPERATIONS is absent`() {
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = CoreDeleteDirStep.KEY,
                encodedInput = CoreDeleteDirStep.definition.contract.inputCodec.encode(DeleteDirInput(path = ".")),
                availableCapabilities = emptySet(),
            )
            assertTrue(
                preparation is ExecutionPreparation.Rejected,
                "missing DELETE_DIR_OPERATIONS must surface as Rejected admission (fail-closed)",
            )
            val rejected = assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
            assertTrue(
                rejected.reason.contains(DELETE_DIR_OPERATIONS_CAPABILITY.key),
                "Rejection MUST identify the missing DELETE_DIR_OPERATIONS capability",
            )
        }
    }

    // ===== 12b. conditional exposure (controlDirRoot == null) =====

    @Test
    fun `conditional exposure — controlDirRoot null means DELETE_DIR_OPERATIONS absent and admission rejects`() {
        // Capability-scoped controlDirRoot: CanonicalRuntimeCapabilityAccess must not throw on
        // controlDirRoot=null; the capability is simply NOT registered and core.deleteDir
        // admission fails closed.
        val nullContext = CanonicalRuntimeContext(
            opId = OpId("deletedir-null-ctrl", 0, 0),
            runId = "deletedir-null-ctrl",
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
            DELETE_DIR_OPERATIONS_CAPABILITY !in available,
            "DELETE_DIR_OPERATIONS_CAPABILITY must NOT be available when controlDirRoot is null",
        )
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = CoreDeleteDirStep.KEY,
                encodedInput = CoreDeleteDirStep.definition.contract.inputCodec.encode(DeleteDirInput(path = ".")),
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
    fun `success — registry-routed deleteDir SUCCEEDS with one terminal SUCCEEDED operation and typed output`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            val outcome = h.coord.run(pipeline(deleteDirNode()), RunId("deletedir-ok"))
            assertEquals(
                RunOutcome.Success,
                outcome,
                "a deleteDir against the canonical stage workspace must succeed",
            )
            val rows = h.journal.listForRun("deletedir-ok")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    // ===== 15. typed failure (handler exception) =====

    @Test
    fun `typed failure — a registry-routed deleteDir whose handler throws surfaces as RunOutcome Failure`() {
        val throwingHandler: StepHandler<DeleteDirInput, DeleteDirOutput> =
            StepHandler { _: DeleteDirInput, _: StepHandlerContext ->
                throw IllegalStateException("core.deleteDir handler contract violated for test")
            }
        val throwingRegistry = InMemoryStepRegistry().apply {
            register(
                object : StepDefinition<DeleteDirInput, DeleteDirOutput> {
                    override val contract: StepContract<DeleteDirInput, DeleteDirOutput> = StepContract(
                        key = CoreDeleteDirStep.KEY,
                        descriptor = CoreDeleteDirStep.definition.contract.descriptor,
                        inputCodec = CoreDeleteDirStep.definition.contract.inputCodec,
                        outputCodec = CoreDeleteDirStep.definition.contract.outputCodec,
                        requiredCapabilities = setOf(DELETE_DIR_OPERATIONS_CAPABILITY),
                    )
                    override val handler: StepHandler<DeleteDirInput, DeleteDirOutput> = throwingHandler
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
            controlDirRoot = Files.createTempDirectory("deletedir-contract-fail-"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = throwingRegistry,
        )
        runBlocking {
            val outcome = coord.run(pipeline(deleteDirNode()), RunId("deletedir-throw"))
            assertTrue(
                outcome is RunOutcome.Failure,
                "handler exceptions must surface as a typed RunOutcome.Failure, not silent success; got $outcome",
            )
        }
    }

    // ===== 16. fresh durable =====

    @Test
    fun `fresh durable — first execution of core dot deleteDir writes one terminal SUCCEEDED operation`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(deleteDirNode()), RunId("deletedir-first"))
            val rows = h.journal.listForRun("deletedir-first")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    // ===== 17. replay (MEMOIZED, WRITES_WORKSPACE) =====

    @Test
    fun `replay — MEMOIZED deleteDir with WRITES_WORKSPACE reruns idempotently (deletedCount collapses to 0)`() {
        // Decision matrix (EffectReplayPolicy): MEMOIZED + WRITES_WORKSPACE → RERUN. The
        // durable law for deleteDir is IDEMPOTENCE, not memoized skip: re-execution on the
        // already-deleted workspace emits a new DirDeleted with deletedCount=0 (same path,
        // same sha), and the run still SUCCEEDS with one terminal row per fresh execution.
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            val firstOutcome = h.coord.run(pipeline(deleteDirNode()), RunId("deletedir-replay"))
            assertEquals(RunOutcome.Success, firstOutcome)
            val firstDeleted = h.eventStore.eventsFor("deletedir-replay").filterIsInstance<DirDeleted>().toList()
            assertEquals(1, firstDeleted.size, "first execution emits exactly one DirDeleted")
            assertTrue(
                firstDeleted.single().deletedCount >= 0,
                "first execution deletes whatever the fresh workspace contained",
            )

            // Second execution at the SAME runId: policy decides RERUN (WRITES_WORKSPACE);
            // the handler re-runs safely and the deletion is idempotent.
            val secondOutcome = h.coord.run(pipeline(deleteDirNode()), RunId("deletedir-replay"))
            assertEquals(
                RunOutcome.Success,
                secondOutcome,
                "replay of a WRITES_WORKSPACE deleteDir MUST rerun and succeed (idempotent)",
            )
            val allDeleted = h.eventStore.eventsFor("deletedir-replay").filterIsInstance<DirDeleted>().toList()
            assertEquals(2, allDeleted.size, "rerun emits exactly one additional DirDeleted")
            assertEquals(
                0,
                allDeleted.last().deletedCount,
                "re-execution on the already-deleted workspace MUST observe deletedCount=0 (idempotent)",
            )
            assertEquals(
                allDeleted.first().path,
                allDeleted.last().path,
                "both observations MUST target the same canonical stage workspace path",
            )
            val rows = h.journal.listForRun("deletedir-replay")
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
    fun `observability — every core dot deleteDir run emits a StepStarted StepFinished pair`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(deleteDirNode()), RunId("deletedir-obs"))
            val events = h.eventStore.eventsFor("deletedir-obs").toList()
            assertTrue(events.any { it is StepStarted }, "StepStarted must be emitted (lifecycle observability)")
            assertTrue(events.any { it is StepFinished }, "StepFinished must be emitted (lifecycle observability)")
        }
    }

    // ===== 19. DirDeleted event payload =====

    @Test
    fun `DirDeleted event payload — exactly one event with workspace path, non-negative deletedCount, 64-hex sha256`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(deleteDirNode()), RunId("deletedir-event"))
            val events = eventStore.eventsFor("deletedir-event").toList().filterIsInstance<DirDeleted>()
            assertEquals(1, events.size, "exactly one DirDeleted event is emitted")
            val event = events.single()
            assertEquals("DirDeleted", event.kind)
            assertTrue(
                event.path.endsWith("workspace/build-0"),
                "DirDeleted path MUST be the canonical stage workspace (workspace/build-0); got ${event.path}",
            )
            assertTrue(
                event.deletedCount >= 0,
                "DirDeleted deletedCount MUST be non-negative; got ${event.deletedCount}",
            )
            assertEquals(
                64,
                event.sha256.length,
                "DirDeleted sha256 MUST be a 64-char hex SHA-256; got ${event.sha256}",
            )
            // eventId is a uuid — assert it parses as such.
            runCatching { UUID.fromString(event.eventId) }
                .onFailure { throw AssertionError("DirDeleted eventId MUST be a UUID: ${event.eventId}", it) }
        }
    }

    // ===== 20. real registry path scenario =====

    @Test
    fun `real registry path — canonical coordinator + capability bridge exercises core dot deleteDir end-to-end`() {
        // Full registry seam: RegistryExecutionPreparation → capability admission → handler execution
        // → typed output → durable journal → DirDeleted observation. Exercises the SAME code path
        // the production canonical coordinator uses, without coupling the suite to the DSL compiler.
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)

            val encoded = CoreDeleteDirStep.definition.contract.inputCodec.encode(DeleteDirInput(path = "."))
            val node = OpaqueStepNode(
                id = StepId("real/deleteDir"),
                pluginStepId = CoreDeleteDirStep.KEY,
                payload = VersionedStepPayload(
                    schemaVersion = "dsl-v1",
                    encoded = encoded.value,
                ),
            )

            val preparation = RegistryExecutionPreparation.prepare(
                registry = h.registry,
                key = CoreDeleteDirStep.KEY,
                encodedInput = encoded,
                availableCapabilities = setOf(DELETE_DIR_OPERATIONS_CAPABILITY),
            )
            val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
            val prepared = assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)

            val outcome = h.coord.run(pipeline(node), RunId("deletedir-real"))
            assertEquals(RunOutcome.Success, outcome, "registry seam end-to-end must succeed")

            // Independently exercise the boundary coexecute path to prove the typed outcome.
            val boundaryEventStore = InMemoryEventStore()
            val ctx = CanonicalRuntimeContext(
                opId = OpId("deletedir-real-boundary", 0, 0),
                runId = "deletedir-real-boundary",
                stageName = "build",
                stageIndex = 0,
                stepIndex = 0,
                shOptions = ShOptions.EMPTY,
                controlDirRoot = Files.createTempDirectory("deletedir-real-boundary-"),
                eventSink = boundaryEventStore,
            )
            val result = RegistryExecutionBoundary.coexecute(prepared, ctx)
            assertEquals(dev.rubentxu.pipeline.v2.domain.StepOutcome.Success, result.outcome)
            val typed = CoreDeleteDirStep.definition.contract.outputCodec.decode(result.encodedOutput!!)
            assertTrue(
                typed.path.isNotEmpty(),
                "DeleteDirOutput.path MUST carry the deleted workspace path",
            )
            assertTrue(typed.deletedCount >= 0)
            assertEquals(64, typed.sha256.length)
            assertEquals(
                1,
                boundaryEventStore.eventsFor("deletedir-real-boundary").toList().filterIsInstance<DirDeleted>().size,
                "the boundary path must emit exactly one DirDeleted observation",
            )
        }
    }
}
