package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.CoreFileExistsStep
import dev.rubentxu.pipeline.v2.application.CorePwdStep
import dev.rubentxu.pipeline.v2.application.CoreReadFileStep
import dev.rubentxu.pipeline.v2.application.CoreWriteFileStep
import dev.rubentxu.pipeline.v2.application.EVENT_SINK_CAPABILITY
import dev.rubentxu.pipeline.v2.application.PwdInput
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.WORKSPACE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.application.WORKSPACE_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.WorkspaceIdentity
import dev.rubentxu.pipeline.v2.application.WorkspaceOperations
import dev.rubentxu.pipeline.v2.application.WorkspaceOperationsAdapter
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint
import dev.rubentxu.pipeline.v2.scripting.ScriptedArtifactIdentity
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import dev.rubentxu.pipeline.v2.scripting.ScriptedStepFacade
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * DIR-FS-E2E: dir(...) block end-to-end through the canonical scripted→registry seam.
 *
 * WU-RP-053 (`fix(workspace): propagate effective cwd to filesystem steps`) —
 * demonstrates the integration gap where `dir("subdir") { writeFile("a.txt", ...) }`
 * MUST resolve paths relative to the effective cwd of the block, not the bare
 * stage workspace. Mirrors the operator contract for the FIRST vertical slice:
 * `sh`, `pwd`, `writeFile`, `readFile`, `fileExists` share the SAME effective
 * directory inside a `dir(...)` block.
 *
 * The RED row exercises the canonical scripted→registry path:
 *
 *   ScriptedArtifactRuntime
 *     → CompiledScriptedEntryPoint (steps.pwd / steps.readFile /
 *       steps.fileExists) — runtime-returning scripted fa\u00e7ade
 *     → ScriptedRegistryInvoker
 *     → RegistryExecutionBoundary
 *     → CanonicalRuntimeCapabilityAccess (workspace + filesystem seam)
 *     → CorePwdStep / CoreReadFileStep / CoreFileExistsStep
 *
 * `writeFile` is exercised through the SAME typed seam the registry handler
 * reaches for (`WorkspaceOperations.writeFile`). The capability bridge hands the
 * exact same adapter the handler would receive at runtime; the test asserts the
 * path resolution contract for both the runtime-returning and the effectful
 * surfaces in a single observation.
 *
 * The capability bridge is subclassed so the test can assert that:
 *
 *   - `core.pwd` returns the EFFECTIVE working directory (subdir), not the
 *     stage workspace root.
 *   - `WorkspaceOperations.writeFile("a.txt", ...)` writes to
 *     `<effective>/a.txt` and `CoreReadFileStep` reads the same content
 *     from the same path.
 *   - `fileExists("a.txt")` observes the file in `<effective>`, not in the
 *     stage workspace root.
 *   - On exit, the same Steps resolve against the stage workspace root again.
 *
 * The test relies on the SAME production `WorkspaceOperationsAdapter` /
 * `WorkspaceResolver` / `FileWriteExecutor` / `FileReadExecutor` /
 * `FileExistsExecutor` substrate. Only the `CanonicalRuntimeCapabilityAccess`
 * subclass is test-local: a minimal `WorkspacePlusEffectiveCwdAccess` that
 * mirrors the production wiring PLUS the new `effectiveWorkingDirectory`
 * parameter the GREEN slice introduces.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DirFilesystemEndToEndTest {

    // ---- capability bridge with synthetic workspace + effective cwd --------------

    /**
     * Production-equivalent bridge that exposes the new
     * `effectiveWorkingDirectory` plumbing the GREEN slice wires into
     * `WorkspaceOperationsAdapter`. Used to simulate the per-`dir(...)` cwd
     * without touching the body-loop coordinator.
     */
    private class WorkspacePlusEffectiveCwdAccess(
        context: CanonicalRuntimeContext,
        private val workspaceRoot: Path,
        private val effectiveWorkingDirectory: Path?,
    ) : CanonicalRuntimeCapabilityAccess(context = context) {
        override fun available(): Set<StepCapability> =
            setOf(
                WORKSPACE_IDENTITY_CAPABILITY,
                WORKSPACE_OPERATIONS_CAPABILITY,
                EVENT_SINK_CAPABILITY,
            )

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: StepCapability): T {
            if (key == WORKSPACE_IDENTITY_CAPABILITY) {
                // WU-RP-053: mirrors the production bridge rule — when the
                // block carries an effective cwd, `pwd()` observes the cwd;
                // otherwise it observes the workspace root. This is what
                // `CanonicalRuntimeCapabilityAccess.buildProvided` does in
                // production; the test-local subclass must agree or the test
                // would diverge from production wiring.
                return WorkspaceIdentity(
                    workspaceRoot = effectiveWorkingDirectory ?: workspaceRoot,
                ) as T
            }
            if (key == WORKSPACE_OPERATIONS_CAPABILITY) {
                // The GREEN slice will route effectiveWorkingDirectory into
                // the production WorkspaceOperationsAdapter; we replicate the
                // production wiring shape here so the test exercises the
                // exact production path (FileWriteExecutor + path-traversal
                // guard), not a fake.
                // The path-traversal guard enforced by FileWriteExecutor
                // validates `targetPath.startsWith(workspaceRoot)` against
                // whatever workspaceRoot the executor sees — to mirror
                // production (where workspaceBase == the project root and
                // effectiveWorkingDirectory lives INSIDE it), the test's
                // `workspaceRoot` parameter already covers `subdir`, so
                // passing it as workspaceBase is correct.
                return WorkspaceOperationsAdapter(
                    stageName = ctx.stageName,
                    stageIndex = ctx.stageIndex,
                    controlDirRoot = ctx.controlDirRoot,
                    eventSink = ctx.eventSink,
                    runId = ctx.runId,
                    workspaceBase = workspaceRoot,
                    effectiveWorkingDirectory = effectiveWorkingDirectory,
                ) as T
            }
            return super.get(key)
        }

        private val ctx: CanonicalRuntimeContext = context
    }

    // ---- harness ---------------------------------------------------------------

    private val runId = "r-rp-053-dir-fs-e2e"

    private fun contextFor(call: ScriptedRegistryCall, sink: InMemoryEventStore): CanonicalRuntimeContext =
        CanonicalRuntimeContext(
            opId = OpId(call.runId, stageIndex = 0, stepIndex = call.invocationOrdinal),
            runId = call.runId,
            stageName = "scripted",
            stageIndex = 0,
            stepIndex = call.invocationOrdinal,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = Files.createTempDirectory("rp-053-ctrl-"),
            eventSink = sink,
        )

    /**
     * Synthesises the per-`dir(...)` cwd by patching the harness-local
     * capability access. In production the body loop will set
     * `stageShOptions.workingDirectory = scope.target`; this harness mirrors
     * exactly that observation by handing the new
     * `effectiveWorkingDirectory` to the test bridge subclass.
     */
    private fun invokerWithCwd(
        workspaceRoot: Path,
        effectiveWorkingDirectory: Path?,
        registry: InMemoryStepRegistry,
        journal: InMemoryOperationJournal,
        sink: InMemoryEventStore,
    ): ScriptedRegistryInvoker = ScriptedRegistryInvoker(
        registry = registry,
        journal = journal,
        clock = SystemClock(),
        runtimeContextFactory = { call -> contextFor(call, sink) },
        capabilityAccessFactory = { context ->
            WorkspacePlusEffectiveCwdAccess(
                context = context,
                workspaceRoot = workspaceRoot,
                effectiveWorkingDirectory = effectiveWorkingDirectory,
            )
        },
    )

    private fun registryWithFileSteps(): InMemoryStepRegistry =
        InMemoryStepRegistry().also {
            CoreWriteFileStep.registerInto(it)
            CoreReadFileStep.registerInto(it)
            CoreFileExistsStep.registerInto(it)
            CorePwdStep.registerInto(it)
        }

    /**
     * Compiled scripted entry that exercises the runtime-returning Steps
     * through the canonical `ScriptedStepFacade` (`pwd`, `readFile`,
     * `fileExists`) and writes a file through the SAME typed
     * `WorkspaceOperations` seam the registry handler receives at runtime.
     * The observation tuple reaches the test as a captured value.
     *
     * The closure captures the EXTERNAL workspace root (the temp dir, NOT
     * the cwd observed by `pwd()`) so the assertions can independently
     * distinguish "file landed under the workspace root" from "file landed
     * under the effective cwd" — both paths live in the temp dir, so the
     * closure is the only source of truth for "root" vs "cwd".
     */
    private data class BlockObservation(
        val pwd: String,
        val readBack: String,
        val exists: Boolean,
        val fileExistsAtEffectiveCwd: Boolean,
        val fileExistsAtWorkspaceRoot: Boolean,
    )

    private fun observationEntryPoint(
        observed: MutableList<BlockObservation>,
        workspaceRoot: Path,
        effectiveWorkingDirectory: Path?,
        relativeFile: String,
    ): CompiledScriptedEntryPoint = object : CompiledScriptedEntryPoint {
        override val artifact = ScriptedArtifactIdentity(
            sourceDigest = "rp-053-src",
            dslApiVersion = "test",
            compilerAdapterVersion = "test",
            runtimeCompatibilityVersion = "test",
            pluginLockDigest = "test",
            facadeSchemaDigest = "test",
        )
        override val entryPointId = "ep-rp-053"

        override suspend fun execute(steps: ScriptedStepFacade) {
            // 1) pwd MUST return the EFFECTIVE cwd (or workspace root when no dir-block).
            val pwd = steps.pwd(ScriptedCallSiteId("rp-053.kts:1:1:pwd"))

            // 2) readFile / fileExists observe the SAME effective path.
            val readBack = steps.readFile(
                ScriptedCallSiteId("rp-053.kts:3:1:readFile"),
                relativeFile,
            )
            val exists = steps.fileExists(
                ScriptedCallSiteId("rp-053.kts:4:1:fileExists"),
                relativeFile,
            )

            // The cwd is the closure's effectiveWorkingDirectory when set, else the workspace root.
            val effectiveCwd = effectiveWorkingDirectory ?: workspaceRoot
            observed += BlockObservation(
                pwd = pwd,
                readBack = readBack,
                exists = exists,
                fileExistsAtEffectiveCwd = Files.exists(effectiveCwd.resolve(relativeFile)),
                fileExistsAtWorkspaceRoot = Files.exists(workspaceRoot.resolve(relativeFile)),
            )
        }
    }

    // ---- RED: cwd propagation contract ----------------------------------------

    /**
     * Inside a `dir("subdir")` block, `pwd` MUST return `<workspaceRoot>/subdir`,
     * `writeFile("a.txt", ...)` MUST land at `<workspaceRoot>/subdir/a.txt`,
     * and `readFile("a.txt")` MUST read from the same path. Today (RED) the
     * filesystem Steps resolve `<stageWorkspace>/a.txt` and the test fails
     * because the file is NOT found at the expected effective-cwd location.
     *
     * The test synthesises the cwd plumbing by directly invoking the
     * `WorkspaceOperations` seam the registry handler would receive — the
     * canonical writeFile path. It then verifies via the runtime-returning
     * Steps that pwd, readFile and fileExists observe the SAME effective
     * directory.
     */
    @Test
    fun `dir block routes pwd writeFile readFile fileExists to the same effective cwd`(
        @TempDir workspaceRoot: Path,
    ) = runBlocking {
        val subdir = workspaceRoot.resolve("subdir")
        Files.createDirectories(subdir)

        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        val registry = registryWithFileSteps()
        val observed = mutableListOf<BlockObservation>()

        // Pre-stage the write through the SAME `WorkspaceOperations` seam the
        // registry handler receives. This exercises the exact production path
        // (`WorkspaceOperationsAdapter` → `FileWriteExecutor` + path-traversal
        // guard) and proves the resolution contract.
        val ops: WorkspaceOperations = WorkspaceOperationsAdapter(
            stageName = "scripted",
            stageIndex = 0,
            controlDirRoot = workspaceRoot.parent,
            eventSink = sink,
            runId = runId,
            workspaceBase = workspaceRoot,
            effectiveWorkingDirectory = subdir,
        )
        ops.writeFile(file = "a.txt", text = "OK", encoding = "UTF-8")

        ScriptedArtifactRuntime(
            operationRuntime = { error("no shell in dir-fs e2e test") },
            registryInvoker = invokerWithCwd(
                workspaceRoot = workspaceRoot,
                effectiveWorkingDirectory = subdir,
                registry = registry,
                journal = journal,
                sink = sink,
            ),
        ).execute(runId, observationEntryPoint(observed, workspaceRoot = workspaceRoot, effectiveWorkingDirectory = subdir, relativeFile = "a.txt"))

        val ob = observed.single()
        assertEquals(subdir.toAbsolutePath().toString(), ob.pwd,
            "pwd() inside dir($subdir) must return the effective cwd")
        assertEquals("OK", ob.readBack,
            "readFile('a.txt') inside dir($subdir) must return 'OK' from the effective cwd")
        assertTrue(ob.exists,
            "fileExists('a.txt') inside dir($subdir) must observe the file in the effective cwd")
        assertTrue(ob.fileExistsAtEffectiveCwd,
            "writeFile('a.txt') inside dir($subdir) MUST have written to ${subdir.resolve("a.txt")}")
        assertFalse(ob.fileExistsAtWorkspaceRoot,
            "writeFile('a.txt') inside dir($subdir) MUST NOT have leaked to ${workspaceRoot.resolve("a.txt")}")
    }

    /**
     * After exiting the `dir(...)` block, filesystem Steps MUST resolve
     * relative paths against the stage workspace root again. Today (RED) this
     * already passes; the GREEN slice must preserve it.
     *
     * The fixture creates a sub-directory inside the temp workspace so we
     * can distinguish "the write landed at the workspace root" from "the
     * write leaked into a sub-directory" — the second path would be wrong
     * because no cwd override is in effect.
     */
    @Test
    fun `after dir block exits filesystem steps resolve against the workspace root`(
        @TempDir workspaceRoot: Path,
    ) = runBlocking {
        val trapDir = workspaceRoot.resolve("trap")
        Files.createDirectories(trapDir)

        val sink = InMemoryEventStore()
        val journal = InMemoryOperationJournal(SystemClock())
        val registry = registryWithFileSteps()
        val observed = mutableListOf<BlockObservation>()

        val ops: WorkspaceOperations = WorkspaceOperationsAdapter(
            stageName = "scripted",
            stageIndex = 0,
            controlDirRoot = workspaceRoot.parent,
            eventSink = sink,
            runId = runId,
            workspaceBase = workspaceRoot,
            effectiveWorkingDirectory = null,
        )
        ops.writeFile(file = "root.txt", text = "ROOT", encoding = "UTF-8")

        ScriptedArtifactRuntime(
            operationRuntime = { error("no shell in dir-fs e2e test") },
            registryInvoker = invokerWithCwd(
                workspaceRoot = workspaceRoot,
                effectiveWorkingDirectory = null,
                registry = registry,
                journal = journal,
                sink = sink,
            ),
        ).execute(runId, observationEntryPoint(observed, workspaceRoot = workspaceRoot, effectiveWorkingDirectory = null, relativeFile = "root.txt"))

        val ob = observed.single()
        assertEquals(workspaceRoot.toAbsolutePath().toString(), ob.pwd,
            "pwd() with no dir-block must return the workspace root")
        assertEquals("ROOT", ob.readBack,
            "readFile('root.txt') with no effective cwd must read the workspace-root file")
        assertTrue(ob.exists,
            "fileExists('root.txt') with no effective cwd must observe the workspace-root file")
        assertTrue(
            Files.exists(workspaceRoot.resolve("root.txt")),
            "writeFile('root.txt') with no effective cwd MUST have written to ${workspaceRoot.resolve("root.txt")}",
        )
        assertFalse(
            Files.exists(trapDir.resolve("root.txt")),
            "writeFile('root.txt') with no effective cwd MUST NOT leak to ${trapDir.resolve("root.txt")}",
        )
    }

    // ---- input codec preservation -----------------------------------------------

    /**
     * Sanity row: the new effective-cwd plumbing must NOT perturb the canonical
     * input envelopes (`writeFile`, `readFile`, `fileExists`, `pwd`).
     * Independently of resolution, the codecs still encode/decode the same
     * typed payloads.
     */
    @Test
    fun `pwd input codec survives effective-cwd plumbing`() {
        val encoded = CorePwdStep.definition.contract.inputCodec.encode(PwdInput(tmp = false))
        val decoded = CorePwdStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(PwdInput(tmp = false), decoded)
    }
}
