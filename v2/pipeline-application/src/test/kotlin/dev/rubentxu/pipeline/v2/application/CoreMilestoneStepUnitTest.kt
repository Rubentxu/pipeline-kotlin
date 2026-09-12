package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.MilestoneAborted
import dev.rubentxu.pipeline.v2.events.MilestoneReached
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * G2: Corpus migration for `core.milestone` — drives CoreMilestoneStep through the
 * registry path using direct handler invocation with a mock event sink.
 *
 * This test proves:
 * 1. The registry path for milestone is functional (handler executes correctly).
 * 2. Ordinal monotonicity (strictly increasing → MilestoneReached + Success;
 *    non-increasing → MilestoneAborted + Unstable).
 * 3. Events are emitted correctly via EVENT_SINK_CAPABILITY.
 * 4. Typed MilestoneOutput carries the correct status.
 * 5. The registry contains core.milestone via CoreStepRegistryFactory.
 *
 * ## S2-A9 Spike: State seam
 *
 * State management uses the new MilestoneStateStore pattern (S2-A9 spike):
 * - Each test creates its own MilestoneStateStore (test isolation)
 * - The store is wrapped in MilestoneOperationsAdapter and provided via capability
 * - The handler delegates state management to the capability, NOT holding mutable state
 * - No resetState() call needed — each test gets its own fresh store
 */
@Timeout(30)
class CoreMilestoneStepUnitTest {

    private lateinit var eventStore: InMemoryEventStore
    private lateinit var milestoneStore: MilestoneStateStore
    private lateinit var handlerContext: StepHandlerContext

    @BeforeEach
    fun setup() {
        // S2-A9 spike: create a fresh MilestoneStateStore for this test.
        // Each test gets its own store — no resetState() call needed.
        milestoneStore = MilestoneStateStore()
        eventStore = InMemoryEventStore()
        val capabilities = MapStepCapabilityAccess(
            mapOf(
                EVENT_SINK_CAPABILITY to eventStore,
                MILESTONE_OPERATIONS_CAPABILITY to MilestoneOperationsAdapter(milestoneStore),
            ),
        )
        handlerContext = StepHandlerContext(
            runId = RunId("test-run"),
            stepIndex = 0,
            capabilities = capabilities,
        )
    }

    @AfterEach
    fun teardown() {
        // S2-A9 spike: no resetState() call needed.
        // Each test has its own MilestoneStateStore that is garbage-collected.
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Registry resolution
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `registry resolution — CoreStepRegistryFactory contains core dot milestone`() {
        val r = CoreStepRegistryFactory.registry()
        assertTrue(
            r.contains(CoreMilestoneStep.KEY),
            "core.milestone must be in the production registry",
        )
        assertEquals(
            CoreMilestoneStep.KEY,
            r.definition(CoreMilestoneStep.KEY)?.contract?.key,
        )
    }

    @Test
    fun `registry resolution — definition is CoreMilestoneStep definition with correct key`() {
        val def = CoreStepRegistryFactory.registry().definition(CoreMilestoneStep.KEY)
        assertTrue(def != null, "definition must not be null")
        assertEquals(CoreMilestoneStep.KEY, def!!.contract.key)
        assertEquals("milestone", def.contract.descriptor.name)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Capability declaration
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `capability declaration — requires EVENT_SINK_CAPABILITY and MILESTONE_OPERATIONS_CAPABILITY`() {
        val contract = CoreMilestoneStep.definition.contract
        assertEquals(
            setOf(EVENT_SINK_CAPABILITY, MILESTONE_OPERATIONS_CAPABILITY),
            contract.requiredCapabilities,
            "core.milestone requires EVENT_SINK_CAPABILITY and MILESTONE_OPERATIONS_CAPABILITY",
        )
    }

    @Test
    fun `capability declaration — descriptor has READ_ONLY effects and MEMOIZED replay`() {
        val descriptor = CoreMilestoneStep.definition.contract.descriptor
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.Effect.READ_ONLY,
            descriptor.effects.single(),
            "effects must be READ_ONLY",
        )
        assertEquals(
            ReplayPolicy.MEMOIZED,
            descriptor.replayPolicy,
            "replayPolicy must be MEMOIZED",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Input codec
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `input codec — round-trip preserves ordinal and label`() {
        val input = MilestoneInput(ordinal = 1, label = "first")
        val encoded = CoreMilestoneStep.definition.contract.inputCodec.encode(input)
        val decoded = CoreMilestoneStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(input, decoded, "input codec round-trip must preserve ordinal and label")
    }

    @Test
    fun `input codec — encode produces canonical dsl-v1 envelope with kind milestone`() {
        val encoded = CoreMilestoneStep.definition.contract.inputCodec.encode(
            MilestoneInput(ordinal = 5, label = "deploy"),
        )
        assertTrue(
            encoded.value.contains("\"kind\":\"milestone\""),
            "input codec must emit kind=milestone: ${encoded.value}",
        )
        assertTrue(
            encoded.value.contains("\"ordinal\":5"),
            "input codec must emit ordinal=5: ${encoded.value}",
        )
        assertTrue(
            encoded.value.contains("\"label\":\"deploy\""),
            "input codec must emit label=deploy: ${encoded.value}",
        )
    }

    @Test
    fun `input codec — encode omits label when null (legacy behavior)`() {
        val encoded = CoreMilestoneStep.definition.contract.inputCodec.encode(
            MilestoneInput(ordinal = 1, label = null),
        )
        val decoded = CoreMilestoneStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(null, decoded.label, "null label must be preserved in round-trip")
        assertTrue(
            !encoded.value.contains("\"label\""),
            "null label must be omitted from encoded payload: ${encoded.value}",
        )
    }

    @Test
    fun `input codec — decode rejects non-milestone kind`() {
        val foreign = EncodedStepValue("""{"kind":"not-milestone","ordinal":1}""")
        assertTrue(
            runCatching {
                CoreMilestoneStep.definition.contract.inputCodec.decode(foreign)
            }.isFailure,
            "decode must fail on non-milestone kind",
        )
    }

    @Test
    fun `input codec — decode rejects non-positive ordinal`() {
        val badOrdinal = EncodedStepValue("""{"kind":"milestone","ordinal":0}""")
        assertTrue(
            runCatching {
                CoreMilestoneStep.definition.contract.inputCodec.decode(badOrdinal)
            }.isFailure,
            "decode must fail on ordinal=0",
        )

        val negativeOrdinal = EncodedStepValue("""{"kind":"milestone","ordinal":-1}""")
        assertTrue(
            runCatching {
                CoreMilestoneStep.definition.contract.inputCodec.decode(negativeOrdinal)
            }.isFailure,
            "decode must fail on negative ordinal",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Output codec
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `output codec — round-trip preserves MilestoneOutput with Reached status`() {
        val output = MilestoneOutput(
            ordinal = 1,
            label = "first",
            status = MilestoneStatus.Reached,
        )
        val encoded = CoreMilestoneStep.definition.contract.outputCodec.encode(output)
        val decoded = CoreMilestoneStep.definition.contract.outputCodec.decode(encoded)
        assertEquals(output, decoded, "output codec round-trip must preserve Reached output")
    }

    @Test
    fun `output codec — round-trip preserves MilestoneOutput with Aborted status`() {
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
    // Handler — ordinal monotonicity (G2 canonical milestone characterization)
    // S2-A9 spike: state delegated to MilestoneOperations capability
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `handler — first milestone with ordinal 1 emits MilestoneReached and returns Success`() =
        runBlocking {
            val input = MilestoneInput(ordinal = 1, label = "first")
            val output = CoreMilestoneStep.definition.handler.execute(input, handlerContext)

            assertInstanceOf(MilestoneStatus.Reached::class.java, output.status)
            assertEquals(1, output.ordinal)
            assertEquals("first", output.label)
            assertEquals(
                dev.rubentxu.pipeline.v2.domain.StepOutcome.Success,
                output.outcome,
            )

            val events = eventStore.eventsFor("test-run").toList()
            val reached = events.filterIsInstance<MilestoneReached>()
            assertEquals(1, reached.size, "exactly one MilestoneReached must be emitted")
            assertEquals(1, reached.single().ordinal)
            assertEquals("first", reached.single().label)
        }

    @Test
    fun `handler — increasing ordinal emits MilestoneReached for each step`() = runBlocking {
        // First milestone: ordinal 1 → Reached
        val out1 = CoreMilestoneStep.definition.handler.execute(
            MilestoneInput(ordinal = 1, label = "one"),
            handlerContext,
        )
        assertInstanceOf(MilestoneStatus.Reached::class.java, out1.status)

        // Second milestone: ordinal 2 > 1 → Reached
        val out2 = CoreMilestoneStep.definition.handler.execute(
            MilestoneInput(ordinal = 2, label = "two"),
            handlerContext,
        )
        assertInstanceOf(MilestoneStatus.Reached::class.java, out2.status)

        val events = eventStore.eventsFor("test-run").toList()
        val reached = events.filterIsInstance<MilestoneReached>()
        assertEquals(2, reached.size, "exactly two MilestoneReached events must be emitted")
        assertEquals(1, reached[0].ordinal)
        assertEquals(2, reached[1].ordinal)
    }

    @Test
    fun `handler — non-increasing ordinal emits MilestoneAborted and returns Unstable`() =
        runBlocking {
            // First: ordinal 2 → Reached
            CoreMilestoneStep.definition.handler.execute(
                MilestoneInput(ordinal = 2, label = "second"),
                handlerContext,
            )

            // Second: ordinal 1 ≤ 2 → Aborted
            val output = CoreMilestoneStep.definition.handler.execute(
                MilestoneInput(ordinal = 1, label = "first"),
                handlerContext,
            )
            assertInstanceOf(MilestoneStatus.Aborted::class.java, output.status)
            assertEquals(
                dev.rubentxu.pipeline.v2.domain.StepOutcome.Unstable,
                output.outcome,
            )

            val events = eventStore.eventsFor("test-run").toList()
            val reached = events.filterIsInstance<MilestoneReached>()
            val aborted = events.filterIsInstance<MilestoneAborted>()
            assertEquals(1, reached.size)
            assertEquals(1, aborted.size)

            assertEquals(1, aborted.single().ordinal)
            assertTrue(
                aborted.single().reason.contains("ordinal-already-reached"),
                "reason must mention ordinal-already-reached",
            )
        }

    @Test
    fun `handler — equal ordinal emits MilestoneAborted`() = runBlocking {
        // Ordinal 5 → Reached
        CoreMilestoneStep.definition.handler.execute(
            MilestoneInput(ordinal = 5, label = "five"),
            handlerContext,
        )

        // Same ordinal 5 → Aborted
        val output = CoreMilestoneStep.definition.handler.execute(
            MilestoneInput(ordinal = 5, label = "five-again"),
            handlerContext,
        )
        assertInstanceOf(MilestoneStatus.Aborted::class.java, output.status)
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.StepOutcome.Unstable,
            output.outcome,
        )

        val events = eventStore.eventsFor("test-run").toList()
        assertEquals(1, events.filterIsInstance<MilestoneAborted>().size)
    }

    @Test
    fun `handler — null label emits MilestoneReached with null label`() = runBlocking {
        val output = CoreMilestoneStep.definition.handler.execute(
            MilestoneInput(ordinal = 1, label = null),
            handlerContext,
        )
        assertInstanceOf(MilestoneStatus.Reached::class.java, output.status)
        assertEquals(null, output.label)

        val reached = eventStore.eventsFor("test-run").toList().filterIsInstance<MilestoneReached>()
        assertEquals(null, reached.single().label)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Missing capability
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `missing capability — execution fails when EVENT_SINK is absent`() = runBlocking {
        // Create a context without EVENT_SINK_CAPABILITY
        val noSinkContext = StepHandlerContext(
            runId = RunId("test-run"),
            stepIndex = 0,
            capabilities = MapStepCapabilityAccess(
                mapOf(MILESTONE_OPERATIONS_CAPABILITY to MilestoneOperationsAdapter(milestoneStore)),
            ),
        )
        val input = MilestoneInput(ordinal = 1, label = "orphan")
        val exception = runCatching {
            CoreMilestoneStep.definition.handler.execute(input, noSinkContext)
        }.exceptionOrNull()
        assertTrue(
            exception != null,
            "handler must throw when EVENT_SINK capability is absent",
        )
    }

    @Test
    fun `missing capability — execution fails when MILESTONE_OPERATIONS is absent`() = runBlocking {
        // Create a context without MILESTONE_OPERATIONS_CAPABILITY
        val noOpsContext = StepHandlerContext(
            runId = RunId("test-run"),
            stepIndex = 0,
            capabilities = MapStepCapabilityAccess(
                mapOf(EVENT_SINK_CAPABILITY to eventStore),
            ),
        )
        val input = MilestoneInput(ordinal = 1, label = "orphan")
        val exception = runCatching {
            CoreMilestoneStep.definition.handler.execute(input, noOpsContext)
        }.exceptionOrNull()
        assertTrue(
            exception != null,
            "handler must throw when MILESTONE_OPERATIONS capability is absent",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Contract completeness
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `contract completeness — key, descriptor, codecs, capabilities all present`() {
        val contract = CoreMilestoneStep.definition.contract
        assertEquals(CoreMilestoneStep.KEY, contract.key)
        assertTrue(contract.descriptor.stepId.isNotBlank())
        assertTrue(contract.inputCodec != null)
        assertTrue(contract.outputCodec != null)
        assertTrue(contract.requiredCapabilities.isNotEmpty())
    }
}

/**
 * Minimal [dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess] for testing:
 * provides exactly the capabilities declared in the map.
 */
private class MapStepCapabilityAccess(
    private val capabilities: Map<dev.rubentxu.pipeline.v2.domain.step.StepCapability, Any>,
) : dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess {
    override fun available(): Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> =
        capabilities.keys

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(key: dev.rubentxu.pipeline.v2.domain.step.StepCapability): T =
        capabilities[key] as? T
            ?: throw IllegalArgumentException("capability not available: $key")
}
