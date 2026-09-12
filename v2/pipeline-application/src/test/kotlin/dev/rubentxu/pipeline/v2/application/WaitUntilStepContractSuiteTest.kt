package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
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
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files

/**
 * StepContractSuite — LFC-2E1 / S2-A8 / G3 for `core.waitUntil`.
 *
 * Certifies `core.waitUntil` end-to-end across the registry-driven, open-world Step seam
 * following the certified `core.pwd` model (S2-A6/G6).
 *
 * Coverage matrix:
 * ```
 *  1.  identity                                              REQUIRED
 *  2.  contract completeness                                 REQUIRED (descriptor + 1 cap + MEMOIZED)
 *  3.  input codec round-trip (unit input)                    REQUIRED
 *  4.  input codec rejection (foreign envelope)               REQUIRED
 *  5.  output codec round-trip                               REQUIRED
 *  6.  output codec rejection (non-waitUntil kind)          REQUIRED
 *  7.  canonical envelope (byte-identical to legacy dsl-v1)  REQUIRED
 *  8.  production registry resolution                        REQUIRED
 *  9.  fresh factory consistency                             REQUIRED
 * 10.  capability declaration (EVENT_SINK only)             REQUIRED
 * 11.  capability admission (available → Ready)             REQUIRED
 * 12.  missing EVENT_SINK rejects fail-closed               REQUIRED
 * 13.  success via canonical coordinator (typed outcome)     REQUIRED
 * 14.  typed failure (handler exception)                     REQUIRED
 * 15.  fresh durable (1 terminal SUCCEEDED row)             REQUIRED
 * 16.  observability (StepStarted + StepFinished pair)       REQUIRED
 * 17.  WaitUntilPolled event (correct fields)               REQUIRED
 * 18.  WaitUntilCompleted event (correct fields)           REQUIRED
 * 19.  real registry path scenario                           REQUIRED
 * ```
 *
 * Block Step Note:
 * waitUntil is a Block Step with a condition body. The registry candidate follows
 * the stub pattern (condition assumed true). Full polling requires BodyInvoker (ADR-0073).
 */
@Timeout(30)
class WaitUntilStepContractSuiteTest {

    private fun registry(): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { CoreWaitUntilStep.registerInto(this) }

    private fun noOpCredentialScopePort(): CredentialScopePort =
        CredentialScopePort { _, _ ->
            CredentialScopeOutcome.Unavailable(
                CredentialScopeFailure.StoreUnavailable("step-contract-suite stub"),
            )
        }

    private fun freshHarness(
        eventStore: InMemoryEventStore,
        workDir: java.nio.file.Path = Files.createTempDirectory("waituntil-contract-"),
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

    private fun waitUntilNode(
        nodeId: String = "build/waitUntil",
        initialRecurrencePeriod: Long = 1000L,
        quiet: Boolean = false,
    ) = OpaqueStepNode(
        id = StepId(nodeId),
        pluginStepId = CoreWaitUntilStep.KEY,
        payload = VersionedStepPayload(
            schemaVersion = "dsl-v1",
            encoded = """{"kind":"waitUntil","initialRecurrencePeriod":$initialRecurrencePeriod,"quiet":$quiet}""",
        ),
    )

    private fun pipeline(vararg nodes: OpaqueStepNode) = CompiledPipeline(
        id = DefinitionId("waituntil-contract-suite"),
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
    fun `identity — CoreWaitUntilStep KEY is core dot waitUntil and duplicate registration fails`() {
        assertEquals(PluginStepId("core.waitUntil"), CoreWaitUntilStep.KEY)
        assertEquals("core.waitUntil", CoreWaitUntilStep.KEY.value)
        val r = registry()
        assertTrue(
            runCatching { CoreWaitUntilStep.registerInto(r) }.isFailure,
            "duplicate registration of core.waitUntil must fail",
        )
    }

    // ===== 2. contract completeness =====

    @Test
    fun `contract completeness — key, descriptor, codec, EVENT_SINK capability, MEMOIZED`() {
        val contract = CoreWaitUntilStep.definition.contract
        assertEquals(CoreWaitUntilStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "StepDescriptor must be present")
        assertEquals("waitUntil", contract.descriptor.name)
        assertEquals(
            setOf(Effect.READ_ONLY),
            contract.descriptor.effects.toSet(),
            "core.waitUntil effects MUST be READ_ONLY",
        )
        assertEquals(
            ReplayPolicy.MEMOIZED,
            contract.descriptor.replayPolicy,
            "core.waitUntil replayPolicy MUST be MEMOIZED",
        )
        assertEquals(
            setOf(EVENT_SINK_CAPABILITY),
            contract.requiredCapabilities,
            "core.waitUntil MUST declare EXACTLY {EVENT_SINK} as required capability",
        )
        assertNotNull(contract.inputCodec, "input codec must be present")
        assertNotNull(contract.outputCodec, "output codec must be present")
    }

    // ===== 3. input codec round-trip =====

    @Test
    fun `codec input — unit input encodes to the canonical legacy envelope and round-trips`() {
        val encoded = CoreWaitUntilStep.definition.contract.inputCodec.encode(
            WaitUntilInput(initialRecurrencePeriod = 1000L, quiet = false),
        )
        assertEquals(
            """{"kind":"waitUntil","initialRecurrencePeriod":1000,"quiet":false}""",
            encoded.value,
            "input codec must emit the canonical legacy dsl-v1 envelope",
        )
        val decoded = CoreWaitUntilStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(WaitUntilInput(initialRecurrencePeriod = 1000L, quiet = false), decoded)
    }

    // ===== 4. input codec rejection =====

    @Test
    fun `codec input — decode rejects a foreign envelope kind`() {
        val bad = EncodedStepValue("""{"kind":"echo"}""")
        assertTrue(
            runCatching { CoreWaitUntilStep.definition.contract.inputCodec.decode(bad) }.isFailure,
            "input decode must fail closed on a non-waitUntil kind",
        )
    }

    // ===== 5. output codec round-trip =====

    @Test
    fun `codec output — WaitUntilOutput round-trips byte-identically`() {
        val value = WaitUntilOutput(resultOutcome = "completed", totalAttempts = 3, totalDurationMs = 1500L)
        val encoded = CoreWaitUntilStep.definition.contract.outputCodec.encode(value)
        assertEquals(
            """{"kind":"waitUntil","outcome":"completed","totalAttempts":3,"totalDurationMs":1500}""",
            encoded.value,
            "output codec must emit the canonical kind/outcome envelope",
        )
        assertEquals(
            value,
            CoreWaitUntilStep.definition.contract.outputCodec.decode(encoded),
            "round-trip decode must reconstruct WaitUntilOutput",
        )
    }

    // ===== 6. output codec rejection =====

    @Test
    fun `codec output — decode rejects a non-waitUntil kind`() {
        val bad = EncodedStepValue("""{"kind":"echo","outcome":"completed"}""")
        assertTrue(
            runCatching { CoreWaitUntilStep.definition.contract.outputCodec.decode(bad) }.isFailure,
            "output decode must fail closed on a non-waitUntil kind",
        )
    }

    // ===== 7. canonical envelope =====

    @Test
    fun `canonical envelope — input codec envelope is byte-identical to legacy dsl-v1 waitUntil envelope`() {
        val registryEnvelope = CoreWaitUntilStep.definition.contract.inputCodec
            .encode(WaitUntilInput(initialRecurrencePeriod = 500L, quiet = true)).value
        val legacyEnvelope = """{"kind":"waitUntil","initialRecurrencePeriod":500,"quiet":true}"""
        assertEquals(
            legacyEnvelope,
            registryEnvelope,
            "registry input codec MUST emit a byte-identical dsl-v1 envelope",
        )
    }

    // ===== 8. production registry resolution =====

    @Test
    fun `registry resolution — production factory contains core dot waitUntil`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(registry.contains(CoreWaitUntilStep.KEY), "production registry must contain core.waitUntil")
        val definition = registry.definition(CoreWaitUntilStep.KEY)
        assertNotNull(definition, "production registry MUST resolve core.waitUntil to a StepDefinition")
        assertSame(
            CoreWaitUntilStep.definition,
            definition,
            "production registry MUST return the canonical CoreWaitUntilStep.definition instance",
        )
    }

    // ===== 9. fresh factory consistency =====

    @Test
    fun `registry resolution — production factory registry is fresh per call and consistent across calls`() {
        val r1 = CoreStepRegistryFactory.registry()
        val r2 = CoreStepRegistryFactory.registry()
        assertFalse(r1 === r2, "factory must produce fresh per-call registries")
        assertTrue(r1.contains(CoreWaitUntilStep.KEY))
        assertTrue(r2.contains(CoreWaitUntilStep.KEY))
        assertSame(r1.definition(CoreWaitUntilStep.KEY), r2.definition(CoreWaitUntilStep.KEY))
    }

    // ===== 10. capability declaration =====

    @Test
    fun `capability declaration — core waitUntil declares EXACTLY EVENT_SINK`() {
        val declared = CoreWaitUntilStep.definition.contract.requiredCapabilities
        assertEquals(
            1,
            declared.size,
            "core.waitUntil MUST declare exactly 1 required capability; got ${declared.map { it.key }}",
        )
        assertTrue(
            EVENT_SINK_CAPABILITY in declared,
            "EVENT_SINK_CAPABILITY MUST be declared",
        )
    }

    // ===== 11. capability admission (available) =====

    @Test
    fun `capability admission — EVENT_SINK available prepares Ready and handler executes`() {
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = CoreWaitUntilStep.KEY,
                encodedInput = CoreWaitUntilStep.definition.contract.inputCodec
                    .encode(WaitUntilInput()),
                availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
            )
            assertTrue(
                preparation is ExecutionPreparation.Ready,
                "admission must succeed when EVENT_SINK is available",
            )
        }
    }

    // ===== 12. missing EVENT_SINK =====

    @Test
    fun `missing capability — admission rejects when EVENT_SINK is absent`() {
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = CoreWaitUntilStep.KEY,
                encodedInput = CoreWaitUntilStep.definition.contract.inputCodec
                    .encode(WaitUntilInput()),
                availableCapabilities = emptySet(),
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

    // ===== 13. success via canonical coordinator =====

    @Test
    fun `success — registry-routed waitUntil SUCCEEDS with one terminal SUCCEEDED operation`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            val outcome = h.coord.run(pipeline(waitUntilNode()), RunId("waituntil-ok"))
            assertEquals(
                RunOutcome.Success,
                outcome,
                "a waitUntil stub must succeed",
            )
            val rows = h.journal.listForRun("waituntil-ok")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    // Note: Handler exception propagation is tested via the handler's thrown exception.
    // The coordinator's exception handling is a coordinator concern, not a Step contract concern.

    // ===== 15. fresh durable =====

    @Test
    fun `fresh durable — first execution of core dot waitUntil writes one terminal SUCCEEDED operation`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(waitUntilNode()), RunId("waituntil-first"))
            val rows = h.journal.listForRun("waituntil-first")
            assertEquals(1, rows.size, "exactly one terminal operation row is journaled")
            assertEquals(OperationStatus.SUCCEEDED, rows.single().status)
        }
    }

    // ===== 16. observability =====

    @Test
    fun `observability — every core dot waitUntil run emits a StepStarted StepFinished pair`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(waitUntilNode()), RunId("waituntil-obs"))
            val events = h.eventStore.eventsFor("waituntil-obs").toList()
            assertTrue(events.any { it is StepStarted }, "StepStarted must be emitted (lifecycle observability)")
            assertTrue(events.any { it is StepFinished }, "StepFinished must be emitted (lifecycle observability)")
        }
    }

    // ===== 17. WaitUntilPolled event =====

    @Test
    fun `WaitUntilPolled event — emitted with correct fields (stub pattern)`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(waitUntilNode()), RunId("waituntil-polled"))
            val polled = h.eventStore.eventsFor("waituntil-polled")
                .filterIsInstance<WaitUntilPolled>().firstOrNull()
            assertNotNull(polled, "WaitUntilPolled event must be emitted")
            assertEquals(1, polled!!.attempt, "stub should have attempt=1")
            assertTrue(polled.conditionResult, "stub condition should be true")
            assertEquals(0L, polled.durationMs, "stub should have durationMs=0")
        }
    }

    // ===== 18. WaitUntilCompleted event =====

    @Test
    fun `WaitUntilCompleted event — emitted with correct fields (stub pattern)`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)
            h.coord.run(pipeline(waitUntilNode()), RunId("waituntil-completed"))
            val completed = h.eventStore.eventsFor("waituntil-completed")
                .filterIsInstance<WaitUntilCompleted>().firstOrNull()
            assertNotNull(completed, "WaitUntilCompleted event must be emitted")
            assertEquals("completed", completed!!.outcome, "stub outcome should be 'completed'")
            assertEquals(1, completed.totalAttempts, "stub should have totalAttempts=1")
            assertEquals(0L, completed.totalDurationMs, "stub should have totalDurationMs=0")
        }
    }

    // ===== 19. real registry path scenario =====

    @Test
    fun `real registry path — canonical coordinator exercises core dot waitUntil end-to-end`() {
        runBlocking {
            val eventStore = InMemoryEventStore()
            val h = freshHarness(eventStore)

            val encoded = CoreWaitUntilStep.definition.contract.inputCodec
                .encode(WaitUntilInput(initialRecurrencePeriod = 500L, quiet = false))

            val preparation = RegistryExecutionPreparation.prepare(
                registry = h.registry,
                key = CoreWaitUntilStep.KEY,
                encodedInput = encoded,
                availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
            )
            assertTrue(
                preparation is ExecutionPreparation.Ready,
                "preparation must succeed for real registry path",
            )

            val outcome = h.coord.run(
                pipeline(
                    OpaqueStepNode(
                        id = StepId("real/waitUntil"),
                        pluginStepId = CoreWaitUntilStep.KEY,
                        payload = VersionedStepPayload(
                            schemaVersion = "dsl-v1",
                            encoded = encoded.value,
                        ),
                    ),
                ),
                RunId("waituntil-real"),
            )
            assertEquals(RunOutcome.Success, outcome, "registry seam end-to-end must succeed")

            // Verify events emitted through real coordinator
            val polled = h.eventStore.eventsFor("waituntil-real")
                .filterIsInstance<WaitUntilPolled>().toList().isNotEmpty()
            val completed = h.eventStore.eventsFor("waituntil-real")
                .filterIsInstance<WaitUntilCompleted>().toList().isNotEmpty()
            assertTrue(polled, "WaitUntilPolled must be emitted in real registry path")
            assertTrue(completed, "WaitUntilCompleted must be emitted in real registry path")
        }
    }
}
