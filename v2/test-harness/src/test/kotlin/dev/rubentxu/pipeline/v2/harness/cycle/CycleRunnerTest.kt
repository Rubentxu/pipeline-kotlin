package dev.rubentxu.pipeline.v2.harness.cycle

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity
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
 * and owns deletion in `finalise` (no callback). The
 * `requestPromote` path now routes through
 * [PromotionEvidenceVerifier] before the decider sees the
 * `PromoteRequested` event, so the tests below use a
 * [MapOracleLedger] and a real contract file staged in the
 * temp dir.
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
        override fun execute(probe: ProbeEntry, manifest: CycleManifest): ProbeExecutor.Companion.ProbeExecutionResult {
            val idx = probeIndex.getAndIncrement()
            return ProbeExecutor.Companion.ProbeExecutionResult.AlreadyPersisted(outcomes[idx])
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
        override fun execute(probe: ProbeEntry, manifest: CycleManifest): ProbeExecutor.Companion.ProbeExecutionResult =
            ProbeExecutor.Companion.ProbeExecutionResult.AlreadyPersisted(
                ProbeOutcome.Verified(evidenceRef = ref)
            )
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

    /**
     * The "well-formed but never verified" evidence the
     * non-promote tests use. The verifier never runs in those
     * tests, so a real file and a real oracle are not
     * required — the carrier just has to be parseable JSON.
     */
    private fun stubEvidence(sourceSha: String, expectedHF: Fidelity): PromotionEvidence {
        val oracle = OracleRef.Fitness("CycleRunnerTest.fixture", ":test-harness")
        return PromotionEvidence.PermanentContract(
            contractId = "OutputPlane.redaction.streaming",
            boundCommit = sourceSha,
            sourcePath = "staging/StreamingRedactor.kt",
            contentDigest = "0".repeat(64),
            fidelity = expectedHF,
            oracle = oracle,
        )
    }

    /**
     * Write a manifest with the BQ4 v2 wire format
     * (`replacement.evidence` is a typed [PromotionEvidence],
     * not a free-form string). The `evidence` defaults to a
     * stub carrier that the runner accepts at parse time; tests
     * that exercise `requestPromote` replace it with a real
     * carrier and stage a real contract file.
     */
    private fun writeManifest(
        path: Path,
        cycleId: String = "cyc-1",
        sourceSha: String = "0123456789abcdef0123456789abcdef01234567",
        expectedHF: Fidelity = Fidelity.HF0,
        contractId: String = "OutputPlane.redaction.streaming",
        relativePath: String = "v2/pipeline-credentials-api/src/main/kotlin/dev/rubentxu/pipeline/v2/credentials/api/StreamingRedactor.kt",
        evidence: PromotionEvidence = stubEvidence(sourceSha, expectedHF),
    ) {
        val oracle = when (val o = evidence.oracle) {
            is OracleRef.Fitness ->
                """{"type":"fitness","name":"${o.name}","module":"${o.module}"}"""
            is OracleRef.Junit ->
                """{"type":"junit","test_class":"${o.testClass}","test_method":"${o.testMethod}","module":"${o.module}"}"""
        }
        val evidenceJson = when (evidence) {
            is PromotionEvidence.PermanentContract -> """
                {
                  "type": "permanent_contract",
                  "contract_id": "${evidence.contractId}",
                  "bound_commit": "${evidence.boundCommit}",
                  "source_path": "${evidence.sourcePath}",
                  "content_digest": "${evidence.contentDigest}",
                  "fidelity": "${evidence.fidelity}",
                  "oracle": $oracle
                }
            """.trimIndent()
            is PromotionEvidence.CertifierReceipt -> """
                {
                  "type": "certifier_receipt",
                  "receipt_id": "${evidence.receiptId}",
                  "signed_commit": "${evidence.boundCommit}",
                  "receipt_digest": "${evidence.receiptDigest}",
                  "fidelity": "${evidence.fidelity}",
                  "oracle": $oracle
                }
            """.trimIndent()
        }
        // contentSha256 on the probe is just a 64-char hex stub;
        // the runner does not verify it against the file (only
        // validates the format and that the resolved path is
        // inside the cycle dir).
        val sha = "0".repeat(64)
        val manifest = """
            {
              "cycle_id": "$cycleId",
              "source_sha": "$sourceSha",
              "expected_hf": "${expectedHF.name}",
              "isolation_policy": "cycleDirOnly",
              "replacement": {
                "evidence": $evidenceJson
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
                  "mutation_or_negative": "redact transcript",
                  "content_sha256": "$sha"
                }
              ]
            }
        """.trimIndent()
        Files.writeString(path, manifest)
    }

    /**
     * SHA-256 of the file at [path], lowercase hex (64 chars).
     * Mirrors the runner's `sha256HexOfFile` so the test can
     * build a contract file whose digest matches the manifest
     * claim exactly.
     */
    private fun sha256HexOfFile(path: Path): String =
        dev.rubentxu.pipeline.v2.harness.cycle.sha256HexOfFile(path)

    /**
     * Build a runner whose verifier will accept a
     * [PromotionEvidence.PermanentContract]: stage a real
     * contract file under [tmp], compute its digest, write a
     * manifest pointing at it, and inject a
     * [MapOracleLedger] that records a PASS at the
     * [sourceSha]. Returns the runner, the staged contract
     * file, and the structured evidence.
     */
    private data class PromotableRunner(
        val runner: CycleRunner,
        val evidence: PromotionEvidence,
        val contractFile: Path,
    )

    private fun setupPromotableRunner(
        tmp: Path,
        sourceSha: String = "0123456789abcdef0123456789abcdef01234567",
        expectedHF: Fidelity = Fidelity.HF0,
    ): PromotableRunner {
        val contractFile = tmp.resolve("MyContract.kt")
        Files.writeString(contractFile, "// permanent contract body\nclass MyContract\n")
        val contractDigest = sha256HexOfFile(contractFile)
        val oracle = OracleRef.Fitness("TestRunner.fixture", ":test-harness")
        val evidence = PromotionEvidence.PermanentContract(
            contractId = "OutputPlane.redaction.streaming",
            boundCommit = sourceSha,
            sourcePath = "MyContract.kt",
            contentDigest = contractDigest,
            fidelity = expectedHF,
            oracle = oracle,
        )
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest, sourceSha = sourceSha, expectedHF = expectedHF, evidence = evidence)
        val ledger = MapOracleLedger(mapOf(oracle to setOf(sourceSha)))
        val runner = CycleRunner(
            cycleId = "cyc-1",
            manifestPath = manifest,
            cycleDir = tmp.resolve("cycles/cyc-1"),
            oracleLedger = ledger,
            repoRoot = tmp,
        )
        return PromotableRunner(runner, evidence, contractFile)
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
        writeManifest(manifest, expectedHF = Fidelity.HF2)
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
        val runner = CycleRunner(cycleId = "cyc-1", manifestPath = manifest, cycleDir = tmp.resolve("cycles/cyc-1"))
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
        val runner = CycleRunner(cycleId = "cyc-1", manifestPath = manifest, cycleDir = tmp.resolve("cycles/cyc-1"))
        runner.prepare()
        runner.runProbesWithVerified()
        runner.observeAll(observedCommit = "abc123")
        assertEquals(CycleState.Observed("abc123"), runner.state)
    }

    @Test
    fun `adjudicate moves Observed to Promoted when verifier accepts`(@TempDir tmp: Path) {
        // BQ4 v2: the runner routes requestPromote through
        // PromotionEvidenceVerifier. The verifier checks
        // existence + digest + commit + fidelity + oracle; the
        // test below stages a real contract file with a real
        // digest, matches the bound commit to the manifest's
        // source_sha, and injects a MapOracleLedger that says
        // PASS at that commit. The cycle advances to Promoted.
        val (runner, _, _) = setupPromotableRunner(tmp)
        runner.prepare()
        runner.runProbesWithVerified()
        runner.observeAll("abc123")
        runner.requestPromote(evidence = requireNotNull(runner.manifest).replacement.evidence)
        assertEquals(CycleState.Promoted("abc123"), runner.state) { "got ${runner.state}" }
    }

    @Test
    fun `requestPromote refuses on OtherCommit (verifier commit-mismatch)`(@TempDir tmp: Path) {
        // BQ4 v2: an evidence whose boundCommit does not match
        // the runner's sourceSha is refused with
        // "other commit: ..." (the exact reason string the
        // verifier produces). The previous shape accepted any
        // non-blank string; the new shape refuses on the ADT.
        val (runner, _, _) = setupPromotableRunner(tmp)
        runner.prepare()
        runner.runProbesWithVerified()
        runner.observeAll("abc123")
        val mismatched = PromotionEvidence.PermanentContract(
            contractId = "OutputPlane.redaction.streaming",
            boundCommit = "fedcba9876543210fedcba9876543210fedcba98",
            sourcePath = "MyContract.kt",
            contentDigest = "0".repeat(64),
            fidelity = Fidelity.HF0,
            oracle = OracleRef.Fitness("TestRunner.fixture", ":test-harness"),
        )
        val outcome = runner.requestPromote(mismatched)
        assertTrue(outcome is CycleOutcome.Next) { "got $outcome" }
        val state = (outcome as CycleOutcome.Next).nextState
        assertTrue(state is CycleState.Refused) { "expected Refused on commit-mismatch, got $state" }
        val reason = (state as CycleState.Refused).reason
        assertTrue(reason.startsWith("other commit:"), "got '$reason'")
    }

    @Test
    fun `requestPromote refuses on content_digest mismatch`(@TempDir tmp: Path) {
        // BQ4 v2: a contract whose recorded content_digest
        // does not match the SHA-256 of the file at sourcePath
        // is refused. The previous shape accepted any string;
        // the new shape refuses on the digest property.
        val (runner, _, _) = setupPromotableRunner(tmp)
        runner.prepare()
        runner.runProbesWithVerified()
        runner.observeAll("abc123")
        val oracle = OracleRef.Fitness("TestRunner.fixture", ":test-harness")
        val ledger = MapOracleLedger(mapOf(oracle to setOf("0123456789abcdef0123456789abcdef01234567")))
        val badEvidence = PromotionEvidence.PermanentContract(
            contractId = "OutputPlane.redaction.streaming",
            boundCommit = "0123456789abcdef0123456789abcdef01234567",
            sourcePath = "MyContract.kt",
            contentDigest = "deadbeef".repeat(8),
            fidelity = Fidelity.HF0,
            oracle = oracle,
        )
        val outcome = runner.requestPromote(badEvidence)
        val state = (outcome as CycleOutcome.Next).nextState
        assertTrue(state is CycleState.Refused) { "expected Refused on digest-mismatch, got $state" }
        assertTrue(
            (state as CycleState.Refused).reason.contains("content_digest mismatch"),
            "got '${state.reason}'",
        )
    }

    @Test
    fun `requestPromote refuses when contract file is missing`(@TempDir tmp: Path) {
        // BQ4 v2: existence is the first property the verifier
        // checks for a PermanentContract. A manifest that
        // names a file which is not on disk is refused.
        val (runner, _, _) = setupPromotableRunner(tmp)
        runner.prepare()
        runner.runProbesWithVerified()
        runner.observeAll("abc123")
        val sourceSha = "0123456789abcdef0123456789abcdef01234567"
        val oracle = OracleRef.Fitness("TestRunner.fixture", ":test-harness")
        val ledger = MapOracleLedger(mapOf(oracle to setOf(sourceSha)))
        // Replace the runner with one that has an empty
        // repoRoot so sourcePath does not resolve.
        val manifest = tmp.resolve("manifest-missing.json")
        val evidence = PromotionEvidence.PermanentContract(
            contractId = "OutputPlane.redaction.streaming",
            boundCommit = sourceSha,
            sourcePath = "does-not-exist/MyContract.kt",
            contentDigest = "0".repeat(64),
            fidelity = Fidelity.HF0,
            oracle = oracle,
        )
        writeManifest(manifest, sourceSha = sourceSha, evidence = evidence)
        val runner2 = CycleRunner(
            cycleId = "cyc-1",
            manifestPath = manifest,
            cycleDir = tmp.resolve("cycles/cyc-1-missing"),
            oracleLedger = ledger,
            repoRoot = tmp,
        )
        runner2.prepare()
        runner2.runProbesWithVerified()
        runner2.observeAll("abc123")
        val outcome = runner2.requestPromote(evidence)
        val state = (outcome as CycleOutcome.Next).nextState
        assertTrue(state is CycleState.Refused) { "expected Refused on missing file, got $state" }
        assertTrue(
            (state as CycleState.Refused).reason.contains("contract file missing"),
            "got '${(state as CycleState.Refused).reason}'",
        )
    }

    @Test
    fun `requestPromote with CertifierReceipt transitions to BlockedExternal`(@TempDir tmp: Path) {
        // BQ4 v2: a signed receipt cannot be verified in-process;
        // the cycle becomes BlockedExternal (a `CertifierReceipt`
        // is deferred to the certifier). The carrier still
        // carries the bound commit, digest and oracle so the
        // certifier can verify the receipt's evidence on its
        // side; the runner's job is to refuse to pretend it
        // already did.
        val (runner, _, _) = setupPromotableRunner(tmp)
        runner.prepare()
        runner.runProbesWithVerified()
        runner.observeAll("abc123")
        val sourceSha = "0123456789abcdef0123456789abcdef01234567"
        val oracle = OracleRef.Fitness("TestRunner.fixture", ":test-harness")
        val receipt = PromotionEvidence.CertifierReceipt(
            receiptId = "v0.62.0-rc5",
            boundCommit = sourceSha,
            receiptDigest = "0".repeat(64),
            fidelity = Fidelity.HF0,
            oracle = oracle,
        )
        val outcome = runner.requestPromote(receipt)
        assertTrue(outcome is CycleOutcome.Next) { "got $outcome" }
        val state = (outcome as CycleOutcome.Next).nextState
        assertTrue(state is CycleState.BlockedExternal) { "expected BlockedExternal, got $state" }
        assertTrue(
            (state as CycleState.BlockedExternal).blocker.contains("certifier receipt"),
            "got '${(state as CycleState.BlockedExternal).blocker}'",
        )
    }

    @Test
    fun `close moves Promoted to Closed with the close reason`(@TempDir tmp: Path) {
        val (runner, _, _) = setupPromotableRunner(tmp)
        runner.prepare()
        runner.runProbesWithVerified()
        runner.observeAll("abc123")
        runner.requestPromote(evidence = requireNotNull(runner.manifest).replacement.evidence)
        runner.requestClose("temporary probes retired")
        assertEquals(CycleState.Closed("temporary probes retired"), runner.state)
    }

    @Test
    fun `close moves Observed to Closed is IllegalTransition (BQ4 audit)`(@TempDir tmp: Path) {
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest)
        val runner = CycleRunner(cycleId = "cyc-1", manifestPath = manifest, cycleDir = tmp.resolve("cycles/cyc-1"))
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
        val runner = CycleRunner(cycleId = "cyc-1", manifestPath = manifest, cycleDir = tmp.resolve("cycles/cyc-1"))
        runner.requestClose("manifest invalid")
        assertTrue(runner.state is CycleState.Refused) { "got ${runner.state}" }
    }

    @Test
    fun `close from Running is Refused (probes never finished)`(@TempDir tmp: Path) {
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest)
        val runner = CycleRunner(cycleId = "cyc-1", manifestPath = manifest, cycleDir = tmp.resolve("cycles/cyc-1"))
        runner.prepare()
        // No probe run; the cycle is still Running.
        runner.requestClose("probes never finished")
        assertTrue(runner.state is CycleState.Refused) { "got ${runner.state}" }
    }

    @Test
    fun `BQ4 P0 HITO 1 weakness 1 - finalise does NOT delete a file added concurrently after prepare`(
        @TempDir tmp: Path,
    ) {
        // The HITO 1 "Propiedad explícita de ficheros" bullet
        // in QUALITY_VALUE_ADDENDUM.md (2026-10-17) named this
        // as the most expensive defect class in the cycle:
        // the previous shape used
        // `currentEntries - operatorOwnedSnapshot` to decide
        // what the runner owned. A file added by an external
        // process (a co-resident tool, a watcher, a manual
        // operator action) AFTER the runner's `prepare()`
        // snapshot was therefore in the diff and was DELETED
        // by `finalise()`. The audit's "Debilidades" list
        // names this as item (1).
        //
        // The new shape uses an explicit registry of paths
        // the runner and the executor themselves created;
        // concurrent files are not in the registry and
        // survive `finalise()`. This is the property the
        // test pins.
        val dir = tmp.resolve("cycles/cyc-1")
        val (runner, _, _) = setupPromotableRunner(tmp)
        val aligned = CycleRunner(
            cycleId = "cyc-1",
            manifestPath = runner.manifestPath,
            cycleDir = dir,
            oracleLedger = MapOracleLedger(
                mapOf(
                    OracleRef.Fitness("TestRunner.fixture", ":test-harness") to
                        setOf("0123456789abcdef0123456789abcdef01234567"),
                ),
            ),
            repoRoot = tmp,
        )
        aligned.prepare()
        aligned.runProbesWithVerified()
        aligned.observeAll("abc123")
        aligned.requestPromote(evidence = requireNotNull(aligned.manifest).replacement.evidence)
        assertTrue(aligned.state is CycleState.Promoted) { "got ${aligned.state}" }
        aligned.requestClose("test close")
        assertTrue(aligned.state is CycleState.Closed) { "got ${aligned.state}" }

        // A concurrent process adds a file AFTER prepare() and
        // BEFORE finalise(). The runner's registry does not
        // know about this file; finalise() must leave it
        // alone. The audit's "carreras" (races) concern is
        // exactly this scenario.
        val alienFile = dir.resolve("alien-from-other-process.txt")
        Files.writeString(alienFile, "this file was added by a concurrent process")

        aligned.finalise()

        assertTrue(
            Files.exists(alienFile),
            "BQ4 P0 HITO 1: finalise() MUST NOT delete a file added by an " +
                "external process after prepare() — only files the runner or " +
                "executor themselves registered as created are owned by the " +
                "runner. The previous snapshot-diff shape classified the " +
                "concurrent file as runner-owned and DELETED it; the new " +
                "registry shape leaves it in place. A regression to the " +
                "snapshot-diff re-opens the 'carreras' (races) defect class.",
        )
        assertEquals(
            "this file was added by a concurrent process",
            Files.readString(alienFile),
            "BQ4 P0 HITO 1: the concurrent file's content MUST survive " +
                "finalise() unchanged",
        )
    }

    @Test
    fun `BQ4 P0 HITO 1 AAT - finalise refuses to delete a probe JSON whose body was tampered after persistence`(
        @TempDir tmp: Path,
    ) {
        // The HITO 1 "AAT de evidencia falsificada que
        // debe rechazarse" bullet in
        // QUALITY_VALUE_ADDENDUM.md (2026-10-17) names
        // this as a required AAT: a probe evidence
        // file whose body has been modified after
        // persistence (e.g. an attacker swapped the
        // captured bytes, or a writer defect
        // serialised a different body) MUST be
        // refused at `finalise()` time. The runner's
        // typed [validateEvidenceDigest] recomputes
        // the SHA-256 of the body (minus the recorded
        // `evidenceDigest` field) and refuses the
        // cycle on mismatch. The cycle's deletion
        // scope is therefore anchored to the byte
        // content, not the file's mere presence.
        //
        // The test stages a real probe JSON via the
        // TrivialVerifiedExecutor-shaped path: we
        // take the real ProbeExecutor's
        // `body + evidenceDigest` format, write a
        // valid file, then mutate a single byte in
        // the body. The runner's `finalise()` must
        // refuse with the typed
        // "(JSON parse failed)" or "evidenceDigest
        // mismatch" error; the cycle dir is left
        // intact so the operator can inspect what
        // was tampered.
        val dir = tmp.resolve("cycles/cyc-1")
        val (runner, _, _) = setupPromotableRunner(tmp)
        val aligned = CycleRunner(
            cycleId = "cyc-1",
            manifestPath = runner.manifestPath,
            cycleDir = dir,
            oracleLedger = MapOracleLedger(
                mapOf(
                    OracleRef.Fitness("TestRunner.fixture", ":test-harness") to
                        setOf("0123456789abcdef0123456789abcdef01234567"),
                ),
            ),
            repoRoot = tmp,
        )
        aligned.prepare()
        aligned.runProbesWithVerified()
        aligned.observeAll("abc123")
        aligned.requestPromote(evidence = requireNotNull(aligned.manifest).replacement.evidence)
        assertTrue(aligned.state is CycleState.Promoted) { "got ${aligned.state}" }
        aligned.requestClose("test close")
        assertTrue(aligned.state is CycleState.Closed) { "got ${aligned.state}" }

        // Stage a fake probe JSON in the cycle's
        // `probes/` subdir with a valid
        // evidenceDigest field. The runner
        // `finalise()` will iterate the subdir and
        // call `validateEvidenceDigest` on every
        // `.json` file.
        val probesDir = dir.resolve("probes")
        Files.createDirectories(probesDir)
        val probeFile = probesDir.resolve("probe-1.json")
        val body = """{
  "probeId": "probe-1",
  "contractId": "TestRunner.fixture",
  "kind": "Verified",
  "outcome": "Verified",
  "exitCode": 0,
  "evidenceRef": "<persisted>",
  "reason": null
}"""
        // Compute the recorded digest the way
        // ProbeExecutor.persist does: SHA-256 of
        // the body bytes ending in `\n` (after the
        // last data line) BEFORE the
        // `,\n  "evidenceDigest":` substring.
        val bodyBytes = (body + "\n").toByteArray()
        val recordedDigest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bodyBytes)
            .joinToString("") { "%02x".format(it) }
        Files.writeString(
            probeFile,
            body + ",\n  \"evidenceDigest\": \"$recordedDigest\"\n}\n",
        )

        // Sanity: the un-tampered file would
        // validate. We then mutate a single byte
        // in the body (the digit `0` becomes `1` in
        // `exitCode`); the recomputed digest will
        // mismatch the recorded one.
        val originalText = Files.readString(probeFile)
        val tamperedText = originalText.replace("\"exitCode\": 0", "\"exitCode\": 1")
        assertTrue(tamperedText != originalText, "mutation must change the file")
        Files.writeString(probeFile, tamperedText)

        // BQ4 P0: finalise() MUST refuse on a
        // tampered probe file. The error is the
        // typed validateEvidenceDigest refusal
        // (the body is still valid JSON, so the
        // parser succeeds; the digest mismatch is
        // the typed failure mode).
        val ex = kotlin.runCatching { aligned.finalise() }.exceptionOrNull()
        assertTrue(
            ex is IllegalStateException &&
                (ex.message?.contains("evidenceDigest") == true ||
                    ex.message?.contains("modified after persistence") == true),
            "BQ4 P0 HITO 1 AAT: finalise() MUST refuse a tampered probe evidence " +
                "file. A regression that accepts the file (silent green) is the " +
                "exact defect the HITO 1 audit named. Got: ${ex?.message}",
        )
        // The cycle dir is left intact so the
        // operator can inspect the tampered file.
        assertTrue(
            Files.exists(probeFile),
            "BQ4 P0 HITO 1 AAT: finalise() must NOT delete a tampered file " +
                "either — the operator needs to inspect the file. The cycle " +
                "dir (or at least the probe file) is left in place when the " +
                "runner refuses.",
        )
    }

    @Test
    fun `finalise deletes the cycle directory only when state is Closed`(@TempDir tmp: Path) {
        val dir = tmp.resolve("cycles/cyc-1")
        val (runner, _, _) = setupPromotableRunner(tmp)
        // Use the freshly-built runner but route the cycle dir
        // to the path this test expects.
        val aligned = CycleRunner(
            cycleId = "cyc-1",
            manifestPath = runner.manifestPath,
            cycleDir = dir,
            oracleLedger = MapOracleLedger(
                mapOf(
                    OracleRef.Fitness("TestRunner.fixture", ":test-harness") to
                        setOf("0123456789abcdef0123456789abcdef01234567"),
                ),
            ),
            repoRoot = tmp,
        )
        aligned.prepare()
        aligned.runProbesWithVerified()
        aligned.observeAll("abc123")
        aligned.requestPromote(evidence = requireNotNull(aligned.manifest).replacement.evidence)
        aligned.requestClose("done")
        assertTrue(Files.isDirectory(dir), "cycle dir must exist before finalise")
        aligned.finalise()
        assertFalse(Files.exists(dir), "finalise must delete the cycle dir")
    }

    @Test
    fun `finalise refuses when state is not Closed`(@TempDir tmp: Path) {
        val manifest = tmp.resolve("manifest.json")
        writeManifest(manifest)
        val runner = CycleRunner(cycleId = "cyc-1", manifestPath = manifest, cycleDir = tmp.resolve("cycles/cyc-1"))
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
        val runner = CycleRunner(cycleId = "cyc-1", manifestPath = manifest, cycleDir = tmp.resolve("cycles/cyc-1"))
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
        writeManifest(manifest, expectedHF = Fidelity.HF0)
        val runner = CycleRunner(cycleId = "cyc-1", manifestPath = manifest, cycleDir = tmp.resolve("cycles/cyc-1"))
        runner.prepare()
        val outcomes = runner.runProbes(object : ProbeExecutor(Path.of("/n")) {
            override fun execute(probe: ProbeEntry, manifest: CycleManifest): ProbeExecutor.Companion.ProbeExecutionResult =
                ProbeExecutor.Companion.ProbeExecutionResult.AlreadyPersisted(
                    ProbeOutcome.UnsupportedFidelity("HF2")
                )
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
        writeManifest(manifest, expectedHF = Fidelity.HF1)
        val runner = CycleRunner(cycleId = "cyc-1", manifestPath = manifest, cycleDir = tmp.resolve("cycles/cyc-1"))
        runner.prepare()
        runner.runProbes(object : ProbeExecutor(Path.of("/n")) {
            override fun execute(probe: ProbeEntry, manifest: CycleManifest): ProbeExecutor.Companion.ProbeExecutionResult =
                ProbeExecutor.Companion.ProbeExecutionResult.AlreadyPersisted(
                    ProbeOutcome.BlockedExternal("", "harness verdict missing")
                )
        })
        val state = runner.state
        assertTrue(state is CycleState.BlockedExternal) {
            "expected BlockedExternal, got $state"
        }
        assertEquals(0, runner.context.probeOutcomes.size)
    }
}
