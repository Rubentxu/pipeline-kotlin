package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The runner is a thin interpreter over [Cycle.decide]. Its job is
 * to wire the state machine to the four lifecycle actions
 * (prepare / run / adjudicate / close) without inventing any
 * transitions of its own. These tests exercise that wire-up.
 */
class CycleRunnerTest {

    @Test
    fun `prepare advances Draft to Running and reserves the cycle directory`(@TempDir tmp: Path) {
        val dir = tmp.resolve("cycles/cyc-1")
        val runner = CycleRunner(
            cycleId = "cyc-1",
            manifestPath = tmp.resolve("manifest.yaml"),
            cycleDir = dir,
        )
        Files.writeString(runner.manifestPath, "cycle: cyc-1\n")
        val outcome = runner.prepare()
        assertTrue(outcome is CycleOutcome.Next) { "got $outcome" }
        assertEquals(CycleState.Running, runner.state)
        assertTrue(Files.isDirectory(dir), "prepare must create the cycle directory")
    }

    @Test
    fun `run keeps the cycle in Running after a probe finishes`() {
        val runner = CycleRunner("cyc-1", Path.of("m"), Path.of("d"))
        runner.prepare()
        val outcome = runner.reportProbe(exitCode = 0, evidenceRef = "ref-1")
        assertTrue(outcome is CycleOutcome.Stay) { "got $outcome" }
        assertEquals(CycleState.Running, runner.state)
    }

    @Test
    fun `adjudicate moves Running to Observed with the commit`(@TempDir tmp: Path) {
        val runner = CycleRunner("cyc-1", tmp.resolve("m"), tmp.resolve("d"))
        runner.prepare()
        runner.observeAll(observedCommit = "abc123")
        assertEquals(CycleState.Observed("abc123"), runner.state)
    }

    @Test
    fun `adjudicate moves Observed to Promoted`(@TempDir tmp: Path) {
        val runner = CycleRunner("cyc-1", tmp.resolve("m"), tmp.resolve("d"))
        runner.prepare()
        runner.observeAll("abc123")
        runner.requestPromote(replacementEvidence = "rec-1")
        assertEquals(CycleState.Promoted("abc123"), runner.state)
    }

    @Test
    fun `close moves Promoted to Closed with the close reason`(@TempDir tmp: Path) {
        val runner = CycleRunner("cyc-1", tmp.resolve("m"), tmp.resolve("d"))
        runner.prepare()
        runner.observeAll("abc123")
        runner.requestPromote("rec-1")
        runner.requestClose("temporary probes retired")
        assertEquals(CycleState.Closed("temporary probes retired"), runner.state)
    }

    @Test
    fun `close moves Observed to Closed when nothing was actionable`(@TempDir tmp: Path) {
        // A cycle may close after observing probes without promoting
        // (e.g. the manifest listed no actionable items). That is a
        // success path, not a refusal.
        val runner = CycleRunner("cyc-1", tmp.resolve("m"), tmp.resolve("d"))
        runner.prepare()
        runner.observeAll("abc123")
        runner.requestClose("no actionable items in manifest")
        assertEquals(CycleState.Closed("no actionable items in manifest"), runner.state)
    }

    @Test
    fun `close from Draft is Refused (manifest never produced evidence)`(@TempDir tmp: Path) {
        val runner = CycleRunner("cyc-1", tmp.resolve("m"), tmp.resolve("d"))
        runner.requestClose("manifest invalid")
        assertTrue(runner.state is CycleState.Refused) { "got ${runner.state}" }
    }

    @Test
    fun `finalise runs the deletion callback only when state is Closed`(@TempDir tmp: Path) {
        val dir = tmp.resolve("cycles/cyc-1")
        val runner = CycleRunner("cyc-1", tmp.resolve("m"), dir)
        runner.prepare()
        runner.observeAll("abc123")
        runner.requestClose("done")
        var deletions = 0
        runner.finalise { deletions++ }
        assertEquals(1, deletions, "finalise must call the deletion callback once")
    }

    @Test
    fun `finalise refuses to run the deletion callback when state is not Closed`(@TempDir tmp: Path) {
        val runner = CycleRunner("cyc-1", tmp.resolve("m"), tmp.resolve("d"))
        runner.prepare()
        runner.observeAll("abc123")
        // state is Observed, not Closed
        var deletions = 0
        try {
            runner.finalise { deletions++ }
        } catch (e: IllegalStateException) {
            assertTrue(
                (e.message ?: "").contains("Closed"),
                "refusal must name the missing state, got ${e.message}",
            )
        }
        assertEquals(0, deletions, "refused cycle must not run the deletion callback")
    }

    @Test
    fun `blockExternal moves any non-terminal state to BlockedExternal`(@TempDir tmp: Path) {
        val runner = CycleRunner("cyc-1", tmp.resolve("m"), tmp.resolve("d"))
        runner.prepare()
        runner.blockExternal("harness verdict missing")
        assertTrue(runner.state is CycleState.BlockedExternal) { "got ${runner.state}" }
    }
}
