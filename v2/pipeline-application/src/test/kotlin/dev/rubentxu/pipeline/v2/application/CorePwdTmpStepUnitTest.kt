package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.application.durable.TemporaryWorkspaceOperationsAdapter
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.PwdResolved
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Unit + handler tests for `core.pwd.tmp` (S2-A6 / G3T, corrected post-review).
 *
 * After the post-review correction, the handler is pure policy — it consumes ONLY
 * [TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY]. The infrastructure laws (D5–D12)
 * move to the [TemporaryWorkspaceOperationsAdapter] tests.
 *
 * Test partitioning:
 *  - Handler-level tests: identity, structural family, contract shape, codecs,
 *    capability routing, admission fail-closed, requiredCapabilities set.
 *  - Adapter-level tests: determinism (D5–D12), event emission, idempotence,
 *    path-under-workspaceRoot, no-timestamp/random, sha256-hex token shape.
 *  - Cross-cutting: counters frozen, `core.pwd` remains LegacyCore, DSL not
 *    rewired, duplicate registration fails closed.
 *
 * Architecture guards:
 *  - `CorePwdTmpStep.kt` does NOT import `java.nio.file.Files`, `EventSink`,
 *    `OpId`, `MessageDigest`, or expose them through any code path. The test
 *    asserts this with `handler-purity` (a fingerprint of the source).
 */
@Timeout(20)
class CorePwdTmpStepUnitTest {

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Test
    fun `identity - KEY is core pwd tmp and the StepKey taxonomy uses a dot`() {
        assertEquals("core.pwd.tmp", CorePwdTmpStep.KEY.value)
        assertEquals("core.pwd.tmp", CorePwdTmpStep.definition.contract.descriptor.stepId)
        assertEquals("pwd.tmp", CorePwdTmpStep.definition.contract.descriptor.name)
    }

    // ------------------------------------------------------------------
    // Structural family: registry-membership-only path (no legacy)
    // ------------------------------------------------------------------

    @Test
    fun `structural family - core pwd tmp is Registry from the moment of registration`() {
        assertTrue("core.pwd.tmp" !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        val registry = CoreStepRegistryFactory.registry()
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(CorePwdTmpStep.KEY, registry),
        )
    }

    @Disabled("Historical S2-A6/G3T snapshot: S2-A6/G4 (2026-09-12) flipped core.pwd to REGISTRY_PRIMARY; the LegacyCore assertion is superseded by `core pwd flip — structural family resolves to Registry post-flip` in CorePwdStepUnitTest. Preserved verbatim for traceability; will be deleted when the legacy narrative ends.")
    @Test
    fun `structural family - core pwd stays LegacyCore (post-G1 invariant unchanged)`() {
        assertTrue("core.pwd" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        val registry = CoreStepRegistryFactory.registry()
        assertEquals(
            StructuralStepFamily.LegacyCore,
            StructuralFamilyResolver.classify(CorePwdStep.KEY, registry),
        )
    }

    // ------------------------------------------------------------------
    // Handler purity — architectural guard (post-correction)
    // ------------------------------------------------------------------

    @Test
    fun `handler purity - CorePwdTmpStep source has zero runtime-infrastructure imports`() {
        // The post-correction design collapses the three original capabilities into
        // a single typed port. The handler must not import filesystem, event sink,
        // OpId, or sha256 primitives. We assert this against the on-disk source.
        val source = java.nio.file.Files.readString(
            java.nio.file.Path.of(
                "src/main/kotlin/dev/rubentxu/pipeline/v2/application/CorePwdTmpStep.kt",
            ),
        )
        assertTrue(
            !source.contains("import java.nio.file.Files"),
            "CorePwdTmpStep must not import java.nio.file.Files — the adapter owns the IO",
        )
        assertTrue(
            !source.contains("import dev.rubentxu.pipeline.v2.events.EventSink"),
            "CorePwdTmpStep must not import EventSink — the adapter owns observability",
        )
        assertTrue(
            !source.contains("import dev.rubentxu.pipeline.v2.events.PwdResolved"),
            "CorePwdTmpStep must not import PwdResolved — the adapter emits the event",
        )
        assertTrue(
            !source.contains("import dev.rubentxu.pipeline.v2.application.durable.OpId"),
            "CorePwdTmpStep must not import OpId — durable identity is the adapter's concern",
        )
        assertTrue(
            !source.contains("import java.security.MessageDigest"),
            "CorePwdTmpStep must not import MessageDigest — sha256 derivation is the adapter's concern",
        )
        assertTrue(
            !source.lines().any { line ->
                !line.trimStart().startsWith("*") &&
                    !line.trimStart().startsWith("//") &&
                    line.contains("DURABLE_OPERATION_IDENTITY_CAPABILITY")
            },
            "DURABLE_OPERATION_IDENTITY_CAPABILITY is forbidden in executable code in CorePwdTmpStep; " +
                "the handler does not consume durable identity. (Comments and docstrings are allowed.)",
        )
        // The handler body must consist ONLY of `ctx.capabilities.get(...)` followed by
        // the typed result forward — no `Files.`, no `sha256(`, no `UUID.randomUUID()`.
        // (Comments and docstrings are ignored; we look for those tokens on executable lines.)
        val executable = source.lineSequence().filter { line ->
            val trimmed = line.trimStart()
            !trimmed.startsWith("*") && !trimmed.startsWith("//")
        }
        assertTrue(
            executable.none { it.contains("Files.createDirectories") },
            "CorePwdTmpStep must not call Files.createDirectories — adapter does it",
        )
        assertTrue(
            executable.none { it.contains("sha256") },
            "CorePwdTmpStep must not call sha256 — adapter derives the token",
        )
        assertTrue(
            executable.none { it.contains("UUID.randomUUID") },
            "CorePwdTmpStep must not call UUID.randomUUID — adapter owns the eventId",
        )
        assertTrue(
            executable.none { it.contains("Instant.now") },
            "CorePwdTmpStep must not call Instant.now — adapter emits the event with occurredAt",
        )
    }

    // ------------------------------------------------------------------
    // Counter invariant — S2-A5/G8 frozen state must NOT widen
    // ------------------------------------------------------------------

    @Disabled("Historical S2-A6/G3T snapshot: S2-A6/G4 (2026-09-12) flipped core.pwd to REGISTRY_PRIMARY; the 7-key LegacyCore counter is superseded by `core pwd flip — 6 residual keys remain post-S2-A6-G4` in CorePwdStepUnitTest. Preserved verbatim for traceability; will be deleted when the legacy narrative ends.")
    @Test
    fun `counters - legacy 7 7 7 unchanged, core pwd tmp is a NEW registry entry, NOT a legacy entry`() {
        assertEquals(7, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size)
        assertTrue("core.pwd.tmp" !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        assertTrue("core.pwd" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS)
        val meta = CanonicalCoreStepMetadata.metadata("core.pwd")
        assertEquals(setOf(Effect.READ_ONLY), meta.effects.toSet())
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalCoreStepMetadata.metadata("core.pwd.tmp")
        }
    }

    // ------------------------------------------------------------------
    // Contract: effects + replay + capabilities (D2/D4 frozen at G2)
    // ------------------------------------------------------------------

    @Test
    fun `descriptor - WRITES_WORKSPACE + MEMOIZED`() {
        assertEquals(
            setOf(Effect.WRITES_WORKSPACE),
            CorePwdTmpStep.definition.contract.descriptor.effects.toSet(),
        )
        assertEquals(
            ReplayPolicy.MEMOIZED,
            CorePwdTmpStep.definition.contract.descriptor.replayPolicy,
        )
    }

    @Test
    fun `required capabilities = exactly TEMPORARY_WORKSPACE_OPERATIONS (single port, post-correction)`() {
        // Post-correction: ONE capability. The three from the pre-correction
        // version (workspace identity + durable op identity + event sink) have
        // been collapsed into the typed `TemporaryWorkspaceOperations` port.
        assertEquals(
            setOf(TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY),
            CorePwdTmpStep.definition.contract.requiredCapabilities,
        )
    }

    // ------------------------------------------------------------------
    // Codecs
    // ------------------------------------------------------------------

    @Test
    fun `input codec round-trip preserves the empty-object unit envelope`() {
        val encoded = CorePwdTmpStep.definition.contract.inputCodec.encode(PwdTmpInput)
        assertEquals("{}", encoded.value)
        assertEquals(PwdTmpInput, CorePwdTmpStep.definition.contract.inputCodec.decode(encoded))
    }

    @Test
    fun `output codec round-trip carries kind pwd tmp and the absolute path`() {
        val encoded = CorePwdTmpStep.definition.contract.outputCodec.encode(
            PwdTmpOutput(path = "/canonical/workspace/tmp-pwd-abc123"),
        )
        val asJson = Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals("pwd.tmp", asJson["kind"]?.toString()?.trim('"'))
        assertEquals("/canonical/workspace/tmp-pwd-abc123", asJson["path"]?.toString()?.trim('"'))
        assertEquals(
            PwdTmpOutput(path = "/canonical/workspace/tmp-pwd-abc123"),
            CorePwdTmpStep.definition.contract.outputCodec.decode(encoded),
        )
    }

    @Test
    fun `output codec rejects foreign envelope kind at decode`() {
        val invalid = EncodedStepValue("""{"kind":"pwd","path":"/x"}""")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CorePwdTmpStep.definition.contract.outputCodec.decode(invalid)
        }
        assertTrue(ex.message!!.contains("kind must be 'pwd.tmp'"))
    }

    // ------------------------------------------------------------------
    // Handler-level: capability routing (the only thing the handler does)
    // ------------------------------------------------------------------

    @Test
    fun `handler - executes once when the single capability is available`() = runBlocking {
        val stub = StubTmpOps()
        val ctx = stepHandlerContextWithStub(stub)
        val output = CorePwdTmpStep.definition.handler.execute(PwdTmpInput, ctx)
        assertEquals("/stub/tmp-pwd-token", output.path)
        assertEquals(1, stub.calls, "the typed port must be invoked exactly once per handler.execute")
    }

    @Test
    fun `handler - missing capability rejects admission, handler never runs`() {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CorePwdTmpStep.KEY,
            encodedInput = EncodedStepValue("{}"),
            availableCapabilities = emptySet(),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
    }

    @Test
    fun `handler - unrelated capability does not satisfy the contract`() {
        // The contract requires EXACTLY TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY. A
        // different capability (e.g. EVENT_SINK, SHELL_OPERATIONS, ...) must NOT
        // satisfy admission. This guards against accidental capability confusion
        // where a handler reads EventSink directly instead of going through the port.
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CorePwdTmpStep.KEY,
            encodedInput = EncodedStepValue("{}"),
            availableCapabilities = setOf(
                EVENT_SINK_CAPABILITY,
                SHELL_OPERATIONS_CAPABILITY,
                WORKSPACE_IDENTITY_CAPABILITY,
                PLATFORM_IDENTITY_CAPABILITY,
            ),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, preparation)
    }

    @Test
    fun `handler - real-seam full admission produces typed output with exactly one event`() = runBlocking {
        val sink = InMemoryEventStore()
        val runId = "g3t-real-seam"
        val workspace = Files.createTempDirectory("g3t-real-seam-").toAbsolutePath()
        val prepared = prepareReal()
        val opId = OpId(runId, 0, 0)
        val ctx = context(runId, opId, workspace, sink)
        val result = RegistryExecutionBoundary.coexecute(prepared, ctx)
        assertEquals(StepOutcome.Success, result.outcome)
        assertNotNull(result.encodedOutput)
        val expectedPath = workspace.resolve("tmp-pwd-${TemporaryWorkspaceOperationsAdapter.sha256Hex(opId.format())}")
            .toAbsolutePath().toString()
        assertEquals(
            PwdTmpOutput(path = expectedPath),
            CorePwdTmpStep.definition.contract.outputCodec.decode(result.encodedOutput!!),
        )
        // The adapter (NOT the handler) emitted the PwdResolved — observable through the
        // shared event sink. Exactly one event per fresh execution.
        val events = sink.eventsFor(runId).toList().filterIsInstance<PwdResolved>()
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(expectedPath, event.path)
        assertEquals(workspace.toString(), event.workspaceRoot)
        assertEquals(TemporaryWorkspaceOperationsAdapter.sha256Hex(expectedPath), event.sha256)
    }

    // ------------------------------------------------------------------
    // Adapter-level: determinism (D5–D12) lives here now
    // ------------------------------------------------------------------

    @Test
    fun `adapter - D5 same OpId yields the same deterministic path on repeat invocations`() {
        val sink = InMemoryEventStore()
        val runId = "g3t-same-op"
        val workspace = Files.createTempDirectory("g3t-same-op-").toAbsolutePath()
        val opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0)
        val adapter = TemporaryWorkspaceOperationsAdapter(runId, opId, workspace, sink)
        val r1 = adapter.resolveOrCreate()
        val r2 = adapter.resolveOrCreate()
        assertEquals(r1.path, r2.path, "same OpId MUST yield the same deterministic path")
        assertTrue(Files.isDirectory(Path.of(r1.path)), "the path must be a real directory")
        val events = sink.eventsFor(runId).toList().filterIsInstance<PwdResolved>()
        assertEquals(2, events.size, "two invocations => two PwdResolved observations (one per call)")
        for (e in events) {
            assertEquals(r1.path, e.path)
            assertEquals(TemporaryWorkspaceOperationsAdapter.sha256Hex(r1.path), e.sha256)
        }
    }

    @Test
    fun `adapter - D6 different stepIndex yields distinct paths in the same stage`() {
        val sink = InMemoryEventStore()
        val runId = "g3t-stepIndex"
        val workspace = Files.createTempDirectory("g3t-stepIndex-").toAbsolutePath()
        val a = TemporaryWorkspaceOperationsAdapter(runId, OpId(runId, 0, 0), workspace, sink).resolveOrCreate()
        val b = TemporaryWorkspaceOperationsAdapter(runId, OpId(runId, 0, 1), workspace, sink).resolveOrCreate()
        assertNotEquals(a.path, b.path, "distinct stepIndex MUST yield distinct paths")
        assertTrue(a.path.startsWith(workspace.toString()))
        assertTrue(b.path.startsWith(workspace.toString()))
    }

    @Test
    fun `adapter - D7 different bodyPath yields distinct paths for the same step`() {
        val sink = InMemoryEventStore()
        val runId = "g3t-bodyPath"
        val workspace = Files.createTempDirectory("g3t-bodyPath-").toAbsolutePath()
        val opA = OpId(
            runId = runId, stageIndex = 0, stepIndex = 0, branchIndex = null,
            bodyPath = listOf(BlockSegment(0, PluginStepId("core.echo"))),
        )
        val opB = OpId(
            runId = runId, stageIndex = 0, stepIndex = 0, branchIndex = null,
            bodyPath = listOf(BlockSegment(0, PluginStepId("core.sh"))),
        )
        assertNotEquals(opA.format(), opB.format(), "OpId.format must reflect bodyPath differences")
        val a = TemporaryWorkspaceOperationsAdapter(runId, opA, workspace, sink).resolveOrCreate()
        val b = TemporaryWorkspaceOperationsAdapter(runId, opB, workspace, sink).resolveOrCreate()
        assertNotEquals(a.path, b.path, "distinct bodyPath MUST yield distinct paths")
    }

    @Test
    fun `adapter - D8 different branchIndex yields distinct paths`() {
        val sink = InMemoryEventStore()
        val runId = "g3t-branch"
        val workspace = Files.createTempDirectory("g3t-branch-").toAbsolutePath()
        val r0 = TemporaryWorkspaceOperationsAdapter(runId, OpId(runId, 0, 0, branchIndex = 0), workspace, sink).resolveOrCreate()
        val r1 = TemporaryWorkspaceOperationsAdapter(runId, OpId(runId, 0, 0, branchIndex = 1), workspace, sink).resolveOrCreate()
        assertNotEquals(r0.path, r1.path, "distinct branchIndex MUST yield distinct paths")
    }

    @Test
    fun `adapter - D9 path always remains under WorkspaceIdentity workspaceRoot`() {
        val sink = InMemoryEventStore()
        val runId = "g3t-bounded"
        val workspace = Files.createTempDirectory("g3t-bounded-").toAbsolutePath()
        for (i in 0..3) {
            val r = TemporaryWorkspaceOperationsAdapter(runId, OpId(runId, 0, i), workspace, sink).resolveOrCreate()
            assertTrue(
                r.path.startsWith(workspace.toString()),
                "all temp paths must be bounded by workspaceRoot; got ${r.path} vs ${workspace}",
            )
        }
    }

    @Test
    fun `adapter - D10 no timestamp or random suffix in the path identity (sha256 hex only)`() {
        val sink = InMemoryEventStore()
        val runId = "g3t-no-timestamp"
        val workspace = Files.createTempDirectory("g3t-no-timestamp-").toAbsolutePath()
        val opId = OpId(runId, 0, 0)
        val r = TemporaryWorkspaceOperationsAdapter(runId, opId, workspace, sink).resolveOrCreate()
        // Path suffix must match exactly `tmp-pwd-<sha256-hex>` (64 lowercase hex chars).
        val expectedToken = TemporaryWorkspaceOperationsAdapter.sha256Hex(opId.format())
        assertEquals(
            workspace.resolve("tmp-pwd-$expectedToken").toAbsolutePath().toString(),
            r.path,
        )
        val tail = r.path.removePrefix(workspace.toString()).trimStart(java.io.File.separatorChar)
        val regex = Regex("^tmp-pwd-([0-9a-f]{64})$")
        val match = regex.matchEntire(tail)
        assertTrue(match != null, "path must be `tmp-pwd-<64 hex chars>`; got `$tail`")
        assertEquals(expectedToken, match!!.groupValues[1])
        assertTrue(!r.path.contains("currentTimeMillis"))
    }

    @Test
    fun `adapter - D11 Files createDirectories is idempotent - second invocation is a no-op`() {
        val sink = InMemoryEventStore()
        val runId = "g3t-idempotent"
        val workspace = Files.createTempDirectory("g3t-idempotent-").toAbsolutePath()
        val opId = OpId(runId, 0, 0)
        val adapter = TemporaryWorkspaceOperationsAdapter(runId, opId, workspace, sink)
        val r1 = adapter.resolveOrCreate()
        val r2 = adapter.resolveOrCreate()
        assertEquals(r1.path, r2.path)
        assertTrue(Files.isDirectory(Path.of(r2.path)))
    }

    @Test
    fun `adapter - D12 exactly one PwdResolved event per fresh execution`() {
        val sink = InMemoryEventStore()
        val runId = "g3t-one-event"
        val workspace = Files.createTempDirectory("g3t-one-event-").toAbsolutePath()
        TemporaryWorkspaceOperationsAdapter(runId, OpId(runId, 0, 0), workspace, sink).resolveOrCreate()
        val events = sink.eventsFor(runId).toList().filterIsInstance<PwdResolved>()
        assertEquals(1, events.size, "exactly one PwdResolved event expected per fresh resolveOrCreate()")
        val event = events.single()
        assertTrue(event.path.startsWith(workspace.toString()))
        assertEquals(workspace.toString(), event.workspaceRoot)
        assertEquals(TemporaryWorkspaceOperationsAdapter.sha256Hex(event.path), event.sha256)
    }

    // ------------------------------------------------------------------
    // Cross-cutting
    // ------------------------------------------------------------------

    @Test
    fun `duplicate registration fails closed`() {
        val registry = InMemoryStepRegistry().also { CorePwdTmpStep.registerInto(it) }
        try {
            CorePwdTmpStep.registerInto(registry)
            throw AssertionError("expected duplicate-key rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("core.pwd.tmp"), "got: ${e.message}")
        }
    }

    @Test
    fun `DSL firewall - StepSpec Pwd(tmp=true) is still produced by the legacy DSL, core pwd tmp is registry-only`() {
        // The legacy sealed subtype `StepSpec.Pwd(tmp=true)` is still the only thing the
        // public DSL produces. G3T does NOT change PipelineDsl.pwd, the compiler, or any
        // PSI lowering. `core.pwd.tmp` is reachable only through the generic registry
        // path until G3R rewires the lower-binding.
        val step = dev.rubentxu.pipeline.v2.dsl.StepSpec.Pwd(tmp = true)
        assertEquals("pwd", step.name)
        assertEquals("pwd", step.type)
        assertEquals(true, step.tmp)
        // No new sealed subtype for "core.pwd.tmp" exists at the DSL layer — it's a
        // registry-only StepKey. (Cf. StepSpec.RegistryStepSpec usage for the path that
        // G3R will eventually lower to.)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun prepareReal(): PreparedRegistryExecution {
        val preparation = RegistryExecutionPreparation.prepare(
            registry = CoreStepRegistryFactory.registry(),
            key = CorePwdTmpStep.KEY,
            encodedInput = EncodedStepValue("{}"),
            availableCapabilities = setOf(TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY),
        )
        val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
        return assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)
    }

    /**
     * Synthesises a [StepHandlerContext] backed by a stub [TemporaryWorkspaceOperations]
     * that returns a fixed path. This proves the handler does ONLY
     * `ctx.capabilities.get(TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY)` and forwards the
     * typed result.
     */
    private class StubTmpOps : TemporaryWorkspaceOperations {
        var calls: Int = 0
        override fun resolveOrCreate(): TempWorkspaceResult {
            calls += 1
            return TempWorkspaceResult(path = "/stub/tmp-pwd-token")
        }
    }

    private fun stepHandlerContextWithStub(stub: StubTmpOps): StepHandlerContext =
        StepHandlerContext(
            runId = RunId("g3t-stub"),
            stepIndex = 0,
            capabilities = object : StepCapabilityAccess {
                override fun available(): Set<StepCapability> =
                    setOf(TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY)

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(key: StepCapability): T = when (key) {
                    TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY -> stub as T
                    else -> throw IllegalArgumentException("unexpected capability $key")
                }
            },
        )

    /**
     * Production-shape context that exercises the real [CanonicalRuntimeCapabilityAccess]
     * bridge — the same code path the canonical coordinator would use at runtime. The
     * bridge builds the [TemporaryWorkspaceOperations] adapter from the runtime context.
     */
    private fun context(
        runId: String,
        opId: OpId,
        workspace: Path,
        sink: InMemoryEventStore,
    ): CanonicalRuntimeContext =
        CanonicalRuntimeContext(
            opId = opId,
            runId = runId,
            stageName = "candidate",
            stageIndex = opId.stageIndex,
            stepIndex = opId.stepIndex,
            shOptions = ShOptions.EMPTY.copy(workspaceRoot = workspace),
            controlDirRoot = Files.createTempDirectory("$runId-ctrl-"),
            eventSink = sink,
        )
}
