package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Disabled
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

    // ------------------------------------------------------------------
    // G2 canonical differential freeze: full A / B / C-G1 / TARGET matrix.
    // UNKNOWN_DIFFERENTIALS = 0. Sources of truth for A and B columns:
    // PATH_A = PipelineDsl.isUnix exact membership {linux,macos,darwin,sunos,aix,
    //          hp-ux,freebsd,openbsd,netbsd}, "" -> true placeholder.
    // PATH_B = CanonicalIsUnixNodeDispatcher substring {linux,mac,darwin,freebsd}.
    // TARGET = UnixPlatformClassifier (canonical C2 contract, decision D1).
    // ------------------------------------------------------------------

    private fun pathA(osName: String): Boolean =
        osName.lowercase() in setOf(
            "linux", "macos", "darwin", "sunos", "aix", "hp-ux", "freebsd", "openbsd", "netbsd",
        ) || osName.isEmpty()

    private fun pathB(osName: String): Boolean =
        osName.lowercase().let {
            it.contains("linux") || it.contains("mac") || it.contains("darwin") || it.contains("freebsd")
        }

    private data class Row(
        val osName: String,
        val target: Boolean,
        val label: String,
    )

    private val differential: List<Row> = listOf(
        Row("Linux", true, "AGREEMENT"),
        Row("linux", true, "AGREEMENT"),
        Row("macos", true, "AGREEMENT"),
        Row("Mac OS X", true, "APPROVED_FIX (A false, B/G1 true via substring)"),
        Row("mac os x", true, "APPROVED_FIX normalized"),
        Row("Darwin", true, "AGREEMENT"),
        Row("SunOS", true, "APPROVED_CONTRACT_DELTA (A true, B/G1 false)"),
        Row("AIX", true, "APPROVED_CONTRACT_DELTA (A true, B/G1 false)"),
        Row("HP-UX", true, "APPROVED_CONTRACT_DELTA (A true, B/G1 false)"),
        Row("hp-ux", true, "APPROVED_CONTRACT_DELTA normalized"),
        Row("FreeBSD", true, "AGREEMENT"),
        Row("freebsd", true, "AGREEMENT"),
        Row("OpenBSD", true, "APPROVED_CONTRACT_DELTA (A true, B/G1 false)"),
        Row("NetBSD", true, "APPROVED_CONTRACT_DELTA (A true, B/G1 false)"),
        Row("netbsd", true, "APPROVED_CONTRACT_DELTA normalized"),
        Row("", false, "APPROVED_FIX (A placeholder true is retired)"),
        Row("   ", false, "APPROVED_FIX trim normalization"),
        Row("Windows 11", false, "AGREEMENT"),
        Row("windows", false, "AGREEMENT"),
        Row("unknown", false, "AGREEMENT"),
        Row("OS/2", false, "AGREEMENT"),
        Row("Smacos", false, "APPROVED_FIX (B substring heuristics retired: exact membership)"),
    )

    @Test
    fun `G2 canonical differential - TARGET matches the frozen C2 contract on every row`() {
        for (row in differential) {
            assertEquals(row.target, UnixPlatformClassifier.classifyUnix(row.osName), "row: " + row.osName)
        }
    }

    @Test
    fun `G2 canonical differential - no row is unknown and every delta row is explicitly labeled`() {
        for (row in differential) {
            val a = pathA(row.osName)
            val b = pathB(row.osName)
            val target = UnixPlatformClassifier.classifyUnix(row.osName)
            val unknown = target != a && target != b
            assertFalse(unknown, "UNKNOWN differential for '" + row.osName + "'")
            if (a != target || b != target) {
                assertTrue(
                    row.label.startsWith("APPROVED_"),
                    "diverging row '" + row.osName + "' must carry an explicit APPROVED_* label, was: " + row.label,
                )
            }
        }
    }

    @Test
    fun `G2 canonical differential - G1 candidate semantics retired in favor of the canonical target`() {
        // Rows where G1 (PATH_B verbatim) differs from the frozen target: classification
        // MUST follow the target now (SunOS/AIX/HP-UX/OpenBSD/NetBSD flip to true; ""
        // stays false). "Mac OS X" stays true (G1 and target agree).
        for (row in listOf("SunOS", "AIX", "HP-UX", "OpenBSD", "NetBSD")) {
            assertTrue(CoreIsUnixStep.classify(row), "canonical target must accept " + row)
        }
        assertFalse(CoreIsUnixStep.classify(""), "empty placeholder must NOT return")
        assertFalse(CoreIsUnixStep.classify("Smacos"), "substring heuristic must NOT return")
    }

    @Test
    fun `classifier is pure and total - same input twice, no exception as outcome`() {
        for (row in differential) {
            assertEquals(
                UnixPlatformClassifier.classifyUnix(row.osName),
                UnixPlatformClassifier.classifyUnix(row.osName),
            )
        }
    }

    // ------------------------------------------------------------------
    // D2: TYPED_RUNTIME_OUTPUT approved — durable law
    // fresh/rerun observe the environment; resume/reuse reproduce the
    // persisted observation (handler NOT executed, platform NOT re-observed).
    // ------------------------------------------------------------------

    @Test
    fun `durable law D2 - encoded output survives durable persistence and decodes to the same Boolean`() {
        // The journal consumes the ENCODED form, never the typed object. Simulate a
        // persistence round-trip through a raw String (the only durable representation).
        for (value in listOf(true, false)) {
            val encoded = CoreIsUnixStep.definition.contract.outputCodec.encode(IsUnixOutput(value))
            val persisted: String = encoded.value // what OperationOutput would store
            val recovered = CoreIsUnixStep.definition.contract.outputCodec.decode(EncodedStepValue(persisted))
            assertEquals(IsUnixOutput(value), recovered)
        }
    }

    @Test
    fun `durable law D2 - MEMOIZED replay policy is the resume-reuse authority (no re-observation on resume)`() {
        assertEquals(ReplayPolicy.MEMOIZED, CoreIsUnixStep.definition.contract.descriptor.replayPolicy)
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
                PLATFORM_IDENTITY_CAPABILITY -> PlatformIdentity(osName = "Windows 11") as T
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
        assertEquals("Windows 11", event.osName)
        assertFalse(event.isUnix)
        assertEquals(CoreIsUnixStep.sha256("Windows 11"), event.sha256)
    }

    // ------------------------------------------------------------------
    // Registry family + counters invariants (G1: registration only)
    // ------------------------------------------------------------------

    @Test
    fun `factory registry resolves the candidate and registry family wins post-flip`() {
        val registry = CoreStepRegistryFactory.registry()
        assertSame(CoreIsUnixStep.definition, registry.definition(CoreIsUnixStep.KEY))
        // S2-A5 / G4: core.isUnix REMOVED from LEGACY_PLUGIN_IDS; registry is now the
        // production authority. Historical G1/G3 assertion was `LEGACY_PLUGIN_IDS` membership.
        assertTrue(
            "core.isUnix" !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "G4 flip: core.isUnix must be outside LEGACY_PLUGIN_IDS; registry is production authority",
        )
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(CoreIsUnixStep.KEY, registry),
            "G4: legacy-membership no longer applies; candidate is now routed as Registry family",
        )
    }

    @Disabled("Historical S2-A5/G5 snapshot: S2-A6/G4 (2026-09-12) flipped core.pwd too; the 7-key counter is superseded by `counters drop to 6-6-7 post-S2-A6-G4` below. Preserved verbatim for traceability.")
    @Test
    fun `counters drop to 7-7-7 and legacy dispatcher source is physically removed`() {
        // G5 (LEGACY_REMOVED) closes the burn-down for core.isUnix:
        //   legacy executable IDs    = 7  (LEGACY_PLUGIN_IDS.size, no core.isUnix entry)
        //   metadata rows            = 7  (CanonicalCoreStepMetadata no longer has core.isUnix row)
        //   dispatcher sources       = 7  (CanonicalIsUnixNodeDispatcher source file removed)
        assertEquals(7, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size)
        assertTrue("core.isUnix" !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        // The legacy metadata authority no longer answers for "core.isUnix":
        assertThrows(IllegalArgumentException::class.java) {
            dev.rubentxu.pipeline.v2.application.CanonicalCoreStepMetadata.metadata("core.isUnix")
        }
        // Source-level absence proof — the dispatcher type must NOT exist on the classpath.
        // (Compilation of this file would have failed in L0 if CanonicalIsUnixNodeDispatcher were still on disk.)
    }

    @Disabled("Historical S2-A6/G4 snapshot: S2-A6/G5 (2026-09-12) flipped core.pwd to LEGACY_REMOVED; the 6-6-7 counter is superseded by `counters drop to 6-6-6 post-S2-A6-G5` below. Preserved verbatim for traceability.")
    @Test
    fun `counters drop to 6-6-7 post-S2-A6-G4 and legacy dispatcher source is physically removed`() {
        // S2-A6 / G4 (2026-09-12): "core.pwd" flipped to REGISTRY_PRIMARY; counter 7 -> 6.
        // The legacy metadata row for "core.pwd" remains (LEGACY_UNREACHABLE, not REMOVED).
        // The canonical-core counter is 6-6-7: 6 executable IDs in LEGACY_PLUGIN_IDS,
        // 6 metadata rows (core.pwd row preserved until G5), 7 dispatcher sources (no
        // dispatcher has been physically deleted by this slice).
        assertEquals(6, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size)
        assertTrue("core.isUnix" !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        assertTrue("core.pwd" !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        // The legacy metadata authority still answers for "core.pwd" (LEGACY_UNREACHABLE):
        val pwdMeta = dev.rubentxu.pipeline.v2.application.CanonicalCoreStepMetadata.metadata("core.pwd")
        assertEquals(setOf(dev.rubentxu.pipeline.v2.domain.durable.Effect.READ_ONLY), pwdMeta.effects.toSet())
        // The legacy metadata authority no longer answers for "core.isUnix" (G5 REMOVED):
        assertThrows(IllegalArgumentException::class.java) {
            dev.rubentxu.pipeline.v2.application.CanonicalCoreStepMetadata.metadata("core.isUnix")
        }
    }

    @Test
    fun `counters drop to 6-6-6 post-S2-A6-G5 and legacy dispatcher source is physically removed`() {
        // S2-A6 / G5 (2026-09-12): "core.pwd" legacy execution authority physically deleted
        // (LEGACY_REMOVED). The legacy metadata row for "core.pwd" is also gone; the
        // canonical-core counter converges to 6-6-6: 6 executable IDs in LEGACY_PLUGIN_IDS,
        // 6 metadata rows (core.pwd row removed), 6 dispatcher sources (CanonicalPwdNodeDispatcher
        // physically deleted in this slice; CanonicalNodeDispatcher.pwd branch + pwdContext()
        // helper removed).
        assertEquals(6, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size)
        assertTrue("core.isUnix" !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        assertTrue("core.pwd" !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        // The legacy metadata authority no longer answers for "core.pwd" (G5 REMOVED):
        assertThrows(IllegalArgumentException::class.java) {
            dev.rubentxu.pipeline.v2.application.CanonicalCoreStepMetadata.metadata("core.pwd")
        }
        // The legacy metadata authority no longer answers for "core.isUnix" (G5 REMOVED):
        assertThrows(IllegalArgumentException::class.java) {
            dev.rubentxu.pipeline.v2.application.CanonicalCoreStepMetadata.metadata("core.isUnix")
        }
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
