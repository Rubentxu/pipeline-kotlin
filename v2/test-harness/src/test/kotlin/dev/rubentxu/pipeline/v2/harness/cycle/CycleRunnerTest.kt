package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertEquals
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
 *
 * BQ4 audit (2026-10-16, RC3 close-out): every test now drives at
 * least one [CycleRunner.reportProbe] call before [CycleRunner.observeAll]
 * so the cycle has accumulated evidence by the time it observes
 * the commit. The previous tests drove `observeAll` directly,
 * which the new decider correctly refused as "no probe succeeded".
 */
class CycleRunnerTest {

    /** Drive a probe so the cycle can observe a non-empty commit. */
    private fun CycleRunner.runOneProbe(ref: String = "ref-1") {
        reportProbe(exitCode = 0, evidenceRef = ref)
    }

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
        assertTrue(outcome is CycleOutcome.Next) { "got $outcome" }
        assertEquals(CycleState.Running, runner.state)
        assertEquals(1, runner.context.probeOutcomes.size)
        assertEquals("ref-1", runner.context.probeOutcomes.first().evidenceRef)
    }

    @Test
    fun `adjudicate moves Running to Observed with the commit`(@TempDir tmp: Path) {
        val runner = CycleRunner("cyc-1", tmp.resolve("m"), tmp.resolve("d"))
        runner.prepare()
        runner.runOneProbe()
        runner.observeAll(observedCommit = "abc123")
        assertEquals(CycleState.Observed("abc123"), runner.state)
    }

    @Test
    fun `adjudicate moves Observed to Promoted`(@TempDir tmp: Path) {
        val runner = CycleRunner("cyc-1", tmp.resolve("m"), tmp.resolve("d"))
        runner.prepare()
        runner.runOneProbe()
        runner.observeAll("abc123")
        runner.requestPromote(replacementEvidence = "rec-1")
        assertEquals(CycleState.Promoted("abc123"), runner.state)
    }

    @Test
    fun `close moves Promoted to Closed with the close reason`(@TempDir tmp: Path) {
        val runner = CycleRunner("cyc-1", tmp.resolve("m"), tmp.resolve("d"))
        runner.prepare()
        runner.runOneProbe()
        runner.observeAll("abc123")
        runner.requestPromote("rec-1")
        runner.requestClose("temporary probes retired")
        assertEquals(CycleState.Closed("temporary probes retired"), runner.state)
    }

    @Test
    fun `close moves Observed to Closed when nothing was actionable`(@TempDir tmp: Path) {
        // A cycle may close after observing probes without promoting
        // (e.g. the manifest listed no actionable items). That is a
        // success path, not a refusal, PROVIDED the cycle did run
        // a probe. The audit's adversarial case: a cycle with no
        // probes that closes IS Refused; the test below proves
        // both branches.
        val runner = CycleRunner("cyc-1", tmp.resolve("m"), tmp.resolve("d"))
        runner.prepare()
        runner.runOneProbe()
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
    fun `close from Running is Refused (probes never finished)`(@TempDir tmp: Path) {
        val runner = CycleRunner("cyc-1", tmp.resolve("m"), tmp.resolve("d"))
        runner.prepare()
        // No probe run. observeAll is impossible from Running
        // without probes; requestClose from Running is Refused.
        runner.requestClose("probes never finished")
        assertTrue(runner.state is CycleState.Refused) { "got ${runner.state}" }
    }

    @Test
    fun `finalise runs the deletion callback only when state is Closed`(@TempDir tmp: Path) {
        val dir = tmp.resolve("cycles/cyc-1")
        val runner = CycleRunner("cyc-1", tmp.resolve("m"), dir)
        runner.prepare()
        runner.runOneProbe()
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
        runner.runOneProbe()
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

    /**
     * BQ4 audit (2026-10-16, RC3 close-out): the audit's central
     * concern was that a cycle could close without evidence. The
     * runner-level test below drives the **exact** audit-flagged
     * sequence and proves the runner now refuses to finalise on it.
     */
    @Test
    fun `BQ4 adversarial - a cycle that never ran a probe cannot be finalised`(@TempDir tmp: Path) {
        val dir = tmp.resolve("cycles/cyc-1")
        val runner = CycleRunner("cyc-1", tmp.resolve("m"), dir)
        runner.prepare()
        // Audit sequence:
        //   Draft -> Running (no probes)
        //   Running -> observeAll(observedCommit)   [now Refused, was Observed]
        //   Observed -> closeRequested              [would be Closed; cannot be reached]
        //   Closed -> finalise                      [cannot be reached]
        // The new decider refuses observeAll from Running without
        // probes. The runner threads the state correctly:
        // observeAll returns a Next whose nextState is Refused,
        // not Observed. The runner's state field reflects that.
        val outcome = runner.observeAll(observedCommit = "abc123")
        val next = outcome as CycleOutcome.Next
        assertTrue(next.nextState is CycleState.Refused) {
            "got ${next.nextState}; the audit's P0 sequence MUST refuse at observeAll"
        }
        assertTrue(runner.state is CycleState.Refused) {
            "got ${runner.state}; the runner's state field MUST follow"
        }
        // finalise refuses because state is not Closed.
        var deletions = 0
        try {
            runner.finalise { deletions++ }
        } catch (e: IllegalStateException) {
            assertTrue(
                (e.message ?: "").contains("Closed"),
                "refusal must name the missing state, got ${e.message}",
            )
        }
        assertEquals(0, deletions, "a refused cycle must not run the deletion callback")
    }
}
