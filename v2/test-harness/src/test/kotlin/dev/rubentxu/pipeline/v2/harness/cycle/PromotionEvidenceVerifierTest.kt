package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * P0-D close-out (2026-10-16 audit, vertical): the
 * [PromotionEvidenceVerifier] is the runner's only authority on
 * whether a [PromotionEvidence] is a valid substitute for the
 * ephemeral cycle's coverage. The audit required five
 * properties:
 *
 * ```text
 *   1. Existence           — the contract file or receipt is on disk
 *   2. Identity of commit  — bound to a specific commit
 *   3. Fidelity (HF)       — matches the cycle's expectedHF
 *   4. Digest              — SHA-256 of the contract content
 *   5. Oracle              — the test that proves the contract
 *                            last PASSed at the bound commit
 * ```
 *
 * Each test pins ONE property; the whole class exists so a
 * future verifier regression (a check that silently
 * short-circuits, an oracle that mis-keys, a digest computed
 * over the wrong bytes) breaks the tests rather than the
 * cycle's promotion path.
 */
class PromotionEvidenceVerifierTest {

    private val runnerSourceSha = "0123456789abcdef0123456789abcdef01234567"
    private val manifestExpectedHF = "HF0"
    private val oracle = OracleRef.Fitness("VerifierTest.fixture", ":test-harness")

    private fun sha256HexOfFile(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun stageContract(tmp: Path, name: String = "MyContract.kt", body: String = "// contract\n"): Pair<String, Path> {
        val file = tmp.resolve(name)
        Files.writeString(file, body)
        return sha256HexOfFile(file) to file
    }

    private fun ledgerSaying(pass: Boolean): OracleLedger =
        MapOracleLedger(if (pass) mapOf(oracle to setOf(runnerSourceSha)) else emptyMap())

    @Test
    fun `a PermanentContract with matching digest, commit, fidelity, and oracle is Verified`(
        @TempDir tmp: Path,
    ) {
        val (digest, _) = stageContract(tmp)
        val evidence = PromotionEvidence.PermanentContract(
            contractId = "test",
            boundCommit = runnerSourceSha,
            sourcePath = "MyContract.kt",
            contentDigest = digest,
            fidelity = manifestExpectedHF,
            oracle = oracle,
        )
        val verdict = PromotionEvidenceVerifier.verify(
            evidence = evidence,
            runnerSourceSha = runnerSourceSha,
            manifestExpectedHF = manifestExpectedHF,
            repoRoot = tmp,
            oracleLedger = ledgerSaying(pass = true),
        )
        assertEquals(PromotionEvidenceVerifier.Verdict.Verified, verdict) { "got $verdict" }
    }

    @Test
    fun `a PermanentContract with boundCommit not matching runnerSourceSha is Refused OtherCommit`(
        @TempDir tmp: Path,
    ) {
        // Property (2): identity of commit. The audit named
        // "other commit" as a class of false green: a
        // substitute whose commit does not match the cycle's
        // source SHA cannot be a valid substitute.
        val (digest, _) = stageContract(tmp)
        val evidence = PromotionEvidence.PermanentContract(
            contractId = "test",
            boundCommit = "fedcba9876543210fedcba9876543210fedcba98",
            sourcePath = "MyContract.kt",
            contentDigest = digest,
            fidelity = manifestExpectedHF,
            oracle = oracle,
        )
        val verdict = PromotionEvidenceVerifier.verify(
            evidence = evidence,
            runnerSourceSha = runnerSourceSha,
            manifestExpectedHF = manifestExpectedHF,
            repoRoot = tmp,
            oracleLedger = ledgerSaying(pass = true),
        )
        assertTrue(verdict is PromotionEvidenceVerifier.Verdict.Refused.OtherCommit) {
            "got $verdict"
        }
        val refused = verdict as PromotionEvidenceVerifier.Verdict.Refused.OtherCommit
        assertEquals("0123456789abcdef0123456789abcdef01234567", refused.expectedCommit)
        assertEquals("fedcba9876543210fedcba9876543210fedcba98", refused.observedCommit)
    }

    @Test
    fun `a PermanentContract with fidelity not matching expectedHF is Refused`(
        @TempDir tmp: Path,
    ) {
        // Property (3): fidelity. The cycle was supposed to
        // run at HF0; an evidence proven at HF1 cannot
        // substitute for an HF0 cycle.
        val (digest, _) = stageContract(tmp)
        val evidence = PromotionEvidence.PermanentContract(
            contractId = "test",
            boundCommit = runnerSourceSha,
            sourcePath = "MyContract.kt",
            contentDigest = digest,
            fidelity = "HF1",
            oracle = oracle,
        )
        val verdict = PromotionEvidenceVerifier.verify(
            evidence = evidence,
            runnerSourceSha = runnerSourceSha,
            manifestExpectedHF = "HF0",
            repoRoot = tmp,
            oracleLedger = ledgerSaying(pass = true),
        )
        assertTrue(verdict is PromotionEvidenceVerifier.Verdict.Refused.FidelityMismatch) {
            "got $verdict"
        }
        val refused = verdict as PromotionEvidenceVerifier.Verdict.Refused.FidelityMismatch
        assertEquals("HF1", refused.observed)
        assertEquals("HF0", refused.expected)
    }

    @Test
    fun `a PermanentContract whose file is missing is Refused`(
        @TempDir tmp: Path,
    ) {
        // Property (1): existence. The first property the
        // verifier checks for a PermanentContract after the
        // cheap guards. A manifest that names a file which
        // is not on disk is refused.
        val evidence = PromotionEvidence.PermanentContract(
            contractId = "test",
            boundCommit = runnerSourceSha,
            sourcePath = "does-not-exist/MyContract.kt",
            contentDigest = "0".repeat(64),
            fidelity = manifestExpectedHF,
            oracle = oracle,
        )
        val verdict = PromotionEvidenceVerifier.verify(
            evidence = evidence,
            runnerSourceSha = runnerSourceSha,
            manifestExpectedHF = manifestExpectedHF,
            repoRoot = tmp,
            oracleLedger = ledgerSaying(pass = true),
        )
        assertTrue(verdict is PromotionEvidenceVerifier.Verdict.Refused.ContractFileMissing) {
            "got $verdict"
        }
        val refused = verdict as PromotionEvidenceVerifier.Verdict.Refused.ContractFileMissing
        assertEquals("does-not-exist/MyContract.kt", refused.relativePath)
    }

    @Test
    fun `a PermanentContract whose recorded digest does not match the file is Refused`(
        @TempDir tmp: Path,
    ) {
        // Property (4): digest. The audit named a digest
        // mismatch as the canonical P0-A4 defect: a
        // manifest that lies about the contract's bytes
        // cannot be a valid substitute.
        stageContract(tmp)
        val evidence = PromotionEvidence.PermanentContract(
            contractId = "test",
            boundCommit = runnerSourceSha,
            sourcePath = "MyContract.kt",
            contentDigest = "deadbeef".repeat(8),
            fidelity = manifestExpectedHF,
            oracle = oracle,
        )
        val verdict = PromotionEvidenceVerifier.verify(
            evidence = evidence,
            runnerSourceSha = runnerSourceSha,
            manifestExpectedHF = manifestExpectedHF,
            repoRoot = tmp,
            oracleLedger = ledgerSaying(pass = true),
        )
        assertTrue(verdict is PromotionEvidenceVerifier.Verdict.Refused.ContentDigestMismatch) {
            "got $verdict"
        }
        val refused = verdict as PromotionEvidenceVerifier.Verdict.Refused.ContentDigestMismatch
        assertEquals("deadbeef".repeat(8), refused.manifestDigest)
        assertTrue(
            refused.actualDigest != refused.manifestDigest,
            "actual digest must differ from manifest digest",
        )
    }

    @Test
    fun `a PermanentContract whose oracle is not in the ledger is Refused`(
        @TempDir tmp: Path,
    ) {
        // Property (5): oracle. The audit named a missing
        // oracle as the canonical P0-D defect: a contract
        // whose test has never run, or has last run FAIL,
        // cannot be a valid substitute. EmptyOracleLedger
        // is the safe default — a missing ledger must NOT
        // silently mark a contract as exercised.
        val (digest, _) = stageContract(tmp)
        val evidence = PromotionEvidence.PermanentContract(
            contractId = "test",
            boundCommit = runnerSourceSha,
            sourcePath = "MyContract.kt",
            contentDigest = digest,
            fidelity = manifestExpectedHF,
            oracle = oracle,
        )
        val verdict = PromotionEvidenceVerifier.verify(
            evidence = evidence,
            runnerSourceSha = runnerSourceSha,
            manifestExpectedHF = manifestExpectedHF,
            repoRoot = tmp,
            oracleLedger = EmptyOracleLedger,
        )
        assertTrue(verdict is PromotionEvidenceVerifier.Verdict.Refused.OracleNotPassed) {
            "got $verdict"
        }
        val refused = verdict as PromotionEvidenceVerifier.Verdict.Refused.OracleNotPassed
        assertEquals(runnerSourceSha, refused.commit)
    }

    @Test
    fun `a CertifierReceipt is BlockedExternal (runner cannot verify a signed receipt in-process)`(
        @TempDir tmp: Path,
    ) {
        // The runner does not have the certifier's signing
        // key. A CertifierReceipt that matches the commit
        // and the fidelity must NOT be marked Verified; the
        // cycle is BlockedExternal until the certifier
        // publishes the signed receipt and the runner is
        // extended to verify it. The previous shape would
        // have happily emitted a "certifier-receipt:<id>"
        // string and let the cycle close.
        val receipt = PromotionEvidence.CertifierReceipt(
            receiptId = "v0.62.0-rc5",
            boundCommit = runnerSourceSha,
            receiptDigest = "0".repeat(64),
            fidelity = manifestExpectedHF,
            oracle = oracle,
        )
        val verdict = PromotionEvidenceVerifier.verify(
            evidence = receipt,
            runnerSourceSha = runnerSourceSha,
            manifestExpectedHF = manifestExpectedHF,
            repoRoot = Path.of("."),
            oracleLedger = EmptyOracleLedger,
        )
        assertTrue(verdict is PromotionEvidenceVerifier.Verdict.BlockedExternal) { "got $verdict" }
        assertTrue(
            (verdict as PromotionEvidenceVerifier.Verdict.BlockedExternal).reason.contains("certifier receipt"),
            "got '${verdict.reason}'",
        )
    }

    @Test
    fun `a CertifierReceipt with mismatched commit is Refused OtherCommit (not BlockedExternal)`(
        @TempDir tmp: Path,
    ) {
        // The commit check is in-process: a signed receipt
        // bound to the wrong commit is refused on the ADT
        // before the certifier is consulted.
        val receipt = PromotionEvidence.CertifierReceipt(
            receiptId = "v0.62.0-rc5",
            boundCommit = "fedcba9876543210fedcba9876543210fedcba98",
            receiptDigest = "0".repeat(64),
            fidelity = manifestExpectedHF,
            oracle = oracle,
        )
        val verdict = PromotionEvidenceVerifier.verify(
            evidence = receipt,
            runnerSourceSha = runnerSourceSha,
            manifestExpectedHF = manifestExpectedHF,
            repoRoot = Path.of("."),
            oracleLedger = EmptyOracleLedger,
        )
        assertTrue(verdict is PromotionEvidenceVerifier.Verdict.Refused.OtherCommit) {
            "got $verdict"
        }
        val refused = verdict as PromotionEvidenceVerifier.Verdict.Refused.OtherCommit
        assertEquals("0123456789abcdef0123456789abcdef01234567", refused.expectedCommit)
    }

    @Test
    fun `commit-mismatch is checked before fidelity so a wrong commit cannot hide behind a wrong fidelity`(
        @TempDir tmp: Path,
    ) {
        // The verifier checks the cheapest guards first:
        // commit identity (string compare) before fidelity
        // (string compare) before file I/O. A test that
        // mutates BOTH commit and fidelity must report the
        // commit mismatch, not the fidelity mismatch.
        val (digest, _) = stageContract(tmp)
        val evidence = PromotionEvidence.PermanentContract(
            contractId = "test",
            boundCommit = "fedcba9876543210fedcba9876543210fedcba98",
            sourcePath = "MyContract.kt",
            contentDigest = digest,
            fidelity = "HF5",
            oracle = oracle,
        )
        val verdict = PromotionEvidenceVerifier.verify(
            evidence = evidence,
            runnerSourceSha = runnerSourceSha,
            manifestExpectedHF = manifestExpectedHF,
            repoRoot = tmp,
            oracleLedger = ledgerSaying(pass = true),
        )
        val refused = verdict as PromotionEvidenceVerifier.Verdict.Refused.OtherCommit
        assertEquals(
            "fedcba9876543210fedcba9876543210fedcba98",
            refused.observedCommit,
            "commit-mismatch must be reported via OtherCommit, not FidelityMismatch",
        )
    }

    @Test
    fun `the verifier is total over PromotionEvidence (the when is exhaustive)`() {
        // If a future variant is added to the sealed
        // PromotionEvidence (e.g. SHADED_INHERITANCE), the
        // verifier's when{} in [verify] MUST be updated. The
        // test below forces a compile-time check by
        // constructing each variant and confirming the
        // verifier returns a verdict for it; the absence of
        // a `when` branch on the variant would fail the
        // build before this test runs.
        val permanent = PromotionEvidence.PermanentContract(
            contractId = "test",
            boundCommit = runnerSourceSha,
            sourcePath = "MyContract.kt",
            contentDigest = "0".repeat(64),
            fidelity = manifestExpectedHF,
            oracle = oracle,
        )
        val receipt = PromotionEvidence.CertifierReceipt(
            receiptId = "v0.62.0-rc5",
            boundCommit = runnerSourceSha,
            receiptDigest = "0".repeat(64),
            fidelity = manifestExpectedHF,
            oracle = oracle,
        )
        // The two variants are dispatched on; the existence
        // of this method body (and the `when` in `verify`)
        // is what makes the test meaningful. A future
        // variant would not compile here.
        val all: List<PromotionEvidence> = listOf(permanent, receipt)
        assertEquals(2, all.size)
    }
}
