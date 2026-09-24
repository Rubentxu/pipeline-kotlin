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
import dev.rubentxu.pipeline.v2.events.BlockFailureContained
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * WU-RP-053-DIR-FAILURE-MODE: GREEN-phase coordinator test for the
 * `dir(...) { ... }` block failure-mode semantics.
 *
 * The Jenkins default (and our new default) is [DirFailureMode.Contained]:
 * a StepFailed inside the block is captured; the cwd is restored; the
 * pipeline proceeds with the next sibling statement; the stage finishes
 * `Success` because no contained failure has bubbled out of an outer scope.
 *
 * Two assertions drive the proof:
 *   1. A [BlockFailureContained] event is emitted for the failed child step
 *      with the right stage index and the original StepKey-derived block id.
 *   2. The paired [DirExited] event still fires (cwd restored), and the
 *      overall [RunOutcome] is `Success` — the pipeline did not abort.
 *
 * This test uses the production coordinator composition (production
 * `CoreStepRegistryFactory` + default effect-replay + in-memory durable
 * journal/cursor). It does NOT touch the DSL; the assertion is purely on
 * the durable engine's typed decision seam.
 *
 * Reference: docs/v2/07-uat/HAR_007_DIR_RESTORE_CHARACTERIZATION.md.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DirFailureContainedRuntimeTest {

    @Test
    fun `dir with Contained default captures the failure and the pipeline continues`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        // 1. Build a pipeline: a `dir("errdir")` block whose body runs `sh false`
        //    (which exits with code 1), followed by a second `dir("chk")` block
        //    whose body writes a marker file. The marker is the GREEN-phase
        //    observable; it proves the pipeline continued.
        val errDir = Files.createDirectories(tempDir.resolve("errdir"))
        val chkDir = Files.createDirectories(tempDir.resolve("chk"))
        val marker = chkDir.resolve("marker.txt")

        val events = InMemoryEventStore()

        val pipeline = CompiledPipeline(
            id = DefinitionId("har007-dir-contained-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    id = StageId("build"),
                    name = "build",
                    body = StageBody.Steps(
                        listOf(
                            BlockStepNode(
                                id = StepId("build/errdir"),
                                pluginStepId = PluginStepId("core.dir"),
                                payload = VersionedStepPayload(
                                    "dsl-v1",
                                    """{"kind":"dir","path":"$errDir"}""",
                                ),
                                body = listOf(
                                    OpaqueStepNode(
                                        id = StepId("build/errdir/sh-fail"),
                                        pluginStepId = PluginStepId("core.sh"),
                                        payload = VersionedStepPayload(
                                            "dsl-v1",
                                            """{"kind":"sh","command":"false","isScriptBlock":false,"returnStdout":false}""",
                                        ),
                                    ),
                                ),
                            ),
                            BlockStepNode(
                                id = StepId("build/chk"),
                                pluginStepId = PluginStepId("core.dir"),
                                payload = VersionedStepPayload(
                                    "dsl-v1",
                                    """{"kind":"dir","path":"$chkDir"}""",
                                ),
                                body = listOf(
                                    OpaqueStepNode(
                                        id = StepId("build/chk/sh-marker"),
                                        pluginStepId = PluginStepId("core.sh"),
                                        payload = VersionedStepPayload(
                                            "dsl-v1",
                                            """{"kind":"sh","command":"touch $marker","isScriptBlock":false,"returnStdout":false}""",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // 2. Run through the production coordinator.
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
        ).run(pipeline, RunId("har007-contained-green"))

        // 3. Outcome: GREEN — the pipeline continued; the second `dir("chk")`
        //    block ran and wrote the marker.
        assertEquals(
            RunOutcome.Success,
            outcome,
            "with DirFailureMode.Contained the stage must finish Success even though " +
                "the first dir's body failed; the contained failure is captured " +
                "and the sibling statement runs. got=$outcome",
        )
        assertTrue(
            Files.exists(marker),
            "marker file must exist — the second dir block must have run after the " +
                "first dir's contained failure. path=$marker",
        )

        // 4. Observability: BlockFailureContained + paired DirExited for the first dir.
        val captured = events.eventsFor("har007-contained-green").toList()
        val containedEvents = captured.filterIsInstance<BlockFailureContained>()
        assertEquals(
            1,
            containedEvents.size,
            "exactly one BlockFailureContained event is expected; got " +
                "${containedEvents.size}: $captured",
        )
        val contained = containedEvents.single()
        assertEquals(errDir.toString(), contained.path)
        assertEquals(0, contained.stageIndex)
        // The block.id.value is the StepKey-derived id passed through the
        // canonical dispatch (not the plugin StepKey); the operator-visible
        // identifier in the BlockFailureContained event is the block's id.
        assertEquals("build/errdir", contained.stepName)
        assertNotNull(contained.failureKind)
        assertTrue(
            contained.message.isNotBlank(),
            "captured failure must carry the underlying message; got='${contained.message}'",
        )

        // The paired DirExited for the first dir must still fire (cwd restored).
        val dirExitedForErr = captured.filterIsInstance<DirExited>().filter { it.path == errDir.toString() }
        assertEquals(
            1,
            dirExitedForErr.size,
            "DirExited must still fire for the failed dir block (cwd restored). " +
                "got ${dirExitedForErr.size}",
        )

        // And the second dir's DirEntered must have fired too — proving the
        // stage loop proceeded past the captured failure.
        val dirEnteredForChk = captured.filterIsInstance<DirEntered>().filter { it.path == chkDir.toString() }
        assertEquals(
            1,
            dirEnteredForChk.size,
            "DirEntered must fire for the second dir block; got ${dirEnteredForChk.size}",
        )
    }

    /**
     * Companion invariant: when the user explicitly opts out of the default
     * (via a `dir(..., mode = AbortStage)` — modelled here by constructing a
     * `BlockShellScope.Directory` with `AbortStage` directly into a sibling
     * statement), the legacy abort-the-stage semantics is preserved.
     *
     * Note: the DSL-level opt-in syntax is not in scope of WU-RP-053-DIR-FAILURE-MODE;
     * this test wires the abort semantics at the structural-decision seam
     * (constructing the scope explicitly) to prove the legacy path is intact.
     *
     * The test exercises the structural decision path by replacing the inner
     * body of the dir block to fail without the contained-mode capture.
     */
    @Test
    fun `dir block failure inside body without Contained semantics aborts the stage`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        // Build a pipeline where the failing dir's body raises but the
        // marker dir is wrapped in a try/finally-free path. With AbortStage,
        // the second dir never runs.
        val errDir = Files.createDirectories(tempDir.resolve("errdir-abort"))
        val chkDir = Files.createDirectories(tempDir.resolve("chk-abort"))
        val marker = chkDir.resolve("marker.txt")

        val events = InMemoryEventStore()

        val pipeline = CompiledPipeline(
            id = DefinitionId("har007-dir-abort-pipeline"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            pluginLockDigest = Digest("lock"),
            stages = listOf(
                StageNode(
                    id = StageId("build"),
                    name = "build",
                    body = StageBody.Steps(
                        listOf(
                            // The first dir uses a payload that explicitly carries
                            // `failureMode=AbortStage`. We use the public pure
                            // projection to encode that.
                            BlockStepNode(
                                id = StepId("build/errdir-abort"),
                                pluginStepId = PluginStepId("core.dir"),
                                payload = VersionedStepPayload(
                                    "dsl-v1",
                                    """{"kind":"dir","path":"$errDir","failureMode":"AbortStage"}""",
                                ),
                                body = listOf(
                                    OpaqueStepNode(
                                        id = StepId("build/errdir-abort/sh-fail"),
                                        pluginStepId = PluginStepId("core.sh"),
                                        payload = VersionedStepPayload(
                                            "dsl-v1",
                                            """{"kind":"sh","command":"false","isScriptBlock":false,"returnStdout":false}""",
                                        ),
                                    ),
                                ),
                            ),
                            BlockStepNode(
                                id = StepId("build/chk-abort"),
                                pluginStepId = PluginStepId("core.dir"),
                                payload = VersionedStepPayload(
                                    "dsl-v1",
                                    """{"kind":"dir","path":"$chkDir"}""",
                                ),
                                body = listOf(
                                    OpaqueStepNode(
                                        id = StepId("build/chk-abort/sh-marker"),
                                        pluginStepId = PluginStepId("core.sh"),
                                        payload = VersionedStepPayload(
                                            "dsl-v1",
                                            """{"kind":"sh","command":"touch $marker","isScriptBlock":false,"returnStdout":false}""",
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
        ).run(pipeline, RunId("har007-abort-legacy"))

        // With AbortStage the stage MUST fail and the second dir MUST NOT run.
        // The RunOutcome.Failure data class carries the underlying PipelineFailure;
        // we only assert the outcome TYPE here — the underlying message is the
        // sh handler's "shell exited with code 1", which the test would have to
        // duplicate. Comparing the type via `is` is sufficient proof of the
        // AbortStage semantics: legacy behaviour is preserved, the stage aborts.
        assertTrue(
            outcome is RunOutcome.Failure,
            "AbortStage must produce RunOutcome.Failure; got=$outcome",
        )
        assertEquals(false, Files.exists(marker))
    }

    private fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable("No credential store in this GREEN test"),
        )
    }
}
