package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
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
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.UnixDetected
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
 * StepContractSuite — LFC-2E1 / S2-A5 / G6 for `core.isUnix`.
 *
 * Certifies `core.isUnix` end-to-end across the registry-driven, open-world Step seam following
 * the certified `core.echo` (MEMOIZED replay law) and `core.sleep` (atomic handler, no caps) models.
 * Unlike `core.sleep`, `core.isUnix` declares TWO required capabilities
 * ([PLATFORM_IDENTITY_CAPABILITY] + [EVENT_SINK_CAPABILITY]) — the handler reaches the runtime
 * capability bridge to read the single `System.getProperty("os.name")` and to publish the durable
 * `UnixDetected` observation.
 *
 * Coverage matrix:
 * ```
 *  1.  identity                                              REQUIRED
 *  2.  contract completeness                                 REQUIRED (descriptor + 2 caps + MEMOIZED)
 *  3.  input codec round-trip (unit input, envelope {})       REQUIRED
 *  4.  input codec rejection (foreign envelope)              REQUIRED
 *  5.  output codec round-trip                               REQUIRED (true / false)
 *  6.  output codec rejection (non-isUnix kind)              REQUIRED
 *  7.  canonical envelope (byte-identical to legacy dsl-v1)  REQUIRED (input == "{}")
 *  8.  production registry resolution                        REQUIRED
 *  9.  fresh factory consistency                             REQUIRED
 * 10.  capability declaration (exact 2 keys)                 REQUIRED
 * 11.  capability admission (both available → Ready)         REQUIRED
 * 12.  missing PLATFORM_IDENTITY rejects fail-closed         REQUIRED
 * 13.  missing EVENT_SINK rejects fail-closed                REQUIRED
 * 14.  success via canonical coordinator (typed outcome)     REQUIRED
 * 15.  typed failure (handler exception)                     REQUIRED
 * 16.  fresh durable (1 terminal SUCCEEDED row)              REQUIRED
 * 17.  replay (MEMOIZED: reuse, no handler re-run,          REQUIRED
 *      no duplicate UnixDetected events)
 * 18.  no-divergence by construction (unit input → single    REQUIRED (isUnix-specific)
 *      fingerprint; replay cannot diverge)
 * 19.  observability (StepStarted + StepFinished pair)       REQUIRED
 * 20.  UnixDetected event payload (frozen fields, exact sha) REQUIRED (isUnix-specific)
 * 21.  real registry path scenario                           REQUIRED
 *      (canonical coordinator via OpaqueStepNode with the
 *       full capability bridge — the registry seam exercised
 *       end-to-end without the DSL compiler)
 * 22.  architecture fitness                                  DELEGATED to
 *      S3IsUnixLegacyRemovedFitnessTest + CoreIsUnixRegistryPrimaryFitnessTest (G5)
 * ```
 *
 * Replay semantics follow the MEMOIZED law:
 * ```
 * fresh / no durable entry          → EXECUTE (handler classifies osName, emits UnixDetected)
 * existing SUCCEEDED durable entry  → REUSE; handler MUST NOT re-run; no duplicate UnixDetected
 * existing entry, different input   → n/a: input is a unit value; the durable fingerprint is
 *                                    identical for any payload; replay cannot diverge.
 * ```
 */
@Timeout(30)
class CoreIsUnixStepContractSuiteTest {

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { CoreIsUnixStep.registerInto(this) }

    private fun noOpCredentialScopePort(): CredentialScopePort =
        CredentialScopePort { _, _ ->
            CredentialScopeOutcome.Unavailable(
                CredentialScopeFailure.StoreUnavailable("step-contract-suite stub"),
            )
        }

    /**
     * The harness uses the production [CanonicalRuntimeCapabilityAccess] so the handler's
     * `PlatformIdentity` lookup reads `System.getProperty("os.name")` — exactly as production does.
     * Coupling to the host OS is intentional: the test asserts against the REAL osName so the
     * UnixDetected event payload invariant holds across any host. (If the production bridge ever
     * changes its observation source, this suite will fail closed and force the contract review.)
     */
    private val realOsName: String get() = System.getProperty("os.name") ?: ""

    private fun freshHarness(
        eventStore: InMemoryEventStore,
        workDir: java.nio.file.Path = Files.createTempDirectory("isunix-contract-"),
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

    private fun isUnixNode(nodeId: String = "build/isUnix") = OpaqueStepNode(
        id = StepId(nodeId),
        pluginStepId = CoreIsUnixStep.KEY,
        // Input codec decodes any JSON object as IsUnixInput (unit). The legacy dsl-v1 envelope is "{}".
        payload = VersionedStepPayload(
            schemaVersion = "dsl-v1",
            encoded = """{}""",
        ),
    )

    private fun pipeline(vararg nodes: OpaqueStepNode) = CompiledPipeline(
        id = DefinitionId("isunix-contract-suite"),
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
    fun `identity — CoreIsUnixStep KEY is core dot isUnix and duplicate registration fails`() {
        assertEquals(PluginStepId("core.isUnix"), CoreIsUnixStep.KEY)
        assertEquals("core.isUnix", CoreIsUnixStep.KEY.value)
        val r = registry()
        assertTrue(
            runCatching { CoreIsUnixStep.registerInto(r) }.isFailure,
            "duplicate registration of core.isUnix must fail",
        )
    }

    // ===== 2. contract completeness =====

    @Test
    fun `contract completeness — key, descriptor, codecs, two capabilities, MEMOIZED`() {
        val contract = CoreIsUnixStep.definition.contract
        assertEquals(CoreIsUnixStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "StepDescriptor must be present")
        assertEquals("isUnix", contract.descriptor.name)
        assertEquals(
            setOf(Effect.READ_ONLY),
            contract.descriptor.effects.toSet(),
            "core.isUnix effects MUST be READ_ONLY (pure observation, no mutation)",
        )
        assertEquals(
            ReplayPolicy.MEMOIZED,
            contract.descriptor.replayPolicy,
            "core.isUnix replayPolicy MUST be MEMOIZED (replay reproduces the persisted observation)",
        )
        assertEquals(
            setOf(PLATFORM_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
            contract.requiredCapabilities,
            "core.isUnix MUST declare EXACTLY {PLATFORM_IDENTITY, EVENT_SINK} as required capabilities",
        )
        assertNotNull(contract.inputCodec, "input codec must be present")
        assertNotNull(contract.outputCodec, "output codec must be present")
    }

    // ===== 3. input codec round-trip =====

    @Test
    fun `codec input — unit input encodes to the canonical empty envelope and round-trips`() {
        val encoded = CoreIsUnixStep.definition.contract.inputCodec.encode(IsUnixInput)
        assertEquals(
            """{}""",
            encoded.value,
            "input codec must emit the canonical empty-object envelope (unit input)",
        )
        // Round-trip: decode the encoded envelope back to IsUnixInput (data object).
        val decoded = CoreIsUnixStep.definition.contract.inputCodec.decode(encoded)
        assertSame(IsUnixInput, decoded, "round-trip must reconstruct the singleton IsUnixInput")
    }

    // ===== 4. input codec rejection =====

    @Test
    fun `codec input — decode rejects a non-object JSON value`() {
        // The input codec decodes only JSON objects (any object → unit). A primitive / array is rejected.
        val primitives = listOf(
            """null""",
            """true""",
            """42""",
            """"linux"""",
            """["linux"]""",
        )
        for (bad in primitives) {
            val result = runCatching {
                CoreIsUnixStep.definition.contract.inputCodec.decode(EncodedStepValue(bad))
            }
            assertTrue(
                result.isFailure,
                "decode must fail closed on non-object JSON value: $bad",
            )
        }
    }

    // ===== 5. output codec round-trip =====

    @Test
    fun `codec output — IsUnixOutput round-trips byte-identically for true and false`() {
        for (isUnix in listOf(true, false)) {
            val value = IsUnixOutput(isUnix = isUnix)
            val encoded = CoreIsUnixStep.definition.contract.outputCodec.encode(value)
            assertEquals(
                """{"kind":"isUnix","isUnix":${isUnix}}""",
                encoded.value,
                "output codec must emit the canonical kind/isUnix envelope for value=$isUnix",
            )
            assertEquals(
                value,
                CoreIsUnixStep.definition.contract.outputCodec.decode(encoded),
                "round-trip decode must reconstruct IsUnixOutput(isUnix=$isUnix)",
            )
        }
    }

    // ===== 6. output codec rejection =====

    @Test
    fun `codec output — decode rejects a non-isUnix kind`() {
        val bad = EncodedStepValue("""{"kind":"echo","isUnix":true}""")
        assertTrue(
            runCatching { CoreIsUnixStep.definition.contract.outputCodec.decode(bad) }.isFailure,
            "output decode must fail closed on a non-isUnix kind",
        )
    }

    @Test
    fun `codec output — decode rejects a missing isUnix field`() {
        val bad = EncodedStepValue("""{"kind":"isUnix"}""")
        assertTrue(
            runCatching { CoreIsUnixStep.definition.contract.outputCodec.decode(bad) }.isFailure,
            "output decode must fail closed on a missing isUnix field",
        )
    }

    // ===== 7. canonical envelope =====

    @Test
    fun `canonical envelope — input codec envelope is byte-identical to legacy dsl-v1 isUnix envelope`() {
        // Continuous durable fingerprint/journal identity requires byte-identical envelopes.
        val registryEnvelope = CoreIsUnixStep.definition.contract.inputCodec.encode(IsUnixInput).value
        val legacyEnvelope = """{}"""
        assertEquals(
            legacyEnvelope,
            registryEnvelope,
            "registry input codec MUST emit a byte-identical dsl-v1 envelope (empty object)",
        )
    }

    // ===== 8. production registry resolution =====

    @Test
    fun `registry resolution — production factory contains core dot isUnix`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(registry.contains(CoreIsUnixStep.KEY), "production registry must contain core.isUnix")
        val definition = registry.definition(CoreIsUnixStep.KEY)
        assertNotNull(definition, "production registry MUST resolve core.isUnix to a StepDefinition")
        assertSame(
            CoreIsUnixStep.definition,
            definition,
            "production registry MUST return the canonical CoreIsUnixStep.definition instance",
        )
    }

    // ===== 9. fresh factory consistency =====

    @Test
    fun `registry resolution — production factory registry is fresh per call and consistent across calls`() {
        val r1 = CoreStepRegistryFactory.registry()
        val r2 = CoreStepRegistryFactory.registry()
        assertFalse(r1 === r2, "factory must produce fresh per-call registries")
        assertTrue(r1.contains(CoreIsUnixStep.KEY))
        assertTrue(r2.contains(CoreIsUnixStep.KEY))
        assertSame(r1.definition(CoreIsUnixStep.KEY), r2.definition(CoreIsUnixStep.KEY))
    }

    // ===== 10. capability declaration =====

    @Test
    fun `capability declaration — core isUnix declares EXACTLY PLATFORM_IDENTITY plus EVENT_SINK`() {
        // The handler reaches TWO capabilities: PlatformIdentity (osName observation) and EventSink
        // (UnixDetected publication). This pins the typed boundary — the handler MUST never call
        // System.getProperty or append events directly without going through the bridge.
        val declared = CoreIsUnixStep.definition.contract.requiredCapabilities
        assertEquals(
            2,
            declared.size,
            "core.isUnix MUST declare exactly 2 required capabilities; got ${declared.map { it.key }}",
        )
        assertTrue(
            PLATFORM_IDENTITY_CAPABILITY in declared,
            "PLATFORM_IDENTITY_CAPABILITY MUST be declared",
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
                key = CoreIsUnixStep.KEY,
                encodedInput = CoreIsUnixStep.definition.contract.inputCodec.encode(IsUnixInput),
                availableCapabilities = setOf(PLATFORM_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
            )
            assertTrue(
                preparation is ExecutionPreparation.Ready,
                "admission must succeed when both declared capabilities are available",
            )
        }
    }

    // ===== 12. missing PLATFORM_IDENTITY =====

    @Test
    fun `missing capability — admission rejects when PLATFORM_IDENTITY is absent`() {
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = CoreIsUnixStep.KEY,
                encodedInput = CoreIsUnixStep.definition.contract.inputCodec.encode(IsUnixInput),
                availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
            )
            assertTrue(
                preparation is ExecutionPreparation.Rejected,
                "missing PLATFORM_IDENTITY must surface as Rejected admission (fail-closed)",
            )
            val rejected = assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
            assertTrue(
                rejected.reason.contains(PLATFORM_IDENTITY_CAPABILITY.key),
                "Rejection MUST identify the missing PLATFORM_IDENTITY capability",
            )
        }
    }

    // ===== 13. missing EVENT_SINK =====

    @Test
    fun `missing capability — admission rejects when EVENT_SINK is absent`() {
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = CoreIsUnixStep.KEY,
                encodedInput = CoreIsUnixStep.definition.contract.inputCodec.encode(IsUnixInput),
                availableCapabilities = setOf(PLATFORM_IDENTITY_CAPABILITY),
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
    fun `success — registry-routed isUnix SUCCEEDS with one terminal SUCCEEDED operation and typed output`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            val outcome = h.coord.run(pipeline(isUnixNode()), RunId("isunix-ok"))
            assertEquals(
                RunOutcome.Success,
                outcome,
                "an isUnix classification against the host osName must succeed (the canonical classifier is total)",
            )
            val rows = h.journal.listForRun("isunix-ok")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    // ===== 15. typed failure (handler exception) =====

    @Test
    fun `typed failure — a registry-routed isUnix whose handler throws surfaces as RunOutcome Failure`() {
        val throwingHandler: StepHandler<IsUnixInput, IsUnixOutput> =
            StepHandler { _: IsUnixInput, _: StepHandlerContext ->
                throw IllegalStateException("core.isUnix handler contract violated for test")
            }
        val throwingRegistry = InMemoryStepRegistry().apply {
            register(
                object : StepDefinition<IsUnixInput, IsUnixOutput> {
                    override val contract: StepContract<IsUnixInput, IsUnixOutput> = StepContract(
                        key = CoreIsUnixStep.KEY,
                        descriptor = CoreIsUnixStep.definition.contract.descriptor,
                        inputCodec = CoreIsUnixStep.definition.contract.inputCodec,
                        outputCodec = CoreIsUnixStep.definition.contract.outputCodec,
                        requiredCapabilities = setOf(PLATFORM_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
                    )
                    override val handler: StepHandler<IsUnixInput, IsUnixOutput> = throwingHandler
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
            controlDirRoot = Files.createTempDirectory("isunix-contract-fail-"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = throwingRegistry,
        )
        runBlocking {
            val outcome = coord.run(pipeline(isUnixNode()), RunId("isunix-throw"))
            assertTrue(
                outcome is RunOutcome.Failure,
                "handler exceptions must surface as a typed RunOutcome.Failure, not silent success; got $outcome",
            )
        }
    }

    // ===== 16. fresh durable =====

    @Test
    fun `fresh durable — first execution of core dot isUnix writes one terminal SUCCEEDED operation`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(isUnixNode()), RunId("isunix-first"))
            val rows = h.journal.listForRun("isunix-first")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    // ===== 17. replay (MEMOIZED) =====

    @Test
    fun `replay — a previously SUCCEEDED isUnix is reused without re-running the handler or duplicating UnixDetected`() {
        // MEMOIZED law:
        //   fresh / no durable entry  → EXECUTE (handler classifies, emits exactly ONE UnixDetected)
        //   existing SUCCEEDED entry  → REUSE; handler MUST NOT re-run; UnixDetected count stays at 1.
        // Handler-invocation invariant is observed via:
        //   - the count of journaled terminal rows (stays at 1)
        //   - the count of UnixDetected events (stays at 1; the durable observation is reproduced)
        //   - the count of StepStarted events (stays at 1)
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(isUnixNode()), RunId("isunix-replay"))
            val firstStarts = h.eventStore.eventsFor("isunix-replay").filterIsInstance<StepStarted>().count()
            assertEquals(1, firstStarts, "first execution emits exactly one StepStarted")
            val firstUnix = h.eventStore.eventsFor("isunix-replay").filterIsInstance<UnixDetected>().count()
            assertEquals(1, firstUnix, "first execution emits exactly one UnixDetected")

            // Second execution at the SAME runId: MEMOIZED reuse.
            h.coord.run(pipeline(isUnixNode()), RunId("isunix-replay"))
            val secondStarts = h.eventStore.eventsFor("isunix-replay").filterIsInstance<StepStarted>().count()
            assertEquals(
                1,
                secondStarts,
                "replay must NOT re-run the handler (StepStarted count stays at 1)",
            )
            val secondUnix = h.eventStore.eventsFor("isunix-replay").filterIsInstance<UnixDetected>().count()
            assertEquals(
                1,
                secondUnix,
                "replay must NOT duplicate UnixDetected (durable observation reproduced once)",
            )
            assertEquals(
                1,
                h.journal.listForRun("isunix-replay").count(),
                "replay must reuse the single terminal SUCCEEDED row",
            )
        }
    }

    // ===== 18. no-divergence by construction =====

    @Test
    fun `no-divergence — unit input yields a single fingerprint, replay cannot diverge by content`() {
        // core.isUnix input is a unit value (IsUnixInput); the input codec encodes to the canonical
        // "{}" envelope regardless of when it is invoked. There is no fingerprint content variation
        // that could cause typed divergence on replay. The durable path is structurally monotonic
        // for this Step; this test pins that property so future codec changes cannot silently
        // introduce a divergent path.
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(isUnixNode()), RunId("isunix-nodiv"))
            // Two replays at the same runId must both succeed (MEMOIZED reuse, no divergence).
            val second = h.coord.run(pipeline(isUnixNode()), RunId("isunix-nodiv"))
            val third = h.coord.run(pipeline(isUnixNode()), RunId("isunix-nodiv"))
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
    fun `observability — every core dot isUnix run emits a StepStarted StepFinished pair`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(isUnixNode()), RunId("isunix-obs"))
            val events = h.eventStore.eventsFor("isunix-obs").toList()
            assertTrue(events.any { it is StepStarted }, "StepStarted must be emitted (lifecycle observability)")
            assertTrue(events.any { it is StepFinished }, "StepFinished must be emitted (lifecycle observability)")
        }
    }

    // ===== 20. UnixDetected event payload (frozen fields) =====

    @Test
    fun `UnixDetected event payload — frozen fields, exact isUnix, exact osName, exact sha256`() {
        // The UnixDetected event is the durable observation. Its CONTENT fields are byte-equivalent
        // to the legacy CanonicalIsUnixNodeDispatcher (LEGACY_REMOVED in G5): uuid eventId,
        // sha256 hex of osName. The `sequence` field is re-assigned by [InMemoryEventStore] to a
        // monotonically increasing value (the handler submits `0L` as the marker), so we assert
        // strict positivity rather than a literal value.
        runBlocking {
            val eventStore = InMemoryEventStore()
            val osName = realOsName
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(isUnixNode()), RunId("isunix-event"))
            val events = eventStore.eventsFor("isunix-event").toList().filterIsInstance<UnixDetected>()
            assertEquals(1, events.size, "exactly one UnixDetected event is emitted")
            val event = events.single()
            assertEquals("UnixDetected", event.kind)
            assertTrue(
                event.sequence > 0L,
                "UnixDetected sequence MUST be a positive monotonic value assigned by the event store; got ${event.sequence}",
            )
            assertEquals(osName, event.osName, "UnixDetected osName MUST mirror the PlatformIdentity observation")
            assertEquals(
                CoreIsUnixStep.classify(osName),
                event.isUnix,
                "UnixDetected isUnix MUST match the classification",
            )
            assertEquals(
                CoreIsUnixStep.sha256(osName),
                event.sha256,
                "UnixDetected sha256 MUST be the hex SHA-256 of the osName",
            )
            // eventId is a uuid — assert it parses as such.
            runCatching { UUID.fromString(event.eventId) }
                .onFailure { throw AssertionError("UnixDetected eventId MUST be a UUID: ${event.eventId}", it) }
        }
    }

    // ===== 21. real registry path scenario =====

    @Test
    fun `real registry path — canonical coordinator + capability bridge exercises core dot isUnix end-to-end`() {
        // Full registry seam: RegistryExecutionPreparation → capability admission → handler execution
        // → typed output → durable journal → UnixDetected observation. Asserts against the REAL
        // host osName (the production [CanonicalRuntimeCapabilityAccess] reads System.getProperty
        // directly — no test-only override). This exercises the SAME code path the production
        // canonical coordinator uses, without coupling the suite to the DSL compiler (which
        // currently lowers isUnix() to the legacy StepSpec.IsUnix — out of scope for G6,
        // covered separately by the scripted paths).
        runBlocking {
            val eventStore = InMemoryEventStore()
            val osName = realOsName
            val h = freshHarness(eventStore)

            // Build the StepNode + encoded input exactly as the canonical spine would see them.
            val encoded = CoreIsUnixStep.definition.contract.inputCodec.encode(IsUnixInput)
            val node = OpaqueStepNode(
                id = StepId("real/isUnix"),
                pluginStepId = CoreIsUnixStep.KEY,
                payload = VersionedStepPayload(
                    schemaVersion = "dsl-v1",
                    encoded = encoded.value,
                ),
            )

            // Prepare via the production registry + declared capabilities.
            val preparation = RegistryExecutionPreparation.prepare(
                registry = h.registry,
                key = CoreIsUnixStep.KEY,
                encodedInput = encoded,
                availableCapabilities = setOf(PLATFORM_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
            )
            val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
            val prepared = assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)

            // Run through the canonical coordinator for the durable path.
            val outcome = h.coord.run(pipeline(node), RunId("isunix-real"))
            assertEquals(RunOutcome.Success, outcome, "registry seam end-to-end must succeed")

            // Independently exercise the boundary coexecute path to prove the typed outcome.
            val ctx = CanonicalRuntimeContext(
                opId = OpId("isunix-real-boundary", 0, 0),
                runId = "isunix-real-boundary",
                stageName = "candidate",
                stageIndex = 0,
                stepIndex = 0,
                shOptions = ShOptions.EMPTY,
                controlDirRoot = Files.createTempDirectory("isunix-real-boundary-"),
                eventSink = eventStore,
            )
            val result = RegistryExecutionBoundary.coexecute(prepared, ctx)
            assertEquals(dev.rubentxu.pipeline.v2.domain.StepOutcome.Success, result.outcome)
            val typed = CoreIsUnixStep.definition.contract.outputCodec.decode(result.encodedOutput!!)
            assertEquals(IsUnixOutput(isUnix = CoreIsUnixStep.classify(osName)), typed)
        }
    }

    // Helper for tests that build an envelope manually.
    @Suppress("unused")
    private fun decodeEnvelope(encoded: String): JsonObject =
        Json.parseToJsonElement(encoded).jsonObject

    @Suppress("unused")
    private fun envelopeIsUnix(isUnix: Boolean): String =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("kind", JsonPrimitive("isUnix"))
                put("isUnix", JsonPrimitive(isUnix))
            },
        )
}
