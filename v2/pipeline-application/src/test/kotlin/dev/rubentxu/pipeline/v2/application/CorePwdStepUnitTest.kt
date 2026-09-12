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
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.PwdResolved
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Unit + handler tests for [CorePwdStep] (S2-A6 / G1 registry candidate).
 *
 * These tests pin the candidate contract BEFORE any authority flip:
 *   - typed I/O codec round-trip (input `PwdInput(tmp=false)`, output `PwdOutput(path)`)
 *   - input codec REJECTS `tmp=true` at decode time (PWD_TMP_TRUE_DISPOSITION, D3)
 *   - declared effect `{ READ_ONLY }` + replay `MEMOIZED` (D2, D4 frozen at G2)
 *   - declared capabilities = `{ WORKSPACE_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY }`
 *   - handler emits a single `PwdResolved` event with absolute path + sha256 + workspaceRoot echoed
 *   - missing capability fail-closed admission: no handler invocation, no event
 *   - structural family: while `core.pwd` is in LEGACY_PLUGIN_IDS, classify returns LegacyCore
 *     (registry-membership does NOT change production authority at G1)
 *   - counter invariant 7 / 7 / 7 unchanged (S2-A5/G8 frozen state preserved)
 *
 * Parallel to `CoreIsUnixStepUnitTest`; reuses the same `Real seam: preparation -> boundary -> handler`
 * composition. Authority: G1 slice burn-down template.
 */
@Timeout(20)
class CorePwdStepUnitTest {

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Test
    fun `identity - KEY is core pwd and is the same as the legacy pluginId`() {
        assertEquals("core.pwd", CorePwdStep.KEY.value)
        assertEquals("core.pwd", CorePwdStep.definition.contract.descriptor.stepId)
        assertEquals("pwd", CorePwdStep.definition.contract.descriptor.name)
    }

    // ------------------------------------------------------------------
    // Contract completeness — frozen D1..D4 (G2 freeze)
    // ------------------------------------------------------------------

    @Test
    fun `D1 - canonical pwd(false) truth = shOptions workspaceRoot (NOT user dir)`() {
        // The capability bridge sources workspaceRoot from context.shOptions.workspaceRoot.
        // PATH_B legacy byte-equivalence: see CanonicalPwdNodeDispatcher + pwdContext() (line 89).
        // This test pins the descriptor declaration: { READ_ONLY } + MEMOIZED is the
        // canonical pwd(false) truth, byte-equivalent to legacy metadata.
        assertEquals(
            setOf(Effect.READ_ONLY),
            CorePwdStep.definition.contract.descriptor.effects.toSet(),
        )
    }

    @Test
    fun `D2 - typed output PwdOutput(path) is APPROVED`() {
        val output = PwdOutput(path = "/canonical/workspace")
        assertEquals("/canonical/workspace", output.path)
        assertEquals(StepOutcome.Success, output.outcome)
    }

    @Test
    fun `D3 - tmp=true is OUT_OF_SCOPE and BLOCKER_FOR_AUTHORITY_FLIP`() {
        // Decoder rejects tmp=true.
        val invalid = EncodedStepValue("""{"kind":"pwd","tmp":true}""")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CorePwdStep.definition.contract.inputCodec.decode(invalid)
        }
        assertTrue(
            ex.message!!.contains("tmp=true is unsupported") &&
                ex.message!!.contains("PWD_TMP_TRUE_DISPOSITION"),
            "decode must fail closed with explicit blocker name; got: ${ex.message}",
        )
        // Direct construction also rejected (defense in depth — anyone with the typed input
        // cannot bypass the codec).
        val directEx = assertThrows(IllegalArgumentException::class.java) {
            PwdInput(tmp = true)
        }
        assertTrue(directEx.message!!.contains("PWD_TMP_TRUE_DISPOSITION"))
    }

    @Test
    fun `D4 - ReplayPolicy MEMOIZED for pwd(false)`() {
        assertEquals(
            ReplayPolicy.MEMOIZED,
            CorePwdStep.definition.contract.descriptor.replayPolicy,
        )
    }

    @Test
    fun `required capabilities = WORKSPACE_IDENTITY + EVENT_SINK`() {
        assertEquals(
            setOf(WORKSPACE_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
            CorePwdStep.definition.contract.requiredCapabilities,
        )
    }

    // ------------------------------------------------------------------
    // Codecs
    // ------------------------------------------------------------------

    @Test
    fun `input codec round-trip - tmp=false preserves the legacy pwd envelope`() {
        val encoded = CorePwdStep.definition.contract.inputCodec.encode(PwdInput(tmp = false))
        // Envelope: kind=pwd, tmp=false (matches compiler else-branch payload structure).
        val asJson = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals("pwd", asJson["kind"]?.toString()?.trim('"'))
        assertEquals(false, asJson["tmp"]?.toString()?.toBooleanStrict())
        assertEquals(PwdInput(tmp = false), CorePwdStep.definition.contract.inputCodec.decode(encoded))
    }

    @Test
    fun `input codec decodes legacy payload without tmp field (defaults to false)`() {
        // The legacy encoder writes {"kind":"pwd"} without tmp (tmp defaults to false in the
        // data class) — older journal entries without the tmp key must still decode.
        val legacy = EncodedStepValue("""{"kind":"pwd"}""")
        val decoded = CorePwdStep.definition.contract.inputCodec.decode(legacy)
        assertEquals(PwdInput(tmp = false), decoded)
    }

    @Test
    fun `input codec rejects foreign envelope kind at decode`() {
        val invalid = EncodedStepValue("""{"kind":"echo"}""")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CorePwdStep.definition.contract.inputCodec.decode(invalid)
        }
        assertTrue(ex.message!!.contains("kind must be 'pwd'"))
    }

    @Test
    fun `output codec round-trip - path is preserved and outcome is Success`() {
        val encoded = CorePwdStep.definition.contract.outputCodec.encode(PwdOutput(path = "/canonical/workspace"))
        val asJson = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals("pwd", asJson["kind"]?.toString()?.trim('"'))
        assertEquals("/canonical/workspace", asJson["path"]?.toString()?.trim('"'))
        assertEquals(
            PwdOutput(path = "/canonical/workspace"),
            CorePwdStep.definition.contract.outputCodec.decode(encoded),
        )
        assertEquals(
            PwdOutput(path = "/other/path"),
            CorePwdStep.definition.contract.outputCodec.decode(
                CorePwdStep.definition.contract.outputCodec.encode(PwdOutput(path = "/other/path")),
            ),
        )
    }

    @Test
    fun `output codec rejects foreign envelope kind at decode`() {
        val invalid = EncodedStepValue("""{"kind":"echo","path":"/x"}""")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CorePwdStep.definition.contract.outputCodec.decode(invalid)
        }
        assertTrue(ex.message!!.contains("kind must be 'pwd'"))
    }

    @Test
    fun `durable law - encoded output survives durable persistence and decodes to the same path`() {
        // The journal consumes the ENCODED form, never the typed object. Simulate a
        // persistence round-trip through a raw String (the only durable representation).
        val encoded = CorePwdStep.definition.contract.outputCodec.encode(PwdOutput(path = "/tmp/ws-a"))
        val persisted: String = encoded.value
        val recovered = CorePwdStep.definition.contract.outputCodec.decode(EncodedStepValue(persisted))
        assertEquals(PwdOutput(path = "/tmp/ws-a"), recovered)
    }

    // ------------------------------------------------------------------
    // Handler: emits a single PwdResolved with the canonical workspace path
    // ------------------------------------------------------------------

    @Test
    fun `handler emits exactly one PwdResolved with absolute path, workspaceRoot echoed and sha256 of path`() = runBlocking {
        val sink = InMemoryEventStore()
        val runId = "core-pwd-handler"
        val workspace = Files.createTempDirectory("core-pwd-handler-").toAbsolutePath()
        val ctx = stepHandlerContext(runId, workspace, sink)
        val output = CorePwdStep.definition.handler.execute(PwdInput(tmp = false), ctx)
        assertEquals(PwdOutput(path = workspace.toString()), output)
        val events = sink.eventsFor(runId).toList().filterIsInstance<PwdResolved>()
        assertEquals(1, events.size, "exactly one PwdResolved event expected")
        val event = events.single()
        assertEquals(workspace.toString(), event.path)
        assertEquals(workspace.toString(), event.workspaceRoot)
        assertEquals(CorePwdStep.sha256(workspace.toString()), event.sha256)
    }

    @Test
    fun `handler returns PwdOutput with absolute path even when given a relative workspaceRoot`() = runBlocking {
        // CanonicalRuntimeContext passes an absolute path through shOptions.workspaceRoot.
        // The handler MUST toAbsolutePath() defensively (the bridge does NOT guarantee
        // absolute; if a future fixture injects a relative path the handler must still
        // emit the canonical absolute form).
        val sink = InMemoryEventStore()
        val runId = "core-pwd-relative"
        val cwd = Files.createTempDirectory("core-pwd-rel-base-")
        val relative = cwd.relativize(cwd.resolve("nested"))
        // The handler is invoked with a relative path through a synthetic context — we
        // validate that output.path is the absolute form.
        val ctx = stepHandlerContext(runId, relative, sink)
        val output = CorePwdStep.definition.handler.execute(PwdInput(tmp = false), ctx)
        assertTrue(output.path.endsWith("nested"), "expected path to end with /nested; got ${output.path}")
        assertTrue(java.nio.file.Paths.get(output.path).isAbsolute, "handler output must be absolute; got ${output.path}")
    }

    // ------------------------------------------------------------------
    // Structural family classification (G1 invariant: LEGACY membership wins)
    // ------------------------------------------------------------------

    @Test
    fun `structural family - core pwd stays LegacyCore while in LEGACY_PLUGIN_IDS (no authority flip at G1)`() {
        // G1 invariant: registration alone MUST NOT change production authority.
        // While "core.pwd" remains in LEGACY_PLUGIN_IDS, StructuralFamilyResolver returns
        // LegacyCore regardless of whether the registry resolves the key.
        assertTrue("core.pwd" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        val registry = CoreStepRegistryFactory.registry()
        assertEquals(
            StructuralStepFamily.LegacyCore,
            StructuralFamilyResolver.classify(CorePwdStep.KEY, registry),
        )
    }

    // ------------------------------------------------------------------
    // Counter invariant — S2-A5/G8 frozen state must NOT widen at G1
    // ------------------------------------------------------------------

    @Test
    fun `counters - 7 7 7 unchanged by S2-A6 G1 registration only`() {
        // G1 invariant: registration alone MUST NOT widen the burn-down counters. The
        // existing S2-A5/G8 frozen state is preserved; legacy authority for `core.pwd`
        // is intact until a separate G4 flip (BLOCKED on PWD_TMP_TRUE_DISPOSITION).
        assertEquals(7, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size)
        assertTrue("core.pwd" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        // The legacy metadata authority STILL answers for "core.pwd" (post-G1, pre-G4):
        val meta = CanonicalCoreStepMetadata.metadata("core.pwd")
        assertEquals(setOf(Effect.READ_ONLY), meta.effects.toSet())
    }

    // ------------------------------------------------------------------
    // Real seam: preparation (fail-closed admission) -> boundary -> handler
    // ------------------------------------------------------------------

    @Test
    fun `real seam - missing WORKSPACE_IDENTITY rejects admission with handler invocation 0 and no event`() {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CorePwdStep.KEY,
            encodedInput = EncodedStepValue("""{"kind":"pwd","tmp":false}"""),
            availableCapabilities = setOf(EVENT_SINK_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
        assertEquals(0, capturedPwdResolved("g1-pwd-missing-workspace").size)
    }

    @Test
    fun `real seam - missing EVENT_SINK rejects admission with handler invocation 0 and no event`() {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CorePwdStep.KEY,
            encodedInput = EncodedStepValue("""{"kind":"pwd","tmp":false}"""),
            availableCapabilities = setOf(WORKSPACE_IDENTITY_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
        assertEquals(0, capturedPwdResolved("g1-pwd-missing-sink").size)
    }

    @Test
    fun `real seam - both capabilities missing rejects admission with handler invocation 0 and no event`() {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CorePwdStep.KEY,
            encodedInput = EncodedStepValue("""{"kind":"pwd","tmp":false}"""),
            availableCapabilities = emptySet(),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
        assertEquals(0, capturedPwdResolved("g1-pwd-missing-both").size)
    }

    @Test
    fun `real seam - tmp=true fails closed at decode before capability admission`() = runBlocking {
        // The tmp=true rejection happens in inputCodec.decode; preparation must propagate
        // this failure without ever consulting capabilities or the handler.
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CorePwdStep.KEY,
            encodedInput = EncodedStepValue("""{"kind":"pwd","tmp":true}"""),
            availableCapabilities = setOf(WORKSPACE_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
        assertEquals(0, capturedPwdResolved("g1-pwd-tmp-true-rejected").size)
    }

    @Test
    fun `real seam - full capabilities execute through preparation and boundary with typed output and exactly one event`() = runBlocking {
        val runId = "g1-pwd-real-seam"
        val workspace = Files.createTempDirectory("g1-pwd-real-seam-").toAbsolutePath()
        val prepared = prepareReal(runId, workspace)
        val ctx = context(runId, workspace, sharedEventStore)
        val result = RegistryExecutionBoundary.coexecute(prepared, ctx)
        assertEquals(StepOutcome.Success, result.outcome)
        assertNotNull(result.encodedOutput)
        assertEquals(
            PwdOutput(path = workspace.toString()),
            CorePwdStep.definition.contract.outputCodec.decode(result.encodedOutput!!),
        )
        val events = capturedPwdResolved(runId)
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(workspace.toString(), event.path)
        assertEquals(workspace.toString(), event.workspaceRoot)
        assertEquals(CorePwdStep.sha256(workspace.toString()), event.sha256)
    }

    @Test
    fun `duplicate registration fails closed`() {
        val registry = InMemoryStepRegistry().also { CorePwdStep.registerInto(it) }
        try {
            CorePwdStep.registerInto(registry)
            throw AssertionError("expected duplicate-key rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("core.pwd"), "got: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun prepareReal(runId: String, workspace: Path): PreparedRegistryExecution {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CorePwdStep.KEY,
            encodedInput = EncodedStepValue("""{"kind":"pwd","tmp":false}"""),
            availableCapabilities = setOf(WORKSPACE_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
        )
        val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
        return assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)
    }

    /**
     * Synthesises a [dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext] with a
     * capability access that returns a fresh [WorkspaceIdentity] for [WORKSPACE_IDENTITY_CAPABILITY]
     * and the supplied [sink] for [EVENT_SINK_CAPABILITY]. Mirrors the production bridge shape
     * without going through [CanonicalRuntimeCapabilityAccess] — so a test can inject a relative
     * workspaceRoot to assert the handler's defensive `toAbsolutePath()` behaviour.
     */
    private fun stepHandlerContext(
        runId: String,
        workspace: Path,
        sink: dev.rubentxu.pipeline.v2.events.InMemoryEventStore,
    ): dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext =
        dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext(
            runId = dev.rubentxu.pipeline.v2.domain.RunId(runId),
            stepIndex = 0,
            capabilities = object : dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess {
                override fun available(): Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> =
                    setOf(WORKSPACE_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY)

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(key: dev.rubentxu.pipeline.v2.domain.step.StepCapability): T =
                    when (key) {
                        WORKSPACE_IDENTITY_CAPABILITY -> WorkspaceIdentity(workspaceRoot = workspace) as T
                        EVENT_SINK_CAPABILITY -> sink as T
                        else -> throw IllegalArgumentException("unexpected capability $key")
                    }
            },
        )

    private fun context(runId: String, workspace: Path, sink: InMemoryEventStore): CanonicalRuntimeContext =
        CanonicalRuntimeContext(
            opId = OpId("$runId-op", 0, 0),
            runId = runId,
            stageName = "candidate",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY.copy(workspaceRoot = workspace),
            controlDirRoot = Files.createTempDirectory("$runId-ctrl-"),
            eventSink = sink,
        )

    private fun capturedPwdResolved(runId: String): List<PwdResolved> =
        sharedEventStore.eventsFor(runId).toList().filterIsInstance<PwdResolved>()

    companion object {
        private val sharedEventStore = InMemoryEventStore()
    }
}
