package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * BQ4 audit (2026-10-16, vertical): the runner now parses the
 * manifest in `prepare`, drives probes via [ProbeExecutor] in
 * `runProbes`, refuses `Observed -> Closed` at the decider level,
 * and owns deletion in `finalise` (no callback). The tests below
 * use a [ScriptedExecutor] stub that returns a known [ProbeOutcome]
 * per probe, so the runner's state-machine discipline is exercised
 * without coupling to product-side machinery.
 */
class CycleRunnerTest {

    /**
     * Minimal [ProbeExecutor] for the runner tests: each probe is
     * scripted to a known [ProbeOutcome]. The runner only needs the
     * `exitCode` and `evidenceRef`; the harness never inspects the
     * other fields here. This is a test double, not a probe: it
     * carries the [ProbeOutcome.Verified] evidence ref through
     * without re-running anything, so the runner tests can pin the
     * state machine without dragging in the product classpath.
     */
    private class ScriptedExecutor(private val outcomes: List<ProbeOutcome>) : ProbeExecutor(
        cycleDir = Path.of("/nonexistent"),
    ) {
        override fun execute(probe: ProbeEntry, manifest: CycleManifest): ProbeOutcome {
            val idx = probeIndex.getAndIncrement()
            return outcomes[idx]
        }
        private val probeIndex = java.util.concurrent.atomic.AtomicInteger(0)
    }

    /**
     * Persist nothing and surface a [ProbeOutcome.Verified] with a
     * fixed evidence ref. The real [ProbeExecutor] does the same in
     * its `runHF0` path; for the runner tests we just need an
     * outcome the decider accepts.
     */
    private class TrivialVerifiedExecutor(private val ref: String) : ProbeExecutor(
        cycleDir = Path.of("/nonexistent"),
    ) {
        override fun execute(probe: ProbeEntry, manifest: CycleManifest): ProbeOutcome =
            ProbeOutcome.Verified(evidenceRef = ref)
    }

    private fun CycleRunner.runProbesWithVerified() {
        // The new runner requires `prepare` to have parsed the
        // manifest before `runProbes` will execute. `prepare`
        // was called by the test that owns this runner; if the
        // runner is in Draft, the manifest was not parsed yet, so
        // we call `prepare` again as a no-op (it would refuse on
        // a non-Draft state, but for the runner tests the runner
        // is still in Draft because `runProbesWithVerified` is
        // typically called immediately after construction).
        if (state is CycleState.Draft) {
            val outcome = prepare()
            assertTrue(outcome is CycleOutcome.Next) { "prepare failed: $outcome" }
        }
        runProbes(TrivialVerifiedExecutor(ref = "ref-1"))
    }

    private fun writeManifest(
        path: Path,
        cycleId: String = "cyc-1",
        sourceSha: String = "0123456789abcdef0123456789abcdef01234567",
        expectedHF: String = "HF0",
        contractId: String = "OutputPlane.redaction.streaming",
        relativePath: String = "v2/pipeline-credentials-api/src/main/kotlin/dev/rubentxu/pipeline/v2/credentials/api/StreamingRedactor.kt",
    ) {
        val replacementKind = "PERMANENT_CONTRACT"
        val kind = "AAT"
        val verdict = "PASS"
        val replacementEvidence = "new-test-class"
        // contentSha256 is just a 64-char hex stub here; the
        // runner does not verify it against the file (only
        // validates the format and that the resolved path is
        // inside the cycle dir).
        val sha = "0".repeat(64)
        val manifest = """
            {
              "cycle_id": "$cycleId",
              "source_sha": "$sourceSha",
              "expected_hf": "$expectedHF",
              "isolation_policy": "cycleDirOnly",
              "replacement": {
                "kind": "$replacementKind",
                "id": "$replacementEvidence",
                "evidence": "$replacementEvidence"
              },
              "contracts": [
                {
                  "id": "$contractId",
                  "capability": "$contractId",
                  "module": ":pipeline-credentials-api"
                }
              ],
              "probes": [
                {
                  "id": "probe-1",
                  "contract_id": "$contractId",
                  "relative_path": "$relativePath",
                  "kind": "$kind",
                  "executed": 1,
                  "skipped": 0,
                  "verdict": "$verdict",
                  "mutation_or_negative": "redact transcript",
                  "replacement_kind": "$replacementKind",
                  "replacement_id": "$replacementEvidence",
                  "replacement_evidence": "$replacementEvidence",
                  "content_sha256": "$sha"
                }
              ]
            }
        """.trimIndent()
        Files.writeString(path, manifest)
    }

    @Test
    fun `prepare advances Draft to Running and reserves the cycle directory`(@TempDir tmp: Path) {
        val dir = tmp.resolve("cycles/cyc-1")
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest)
        val runner = CycleRunner(cycleId = "cyc-1", manifestPath = manifest, cycleDir = dir)
        val outcome = runner.prepare()
        assertTrue(outcome is CycleOutcome.Next) { "got $outcome" }
        assertEquals(CycleState.Running, runner.state)
        assertTrue(Files.isDirectory(dir), "prepare must create the cycle directory")
        assertNotNull(runner.manifest, "prepare must expose the parsed manifest")
    }

    @Test
    fun `prepare refuses when the manifest file is missing`(@TempDir tmp: Path) {
        val runner = CycleRunner(
            cycleId = "cyc-1",
            manifestPath = tmp.resolve("does-not-exist.json"),
            cycleDir = tmp.resolve("cycles/cyc-1"),
        )
        val outcome = runner.prepare()
        val next = outcome as CycleOutcome.Next
        assertTrue(next.nextState is CycleState.Refused) { "got ${next.nextState}" }
    }

    @Test
    fun `prepare refuses when the manifest declares expectedHF=HF2`(@TempDir tmp: Path) {
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest, expectedHF = "HF2")
        val runner = CycleRunner("cyc-1", manifest, tmp.resolve("cycles/cyc-1"))
        val outcome = runner.prepare()
        val next = outcome as CycleOutcome.Next
        assertTrue(next.nextState is CycleState.Refused) { "got ${next.nextState}" }
        val reason = (next.nextState as CycleState.Refused).reason
        assertTrue(reason.contains("HF2"), "refusal must name the unsupported fidelity, got '$reason'")
    }

    @Test
    fun `prepare refuses when the manifest has a path traversal`(@TempDir tmp: Path) {
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest, relativePath = "../../../etc/passwd")
        val runner = CycleRunner("cyc-1", manifest, tmp.resolve("cycles/cyc-1"))
        val outcome = runner.prepare()
        val next = outcome as CycleOutcome.Next
        assertTrue(next.nextState is CycleState.Refused) { "got ${next.nextState}" }
    }

    @Test
    fun `runProbes keeps the cycle in Running after a Verified probe`(@TempDir tmp: Path) {
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest)
        val runner = CycleRunner("cyc-1", manifest, tmp.resolve("cycles/cyc-1"))
        runner.prepare()
        runner.runProbesWithVerified()
        assertEquals(CycleState.Running, runner.state)
        assertEquals(1, runner.context.probeOutcomes.size)
        assertEquals("ref-1", runner.context.probeOutcomes.first().outcome.evidenceRef)
    }

    @Test
    fun `adjudicate moves Running to Observed with the commit`(@TempDir tmp: Path) {
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest)
        val runner = CycleRunner("cyc-1", manifest, tmp.resolve("cycles/cyc-1"))
        runner.prepare()
        runner.runProbesWithVerified()
        runner.observeAll(observedCommit = "abc123")
        assertEquals(CycleState.Observed("abc123"), runner.state)
    }

    @Test
    fun `adjudicate moves Observed to Promoted`(@TempDir tmp: Path) {
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest)
        val runner = CycleRunner("cyc-1", manifest, tmp.resolve("cycles/cyc-1"))
        runner.prepare()
        runner.runProbesWithVerified()
        runner.observeAll("abc123")
        runner.requestPromote(replacementEvidence = "permanent-contract:rec-1")
        assertEquals(CycleState.Promoted("abc123"), runner.state)
    }

    @Test
    fun `close moves Promoted to Closed with the close reason`(@TempDir tmp: Path) {
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest)
        val runner = CycleRunner("cyc-1", manifest, tmp.resolve("cycles/cyc-1"))
        runner.prepare()
        runner.runProbesWithVerified()
        runner.observeAll("abc123")
        runner.requestPromote("permanent-contract:rec-1")
        runner.requestClose("temporary probes retired")
        assertEquals(CycleState.Closed("temporary probes retired"), runner.state)
    }

    @Test
    fun `close moves Observed to Closed is IllegalTransition (BQ4 audit)`(@TempDir tmp: Path) {
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest)
        val runner = CycleRunner("cyc-1", manifest, tmp.resolve("cycles/cyc-1"))
        runner.prepare()
        runner.runProbesWithVerified()
        runner.observeAll("abc123")
        val outcome = runner.requestClose("no actionable items in manifest")
        assertTrue(outcome is CycleOutcome.IllegalTransition) {
            "BQ4 audit: Observed -> CloseRequested MUST be IllegalTransition; got $outcome"
        }
        assertTrue(runner.state is CycleState.Observed) {
            "the runner's state must remain Observed on IllegalTransition; got ${runner.state}"
        }
    }

    @Test
    fun `close from Draft is Refused (manifest never produced evidence)`(@TempDir tmp: Path) {
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest)
        val runner = CycleRunner("cyc-1", manifest, tmp.resolve("cycles/cyc-1"))
        runner.requestClose("manifest invalid")
        assertTrue(runner.state is CycleState.Refused) { "got ${runner.state}" }
    }

    @Test
    fun `close from Running is Refused (probes never finished)`(@TempDir tmp: Path) {
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest)
        val runner = CycleRunner("cyc-1", manifest, tmp.resolve("cycles/cyc-1"))
        runner.prepare()
        // No probe run; the cycle is still Running.
        runner.requestClose("probes never finished")
        assertTrue(runner.state is CycleState.Refused) { "got ${runner.state}" }
    }

    @Test
    fun `finalise deletes the cycle directory only when state is Closed`(@TempDir tmp: Path) {
        val dir = tmp.resolve("cycles/cyc-1")
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest)
        val runner = CycleRunner("cyc-1", manifest, dir)
        runner.prepare()
        runner.runProbesWithVerified()
        runner.observeAll("abc123")
        runner.requestPromote("permanent-contract:rec-1")
        runner.requestClose("done")
        assertTrue(Files.isDirectory(dir), "cycle dir must exist before finalise")
        runner.finalise()
        assertFalse(Files.exists(dir), "finalise must delete the cycle dir")
    }

    @Test
    fun `finalise refuses when state is not Closed`(@TempDir tmp: Path) {
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest)
        val runner = CycleRunner("cyc-1", manifest, tmp.resolve("cycles/cyc-1"))
        runner.prepare()
        runner.runProbesWithVerified()
        runner.observeAll("abc123")
        // state is Observed, not Closed.
        val ex = kotlin.runCatching { runner.finalise() }.exceptionOrNull()
        assertTrue(ex is IllegalStateException) { "expected IllegalStateException, got $ex" }
        assertTrue(
            (ex?.message ?: "").contains("Closed"),
            "refusal must name the missing state, got ${ex?.message}",
        )
    }

    @Test
    fun `blockExternal moves any non-terminal state to BlockedExternal`(@TempDir tmp: Path) {
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest)
        val runner = CycleRunner("cyc-1", manifest, tmp.resolve("cycles/cyc-1"))
        runner.prepare()
        runner.blockExternal("harness verdict missing")
        assertTrue(runner.state is CycleState.BlockedExternal) { "got ${runner.state}" }
    }

    @Test
    fun `BQ4 adversarial - runProbes with UnsupportedFidelity refuses the cycle`(@TempDir tmp: Path) {
        // The executor returns UnsupportedFidelity for HF2..HF6.
        // The 2026-10-16 audit (P0) named the previous shape a
        // false green: the runner collapsed UnsupportedFidelity
        // to `exitCode=0, evidenceRef="unsupported:..."` and the
        // decider read it as success. The new shape refuses the
        // cycle on the ADT directly: an UnsupportedFidelity is a
        // Refused state, NOT an evidence row in the accumulator.
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest, expectedHF = "HF0")
        val runner = CycleRunner("cyc-1", manifest, tmp.resolve("cycles/cyc-1"))
        runner.prepare()
        val outcomes = runner.runProbes(object : ProbeExecutor(Path.of("/n")) {
            override fun execute(probe: ProbeEntry, manifest: CycleManifest): ProbeOutcome =
                ProbeOutcome.UnsupportedFidelity("HF2")
        })
        assertEquals(1, outcomes.size)
        assertTrue(outcomes[0] is ProbeOutcome.UnsupportedFidelity)
        // The cycle is now Refused, NOT Running with evidence.
        // The previous assertion `assertEquals(CycleState.Running, ...)`
        // is the P0 false-green the audit named; the new shape
        // refuses the cycle without advancing.
        val state = runner.state
        assertTrue(state is CycleState.Refused) {
            "expected Refused for UnsupportedFidelity, got $state"
        }
        // The accumulator MUST be empty: a refused probe does
        // not contribute evidence.
        assertEquals(0, runner.context.probeOutcomes.size) {
            "an UnsupportedFidelity probe MUST NOT enter the " +
                "accumulator; got ${runner.context.probeOutcomes.size} rows"
        }
    }

    @Test
    fun `BQ4 adversarial - runProbes with BlockedExternal transitions to BlockedExternal`(
        @TempDir tmp: Path,
    ) {
        // The audit (P0) also named BlockedExternal: the
        // previous runner collapsed it to exitCode=0 and the
        // decider accepted it as success. The new shape
        // transitions the cycle to BlockedExternal directly.
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest, expectedHF = "HF1")
        val runner = CycleRunner("cyc-1", manifest, tmp.resolve("cycles/cyc-1"))
        runner.prepare()
        runner.runProbes(object : ProbeExecutor(Path.of("/n")) {
            override fun execute(probe: ProbeEntry, manifest: CycleManifest): ProbeOutcome =
                ProbeOutcome.BlockedExternal("", "harness verdict missing")
        })
        val state = runner.state
        assertTrue(state is CycleState.BlockedExternal) {
            "expected BlockedExternal, got $state"
        }
        assertEquals(0, runner.context.probeOutcomes.size)
    }
}
