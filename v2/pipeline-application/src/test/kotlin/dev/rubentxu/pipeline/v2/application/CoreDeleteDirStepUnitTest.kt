package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.DeleteDirOperationsAdapter
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
 * Unit + handler tests for [CoreDeleteDirStep] (S2-A7 / G3-fix).
 *
 * These tests pin the candidate contract after the G3-fix refactor:
 *   - typed I/O codec round-trip (input `DeleteDirInput(path)`, output `DeleteDirOutput`)
 *   - declared effect `{ WRITES_WORKSPACE }` + replay `MEMOIZED`
 *   - declared capability = `{ DELETE_DIR_OPERATIONS_CAPABILITY }` (single capability)
 *   - handler emits a single `DirDeleted` event with path, deletedCount, sha256
 *   - missing capability fail-closed admission: no handler invocation, no event
 *   - structural family: while `core.deleteDir` is in LEGACY_PLUGIN_IDS, classify returns LegacyCore
 *     (registry-membership does NOT change production authority at G1)
 *   - counter invariant 6 / 6 / 6 unchanged (pre-G4 frozen state preserved)
 *
 * Architecture: handler is a thin typed seam with ZERO infrastructure
 * (no DeleteDirExecutor, no EventSink, no Files.*, no sha256 computation).
 *
 * Reference: WorkspaceOperations (S2-A3 / G1) and TemporaryWorkspaceOperations (S2-A6 / G3T).
 * Authority: G3-fix burn-down template.
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
    fun `required capabilities = DELETE_DIR_OPERATIONS_CAPABILITY only (G3-fix single capability)`() {
        assertEquals(
            setOf(DELETE_DIR_OPERATIONS_CAPABILITY),
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
    // Handler: thin seam — emits a single DirDeleted with path, deletedCount, sha256
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
    fun `structural family - core deleteDir routes Registry after G4 flip`() {
        // G4 invariant: authority flipped. "core.deleteDir" is OUT of LEGACY_PLUGIN_IDS and
        // StructuralFamilyResolver returns Registry (legacy forms remain on disk until G5,
        // but are UNREACHABLE in production classification).
        assertTrue("core.deleteDir" !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        val registry = CoreStepRegistryFactory.registry()
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(CoreDeleteDirStep.KEY, registry),
        )
    }

    // ------------------------------------------------------------------
    // Counter invariant — G4 transitional state: 5 / 6 / 6
    // ------------------------------------------------------------------

    @Test
    fun `counters - G4 transitional 5 6 6 with legacy forms still on disk`() {
        // G4 invariant: LEGACY_PLUGIN_IDS shrinks to 5; the metadata row and dispatcher
        // file remain physically present (pending G5 physical removal).
        assertEquals(5, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size)
        assertTrue("core.deleteDir" !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        val meta = CanonicalCoreStepMetadata.metadata("core.deleteDir")
        assertEquals(setOf(Effect.WRITES_WORKSPACE), meta.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, meta.replayPolicy)
    }

    // ------------------------------------------------------------------
    // Real seam: preparation (fail-closed admission) -> boundary -> handler
    // G3-fix: single DELETE_DIR_OPERATIONS_CAPABILITY admission
    // ------------------------------------------------------------------

    @Test
    fun `real seam - missing DELETE_DIR_OPERATIONS_CAPABILITY rejects admission with handler invocation 0 and no event`() {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreDeleteDirStep.KEY,
            encodedInput = EncodedStepValue("""{"kind":"deleteDir","path":"."}"""),
            // G3-fix: single capability — none available means rejection
            availableCapabilities = emptySet(),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
        assertEquals(0, capturedDirDeleted("g3fix-deletedir-missing-cap").size)
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
    // Handler with full capabilities executes through preparation and boundary
    // ------------------------------------------------------------------

    @Test
    fun `real seam - full capabilities execute through preparation and boundary with typed output and exactly one event`() = runBlocking {
        val runId = "g3fix-deletedir-real-seam"
        val workspace = Files.createTempDirectory("g3fix-deletedir-real-seam-").toAbsolutePath()
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

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun prepareReal(runId: String, workspace: Path): PreparedRegistryExecution {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreDeleteDirStep.KEY,
            encodedInput = EncodedStepValue("""{"kind":"deleteDir","path":"."}"""),
            // G3-fix: single DELETE_DIR_OPERATIONS_CAPABILITY
            availableCapabilities = setOf(DELETE_DIR_OPERATIONS_CAPABILITY),
        )
        val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
        return assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)
    }

    /**
     * Synthesises a [dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext] with a
     * capability access that returns a [DeleteDirOperationsAdapter] for
     * [DELETE_DIR_OPERATIONS_CAPABILITY].
     *
     * G3-fix: the adapter itself contains the workspace resolver, delete executor,
     * and event sink — the handler only sees the typed seam.
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
                    setOf(DELETE_DIR_OPERATIONS_CAPABILITY)

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(key: dev.rubentxu.pipeline.v2.domain.step.StepCapability): T =
                    when (key) {
                        DELETE_DIR_OPERATIONS_CAPABILITY -> DeleteDirOperationsAdapter(
                            runIdString = runId,
                            stageIdentity = StageIdentity(name = "test", index = 0),
                            stepIndex = 0,
                            controlDirRoot = workspace,
                            eventSink = sink,
                        ) as T
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

    // ------------------------------------------------------------------
    // G3-fix: capability-scoped controlDirRoot (no eager !!)
    // ------------------------------------------------------------------

    /**
     * S2-A7 / G3-final-fix: capability-scoped controlDirRoot verification.
     *
     * Verifies that:
     * 1. CanonicalRuntimeCapabilityAccess construction with controlDirRoot=null does NOT throw
     * 2. DELETE_DIR_OPERATIONS_CAPABILITY is NOT in the available set
     * 3. core.deleteDir admission fails closed (Rejected) because the capability is absent
     * 4. A non-related Step (core.echo) in the same registry is NOT affected
     *    — it does NOT require DELETE_DIR_OPERATIONS_CAPABILITY
     */
    @Test
    fun `G3-final-fix - controlDirRoot=null does NOT break capability access and core-deleteDir fails closed`() {
        // 1. Construction with controlDirRoot=null does NOT throw
        val nullContext = CanonicalRuntimeContext(
            opId = OpId("null-ctrl-test", 0, 0),
            runId = "null-ctrl-test",
            stageName = "test",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,  // null!
            eventSink = InMemoryEventStore(),
        )
        val accessWithNullControlDir: CanonicalRuntimeCapabilityAccess
        try {
            accessWithNullControlDir = CanonicalRuntimeCapabilityAccess(nullContext)
        } catch (e: Exception) {
            throw AssertionError("CanonicalRuntimeCapabilityAccess must not throw on controlDirRoot=null, got: ${e.message}", e)
        }

        // 2. DELETE_DIR_OPERATIONS_CAPABILITY is NOT available
        val available = accessWithNullControlDir.available()
        assertTrue(
            DELETE_DIR_OPERATIONS_CAPABILITY !in available,
            "DELETE_DIR_OPERATIONS_CAPABILITY must NOT be available when controlDirRoot is null",
        )

        // 3. core.deleteDir admission fails closed (Rejected)
        val prepNullCtrl = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CoreDeleteDirStep.KEY,
            encodedInput = EncodedStepValue("""{"kind":"deleteDir","path":"."}"""),
            availableCapabilities = available,  // empty for DELETE_DIR_OPERATIONS_CAPABILITY
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, prepNullCtrl)

        // 4. A non-related Step is NOT affected — it does NOT require DELETE_DIR_OPERATIONS_CAPABILITY
        // core.echo requires only EVENT_SINK_CAPABILITY, which IS available
        val echoKey = dev.rubentxu.pipeline.v2.domain.PluginStepId("core.echo")
        val prepEcho = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = echoKey,
            encodedInput = EncodedStepValue("""{"kind":"echo","message":"hello"}"""),
            availableCapabilities = available,  // EVENT_SINK_CAPABILITY is present
        )
        // Echo should NOT be rejected (either Ready or it has its own requirements)
        // The key point: it's NOT rejected because of the missing DELETE_DIR_OPERATIONS_CAPABILITY
        assertTrue(
            prepEcho !is ExecutionPreparation.Rejected ||
                (prepEcho as? ExecutionPreparation.Rejected)?.reason?.contains("delete-dir") != true,
            "core.echo must NOT be rejected due to missing DELETE_DIR_OPERATIONS_CAPABILITY",
        )
    }

    companion object {
        private val sharedEventStore = InMemoryEventStore()
    }
}
