package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalCleanWsNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalCleanWsDispatchContext
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.CleanWsOperationsAdapter
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepNode
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.WsCleaned
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * S2-A10 / G2 — Differential Contract Freeze for `core.cleanWs`.
 *
 * Drives the SAME durable input envelope through BOTH authorities on equivalent
 * workspaces and proves the observations are equivalent:
 *
 * 1. LEGACY path: `CanonicalCoreStepDecoder.decode` → `CanonicalCleanWsNodeDispatcher`
 *    (the production authority today — `core.cleanWs` is in LEGACY_PLUGIN_IDS).
 * 2. REGISTRY path: `CoreCleanWsStep` handler via the typed
 *    `CleanWsOperations` capability seam (the candidate registered in G1).
 *
 * Differential invariants frozen here:
 * - both paths consume the same dsl-v1 input envelope
 *   `{"kind":"cleanWs","deleteDirs":<bool>,"patterns":[...]}`;
 * - both paths emit exactly one `WsCleaned` event per execution;
 * - both paths return a Success-shaped typed outcome;
 * - counts (deletedFiles/deletedDirs) and sha256 shape (64-hex) are equivalent
 *   for equivalent workspace states;
 * - patterns are echoed identically in the event and the typed output.
 *
 * NOTE (single-substrate equivalence by construction): both paths execute the
 * SAME `CleanWsExecutor` SDK substrate over a `WorkspaceResolver(controlDirRoot)`,
 * so the differential proves the wiring (capability admission, adapter binding,
 * codec envelopes), not two independent implementations.
 *
 * Idempotence law (differential): after a first clean, re-cleaning the same
 * workspace through either authority yields deletedFiles=0 / deletedDirs=0
 * with a non-empty sha256 (the `.cleaned` MEMOIZED marker path).
 */
@Timeout(30)
class CoreCleanWsDifferentialContractTest {

    private fun newWorkspace(): Path = Files.createTempDirectory("cleanws-diff-").toAbsolutePath()

    private fun legacyContext(runId: String, workspace: Path, sink: InMemoryEventStore) =
        CanonicalCleanWsDispatchContext(
            runId = runId,
            stageName = "test",
            stageIndex = 0,
            stepIndex = 0,
            controlDirRoot = workspace,
            eventSink = sink,
        )

    private fun registryStepHandlerContext(
        runId: String,
        workspace: Path,
        sink: InMemoryEventStore,
    ): StepHandlerContext =
        StepHandlerContext(
            runId = dev.rubentxu.pipeline.v2.domain.RunId(runId),
            stepIndex = 0,
            capabilities = object : dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess {
                override fun available(): Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> =
                    setOf(CLEAN_WS_OPERATIONS_CAPABILITY)

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(
                    key: dev.rubentxu.pipeline.v2.domain.step.StepCapability,
                ): T = when (key) {
                    CLEAN_WS_OPERATIONS_CAPABILITY -> CleanWsOperationsAdapter(
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

    private fun populatedWorkspace(workspace: Path) {
        val ws = workspace.resolve("workspace/test-0")
        Files.createDirectories(ws.resolve("subdir"))
        Files.writeString(ws.resolve("file.txt"), "content")
        Files.writeString(ws.resolve("subdir").resolve("nested.txt"), "nested")
    }

    private fun capturedWsCleaned(sink: InMemoryEventStore, runId: String): List<WsCleaned> =
        sink.eventsFor(runId).toList().filterIsInstance<WsCleaned>()

    // ===== 1. same envelope through both authorities on equivalent workspaces =====

    @Test
    fun `differential — legacy dispatcher and registry handler emit equivalent WsCleaned for the same input`() {
        val input = CleanWsInput(deleteDirs = true, patterns = listOf("*.txt"))

        // LEGACY authority: decode from the durable node envelope, dispatch.
        val legacyWorkspace = newWorkspace()
        populatedWorkspace(legacyWorkspace)
        val legacySink = InMemoryEventStore()
        val legacyRunId = "cleanws-diff-legacy"
        val legacyNode: StepNode = dev.rubentxu.pipeline.v2.domain.OpaqueStepNode(
            id = StepId("test/cleanWs"),
            pluginStepId = CoreCleanWsStep.KEY,
            payload = VersionedStepPayload(
                schemaVersion = "dsl-v1",
                encoded = """{"kind":"cleanWs","deleteDirs":true,"patterns":["*.txt"]}""",
            ),
        )
        val legacyCommand = CanonicalCoreStepDecoder.decode(legacyNode)
        assertTrue(legacyCommand is CanonicalCoreStepCommand.CleanWs)
        val legacyOutcome = runBlocking {
            CanonicalCleanWsNodeDispatcher().dispatch(
                legacyCommand as CanonicalCoreStepCommand.CleanWs,
                legacyContext(legacyRunId, legacyWorkspace, legacySink),
            )
        }

        // REGISTRY candidate: decode from the SAME envelope via the input codec,
        // execute the handler over the typed capability seam.
        val registryWorkspace = newWorkspace()
        populatedWorkspace(registryWorkspace)
        val registrySink = InMemoryEventStore()
        val registryRunId = "cleanws-diff-registry"
        val registryInput = CoreCleanWsStep.definition.contract.inputCodec.decode(
            dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(
                """{"kind":"cleanWs","deleteDirs":true,"patterns":["*.txt"]}""",
            ),
        )
        assertEquals(input, registryInput, "input codec decode of the legacy envelope MUST match the typed input")
        val registryOutput = runBlocking {
            CoreCleanWsStep.definition.handler.execute(
                registryInput,
                registryStepHandlerContext(registryRunId, registryWorkspace, registrySink),
            )
        }

        // Typed outcome equivalence.
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.StepOutcome.Success,
            legacyOutcome,
            "legacy authority must succeed",
        )
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.StepOutcome.Success,
            registryOutput.outcome,
            "registry candidate must succeed",
        )

        // Observation equivalence: exactly one WsCleaned per path.
        val legacyEvents = capturedWsCleaned(legacySink, legacyRunId)
        val registryEvents = capturedWsCleaned(registrySink, registryRunId)
        assertEquals(1, legacyEvents.size, "legacy path emits exactly one WsCleaned")
        assertEquals(1, registryEvents.size, "registry path emits exactly one WsCleaned")

        val legacyEvent = legacyEvents.single()
        val registryEvent = registryEvents.single()
        assertEquals(legacyEvent.kind, registryEvent.kind, "both authorities emit the WsCleaned kind")
        assertEquals(legacyEvent.patterns, registryEvent.patterns, "patterns echoed identically")
        assertEquals(
            legacyEvent.deletedFiles,
            registryEvent.deletedFiles,
            "equivalent workspaces must produce identical deletedFiles under pattern '*.txt'",
        )
        assertEquals(
            legacyEvent.deletedDirs,
            registryEvent.deletedDirs,
            "equivalent workspaces must produce identical deletedDirs under pattern '*.txt' + deleteDirs",
        )
        assertEquals(64, registryEvent.sha256.length, "registry WsCleaned sha256 must be 64-hex")
        assertEquals(64, legacyEvent.sha256.length, "legacy WsCleaned sha256 must be 64-hex")

        // The typed output mirrors the registry event.
        assertEquals(registryEvent.deletedFiles, registryOutput.deletedFiles)
        assertEquals(registryEvent.deletedDirs, registryOutput.deletedDirs)
        assertEquals(registryEvent.patterns, registryOutput.patterns)
        assertEquals(registryEvent.sha256, registryOutput.sha256)

        // Actual cleanup happened under the pattern: *.txt files are gone on both paths.
        assertTrue(
            !Files.exists(registryWorkspace.resolve("workspace/test-0/file.txt")),
            "registry path must delete file.txt (matches *.txt)",
        )
        assertTrue(
            !Files.exists(legacyWorkspace.resolve("workspace/test-0/file.txt")),
            "legacy path must delete file.txt (matches *.txt)",
        )
    }

    // ===== 2. bare cleanWs() envelope: defaults and delete-all semantics =====

    @Test
    fun `differential — bare cleanWs envelope delete-all is equivalent across authorities`() {
        // LEGACY
        val legacyWorkspace = newWorkspace()
        populatedWorkspace(legacyWorkspace)
        val legacySink = InMemoryEventStore()
        val legacyNode: StepNode = dev.rubentxu.pipeline.v2.domain.OpaqueStepNode(
            id = StepId("test/cleanWs"),
            pluginStepId = CoreCleanWsStep.KEY,
            payload = VersionedStepPayload(
                schemaVersion = "dsl-v1",
                encoded = """{"kind":"cleanWs","deleteDirs":true,"patterns":[]}""",
            ),
        )
        val legacyCommand = CanonicalCoreStepDecoder.decode(legacyNode)
        runBlocking {
            CanonicalCleanWsNodeDispatcher().dispatch(
                legacyCommand as CanonicalCoreStepCommand.CleanWs,
                legacyContext("cleanws-diff2-legacy", legacyWorkspace, legacySink),
            )
        }

        // REGISTRY
        val registryWorkspace = newWorkspace()
        populatedWorkspace(registryWorkspace)
        val registrySink = InMemoryEventStore()
        val registryOutput = runBlocking {
            CoreCleanWsStep.definition.handler.execute(
                CleanWsInput(deleteDirs = true, patterns = emptyList()),
                registryStepHandlerContext("cleanws-diff2-registry", registryWorkspace, registrySink),
            )
        }

        val legacyEvent = capturedWsCleaned(legacySink, "cleanws-diff2-legacy").single()
        assertTrue(legacyEvent.deletedFiles >= 2, "delete-all must remove the populated files")
        assertTrue(registryOutput.deletedFiles >= 2, "registry delete-all must remove the populated files")
        assertEquals(
            legacyEvent.deletedFiles,
            registryOutput.deletedFiles,
            "delete-all counts must be identical across authorities",
        )
        assertEquals(
            legacyEvent.deletedDirs,
            registryOutput.deletedDirs,
            "deleteDirs=true empty-parent counts must be identical across authorities",
        )
    }

    // ===== 3. idempotence law: re-cleaning an already-clean workspace =====

    @Test
    fun `idempotence — re-cleaning an already-clean workspace succeeds with zero counts (registry path)`() {
        val workspace = newWorkspace()
        populatedWorkspace(workspace)
        val sink = InMemoryEventStore()
        val runId = "cleanws-diff-idem"
        val ctx = registryStepHandlerContext(runId, workspace, sink)

        val first = runBlocking {
            CoreCleanWsStep.definition.handler.execute(CleanWsInput(deleteDirs = true), ctx)
        }
        assertTrue(first.deletedFiles >= 2, "first clean deletes the populated files")

        val second = runBlocking {
            CoreCleanWsStep.definition.handler.execute(CleanWsInput(deleteDirs = true), ctx)
        }
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.StepOutcome.Success,
            second.outcome,
            "re-cleaning an already-clean workspace MUST succeed",
        )
        assertEquals(
            0,
            second.deletedFiles,
            "re-clean MUST observe deletedFiles=0 (idempotence law, deleteDir deletedCount=0 shape)",
        )
        assertEquals(0, second.deletedDirs, "re-clean MUST observe deletedDirs=0")
        assertTrue(second.sha256.isNotEmpty(), "MEMOIZED marker sha256 is reproduced on re-clean")

        // Exactly one WsCleaned per execution — no duplicate events.
        assertEquals(2, capturedWsCleaned(sink, runId).size, "exactly one WsCleaned per execution")
    }

    // ===== 4. envelope freeze: codec emits the exact legacy wire shape =====

    @Test
    fun `freeze — input codec emits the legacy dsl-v1 envelope byte-shape for cleanWs`() {
        val encoded = CoreCleanWsStep.definition.contract.inputCodec.encode(
            CleanWsInput(deleteDirs = true, patterns = listOf("*.txt")),
        )
        assertEquals(
            """{"kind":"cleanWs","deleteDirs":true,"patterns":["*.txt"]}""",
            encoded.value,
            "input codec envelope must be byte-identical to the legacy decoder contract",
        )
        // And the legacy decoder accepts it verbatim.
        val node: StepNode = dev.rubentxu.pipeline.v2.domain.OpaqueStepNode(
            id = StepId("test/cleanWs"),
            pluginStepId = CoreCleanWsStep.KEY,
            payload = VersionedStepPayload(schemaVersion = "dsl-v1", encoded = encoded.value),
        )
        val command = CanonicalCoreStepDecoder.decode(node)
        assertTrue(command is CanonicalCoreStepCommand.CleanWs)
        assertEquals(
            CanonicalCoreStepCommand.CleanWs(deleteDirs = true, patterns = listOf("*.txt")),
            command,
            "the registry envelope must decode identically through the legacy authority (pre-G4 freeze)",
        )
    }

    // ===== 5. capability bridge exposes the capability from a real runtime context =====

    @Test
    fun `capability bridge — real CanonicalRuntimeCapabilityContext exposes CLEAN_WS_OPERATIONS and admission Ready`() {
        val workspace = newWorkspace()
        val context = CanonicalRuntimeContext(
            opId = OpId("cleanws-diff-bridge", 0, 0),
            runId = "cleanws-diff-bridge",
            stageName = "test",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = workspace,
            eventSink = InMemoryEventStore(),
        )
        val access = CanonicalRuntimeCapabilityAccess(context)
        assertTrue(
            CLEAN_WS_OPERATIONS_CAPABILITY in access.available(),
            "the bridge must expose CLEAN_WS_OPERATIONS_CAPABILITY when controlDirRoot != null",
        )
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = CoreStepRegistryFactory.registry(),
                key = CoreCleanWsStep.KEY,
                encodedInput = CoreCleanWsStep.definition.contract.inputCodec.encode(CleanWsInput()),
                availableCapabilities = access.available(),
            )
            val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
            val prepared = assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)
            val result = RegistryExecutionBoundary.coexecute(prepared, context)
            assertEquals(
                dev.rubentxu.pipeline.v2.domain.StepOutcome.Success,
                result.outcome,
                "boundary coexecute over the real bridge must succeed",
            )
        }
    }
}
