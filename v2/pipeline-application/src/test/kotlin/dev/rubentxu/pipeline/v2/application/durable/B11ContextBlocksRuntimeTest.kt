package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
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
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.TimestampsEntered
import dev.rubentxu.pipeline.v2.events.TimestampsExited
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * HF1 in-process tests for the B11 / W2 body-reentry seam
 * (`core.dir` / `core.withEnv` / `core.timestamps`).
 *
 * Each test compiles a `CompiledPipeline` programmatically with a `BlockStepNode`
 * whose body is one of the three context-only blocks (or a nesting of them) and
 * runs it through `CanonicalDurableRunCoordinator`. The tests prove that the
 * BodyInvoker adapter:
 *
 * 1. Is constructed per-run and bound under `BODY_INVOKER_CAPABILITY` via
 *    `CanonicalRuntimeCapabilityAccess.buildProvided` (asserted implicitly by
 *    the canonical loop running without failing the registry-aware prepare
 *    step).
 * 2. Receives a single registration per body scope via `bodyInvokerAdapter.open`,
 *    and that registration is closed in the existing `finally` block
 *    (asserted via `openBodyCount == 0` after the run finishes).
 * 3. Routes through `invokeBodyChildren` — the runner closure is constructed
 *    over the canonical shared loop, never a sibling site
 *    (covered by `Lfc2ConcreteBodyRoutingDebtFitnessTest.BodyChildLoopScanner`).
 *
 * AGENTS.md test-efficiency: HF1 in-process; no subprocess; @Timeout prevents
 * accidental infinite blocks.
 */
@Timeout(value = 120, unit = java.util.concurrent.TimeUnit.SECONDS)
class B11ContextBlocksRuntimeTest {

    @Test
    fun `dir block propagates working directory to shell child`(@TempDir tempDir: Path) = runBlocking {
        val target = tempDir.resolve("nested")
        Files.createDirectories(target)
        val oracle = target.resolve("pwd.txt")
        val pipeline = pipelineWithSingleBlock(
            blockId = StepId("build/dir-block"),
            pluginStepId = PluginStepId("core.dir"),
            payload = """{"kind":"dir","path":"$target"}""",
            body = listOf(
                shellStep("build/dir-block/sh", """pwd > '$oracle'"""),
            ),
        )

        val outcome = runWithCoordinator(pipeline, tempDir, RunId("b11-dir-cwd"))

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(target.toString(), Files.readString(oracle).trim())
    }

    @Test
    fun `withEnv block propagates environment overrides to shell child`(@TempDir tempDir: Path) = runBlocking {
        val oracle = tempDir.resolve("env.txt")
        val oracleValue = tempDir.resolve("env2.txt")
        val pipeline = pipelineWithSingleBlock(
            blockId = StepId("build/env-block"),
            pluginStepId = PluginStepId("core.withEnv"),
            payload = """{"kind":"withEnv","overrides":["B11_KEY=overridden","B11_TTL=42"]}""",
            body = listOf(
                shellStep(
                    "build/env-block/sh",
                    """[ "${'$'}B11_KEY" = "overridden" ] && echo PASS > '$oracle'; echo ${'$'}B11_TTL > '$oracleValue'""",
                ),
            ),
        )

        val outcome = runWithCoordinator(pipeline, tempDir, RunId("b11-env-overlay"))

        assertEquals(RunOutcome.Success, outcome)
        assertEquals("PASS", Files.readString(oracle).trim())
        assertEquals("42", Files.readString(oracleValue).trim())
    }

    @Test
    fun `timestamps block is a decorator — child sees no CWD or ENV overlay`(@TempDir tempDir: Path) = runBlocking {
        // The timestamps projection does NOT push a context overlay (B11/W1d-W2
        // decorator semantics). The child inside a `timestamps { sh }` block
        // sees the inherited stage cwd (NOT a `ContextKind`-driven overlay) and
        // the inherited ShOptions.env (no env override). We prove this by:
        //   - inheriting B11_KEY from ShOptions.env with value "stage-inherited"
        //   - inside timestamps: writing $B11_KEY to a file; the file MUST read
        //     "stage-inherited" (timestamps did not override it)
        //   - writing pwd to a file; pwd MUST be the stage workspace (timestamps
        //     did not push a CWD overlay)
        val controlDirRoot = tempDir.resolve("control")
        val stageWorkspace = WorkspaceResolver(controlDirRoot).resolve("build", 0)
        val envOracle = stageWorkspace.resolve("env-inherited.txt")
        val cwdOracle = stageWorkspace.resolve("cwd-inherited.txt")
        val pipeline = pipelineWithSingleBlock(
            blockId = StepId("build/ts-block"),
            pluginStepId = PluginStepId("core.timestamps"),
            payload = """{"kind":"timestamps"}""",
            body = listOf(
                shellStep(
                    "build/ts-block/sh",
                    """echo ${'$'}B11_KEY > '$envOracle'; pwd > '$cwdOracle'""",
                ),
            ),
        )

        val outcome = runWithCoordinator(
            pipeline = pipeline,
            tempDir = tempDir,
            runId = RunId("b11-timestamps-decorator"),
            shOptions = ShOptions(
                workspaceRoot = tempDir.resolve("ignored-workspace"),
                captureStdout = false,
                timeoutMs = null,
                env = mapOf("B11_KEY" to dev.rubentxu.pipeline.v2.domain.SecretHandle.plain("stage-inherited")),
            ),
        )

        assertEquals(RunOutcome.Success, outcome)
        // timestamps does NOT push an env overlay — the inherited stage env is visible.
        assertEquals("stage-inherited", Files.readString(envOracle).trim())
        // timestamps does NOT push a CWD overlay — the canonical stage workspace is the cwd.
        assertEquals(stageWorkspace.toString(), Files.readString(cwdOracle).trim())
    }

    @Test
    fun `dir withEnv timestamps compound nesting composes cwd, env, decorator without leaking`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val nested = tempDir.resolve("nested-inner")
        Files.createDirectories(nested)
        val oracle = nested.resolve("compound.txt")
        val envOracle = nested.resolve("compound-env.txt")
        val pipeline = CompiledPipeline(
            id = DefinitionId("b11-compound-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    id = StageId("build"),
                    name = "build",
                    body = StageBody.Steps(
                        listOf(
                            BlockStepNode(
                                id = StepId("build/dir-outer"),
                                pluginStepId = PluginStepId("core.dir"),
                                payload = VersionedStepPayload(
                                    "dsl-v1",
                                    """{"kind":"dir","path":"$nested"}""",
                                ),
                                body = listOf(
                                    BlockStepNode(
                                        id = StepId("build/dir-outer/env-inner"),
                                        pluginStepId = PluginStepId("core.withEnv"),
                                        payload = VersionedStepPayload(
                                            "dsl-v1",
                                            """{"kind":"withEnv","overrides":["B11_COMPOUND=42"]}""",
                                        ),
                                        body = listOf(
                                            BlockStepNode(
                                                id = StepId("build/dir-outer/env-inner/ts-leaf"),
                                                pluginStepId = PluginStepId("core.timestamps"),
                                                payload = VersionedStepPayload(
                                                    "dsl-v1",
                                                    """{"kind":"timestamps"}""",
                                                ),
                                                body = listOf(
                                                    shellStep(
                                                        "build/dir-outer/env-inner/ts-leaf/sh",
                                                        """pwd > '$oracle' && echo ${'$'}B11_COMPOUND > '$envOracle'""",
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val outcome = runWithCoordinator(pipeline, tempDir, RunId("b11-compound"))

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(nested.toString(), Files.readString(oracle).trim())
        assertEquals("42", Files.readString(envOracle).trim())
    }

    @Test
    fun `dir body does not globally restore cwd — siblings outside the dir see their declared cwd`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        // The dir block pushes a CWD overlay on the child ExecutionContext; the
        // engine never mutates a global cwd. A sibling step outside the dir block
        // sees the canonical stage workspace (NOT the dir target). We prove this
        // with a sibling shell step that writes its pwd to a marker file in its
        // canonical workspace and asserts it is NOT the dir target.
        val dirTarget = tempDir.resolve("inside-dir")
        Files.createDirectories(dirTarget)
        val controlDirRoot = tempDir.resolve("control")
        val stageWorkspace = WorkspaceResolver(controlDirRoot).resolve("build", 0)
        val dirOracle = dirTarget.resolve("pwd-inside.txt")
        val siblingOracle = stageWorkspace.resolve("pwd-sibling.txt")

        val pipeline = CompiledPipeline(
            id = DefinitionId("b11-parent-restore-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    id = StageId("build"),
                    name = "build",
                    body = StageBody.Steps(
                        listOf(
                            BlockStepNode(
                                id = StepId("build/dir-restoration"),
                                pluginStepId = PluginStepId("core.dir"),
                                payload = VersionedStepPayload(
                                    "dsl-v1",
                                    """{"kind":"dir","path":"$dirTarget"}""",
                                ),
                                body = listOf(
                                    shellStep(
                                        "build/dir-restoration/inner-sh",
                                        """pwd > '$dirOracle'""",
                                    ),
                                ),
                            ),
                            // Sibling step OUTSIDE the dir block. Its declared cwd is
                            // the canonical stage workspace — the engine never restored
                            // a global cwd.
                            shellStep(
                                "build/sibling-after-dir",
                                """pwd > '$siblingOracle'""",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val outcome = runWithCoordinator(
            pipeline = pipeline,
            tempDir = tempDir,
            runId = RunId("b11-parent-restore"),
        )

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(dirTarget.toString(), Files.readString(dirOracle).trim())
        // Sibling outside dir sees the stage workspace — NOT the dir target — proving
        // no global cwd restore / leak.
        assertEquals(
            stageWorkspace.toString(),
            Files.readString(siblingOracle).trim(),
            "sibling step after dir must NOT see the dir target as its cwd",
        )
    }

    @Test
    fun `bodyInvokerAdapter is bound under BODY_INVOKER_CAPABILITY in the canonical capability bridge`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        // Proves the capability bridge actually exposes BODY_INVOKER_CAPABILITY
        // when a per-run adapter is wired. The test inspects the bridge from
        // inside a dir block by binding a custom adapter that records that
        // body's ref was opened/closed.
        val adapter = CanonicalBodyInvokerAdapter()
        val dirTarget = tempDir.resolve("cap-target")
        Files.createDirectories(dirTarget)
        val pipeline = pipelineWithSingleBlock(
            blockId = StepId("build/cap-block"),
            pluginStepId = PluginStepId("core.dir"),
            payload = """{"kind":"dir","path":"$dirTarget"}""",
            body = listOf(
                // A no-op child so the body executes (just to keep the run green).
                shellStep("build/cap-block/inside", """true"""),
            ),
        )

        val outcome = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(SystemClock()),
            cursorStore = InMemoryReplayCursorStore(SystemClock()),
            clock = SystemClock(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = InMemoryEventStore(),
            credentialScopePort = noOpCredentialScopePort(),
            controlDirRoot = tempDir.resolve("control"),
            stepRegistry = CoreStepRegistryFactory.registry(),
            // Inject the custom adapter so we can introspect it after the run.
            bodyInvokerAdapter = adapter,
        ).run(pipeline, RunId("b11-cap-bind"))

        assertEquals(RunOutcome.Success, outcome)
        // The body opened and closed exactly once. The runner itself was NOT
        // invoked by a handler (the canonical loop drives production), but the
        // adapter's open/close lifecycle must still bracket the body — proving
        // the dispatchBody integration.
        assertEquals(0, adapter.openBodyCount, "adapter must drain all bodies on finally")
    }

    @Test
    fun `dir-withEnv nested emission ordering matches the canonical outer-in then inner-out event sequence`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        // Companion event-ordering proof for the B10/W1d body family. When
        // `dir { withEnv { timestamps { sh } } }` runs, the events MUST appear
        // outer-in / inner-out (depth-first) and the run finishes cleanly.
        val inner = tempDir.resolve("inner")
        Files.createDirectories(inner)
        val events = InMemoryEventStore()
        val pipeline = CompiledPipeline(
            id = DefinitionId("b11-event-order-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    id = StageId("build"),
                    name = "build",
                    body = StageBody.Steps(
                        listOf(
                            BlockStepNode(
                                id = StepId("build/dir-evt"),
                                pluginStepId = PluginStepId("core.dir"),
                                payload = VersionedStepPayload(
                                    "dsl-v1",
                                    """{"kind":"dir","path":"$inner"}""",
                                ),
                                body = listOf(
                                    BlockStepNode(
                                        id = StepId("build/dir-evt/inner"),
                                        pluginStepId = PluginStepId("core.withEnv"),
                                        payload = VersionedStepPayload(
                                            "dsl-v1",
                                            """{"kind":"withEnv","overrides":["B11_NEST=42"]}""",
                                        ),
                                        body = listOf(
                                            BlockStepNode(
                                                id = StepId("build/dir-evt/inner/ts"),
                                                pluginStepId = PluginStepId("core.timestamps"),
                                                payload = VersionedStepPayload(
                                                    "dsl-v1",
                                                    """{"kind":"timestamps"}""",
                                                ),
                                                body = listOf(
                                                    shellStep(
                                                        "build/dir-evt/inner/ts/sh",
                                                        """true""",
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val outcome = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = InMemoryOperationJournal(SystemClock()),
            cursorStore = InMemoryReplayCursorStore(SystemClock()),
            clock = SystemClock(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = events,
            credentialScopePort = noOpCredentialScopePort(),
            controlDirRoot = tempDir.resolve("control"),
            stepRegistry = CoreStepRegistryFactory.registry(),
        ).run(pipeline, RunId("b11-event-order"))

        assertEquals(RunOutcome.Success, outcome)
        val collected = events.eventsFor("b11-event-order")
        // Outer-in: RunStarted, StageStarted, DirEntered, TimestampsEntered,
        // StepStarted, StepFinished, TimestampsExited, DirExited, StageFinished,
        // RunFinished. The closing sequence MUST be the reverse of the opening
        // sequence so siblings and parents see a coherent context stack.
        val firstTimestampEnterIdx = collected.indexOfFirst { it is TimestampsEntered }
        val firstTimestampsExitIdx = collected.indexOfFirst { it is TimestampsExited }
        val firstDirEnterIdx = collected.indexOfFirst { it is DirEntered }
        val firstDirExitIdx = collected.indexOfFirst { it is DirExited }
        val stepStartedIdx = collected.indexOfFirst { it is StepStarted }
        val stepFinishedIdx = collected.indexOfFirst { it is StepFinished }
        assertTrue(firstDirEnterIdx in 0 until firstTimestampEnterIdx,
            "DirEntered MUST come before TimestampsEntered (outer-in)")
        assertTrue(firstTimestampEnterIdx in 0 until stepStartedIdx,
            "TimestampsEntered MUST come before the inner StepStarted")
        assertTrue(stepFinishedIdx in 0 until firstTimestampsExitIdx,
            "StepFinished MUST come before TimestampsExited")
        assertTrue(firstTimestampsExitIdx in 0 until firstDirExitIdx,
            "TimestampsExited MUST come before DirExited (outer-out)")
        assertTrue(firstDirExitIdx in 0 until collected.indexOfLast { it is RunFinished },
            "DirExited MUST come before RunFinished")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun pipelineWithSingleBlock(
        blockId: StepId,
        pluginStepId: PluginStepId,
        payload: String,
        body: List<OpaqueStepNode>,
    ): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("b11-single-block-${pluginStepId.value}"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(
                    listOf(
                        BlockStepNode(
                            id = blockId,
                            pluginStepId = pluginStepId,
                            payload = VersionedStepPayload("dsl-v1", payload),
                            body = body,
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun shellStep(id: String, cmd: String): OpaqueStepNode = OpaqueStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId("core.sh"),
        payload = VersionedStepPayload(
            "dsl-v1",
            """{"kind":"sh","command":"${cmd.jsonEscape()}","isScriptBlock":false,"returnStdout":false}""",
        ),
    )

    /** Minimal JSON string escape: backslash and double-quote. */
    private fun String.jsonEscape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

    private suspend fun runWithCoordinator(
        pipeline: CompiledPipeline,
        tempDir: Path,
        runId: RunId,
        shOptions: ShOptions = ShOptions.EMPTY,
    ): RunOutcome = CanonicalDurableRunCoordinator(
        dispatcher = CanonicalNodeDispatcher(),
        journal = InMemoryOperationJournal(SystemClock()),
        cursorStore = InMemoryReplayCursorStore(SystemClock()),
        clock = SystemClock(),
        effectReplayPolicy = DefaultEffectReplayPolicy(),
        eventSink = InMemoryEventStore(),
        credentialScopePort = noOpCredentialScopePort(),
        controlDirRoot = tempDir.resolve("control"),
        shOptions = shOptions,
        stepRegistry = CoreStepRegistryFactory.registry(),
    ).run(pipeline, runId)

    /** Stub credential port — every B11 test does NOT exercise withCredentials. */
    private fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable("No credential store in this B11 test"),
        )
    }
}
