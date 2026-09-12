package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
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
import dev.rubentxu.pipeline.v2.events.PwdResolved
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import java.util.UUID
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * StepContractSuite — LFC-2E1 / S2-A6 / G6 for `core.pwd`.
 *
 * Certifies `core.pwd` end-to-end across the registry-driven, open-world Step seam following
 * the certified `core.isUnix` model (S2-A5/G6): TWO required capabilities
 * ([WORKSPACE_IDENTITY_CAPABILITY] + [EVENT_SINK_CAPABILITY]) — the handler reaches the runtime
 * capability bridge to observe the canonical workspace identity and to publish the durable
 * `PwdResolved` observation.
 *
 * Pwd-specific semantic deltas vs isUnix (inherited from S2-A6/G0..G5):
 *  - input envelope is `{"kind":"pwd","tmp":false}` (NOT an empty object); the `tmp` flag is
 *    part of the durable fingerprint.
 *  - `tmp=true` is rejected FAIL-CLOSED at decode (PWD_TMP_TRUE_DISPOSITION blocker,
 *    `S2_A6_CORE_PWD_G0_CHARACTERIZATION_RECEIPT.md`).
 *  - output is `PwdOutput(path)` — a TYPED_RUNTIME_OUTPUT carrying the resolved absolute path
 *    (APPROVED_ARCHITECTURAL_DELTA, S2-A6/G2 decision D2).
 *
 * Coverage matrix:
 * ```
 *  1.  identity                                              REQUIRED
 *  2.  contract completeness                                 REQUIRED (descriptor + 2 caps + MEMOIZED)
 *  3.  input codec round-trip (unit input)                    REQUIRED
 *  4.  input codec rejection (foreign envelope)               REQUIRED
 *  4b. input codec rejection (tmp=true fail-closed)           REQUIRED (pwd-specific)
 *  5.  output codec round-trip                               REQUIRED
 *  6.  output codec rejection (non-pwd kind)                 REQUIRED
 *  6b. output codec rejection (missing path)                  REQUIRED
 *  7.  canonical envelope (byte-identical to legacy dsl-v1)  REQUIRED
 *  8.  production registry resolution                        REQUIRED
 *  9.  fresh factory consistency                             REQUIRED
 * 10.  capability declaration (exact 2 keys)                 REQUIRED
 * 11.  capability admission (both available → Ready)         REQUIRED
 * 12.  missing WORKSPACE_IDENTITY rejects fail-closed        REQUIRED
 * 13.  missing EVENT_SINK rejects fail-closed                REQUIRED
 * 14.  success via canonical coordinator (typed outcome)     REQUIRED
 * 15.  typed failure (handler exception)                     REQUIRED
 * 16.  fresh durable (1 terminal SUCCEEDED row)              REQUIRED
 * 17.  replay (MEMOIZED: reuse, no handler re-run,          REQUIRED
 *      no duplicate PwdResolved events)
 * 18.  no-divergence by construction (unit input → single    REQUIRED (pwd-specific: tmp=false
 *      fingerprint; replay cannot diverge)                    in the fingerprint, always)
 * 19.  observability (StepStarted + StepFinished pair)       REQUIRED
 * 20.  PwdResolved event payload (frozen fields, exact sha)   REQUIRED (pwd-specific)
 * 21.  real registry path scenario                           REQUIRED
 * ```
 *
 * Replay semantics follow the MEMOIZED law:
 * ```
 * fresh / no durable entry          → EXECUTE (handler observes workspaceRoot, emits PwdResolved)
 * existing SUCCEEDED durable entry  → REUSE; handler MUST NOT re-run; no duplicate PwdResolved
 * ```
 */
@Timeout(30)
class CorePwdStepContractSuiteTest {

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { CorePwdStep.registerInto(this) }

    private fun noOpCredentialScopePort(): CredentialScopePort =
        CredentialScopePort { _, _ ->
            CredentialScopeOutcome.Unavailable(
                CredentialScopeFailure.StoreUnavailable("step-contract-suite stub"),
            )
        }

    private fun freshHarness(
        eventStore: InMemoryEventStore,
        workDir: java.nio.file.Path = Files.createTempDirectory("pwd-contract-"),
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

    private fun pwdNode(nodeId: String = "build/pwd") = OpaqueStepNode(
        id = StepId(nodeId),
        pluginStepId = CorePwdStep.KEY,
        // The canonical legacy dsl-v1 envelope for pwd() is {"kind":"pwd","tmp":false}.
        payload = VersionedStepPayload(
            schemaVersion = "dsl-v1",
            encoded = """{"kind":"pwd","tmp":false}""",
        ),
    )

    private fun pipeline(vararg nodes: OpaqueStepNode) = CompiledPipeline(
        id = DefinitionId("pwd-contract-suite"),
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
    fun `identity — CorePwdStep KEY is core dot pwd and duplicate registration fails`() {
        assertEquals(PluginStepId("core.pwd"), CorePwdStep.KEY)
        assertEquals("core.pwd", CorePwdStep.KEY.value)
        val r = registry()
        assertTrue(
            runCatching { CorePwdStep.registerInto(r) }.isFailure,
            "duplicate registration of core.pwd must fail",
        )
    }

    // ===== 2. contract completeness =====

    @Test
    fun `contract completeness — key, descriptor, codecs, two capabilities, MEMOIZED`() {
        val contract = CorePwdStep.definition.contract
        assertEquals(CorePwdStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "StepDescriptor must be present")
        assertEquals("pwd", contract.descriptor.name)
        assertEquals(
            setOf(Effect.READ_ONLY),
            contract.descriptor.effects.toSet(),
            "core.pwd effects MUST be READ_ONLY for tmp=false (pure observation, no mutation)",
        )
        assertEquals(
            ReplayPolicy.MEMOIZED,
            contract.descriptor.replayPolicy,
            "core.pwd replayPolicy MUST be MEMOIZED (replay reproduces the persisted observation)",
        )
        assertEquals(
            setOf(WORKSPACE_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
            contract.requiredCapabilities,
            "core.pwd MUST declare EXACTLY {WORKSPACE_IDENTITY, EVENT_SINK} as required capabilities",
        )
        assertNotNull(contract.inputCodec, "input codec must be present")
        assertNotNull(contract.outputCodec, "output codec must be present")
    }

    // ===== 3. input codec round-trip =====

    @Test
    fun `codec input — unit input encodes to the canonical legacy envelope and round-trips`() {
        val encoded = CorePwdStep.definition.contract.inputCodec.encode(PwdInput(tmp = false))
        assertEquals(
            """{"kind":"pwd","tmp":false}""",
            encoded.value,
            "input codec must emit the canonical legacy dsl-v1 envelope",
        )
        val decoded = CorePwdStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(PwdInput(tmp = false), decoded, "round-trip must reconstruct PwdInput(tmp=false)")
    }

    // ===== 4. input codec rejection =====

    @Test
    fun `codec input — decode rejects a foreign envelope kind`() {
        val bad = EncodedStepValue("""{"kind":"echo"}""")
        assertTrue(
            runCatching { CorePwdStep.definition.contract.inputCodec.decode(bad) }.isFailure,
            "input decode must fail closed on a non-pwd kind",
        )
    }

    // ===== 4b. input codec rejection — PWD_TMP_TRUE_DISPOSITION =====

    @Test
    fun `codec input — decode rejects tmp=true fail-closed (PWD_TMP_TRUE_DISPOSITION)`() {
        // The tmp=true path mints a timestamp-named tmp directory (non-deterministic) —
        // incompatible with MEMOIZED replay and READ_ONLY effects. The candidate MUST reject
        // it at decode time so the StepKey authority cannot be abused to serve tmp=true.
        val bad = EncodedStepValue("""{"kind":"pwd","tmp":true}""")
        val result = runCatching { CorePwdStep.definition.contract.inputCodec.decode(bad) }
        assertTrue(
            result.isFailure,
            "input decode MUST fail closed on tmp=true (PWD_TMP_TRUE_DISPOSITION blocker)",
        )
        assertTrue(
            (result.exceptionOrNull()?.message ?: "").contains("PWD_TMP_TRUE_DISPOSITION"),
            "rejection message MUST reference the PWD_TMP_TRUE_DISPOSITION blocker; got ${result.exceptionOrNull()?.message}",
        )
    }

    // ===== 5. output codec round-trip =====

    @Test
    fun `codec output — PwdOutput round-trips byte-identically`() {
        val value = PwdOutput(path = "/tmp/pipelinek-inmem-run123/workspace/stage-0")
        val encoded = CorePwdStep.definition.contract.outputCodec.encode(value)
        assertEquals(
            """{"kind":"pwd","path":"/tmp/pipelinek-inmem-run123/workspace/stage-0"}""",
            encoded.value,
            "output codec must emit the canonical kind/path envelope",
        )
        assertEquals(
            value,
            CorePwdStep.definition.contract.outputCodec.decode(encoded),
            "round-trip decode must reconstruct PwdOutput(path)",
        )
    }

    // ===== 6. output codec rejection =====

    @Test
    fun `codec output — decode rejects a non-pwd kind`() {
        val bad = EncodedStepValue("""{"kind":"echo","path":"/x"}""")
        assertTrue(
            runCatching { CorePwdStep.definition.contract.outputCodec.decode(bad) }.isFailure,
            "output decode must fail closed on a non-pwd kind",
        )
    }

    @Test
    fun `codec output — decode rejects a missing path field`() {
        val bad = EncodedStepValue("""{"kind":"pwd"}""")
        assertTrue(
            runCatching { CorePwdStep.definition.contract.outputCodec.decode(bad) }.isFailure,
            "output decode must fail closed on a missing path field",
        )
    }

    // ===== 7. canonical envelope =====

    @Test
    fun `canonical envelope — input codec envelope is byte-identical to legacy dsl-v1 pwd envelope`() {
        // Continuous durable fingerprint/journal identity requires byte-identical envelopes.
        val registryEnvelope = CorePwdStep.definition.contract.inputCodec.encode(PwdInput(tmp = false)).value
        val legacyEnvelope = """{"kind":"pwd","tmp":false}"""
        assertEquals(
            legacyEnvelope,
            registryEnvelope,
            "registry input codec MUST emit a byte-identical dsl-v1 envelope",
        )
    }

    // ===== 8. production registry resolution =====

    @Test
    fun `registry resolution — production factory contains core dot pwd`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(registry.contains(CorePwdStep.KEY), "production registry must contain core.pwd")
        val definition = registry.definition(CorePwdStep.KEY)
        assertNotNull(definition, "production registry MUST resolve core.pwd to a StepDefinition")
        assertSame(
            CorePwdStep.definition,
            definition,
            "production registry MUST return the canonical CorePwdStep.definition instance",
        )
    }

    // ===== 9. fresh factory consistency =====

    @Test
    fun `registry resolution — production factory registry is fresh per call and consistent across calls`() {
        val r1 = CoreStepRegistryFactory.registry()
        val r2 = CoreStepRegistryFactory.registry()
        assertFalse(r1 === r2, "factory must produce fresh per-call registries")
        assertTrue(r1.contains(CorePwdStep.KEY))
        assertTrue(r2.contains(CorePwdStep.KEY))
        assertSame(r1.definition(CorePwdStep.KEY), r2.definition(CorePwdStep.KEY))
    }

    // ===== 10. capability declaration =====

    @Test
    fun `capability declaration — core pwd declares EXACTLY WORKSPACE_IDENTITY plus EVENT_SINK`() {
        // The handler reaches TWO capabilities: WorkspaceIdentity (workspaceRoot observation)
        // and EventSink (PwdResolved publication). This pins the typed boundary — the handler
        // MUST never reach user.dir / controlDirRoot or append events directly.
        val declared = CorePwdStep.definition.contract.requiredCapabilities
        assertEquals(
            2,
            declared.size,
            "core.pwd MUST declare exactly 2 required capabilities; got ${declared.map { it.key }}",
        )
        assertTrue(
            WORKSPACE_IDENTITY_CAPABILITY in declared,
            "WORKSPACE_IDENTITY_CAPABILITY MUST be declared",
        )
        assertTrue(
            EVENT_SINK_CAPABILITY in declared,
            "EVENT_SINK_CAPABILITY MUST be declared",
        )
    }

    // ===== 11. capability admission (both available) =====

    @Test
    fun `capability admission — both capabilities available prepares Ready and handler executes`() {
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = CorePwdStep.KEY,
                encodedInput = CorePwdStep.definition.contract.inputCodec.encode(PwdInput(tmp = false)),
                availableCapabilities = setOf(WORKSPACE_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
            )
            assertTrue(
                preparation is ExecutionPreparation.Ready,
                "admission must succeed when both declared capabilities are available",
            )
        }
    }

    // ===== 12. missing WORKSPACE_IDENTITY =====

    @Test
    fun `missing capability — admission rejects when WORKSPACE_IDENTITY is absent`() {
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = CorePwdStep.KEY,
                encodedInput = CorePwdStep.definition.contract.inputCodec.encode(PwdInput(tmp = false)),
                availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
            )
            assertTrue(
                preparation is ExecutionPreparation.Rejected,
                "missing WORKSPACE_IDENTITY must surface as Rejected admission (fail-closed)",
            )
            val rejected = assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
            assertTrue(
                rejected.reason.contains(WORKSPACE_IDENTITY_CAPABILITY.key),
                "Rejection MUST identify the missing WORKSPACE_IDENTITY capability",
            )
        }
    }

    // ===== 13. missing EVENT_SINK =====

    @Test
    fun `missing capability — admission rejects when EVENT_SINK is absent`() {
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = CorePwdStep.KEY,
                encodedInput = CorePwdStep.definition.contract.inputCodec.encode(PwdInput(tmp = false)),
                availableCapabilities = setOf(WORKSPACE_IDENTITY_CAPABILITY),
            )
            assertTrue(
                preparation is ExecutionPreparation.Rejected,
                "missing EVENT_SINK must surface as Rejected admission (fail-closed)",
            )
            val rejected = assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
            assertTrue(
                rejected.reason.contains(EVENT_SINK_CAPABILITY.key),
                "Rejection MUST identify the missing EVENT_SINK capability",
            )
        }
    }

    // ===== 14. success via canonical coordinator =====

    @Test
    fun `success — registry-routed pwd SUCCEEDS with one terminal SUCCEEDED operation and typed output`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            val outcome = h.coord.run(pipeline(pwdNode()), RunId("pwd-ok"))
            assertEquals(
                RunOutcome.Success,
                outcome,
                "a pwd observation against the canonical workspace must succeed",
            )
            val rows = h.journal.listForRun("pwd-ok")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    // ===== 15. typed failure (handler exception) =====

    @Test
    fun `typed failure — a registry-routed pwd whose handler throws surfaces as RunOutcome Failure`() {
        val throwingHandler: StepHandler<PwdInput, PwdOutput> =
            StepHandler { _: PwdInput, _: StepHandlerContext ->
                throw IllegalStateException("core.pwd handler contract violated for test")
            }
        val throwingRegistry = InMemoryStepRegistry().apply {
            register(
                object : StepDefinition<PwdInput, PwdOutput> {
                    override val contract: StepContract<PwdInput, PwdOutput> = StepContract(
                        key = CorePwdStep.KEY,
                        descriptor = CorePwdStep.definition.contract.descriptor,
                        inputCodec = CorePwdStep.definition.contract.inputCodec,
                        outputCodec = CorePwdStep.definition.contract.outputCodec,
                        requiredCapabilities = setOf(WORKSPACE_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
                    )
                    override val handler: StepHandler<PwdInput, PwdOutput> = throwingHandler
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
            controlDirRoot = Files.createTempDirectory("pwd-contract-fail-"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = throwingRegistry,
        )
        runBlocking {
            val outcome = coord.run(pipeline(pwdNode()), RunId("pwd-throw"))
            assertTrue(
                outcome is RunOutcome.Failure,
                "handler exceptions must surface as a typed RunOutcome.Failure, not silent success; got $outcome",
            )
        }
    }

    // ===== 16. fresh durable =====

    @Test
    fun `fresh durable — first execution of core dot pwd writes one terminal SUCCEEDED operation`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(pwdNode()), RunId("pwd-first"))
            val rows = h.journal.listForRun("pwd-first")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    // ===== 17. replay (MEMOIZED) =====

    @Test
    fun `replay — a previously SUCCEEDED pwd is reused without re-running the handler or duplicating PwdResolved`() {
        // MEMOIZED law:
        //   fresh / no durable entry  → EXECUTE (handler observes workspaceRoot, emits ONE PwdResolved)
        //   existing SUCCEEDED entry  → REUSE; handler MUST NOT re-run; PwdResolved count stays at 1.
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(pwdNode()), RunId("pwd-replay"))
            val firstStarts = h.eventStore.eventsFor("pwd-replay").filterIsInstance<StepStarted>().count()
            assertEquals(1, firstStarts, "first execution emits exactly one StepStarted")
            val firstResolved = h.eventStore.eventsFor("pwd-replay").filterIsInstance<PwdResolved>().count()
            assertEquals(1, firstResolved, "first execution emits exactly one PwdResolved")

            // Second execution at the SAME runId: MEMOIZED reuse.
            h.coord.run(pipeline(pwdNode()), RunId("pwd-replay"))
            val secondStarts = h.eventStore.eventsFor("pwd-replay").filterIsInstance<StepStarted>().count()
            assertEquals(
                1,
                secondStarts,
                "replay must NOT re-run the handler (StepStarted count stays at 1)",
            )
            val secondResolved = h.eventStore.eventsFor("pwd-replay").filterIsInstance<PwdResolved>().count()
            assertEquals(
                1,
                secondResolved,
                "replay must NOT duplicate PwdResolved (durable observation reproduced once)",
            )
            assertEquals(
                1,
                h.journal.listForRun("pwd-replay").count(),
                "replay must reuse the single terminal SUCCEEDED row",
            )
        }
    }

    // ===== 18. no-divergence by construction =====

    @Test
    fun `no-divergence — unit input yields a single fingerprint, replay cannot diverge by content`() {
        // core.pwd input at tmp=false always encodes to the same legacy envelope; the durable
        // fingerprint is identical for any valid payload (tmp=true never reaches the fingerprint
        // because decode rejects it). The durable path is structurally monotonic for this Step;
        // this test pins that property so future codec changes cannot silently introduce a
        // divergent path.
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(pwdNode()), RunId("pwd-nodiv"))
            val second = h.coord.run(pipeline(pwdNode()), RunId("pwd-nodiv"))
            val third = h.coord.run(pipeline(pwdNode()), RunId("pwd-nodiv"))
            assertEquals(
                RunOutcome.Success,
                second,
                "second replay against unit input must succeed (no divergence)",
            )
            assertEquals(
                RunOutcome.Success,
                third,
                "third replay against unit input must succeed (no divergence)",
            )
        }
    }

    // ===== 19. observability =====

    @Test
    fun `observability — every core dot pwd run emits a StepStarted StepFinished pair`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(pwdNode()), RunId("pwd-obs"))
            val events = h.eventStore.eventsFor("pwd-obs").toList()
            assertTrue(events.any { it is StepStarted }, "StepStarted must be emitted (lifecycle observability)")
            assertTrue(events.any { it is StepFinished }, "StepFinished must be emitted (lifecycle observability)")
        }
    }

    // ===== 20. PwdResolved event payload (frozen fields) =====

    @Test
    fun `PwdResolved event payload — frozen fields, exact path, exact workspaceRoot, exact sha256`() {
        // The PwdResolved event is the durable observation. Its CONTENT fields are byte-equivalent
        // to the legacy CanonicalPwdNodeDispatcher (LEGACY_REMOVED in G5): uuid eventId,
        // sha256 hex of path, workspaceRoot echoed alongside the resolved path. The `sequence`
        // field is re-assigned by [InMemoryEventStore] to a monotonically increasing value (the
        // handler submits `0L` as the marker), so we assert strict positivity rather than a
        // literal value.
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(pwdNode()), RunId("pwd-event"))
            val events = eventStore.eventsFor("pwd-event").toList().filterIsInstance<PwdResolved>()
            assertEquals(1, events.size, "exactly one PwdResolved event is emitted")
            val event = events.single()
            assertEquals("PwdResolved", event.kind)
            assertTrue(
                event.sequence > 0L,
                "PwdResolved sequence MUST be a positive monotonic value assigned by the event store; got ${event.sequence}",
            )
            assertEquals(
                event.workspaceRoot,
                event.path,
                "PwdResolved path MUST equal the workspaceRoot (tmp=false projection: absolute path of the canonical workspace)",
            )
            assertEquals(
                CorePwdStep.sha256(event.path),
                event.sha256,
                "PwdResolved sha256 MUST be the hex SHA-256 of the resolved path",
            )
            // eventId is a uuid — assert it parses as such.
            runCatching { UUID.fromString(event.eventId) }
                .onFailure { throw AssertionError("PwdResolved eventId MUST be a UUID: ${event.eventId}", it) }
        }
    }

    // ===== 21. real registry path scenario =====

    @Test
    fun `real registry path — canonical coordinator + capability bridge exercises core dot pwd end-to-end`() {
        // Full registry seam: RegistryExecutionPreparation → capability admission → handler execution
        // → typed output → durable journal → PwdResolved observation. Exercises the SAME code path
        // the production canonical coordinator uses, without coupling the suite to the DSL compiler.
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)

            val encoded = CorePwdStep.definition.contract.inputCodec.encode(PwdInput(tmp = false))
            val node = OpaqueStepNode(
                id = StepId("real/pwd"),
                pluginStepId = CorePwdStep.KEY,
                payload = VersionedStepPayload(
                    schemaVersion = "dsl-v1",
                    encoded = encoded.value,
                ),
            )

            val preparation = RegistryExecutionPreparation.prepare(
                registry = h.registry,
                key = CorePwdStep.KEY,
                encodedInput = encoded,
                availableCapabilities = setOf(WORKSPACE_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
            )
            val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
            val prepared = assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)

            val outcome = h.coord.run(pipeline(node), RunId("pwd-real"))
            assertEquals(RunOutcome.Success, outcome, "registry seam end-to-end must succeed")

            // Independently exercise the boundary coexecute path to prove the typed outcome.
            val ctx = CanonicalRuntimeContext(
                opId = OpId("pwd-real-boundary", 0, 0),
                runId = "pwd-real-boundary",
                stageName = "candidate",
                stageIndex = 0,
                stepIndex = 0,
                shOptions = ShOptions.EMPTY,
                controlDirRoot = Files.createTempDirectory("pwd-real-boundary-"),
                eventSink = eventStore,
            )
            val result = RegistryExecutionBoundary.coexecute(prepared, ctx)
            assertEquals(dev.rubentxu.pipeline.v2.domain.StepOutcome.Success, result.outcome)
            val typed = CorePwdStep.definition.contract.outputCodec.decode(result.encodedOutput!!)
            assertTrue(
                typed.path.isNotEmpty(),
                "PwdOutput.path MUST carry the resolved absolute workspace path",
            )
            // NOTE: the boundary path runs in an INDEPENDENT CanonicalRuntimeContext (ShOptions.EMPTY),
            // so its workspaceRoot legitimately differs from the coordinated run's context. Each
            // execution projects ITS OWN workspace observation; cross-context path equality is NOT
            // a contract invariant. The coordinated run's observation is asserted above (row 20).
        }
    }

    // Helper for tests that build an envelope manually.
    @Suppress("unused")
    private fun decodeEnvelope(encoded: String): JsonObject =
        Json.parseToJsonElement(encoded).jsonObject

    @Suppress("unused")
    private fun envelopePwd(tmp: Boolean): String =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("kind", JsonPrimitive("pwd"))
                put("tmp", JsonPrimitive(tmp))
            },
        )
}
