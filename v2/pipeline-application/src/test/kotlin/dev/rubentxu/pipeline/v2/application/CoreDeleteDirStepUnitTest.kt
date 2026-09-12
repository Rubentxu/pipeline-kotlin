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
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.events.DirDeleted
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit + handler tests for [CoreDeleteDirStep] (S2-A7 / G1 registry candidate).
 *
 * These tests pin the candidate contract BEFORE any authority flip:
 *   - typed I/O codec round-trip (input `DeleteDirInput(path)`, output `DeleteDirOutput`)
 *   - declared effect `{ WRITES_WORKSPACE }` + replay `MEMOIZED`
 *   - declared capabilities = `{ WORKSPACE_RESOLVER_CAPABILITY, STAGE_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY }`
 *   - handler emits a single `DirDeleted` event with path, deletedCount, sha256
 *   - missing capability fail-closed admission: no handler invocation, no event
 *   - structural family: while `core.deleteDir` is in LEGACY_PLUGIN_IDS, classify returns LegacyCore
 *     (registry-membership does NOT change production authority at G1)
 *   - counter invariant 6 / 6 / 6 unchanged (pre-G1 frozen state preserved)
 *
 * Parallel to `CorePwdStepUnitTest`; reuses the same pattern.
 * Authority: G1 slice burn-down template.
 */
@Timeout(20)
class CoreDeleteDirStepUnitTest {

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Test
    fun `identity - KEY is core deleteDir and is the same as the legacy pluginId`() {
        assertEquals("core.deleteDir", CoreDeleteDirStep.KEY.value)
        assertEquals("core.deleteDir", CoreDeleteDirStep.definition.contract.descriptor.stepId)
        assertEquals("deleteDir", CoreDeleteDirStep.definition.contract.descriptor.name)
    }

    // ------------------------------------------------------------------
    // Contract completeness
    // ------------------------------------------------------------------

    @Test
    fun `descriptor effects = WRITES_WORKSPACE`() {
        assertEquals(
            setOf(Effect.WRITES_WORKSPACE),
            CoreDeleteDirStep.definition.contract.descriptor.effects.toSet(),
        )
    }

    @Test
    fun `ReplayPolicy MEMOIZED`() {
        assertEquals(
            ReplayPolicy.MEMOIZED,
            CoreDeleteDirStep.definition.contract.descriptor.replayPolicy,
        )
    }

    @Test
    fun `required capabilities = WORKSPACE_RESOLVER + STAGE_IDENTITY + EVENT_SINK`() {
        assertEquals(
            setOf(WORKSPACE_RESOLVER_CAPABILITY, STAGE_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
            CoreDeleteDirStep.definition.contract.requiredCapabilities,
        )
    }

    // ------------------------------------------------------------------
    // Codecs
    // ------------------------------------------------------------------

    @Test
    fun `input codec round-trip - path default preserves the legacy deleteDir envelope`() {
        val encoded = CoreDeleteDirStep.definition.contract.inputCodec.encode(DeleteDirInput(path = "."))
        val asJson = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals("deleteDir", asJson["kind"]?.toString()?.trim('"'))
        assertEquals(".", asJson["path"]?.toString()?.trim('"'))
        assertEquals(DeleteDirInput(path = "."), CoreDeleteDirStep.definition.contract.inputCodec.decode(encoded))
    }

    @Test
    fun `input codec round-trip - custom path preserved`() {
        val encoded = CoreDeleteDirStep.definition.contract.inputCodec.encode(DeleteDirInput(path = "subdir"))
        val asJson = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals("subdir", asJson["path"]?.toString()?.trim('"'))
        assertEquals(DeleteDirInput(path = "subdir"), CoreDeleteDirStep.definition.contract.inputCodec.decode(encoded))
    }

    @Test
    fun `input codec decodes legacy payload without path field (defaults to dot)`() {
        val legacy = EncodedStepValue("""{"kind":"deleteDir"}""")
        val decoded = CoreDeleteDirStep.definition.contract.inputCodec.decode(legacy)
        assertEquals(DeleteDirInput(path = "."), decoded)
    }

    @Test
    fun `input codec rejects foreign envelope kind at decode`() {
        val invalid = EncodedStepValue("""{"kind":"echo","path":"."}""")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CoreDeleteDirStep.definition.contract.inputCodec.decode(invalid)
        }
        assertTrue(ex.message!!.contains("kind must be 'deleteDir'"))
    }

    @Test
    fun `output codec round-trip - path, deletedCount, sha256 preserved`() {
        val encoded = CoreDeleteDirStep.definition.contract.outputCodec.encode(
            DeleteDirOutput(path = "/workspace/test", deletedCount = 5, sha256 = "abc123"),
        )
        val asJson = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals("deleteDir", asJson["kind"]?.toString()?.trim('"'))
        assertEquals("/workspace/test", asJson["path"]?.toString()?.trim('"'))
        assertEquals(5, asJson["deletedCount"]?.jsonPrimitive?.content?.toInt())
        assertEquals("abc123", asJson["sha256"]?.toString()?.trim('"'))
        assertEquals(
            DeleteDirOutput(path = "/workspace/test", deletedCount = 5, sha256 = "abc123"),
            CoreDeleteDirStep.definition.contract.outputCodec.decode(encoded),
        )
    }

    @Test
    fun `output codec rejects foreign envelope kind at decode`() {
        val invalid = EncodedStepValue("""{"kind":"echo","path":"/x","deletedCount":0,"sha256":"y"}""")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CoreDeleteDirStep.definition.contract.outputCodec.decode(invalid)
        }
        assertTrue(ex.message!!.contains("kind must be 'deleteDir'"))
    }

    @Test
    fun `durable law - encoded output survives durable persistence and decodes to the same values`() {
        val encoded = CoreDeleteDirStep.definition.contract.outputCodec.encode(
            DeleteDirOutput(path = "/tmp/ws-a", deletedCount = 3, sha256 = "def456"),
        )
        val persisted: String = encoded.value
        val recovered = CoreDeleteDirStep.definition.contract.outputCodec.decode(EncodedStepValue(persisted))
        assertEquals(DeleteDirOutput(path = "/tmp/ws-a", deletedCount = 3, sha256 = "def456"), recovered)
    }

    // ------------------------------------------------------------------
    // Handler: emits a single DirDeleted with path, deletedCount, sha256
    // ------------------------------------------------------------------

    @Test
    fun `handler emits exactly one DirDeleted with path, deletedCount, sha256`() = runBlocking {
        val sink = InMemoryEventStore()
        val runId = "core-deletedir-handler"
        val workspace = Files.createTempDirectory("core-deletedir-handler-").toAbsolutePath()
        val ctx = stepHandlerContext(runId, workspace, sink)
        val output = CoreDeleteDirStep.definition.handler.execute(DeleteDirInput(path = "."), ctx)
        // The handler resolves the workspace path as workspace/test-0 (stageName=test, stageIndex=0)
        assertEquals(workspace.resolve("workspace/test-0").toString(), output.path)
        assertTrue(output.deletedCount >= 0)
        assertEquals(64, output.sha256.length)
        assertEquals(StepOutcome.Success, output.outcome)
        val events = sink.eventsFor(runId).toList().filterIsInstance<DirDeleted>()
        assertEquals(1, events.size, "exactly one DirDeleted event expected")
        val event = events.single()
        assertEquals(workspace.resolve("workspace/test-0").toString(), event.path)
        assertEquals(64, event.sha256.length)
    }

    // ------------------------------------------------------------------
    // Structural family classification (G1 invariant: LEGACY membership wins)
    // ------------------------------------------------------------------

    @Test
    fun `structural family - core deleteDir stays LegacyCore while in LEGACY_PLUGIN_IDS (no authority flip at G1)`() {
        // G1 invariant: registration alone MUST NOT change production authority.
        // While "core.deleteDir" remains in LEGACY_PLUGIN_IDS, StructuralFamilyResolver returns
        // LegacyCore regardless of whether the registry resolves the key.
        assertTrue("core.deleteDir" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        val registry = CoreStepRegistryFactory.registry()
        assertEquals(
            StructuralStepFamily.LegacyCore,
            StructuralFamilyResolver.classify(CoreDeleteDirStep.KEY, registry),
        )
    }

    // ------------------------------------------------------------------
    // Counter invariant — pre-G1 frozen state must NOT widen at G1
    // ------------------------------------------------------------------

    @Test
    fun `counters - 6 6 6 unchanged by S2-A7 G1 registration only`() {
        // G1 invariant: registration alone MUST NOT widen the burn-down counters.
        // The existing frozen state is preserved; legacy authority for `core.deleteDir`
        // is intact until a separate G4 flip.
        assertEquals(6, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size)
        assertTrue("core.deleteDir" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        val meta = CanonicalCoreStepMetadata.metadata("core.deleteDir")
        assertEquals(setOf(Effect.WRITES_WORKSPACE), meta.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, meta.replayPolicy)
    }

    // ------------------------------------------------------------------
    // Real seam: preparation (fail-closed admission) -> boundary -> handler
    // ------------------------------------------------------------------

    @Test
    fun `real seam - missing WORKSPACE_RESOLVER_CAPABILITY rejects admission with handler invocation 0 and no event`() {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreDeleteDirStep.KEY,
            encodedInput = EncodedStepValue("""{"kind":"deleteDir","path":"."}"""),
            availableCapabilities = setOf(STAGE_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
        assertEquals(0, capturedDirDeleted("g1-deletedir-missing-resolver").size)
    }

    @Test
    fun `real seam - missing STAGE_IDENTITY_CAPABILITY rejects admission with handler invocation 0 and no event`() {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreDeleteDirStep.KEY,
            encodedInput = EncodedStepValue("""{"kind":"deleteDir","path":"."}"""),
            availableCapabilities = setOf(WORKSPACE_RESOLVER_CAPABILITY, EVENT_SINK_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
        assertEquals(0, capturedDirDeleted("g1-deletedir-missing-stage-identity").size)
    }

    @Test
    fun `real seam - missing EVENT_SINK_CAPABILITY rejects admission with handler invocation 0 and no event`() {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreDeleteDirStep.KEY,
            encodedInput = EncodedStepValue("""{"kind":"deleteDir","path":"."}"""),
            availableCapabilities = setOf(WORKSPACE_RESOLVER_CAPABILITY, STAGE_IDENTITY_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
        assertEquals(0, capturedDirDeleted("g1-deletedir-missing-sink").size)
    }

    @Test
    fun `real seam - all capabilities missing rejects admission with handler invocation 0 and no event`() {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreDeleteDirStep.KEY,
            encodedInput = EncodedStepValue("""{"kind":"deleteDir","path":"."}"""),
            availableCapabilities = emptySet(),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
        assertEquals(0, capturedDirDeleted("g1-deletedir-missing-all").size)
    }

    @Test
    fun `real seam - full capabilities execute through preparation and boundary with typed output and exactly one event`() = runBlocking {
        val runId = "g1-deletedir-real-seam"
        val workspace = Files.createTempDirectory("g1-deletedir-real-seam-").toAbsolutePath()
        val prepared = prepareReal(runId, workspace)
        val ctx = context(runId, workspace, sharedEventStore)
        val result = RegistryExecutionBoundary.coexecute(prepared, ctx)
        assertEquals(StepOutcome.Success, result.outcome)
        assertNotNull(result.encodedOutput)
        val decoded = CoreDeleteDirStep.definition.contract.outputCodec.decode(result.encodedOutput!!)
        // The handler resolves the workspace path as workspace/test-0 (stageName=test, stageIndex=0)
        assertEquals(workspace.resolve("workspace/test-0").toString(), decoded.path)
        assertTrue(decoded.deletedCount >= 0)
        assertEquals(64, decoded.sha256.length)
        val events = capturedDirDeleted(runId)
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(workspace.resolve("workspace/test-0").toString(), event.path)
        assertEquals(64, event.sha256.length)
    }

    @Test
    fun `duplicate registration fails closed`() {
        val registry = InMemoryStepRegistry().also { CoreDeleteDirStep.registerInto(it) }
        try {
            CoreDeleteDirStep.registerInto(registry)
            throw AssertionError("expected duplicate-key rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("core.deleteDir"), "got: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun prepareReal(runId: String, workspace: Path): PreparedRegistryExecution {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreDeleteDirStep.KEY,
            encodedInput = EncodedStepValue("""{"kind":"deleteDir","path":"."}"""),
            availableCapabilities = setOf(
                WORKSPACE_RESOLVER_CAPABILITY,
                STAGE_IDENTITY_CAPABILITY,
                EVENT_SINK_CAPABILITY,
            ),
        )
        val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
        return assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)
    }

    /**
     * Synthesises a [dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext] with a
     * capability access that returns a fresh [WorkspaceResolverPort] for [WORKSPACE_RESOLVER_CAPABILITY],
     * [StageIdentity] for [STAGE_IDENTITY_CAPABILITY], and the supplied [sink] for [EVENT_SINK_CAPABILITY].
     */
    private fun stepHandlerContext(
        runId: String,
        workspace: Path,
        sink: InMemoryEventStore,
    ): dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext =
        dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext(
            runId = dev.rubentxu.pipeline.v2.domain.RunId(runId),
            stepIndex = 0,
            capabilities = object : dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess {
                override fun available(): Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> =
                    setOf(WORKSPACE_RESOLVER_CAPABILITY, STAGE_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY)

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(key: dev.rubentxu.pipeline.v2.domain.step.StepCapability): T =
                    when (key) {
                        WORKSPACE_RESOLVER_CAPABILITY -> object : WorkspaceResolverPort {
                            private val resolver = dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver(workspace)
                            override fun resolve(stageName: String, stageIndex: Int): java.nio.file.Path =
                                resolver.resolve(stageName, stageIndex)
                            override fun ensureCreated(path: java.nio.file.Path): java.nio.file.Path =
                                resolver.ensureCreated(path)
                        } as T
                        STAGE_IDENTITY_CAPABILITY -> StageIdentity(name = "test", index = 0) as T
                        EVENT_SINK_CAPABILITY -> sink as T
                        else -> throw IllegalArgumentException("unexpected capability $key")
                    }
            },
        )

    private fun context(runId: String, workspace: Path, sink: InMemoryEventStore): CanonicalRuntimeContext =
        CanonicalRuntimeContext(
            opId = OpId("$runId-op", 0, 0),
            runId = runId,
            stageName = "test",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY.copy(workspaceRoot = workspace),
            controlDirRoot = workspace,
            eventSink = sink,
        )

    private fun capturedDirDeleted(runId: String): List<DirDeleted> =
        sharedEventStore.eventsFor(runId).toList().filterIsInstance<DirDeleted>()

    companion object {
        private val sharedEventStore = InMemoryEventStore()
    }
}
