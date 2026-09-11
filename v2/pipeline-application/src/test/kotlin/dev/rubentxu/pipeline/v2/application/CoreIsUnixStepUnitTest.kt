package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.CanonicalIsUnixNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.UnixDetected
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
import java.util.concurrent.TimeUnit

/**
 * S2-A5 / G1 candidate proof for `core.isUnix`. Registration only: legacy membership wins
 * routing until the cutover gate, counters stay 8/8/8. The candidate classifies with PATH_B
 * verbatim (G0 receipt) and marks TYPED_RUNTIME_OUTPUT as CANDIDATE_ARCHITECTURAL_DELTA
 * (NOT YET APPROVED); the canonical-policy decision belongs to G2.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CoreIsUnixStepUnitTest {

    // ------------------------------------------------------------------
    // PATH_B classifier matrix (G0 characterization, verbatim semantics)
    // ------------------------------------------------------------------

    @Test
    fun `classifier matches PATH_B verbatim - positive rows`() {
        assertTrue(CoreIsUnixStep.classify("Linux"))          // lowercase contains "linux"
        assertTrue(CoreIsUnixStep.classify("linux"))
        assertTrue(CoreIsUnixStep.classify("Mac OS X"))       // substring "mac" (Path B divergence vs PATH_A)
        assertTrue(CoreIsUnixStep.classify("Darwin"))
        assertTrue(CoreIsUnixStep.classify("FreeBSD"))
    }

    @Test
    fun `classifier matches PATH_B verbatim - negative rows`() {
        assertFalse(CoreIsUnixStep.classify(""))               // PATH_A placeholder-true diverges
        assertFalse(CoreIsUnixStep.classify("Windows 11"))
        assertFalse(CoreIsUnixStep.classify("SunOS"))          // PATH_A true diverges
        assertFalse(CoreIsUnixStep.classify("AIX"))            // PATH_A true diverges
        assertFalse(CoreIsUnixStep.classify("OpenBSD"))        // PATH_A true diverges
    }

    // ------------------------------------------------------------------
    // Codecs
    // ------------------------------------------------------------------

    @Test
    fun `input codec preserves the legacy empty-object durable envelope`() {
        val encoded = CoreIsUnixStep.definition.contract.inputCodec.encode(IsUnixInput)
        assertEquals("{}", encoded.value)
        assertEquals(IsUnixInput, CoreIsUnixStep.definition.contract.inputCodec.decode(encoded))
    }

    @Test
    fun `output codec round-trips the typed boolean and carries Success outcome`() {
        val encoded = CoreIsUnixStep.definition.contract.outputCodec.encode(IsUnixOutput(isUnix = true))
        assertEquals("""{"kind":"isUnix","isUnix":true}""", encoded.value)
        assertEquals(IsUnixOutput(true), CoreIsUnixStep.definition.contract.outputCodec.decode(encoded))
        assertEquals(IsUnixOutput(false), CoreIsUnixStep.definition.contract.outputCodec.decode(
            CoreIsUnixStep.definition.contract.outputCodec.encode(IsUnixOutput(false)),
        ))
        val typed: TypedStepOutput = IsUnixOutput(true)
        assertEquals(StepOutcome.Success, typed.outcome)
    }

    @Test
    fun `output codec rejects a foreign envelope at decode`() {
        val invalid = assertThrowsOnDecode(EncodedStepValue("""{"kind":"echo","isUnix":true}"""))
        assertTrue(invalid.message!!.contains("kind must be 'isUnix'"))
    }

    private fun assertThrowsOnDecode(encoded: EncodedStepValue): IllegalArgumentException =
        try {
            CoreIsUnixStep.definition.contract.outputCodec.decode(encoded)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            e
        }

    // ------------------------------------------------------------------
    // Contract / descriptor
    // ------------------------------------------------------------------

    @Test
    fun `descriptor preserves legacy read-only memoized contract`() {
        val descriptor = CoreIsUnixStep.definition.contract.descriptor
        assertEquals("core.isUnix", descriptor.stepId)
        assertEquals("isUnix", descriptor.name)
        assertEquals(listOf(Effect.READ_ONLY), descriptor.effects)
        assertEquals(ReplayPolicy.MEMOIZED, descriptor.replayPolicy)
        assertEquals(RecoveryPolicy.None, descriptor.recoveryPolicy)
    }

    @Test
    fun `candidate declares exactly platform-identity and event-sink capabilities`() {
        assertEquals(
            setOf(PLATFORM_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
            CoreIsUnixStep.definition.contract.requiredCapabilities,
        )
    }

    @Test
    fun `handler never imports process or system-property authority - observation comes from capability`() {
        // The handler reads osName ONLY through PlatformIdentity. This test pins the
        // separation by driving the handler directly with a synthetic observation that
        // differs from the host JVM and asserting the classification follows the CAPABILITY.
        val registry = InMemoryStepRegistry().also { CoreIsUnixStep.registerInto(it) }
        val isUnixDefinition = CoreIsUnixStep.definition
        val sink = InMemoryEventStore()
        val synthetic = object : dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess {
            override fun available(): Set<StepCapability> =
                setOf(PLATFORM_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY)

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> get(key: StepCapability): T = when (key) {
                PLATFORM_IDENTITY_CAPABILITY -> PlatformIdentity(osName = "SunOS") as T
                EVENT_SINK_CAPABILITY -> sink as T
                else -> throw IllegalArgumentException("unexpected capability $key")
            }
        }
        val ctx = dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext(
            runId = dev.rubentxu.pipeline.v2.domain.RunId("g1-synthetic"),
            stepIndex = 0,
            capabilities = synthetic,
        )
        val output = runBlocking { isUnixDefinition.handler.execute(IsUnixInput, ctx) }
        // Host JVM is Linux; a handler leaking System.getProperty would classify true.
        assertFalse(output.isUnix, "classification must follow the PlatformIdentity capability, not the host JVM")
        assertEquals(1, sink.eventsFor("g1-synthetic").toList().filterIsInstance<UnixDetected>().size)
        val event = sink.eventsFor("g1-synthetic").toList().filterIsInstance<UnixDetected>().single()
        assertEquals("SunOS", event.osName)
        assertFalse(event.isUnix)
        assertEquals(CoreIsUnixStep.sha256("SunOS"), event.sha256)
    }

    // ------------------------------------------------------------------
    // Registry family + counters invariants (G1: registration only)
    // ------------------------------------------------------------------

    @Test
    fun `factory registry resolves the candidate and legacy family still wins`() {
        val registry = CoreStepRegistryFactory.registry()
        assertSame(CoreIsUnixStep.definition, registry.definition(CoreIsUnixStep.KEY))
        assertTrue("core.isUnix" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        assertEquals(
            StructuralStepFamily.LegacyCore,
            StructuralFamilyResolver.classify(CoreIsUnixStep.KEY, registry),
            "G1: legacy membership wins; candidate is registered but not production authority",
        )
    }

    @Test
    fun `counters remain 8-8-8 and legacy dispatcher remains present`() {
        assertEquals(8, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size)
        assertTrue("core.isUnix" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        // Legacy execution path still exists untouched (G5 removes it, not G1).
        assertNotNull(CanonicalIsUnixNodeDispatcher())
    }

    @Test
    fun `duplicate registration fails closed`() {
        val registry = InMemoryStepRegistry().also { CoreIsUnixStep.registerInto(it) }
        try {
            CoreIsUnixStep.registerInto(registry)
            throw AssertionError("expected duplicate-key rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("core.isUnix"))
        }
    }

    // ------------------------------------------------------------------
    // Real seam: preparation (fail-closed admission) -> boundary -> handler
    // ------------------------------------------------------------------

    @Test
    fun `real seam - missing PLATFORM_IDENTITY rejects admission with handler invocation 0 and no event`() {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreIsUnixStep.KEY,
            encodedInput = EncodedStepValue("{}"),
            availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
        assertEquals(0, capturedUnixDetected("g1-isunix-missing-platform").size)
    }

    @Test
    fun `real seam - missing EVENT_SINK rejects admission with handler invocation 0 and no event`() {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreIsUnixStep.KEY,
            encodedInput = EncodedStepValue("{}"),
            availableCapabilities = setOf(PLATFORM_IDENTITY_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
        assertEquals(0, capturedUnixDetected("g1-isunix-missing-sink").size)
    }

    @Test
    fun `real seam - full capabilities execute through preparation and boundary with typed output and exactly one event`() = runBlocking {
        val prepared = prepareReal()
        val result = RegistryExecutionBoundary.coexecute(prepared, context("real-seam"))
        assertEquals(StepOutcome.Success, result.outcome)
        assertNotNull(result.encodedOutput)
        assertEquals(
            IsUnixOutput(isUnix = CoreIsUnixStep.classify(System.getProperty("os.name", ""))),
            CoreIsUnixStep.definition.contract.outputCodec.decode(result.encodedOutput!!),
        )
        val events = capturedUnixDetected("g1-isunix-real-seam")
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(event.isUnix, CoreIsUnixStep.classify(event.osName))
        assertEquals(CoreIsUnixStep.sha256(event.osName), event.sha256)
    }

    private fun prepareReal(): PreparedRegistryExecution {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreIsUnixStep.KEY,
            encodedInput = EncodedStepValue("{}"),
            availableCapabilities = setOf(PLATFORM_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
        )
        val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
        return assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)
    }

    private fun capturedUnixDetected(runId: String): List<UnixDetected> =
        sharedEventStore.eventsFor(runId).toList().filterIsInstance<UnixDetected>()

    private fun context(label: String): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = OpId("g1-isunix-$label", 0, 0),
        runId = "g1-isunix-$label",
        stageName = "candidate",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = ShOptions.EMPTY,
        controlDirRoot = Files.createTempDirectory("g1-isunix-$label-"),
        eventSink = sharedEventStore,
    )

    companion object {
        private val sharedEventStore = InMemoryEventStore()
    }
}
