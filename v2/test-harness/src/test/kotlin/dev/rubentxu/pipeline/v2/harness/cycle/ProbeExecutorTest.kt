package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Adversarial tests for [ProbeExecutor.runHF0] — the P0 close-out
 * of the 2026-10-16 BQ4 audit. The previous BQ4 vertical validated
 * the format of `contentSha256` only; these tests pin the
 * real-bytes property: the executor must read the file, compute
 * its SHA-256, and require the digest matches the manifest.
 */
class ProbeExecutorTest {

    /**
     * Helper: write a manifest with a real or fake `content_sha256`
     * that points at a pre-staged file under the cycle dir.
     */
    private fun writeManifest(
        path: Path,
        cycleDir: Path,
        relativePath: String,
        contentSha256: String,
        contractId: String = "OutputPlane.redaction.streaming",
    ) {
        val manifest = """
            {
              "cycle_id": "cyc-1",
              "source_sha": "0123456789abcdef0123456789abcdef01234567",
              "expected_hf": "HF0",
              "isolation_policy": "cycleDirOnly",
              "replacement": {
                "kind": "PERMANENT_CONTRACT",
                "id": "x",
                "evidence": "y"
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
        Files.writeString(path, manifest)
    }

    @Test
    fun `sha256HexOfFile matches the JDK oracle for known bytes`(@TempDir tmp: Path) {
        // BQ4-P0 oracle property: lowercase, 64 chars, equal to
        // a hand-computed SHA-256 of the same bytes. The bytes
        // 0x00 0x01 0x02 0x03 0x7f 0x80 0xff cover the boundary
        // cases (NUL, low ASCII, high ASCII, signed-byte boundary).
        val file = tmp.resolve("x.bin")
        Files.write(file, byteArrayOf(0, 1, 2, 3, 0x7f, 0x80.toByte(), -1))
        val computed = sha256HexOfFile(file)
        assertEquals(64, computed.length)
        assertTrue(computed.all { it in '0'..'9' || it in 'a'..'f' })
        val oracle = java.security.MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it) }
        assertEquals(oracle, computed)
    }

    @Test
    fun `HF0 with matching content_sha256 but unclassified path returns Failed after the SHA check`(
        @TempDir tmp: Path,
    ) {
        val cycleDir = tmp.resolve("cyc")
        // A path the catalogue does NOT know. The audit's P0 fix
        // runs the SHA check first; with matching bytes, the
        // failure moves to the catalogue step. The probe is still
        // a Failed (not Verified): a manifest with a path the
        // catalogue doesn't bind cannot produce Verified.
        val relativePath = "tmp/unknown-path.txt"
        val probeFile = cycleDir.resolve(relativePath)
        Files.createDirectories(probeFile.parent)
        val payload = "the quick brown fox jumps over the lazy dog"
        Files.writeString(probeFile, payload)
        val realDigest = sha256HexOfFile(probeFile)

        val manifest = tmp.resolve("manifest.json")
        writeManifest(
            path = manifest,
            cycleDir = cycleDir,
            relativePath = relativePath,
            contentSha256 = realDigest,
        )

        val runner = CycleRunner("cyc-1", manifest, cycleDir)
        runner.prepare()
        val outcomes = runner.runProbes(ProbeExecutor(cycleDir = cycleDir))
        val outcome = outcomes.single()
        assertTrue(outcome is ProbeOutcome.Failed) { "got $outcome" }
        val reason = (outcome as ProbeOutcome.Failed).reason
        // The catalogue step is what rejects; the SHA step must
        // not have rejected first.
        assertTrue(
            reason.contains("HF0 contract mismatch") || reason.contains("HF0 fidelity") ||
                reason.contains("UnclassifiedPath") || reason.contains("ImpactResolver refused"),
            "expected catalogue rejection, got '$reason'",
        )
    }

    @Test
    fun `HF0 with mismatched content_sha256 produces Failed with explicit reason`(
        @TempDir tmp: Path,
    ) {
        val cycleDir = tmp.resolve("cyc")
        val relativePath = "tmp/source.bin"
        val probeFile = cycleDir.resolve(relativePath)
        Files.createDirectories(probeFile.parent)
        Files.writeString(probeFile, "real bytes")
        val realDigest = sha256HexOfFile(probeFile)
        val fakeDigest = "f".repeat(64).let { if (it == realDigest) "0".repeat(64) else it }

        val manifest = tmp.resolve("manifest.json")
        writeManifest(
            path = manifest,
            cycleDir = cycleDir,
            relativePath = relativePath,
            contentSha256 = fakeDigest,
        )

        val runner = CycleRunner("cyc-1", manifest, cycleDir)
        runner.prepare()
        val outcomes = runner.runProbes(ProbeExecutor(cycleDir = cycleDir))
        val outcome = outcomes.single()
        assertTrue(outcome is ProbeOutcome.Failed) { "got $outcome" }
        val reason = (outcome as ProbeOutcome.Failed).reason
        assertTrue(reason.contains("mismatch"), "reason must name the mismatch, got '$reason'")
        assertTrue(reason.contains("manifest=$fakeDigest"), "reason must include manifest digest")
        assertTrue(reason.contains("file=$realDigest"), "reason must include file digest")
    }

    @Test
    fun `HF0 with absent file produces Failed naming the missing path`(
        @TempDir tmp: Path,
    ) {
        val cycleDir = tmp.resolve("cyc")
        Files.createDirectories(cycleDir)
        val manifest = tmp.resolve("manifest.json")
        writeManifest(
            path = manifest,
            cycleDir = cycleDir,
            relativePath = "tmp/does-not-exist.bin",
            contentSha256 = "0".repeat(64),
        )

        val runner = CycleRunner("cyc-1", manifest, cycleDir)
        runner.prepare()
        val outcomes = runner.runProbes(ProbeExecutor(cycleDir = cycleDir))
        val outcome = outcomes.single()
        assertTrue(outcome is ProbeOutcome.Failed) { "got $outcome" }
        val reason = (outcome as ProbeOutcome.Failed).reason
        assertTrue(reason.contains("absent"), "reason must name the absent file, got '$reason'")
    }

    @Test
    fun `finalise recomputes evidenceDigest and refuses on mismatch`(@TempDir tmp: Path) {
        // The `finalise` test uses a stub executor that returns
        // `Verified` for the probe (the harness-internal
        // `ProbeExecutor.runHF0` cannot produce Verified without
        // a catalogue-bound contract at HF0; this test pins the
        // runner's digest-verification behaviour in isolation).
        val cycleDir = tmp.resolve("cyc")
        Files.createDirectories(cycleDir)
        val manifest = tmp.resolve("manifest.json")
        writeManifest(
            path = manifest,
            cycleDir = cycleDir,
            relativePath = "tmp/source.bin",
            contentSha256 = "0".repeat(64),
        )

        val runner = CycleRunner("cyc-1", manifest, cycleDir)
        runner.prepare()
        val executor = object : ProbeExecutor(cycleDir) {
            override fun execute(probe: ProbeEntry, manifest: CycleManifest): ProbeOutcome =
                ProbeOutcome.Verified(evidenceRef = "<pending>")
        }
        runner.runProbes(executor)
        runner.observeAll(observedCommit = "0123456789abcdef0123456789abcdef01234567")
        runner.requestPromote(replacementEvidence = "rec")
        runner.requestClose("done")

        // Tamper with the persisted evidence file. The runner must
        // refuse to delete the cycle dir.
        val evidenceFile = cycleDir.resolve("probes/probe-1.json")
        Files.writeString(evidenceFile, Files.readString(evidenceFile) + "\n// tampered\n")

        val ex = kotlin.runCatching { runner.finalise() }.exceptionOrNull()
        assertNotNull(ex, "finalise must throw on tampered evidence")
        val exMessage = ex?.message ?: "<no message>"
        assertTrue(
            exMessage.contains("modified after persistence"),
            "refusal must name the modification, got '$exMessage'",
        )
        assertTrue(Files.isDirectory(cycleDir), "the cycle dir must NOT have been deleted")
    }

    @Test
    fun `finalise succeeds when evidenceDigest matches`(@TempDir tmp: Path) {
        // Stub executor (same reason as the tampered test):
        // the runner's digest-verification is the subject under
        // test, not the catalogue. A `Verified` outcome drives the
        // cycle to Closed so finalise can run.
        val cycleDir = tmp.resolve("cyc")
        Files.createDirectories(cycleDir)
        val manifest = tmp.resolve("manifest.json")
        writeManifest(
            path = manifest,
            cycleDir = cycleDir,
            relativePath = "tmp/source.bin",
            contentSha256 = "0".repeat(64),
        )

        val runner = CycleRunner("cyc-1", manifest, cycleDir)
        runner.prepare()
        val executor = object : ProbeExecutor(cycleDir) {
            override fun execute(probe: ProbeEntry, manifest: CycleManifest): ProbeOutcome =
                ProbeOutcome.Verified(evidenceRef = "<pending>")
        }
        runner.runProbes(executor)
        runner.observeAll(observedCommit = "0123456789abcdef0123456789abcdef01234567")
        runner.requestPromote(replacementEvidence = "rec")
        runner.requestClose("done")
        runner.finalise()
        org.junit.jupiter.api.Assertions.assertFalse(
            Files.exists(cycleDir),
            "the cycle dir must be deleted after a clean finalise",
        )
    }

    @Test
    fun `HF0 persists a JSON file with an evidenceDigest field`(
        @TempDir tmp: Path,
    ) {
        // The audit's P0-A4: the persisted JSON MUST contain an
        // `evidenceDigest` field. Use the stub executor so the
        // probe is Verified (the harness's real `runHF0` cannot
        // produce Verified for an unclassified path).
        val cycleDir = tmp.resolve("cyc")
        Files.createDirectories(cycleDir)
        val manifest = tmp.resolve("manifest.json")
        writeManifest(
            path = manifest,
            cycleDir = cycleDir,
            relativePath = "tmp/source.bin",
            contentSha256 = "0".repeat(64),
        )

        val runner = CycleRunner("cyc-1", manifest, cycleDir)
        runner.prepare()
        val executor = object : ProbeExecutor(cycleDir) {
            override fun execute(probe: ProbeEntry, manifest: CycleManifest): ProbeOutcome =
                ProbeOutcome.Verified(evidenceRef = "<pending>")
        }
        runner.runProbes(executor)

        val jsonFile = cycleDir.resolve("probes/probe-1.json")
        assertTrue(Files.exists(jsonFile), "executor must persist evidence JSON")
        val json = Files.readString(jsonFile)
        assertTrue(json.contains("\"evidenceDigest\""), "persisted JSON has no evidenceDigest field")
        val digestMatch = Regex("\"evidenceDigest\"\\s*:\\s*\"([0-9a-f]{64})\"").find(json)
        assertNotNull(digestMatch, "evidenceDigest is not 64-char lowercase hex")
    }
}
