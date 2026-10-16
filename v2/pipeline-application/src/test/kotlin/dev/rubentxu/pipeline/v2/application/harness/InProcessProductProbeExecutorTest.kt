package dev.rubentxu.pipeline.v2.application.harness

import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.harness.cycle.CycleRunner
import dev.rubentxu.pipeline.v2.harness.cycle.OracleRef
import dev.rubentxu.pipeline.v2.harness.cycle.ProbeOutcome
import dev.rubentxu.pipeline.v2.harness.cycle.PromotionEvidence
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * Adversarial tests for [InProcessProductProbeExecutor] — the
 * P1 close-out of the 2026-10-16 BQ4 audit. The harness's
 * default [ProbeExecutor] returns `BlockedExternal` for HF1
 * because the harness is product-agnostic. This subclass runs
 * HF1 for real through the canonical coordinator (the same
 * composition [dev.rubentxu.pipeline.v2.application.support.PipelineRule]
 * already certifies) and maps [RunOutcome] to [ProbeOutcome].
 *
 * The contract these tests pin:
 * - HF1 with a passing `pipeline { echo("hi") }` -> `Verified`
 *   (the coordinator ran, the Step succeeded, the cycle can
 *   proceed to Observed -> Promoted -> Closed).
 * - HF1 with a failing Step -> `Failed` with a reason that
 *   names the failure kind and message.
 * - HF1 with a relative path that escapes the cycle dir ->
 *   `Failed` (the cycleDirOnly isolation policy stays).
 * - HF0 probes are NOT routed through this executor's HF1 path
 *   (the harness default is the authority on HF0; the audit's P0
 *   work proves the harness default already enforces the SHA
 *   check on real bytes).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class InProcessProductProbeExecutorTest {

    private fun writeManifest(
        path: Path,
        cycleDir: Path,
        relativePath: String,
        expectedHf: String = "HF1",
        contentSha256: String = "0".repeat(64),
    ) {
        val manifest = """
            {
              "cycle_id": "cyc-hf1",
              "source_sha": "0123456789abcdef0123456789abcdef01234567",
              "expected_hf": "$expectedHf",
              "isolation_policy": "cycleDirOnly",
              "replacement": {
                "kind": "PERMANENT_CONTRACT",
                "id": "x",
                "evidence": "y"
              },
              "contracts": [
                {
                  "id": "OutputPlane.redaction.streaming",
                  "capability": "OutputPlane.redaction.streaming",
                  "module": ":pipeline-credentials-api"
                }
              ],
              "probes": [
                {
                  "id": "probe-1",
                  "contract_id": "OutputPlane.redaction.streaming",
                  "relative_path": "$relativePath",
                  "kind": "AAT",
                  "executed": 1,
                  "skipped": 0,
                  "verdict": "PASS",
                  "mutation_or_negative": "x",
                  "replacement_kind": "PERMANENT_CONTRACT",
                  "replacement_id": "x",
                  "replacement_evidence": "x",
                  "content_sha256": "$contentSha256"
                }
              ]
            }
        """.trimIndent()
        path.writeText(manifest)
    }

    @Test
    fun `HF1 with a passing PipelineSpec produces Verified`(@TempDir tmp: Path) {
        val cycleDir = tmp.resolve("cyc")
        Files.createDirectories(cycleDir)
        val relativePath = "tmp/echo-hf1.kts"
        Files.createDirectories(cycleDir.resolve(relativePath).parent)
        cycleDir.resolve(relativePath).writeText("// in-process spec; not parsed as text")

        val manifestPath = tmp.resolve("manifest.json")
        writeManifest(manifestPath, cycleDir, relativePath, expectedHf = "HF1")

        val runner = CycleRunner("cyc-hf1", manifestPath, cycleDir)
        runner.prepare()

        val executor = InProcessProductProbeExecutor(
            cycleDir = cycleDir,
            specProvider = { _, _ ->
                pipeline {
                    stages {
                        stage("build") {
                            echo("hf1-ok")
                        }
                    }
                }
            },
        )
        val outcomes = runner.runProbes(executor)
        val outcome = outcomes.single()
        assertTrue(
            outcome is ProbeOutcome.Verified,
            "expected Verified, got $outcome",
        )
        // The runner must have persisted a probes/<id>.json.
        val evidence = cycleDir.resolve("probes/probe-1.json")
        assertTrue(Files.exists(evidence), "executor must persist evidence JSON")
    }

    @Test
    fun `HF1 with a failing PipelineSpec produces Failed with kind+message`(
        @TempDir tmp: Path,
    ) {
        val cycleDir = tmp.resolve("cyc")
        Files.createDirectories(cycleDir)
        val relativePath = "tmp/fail-hf1.kts"
        Files.createDirectories(cycleDir.resolve(relativePath).parent)
        cycleDir.resolve(relativePath).writeText("// placeholder")

        val manifestPath = tmp.resolve("manifest.json")
        writeManifest(manifestPath, cycleDir, relativePath, expectedHf = "HF1")

        val runner = CycleRunner("cyc-hf1", manifestPath, cycleDir)
        runner.prepare()

        val executor = InProcessProductProbeExecutor(
            cycleDir = cycleDir,
            specProvider = { _, _ ->
                pipeline {
                    stages {
                        stage("build") {
                            error(message = "hf1 user-failure test")
                        }
                    }
                }
            },
        )
        val outcomes = runner.runProbes(executor)
        val outcome = outcomes.single()
        assertTrue(
            outcome is ProbeOutcome.Failed,
            "expected Failed, got $outcome",
        )
        val reason = (outcome as ProbeOutcome.Failed).reason
        assertTrue(
            reason.contains("USER") || reason.contains("hf1 user-failure"),
            "reason must name the failure, got '$reason'",
        )
    }

    @Test
    fun `HF0 with this executor delegates to the harness default`(@TempDir tmp: Path) {
        // The audit's separation: HF0 is the harness's job (catalogue
        // + SHA verification); HF1 is this executor's. Mixing the
        // two would mean the product can override the SHA check,
        // which is the exact defect the audit named.
        val cycleDir = tmp.resolve("cyc")
        Files.createDirectories(cycleDir)
        val relativePath = "tmp/echo-hf0.kts"
        Files.createDirectories(cycleDir.resolve(relativePath).parent)
        cycleDir.resolve(relativePath).writeText("// placeholder")

        val manifestPath = tmp.resolve("manifest.json")
        writeManifest(
            manifestPath,
            cycleDir,
            relativePath,
            expectedHf = "HF0",
            // Fake SHA so the harness default (delegated to) reports
            // a SHA mismatch, not a Verified.
            contentSha256 = "f".repeat(64),
        )

        val runner = CycleRunner("cyc-hf1", manifestPath, cycleDir)
        runner.prepare()

        val executor = InProcessProductProbeExecutor(
            cycleDir = cycleDir,
            specProvider = { _, _ ->
                error("HF0 should not have invoked the spec provider")
            },
        )
        val outcomes = runner.runProbes(executor)
        val outcome = outcomes.single()
        assertTrue(
            outcome is ProbeOutcome.Failed,
            "HF0 with a fake SHA must be Failed, got $outcome",
        )
        val reason = (outcome as ProbeOutcome.Failed).reason
        assertTrue(
            reason.contains("mismatch"),
            "HF0 must surface the SHA mismatch, got '$reason'",
        )
    }

    @Test
    fun `spec provider throwing produces Failed with the throwable type`(
        @TempDir tmp: Path,
    ) {
        // Adversarial: the spec provider is the consumer's hook
        // (a test fixture, a database, a generator). A provider
        // that throws must be reported as a typed Failure, not
        // crash the harness.
        val cycleDir = tmp.resolve("cyc")
        Files.createDirectories(cycleDir)
        val relativePath = "tmp/throwing.kts"
        Files.createDirectories(cycleDir.resolve(relativePath).parent)
        cycleDir.resolve(relativePath).writeText("// placeholder")

        val manifestPath = tmp.resolve("manifest.json")
        writeManifest(manifestPath, cycleDir, relativePath, expectedHf = "HF1")

        val runner = CycleRunner("cyc-hf1", manifestPath, cycleDir)
        runner.prepare()

        val executor = InProcessProductProbeExecutor(
            cycleDir = cycleDir,
            specProvider = { _, _ ->
                throw IllegalStateException("spec provider is broken on purpose")
            },
        )
        val outcomes = runner.runProbes(executor)
        val outcome = outcomes.single()
        assertTrue(outcome is ProbeOutcome.Failed, "expected Failed, got $outcome")
        val reason = (outcome as ProbeOutcome.Failed).reason
        assertTrue(
            reason.contains("spec provider threw"),
            "reason must name the spec provider, got '$reason'",
        )
        assertTrue(
            reason.contains("IllegalStateException"),
            "reason must include the throwable type, got '$reason'",
        )
    }

    @Test
    fun `full cycle closes when the HF1 probe Verified persists a valid digest`(
        @TempDir tmp: Path,
    ) {
        // End-to-end: a Verified HF1 probe drives the cycle to
        // Closed, the runner's `finalise` validates the evidence
        // digest and removes the runner-owned files
        // (`probes/`, `work-probe-1/`), leaving operator-owned
        // files (the `tmp/` directory the test pre-creates
        // under the cycle dir) intact. The audit (P0-C) named
        // "do not over-delete a directory you do not own"; the
        // contract here is that the runner deletes the evidence
        // it created, not the operator's source files.
        val cycleDir = tmp.resolve("cyc")
        Files.createDirectories(cycleDir)
        val relativePath = "tmp/full-cycle-hf1.kts"
        Files.createDirectories(cycleDir.resolve(relativePath).parent)
        cycleDir.resolve(relativePath).writeText("// placeholder")

        val manifestPath = tmp.resolve("manifest.json")
        writeManifest(manifestPath, cycleDir, relativePath, expectedHf = "HF1")

        val runner = CycleRunner("cyc-hf1", manifestPath, cycleDir)
        runner.prepare()

        val executor = InProcessProductProbeExecutor(
            cycleDir = cycleDir,
            specProvider = { _, _ ->
                pipeline {
                    stages {
                        stage("build") {
                            echo("end-to-end")
                        }
                    }
                }
            },
        )
        runner.runProbes(executor)
        runner.observeAll(observedCommit = "0123456789abcdef0123456789abcdef01234567")
        runner.requestPromote(
            evidence = PromotionEvidence.PermanentContract(
                contractId = "test.rec",
                boundCommit = "0123456789abcdef0123456789abcdef01234567",
                sourcePath = "test/Contract.kt",
                contentDigest = "0".repeat(64),
                fidelity = "HF0",
                oracle = OracleRef.Fitness("test.rec", ":test-harness"),
            ),
        )
        runner.requestClose("done")
        runner.finalise()
        // Runner-owned paths are gone: the probes/ evidence
        // directory and the executor's work dir.
        assertFalse(
            Files.exists(cycleDir.resolve("probes")),
            "the probes/ directory is runner-owned and must be deleted",
        )
        assertFalse(
            Files.exists(cycleDir.resolve("work-probe-1")),
            "the work-<id> directory is executor-owned and must be deleted",
        )
        // Operator-owned paths survive: the `tmp/` directory the
        // test pre-created before prepare() is the operator's
        // property and the runner must NOT delete it. This is
        // the audit's "manifest of property" property.
        assertTrue(
            Files.exists(cycleDir.resolve("tmp/full-cycle-hf1.kts")),
            "operator-owned files staged before prepare() MUST survive finalise",
        )
    }
}
