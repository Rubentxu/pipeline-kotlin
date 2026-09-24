package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.WORKSPACE_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.WorkspaceOperations
import dev.rubentxu.pipeline.v2.application.WorkspaceOperationsAdapter
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
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
 * HAR-007 / WU-RP-053 characterization: `dir(...)` cwd semantics.
 *
 * Per the operator directive (2026-09-24T12:23:58Z on Jenkins-parity workspace
 * semantics), the contract `dir(path) { ... }` must:
 *
 *   1. Change cwd to the requested path inside the block.
 *   2. Restore cwd when the block exits, even if a Step inside the block failed.
 *   3. Allow the pipeline to CONTINUE with the next sibling statement after a
 *      failed `dir(...)` block — the failure is scoped to the block, NOT to
 *      the whole stage (mirroring Jenkins' dir() semantics).
 *
 * This file is the CHARACTERIZATION layer: it documents the current contract
 * via the production capability composition (CanonicalRuntimeCapabilityAccess +
 * WorkspaceOperationsAdapter) and links to the receipts that hold the
 * GREEN-state evidence for the rebased PR #90/#95/#96 branches.
 *
 * The receipts:
 *   - docs/v2/07-uat/HAR_007_DIR_RESTORE_CHARACTERIZATION.md (this branch)
 *   - docs/v2/07-uat/WU_RP_053_COHERENCE_CONTRACT_RECEIPT.md (PR set evidence)
 *
 * The harness scenario:
 *   Rubentxu/pipelinek-release-harness/scenarios/smoke/har007-dir-restore.pipeline.kts
 *
 * The harness scenario assumes (3). On the pre-PR main build, cwd restore (2)
 * works (DirExited `restoredTo` is correct) BUT the Stage aborts after
 * StepFailed — the next `dir(...)` block never runs.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DirRestoreAfterErrorCharacterizationTest {

    /**
     * LOCAL guard characterization (cwd path composition).
     *
     * Verifies that, given a [WorkspaceOperationsAdapter] constructed with an
     * explicit `effectiveWorkingDirectory`, a `writeFile("a.txt", ...)` lands
     * at `<effectiveCwd>/a.txt` and NOT at `<authorizedRoot>/a.txt`.
     *
     * Pre-PR (main): does not compile — the `effectiveWorkingDirectory` parameter
     * does not exist on `WorkspaceOperationsAdapter`. This itself is the
     * characterisation gap. Receipt: see [DirFilesystemEndToEndTest] for the
     * operator's pre-PR RED proof on the proposed seam.
     *
     * Post-PR (cut1/cut4/cut5): PASS via the threaded seam.
     *
     * Re-enabled on WU-RP-053-MERGE M2 (2026-09-24): the workspace-authorization
     * slice and the Contained-failure slice now live on the same branch. This
     * test is the LOCAL-GUARD that proves the integration of the two slices —
     * i.e. the proof that motivated WU-RP-053-MERGE in the first place.
     */
    @Test
    fun `writeFile inside dir composes against effective cwd`(@TempDir checkout: Path) {
        val control = checkout.resolve(".control")
        Files.createDirectories(control)
        val workspace = Files.createDirectories(checkout.resolve("ws"))
        val nested = Files.createDirectories(workspace.resolve("errdir"))

        val sink = InMemoryEventStore()
        val context = CanonicalRuntimeContext(
            opId = OpId("har007-write-cwd", 0, 0),
            runId = "har007-write-cwd",
            stageName = "dir-restore",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY.copy(
                workspaceRoot = workspace,
                workingDirectory = nested,
            ),
            controlDirRoot = control,
            eventSink = sink,
            workspaceBase = workspace,
        )
        val capabilities = CanonicalRuntimeCapabilityAccess(context)
        val workspaceOps: WorkspaceOperations =
            capabilities.get(WORKSPACE_OPERATIONS_CAPABILITY)

        workspaceOps.writeFile("in-errdir.txt", "should-not-survive", "UTF-8")

        // Verify the file landed at the effective cwd (nested) and NOT at the
        // authorized workspace root.
        assertTrue(
            Files.exists(nested.resolve("in-errdir.txt")),
            "writeFile must compose against effectiveWorkingDirectory=${nested}; " +
                "expected file at ${nested.resolve("in-errdir.txt")} but was missing",
        )
        assertEquals(
            "should-not-survive",
            Files.readString(nested.resolve("in-errdir.txt")),
        )
        assertEquals(
            false,
            Files.exists(workspace.resolve("in-errdir.txt")),
            "writeFile must NOT write to the authorized workspace root when an " +
                "effective cwd is in scope",
        )

        // Single-emitter invariant: exactly one FileWritten event for the run.
        val writes = sink.eventsFor("har007-write-cwd")
            .filterIsInstance<FileWritten>()
            .toList()
        assertEquals(1, writes.size, "exactly one FileWritten expected, got ${writes.size}")
        assertEquals(
            nested.resolve("in-errdir.txt").toString(),
            writes.single().path.toString(),
        )
    }

    /**
     * WIDE-GAP characterization: pipeline continuation after a failed dir block.
     *
     * The full scripted→registry step graph that would express this assertion
     * is not feasible without a bound scripted runtime. The assertion is
     * captured end-to-end at the CLI surface in the receipt:
     *   docs/v2/07-uat/HAR_007_DIR_RESTORE_CHARACTERIZATION.md
     *
     * The harness scenario:
     *   Rubentxu/pipelinek-release-harness/scenarios/smoke/har007-dir-restore.pipeline.kts
     *
     * CLI evidence (RED on current main, recorded against the cut5-stash binary):
     *
     *   $ pipelinek run --db db.sqlite --control-root ctl --workspace ws har007.pipeline.kts
     *   [{"eventId":"...","kind":"StepFailed","message":"shell exited with code 1"},
     *    {"eventId":"...","kind":"RunFinished","outcome":"failure",...}]
     *
     * The pipeline aborts after StepFailed, the second `dir("chk")` block never
     * runs, the marker file is NOT written.
     *
     * GREEN-phase requirement: a `dir.failureMode` ADT
     * (CONTAINED | ABORT_STAGE) with CONTAINED as the Jenkins default, OR a
     * try/finally isolation around the body of `dir(...)` that re-throws but
     * leaves the stage loop alive. Either path requires its own dedicated
     * slice — see WU-RP-053-COHERENCE-CONTRACT-RECEIPT §"Known gaps".
     */
    @Test
    @org.junit.jupiter.api.Disabled(
        "WIDE-GAP closed by WU-RP-053-DIR-FAILURE-MODE: the GREEN-phase proof is the " +
            "DirFailureContainedRuntimeTest.dir-with-Contained-default-captures-the-failure-and-the-pipeline-continues " +
            "test under pipeline-application. This CLI-side body remains a no-op " +
            "placeholder; the canonical GREEN-phase proof is the coordinator-level " +
            "test above. Re-enable when a full scripted-runtime seam is in scope.",
    )
    fun `dir failure is contained and the pipeline continues`(@TempDir checkout: Path) {
        // Intentionally a no-op body; the @Disabled annotation above is the
        // evidence. The CLI receipt documents the RED/GREEN diff.
        assertNotNull(checkout)
    }
}
