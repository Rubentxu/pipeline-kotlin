package dev.rubentxu.pipeline.v2.harness.cycle

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The runner's authority on whether a [PromotionEvidence] is a
 * valid substitute for the ephemeral cycle's coverage. Closes
 * the P0-D defect the 2026-10-16 external audit named: a
 * free-form string was accepted as a verified substitute even
 * when the contract file was missing, the digest was wrong,
 * the commit did not match, the fidelity was wrong, or the
 * oracle had never run.
 *
 * The verifier is total over [PromotionEvidence]: every
 * variant is handled; the `when` is exhaustive. The verdict is
 * a sealed [Verdict] ADT:
 *
 *   - [Verdict.Verified] — all five properties (existence,
 *     identity, fidelity, digest, oracle) hold.
 *   - [Verdict.Refused] — at least one property failed; the
 *     reason names the failing check.
 *   - [Verdict.BlockedExternal] — the evidence is a
 *     [PromotionEvidence.CertifierReceipt]; the runner cannot
 *     verify a signed receipt in-process, the cycle must wait
 *     on the certifier.
 *
 * The verifier does NOT touch the cycle's state machine. The
 * runner takes the verdict and decides whether to advance,
 * refuse, or block. The decider stays a pure transition; the
 * verifier is the only place that does filesystem I/O on the
 * substitute guarantee.
 */
object PromotionEvidenceVerifier {

    sealed interface Verdict {
        data object Verified : Verdict
        data class Refused(val reason: String) : Verdict
        data class BlockedExternal(val reason: String) : Verdict
    }

    /**
     * Verify the [evidence] against the cycle's [runnerSourceSha]
     * and [manifestExpectedHF], the repo's [repoRoot], and the
     * injected [oracleLedger].
     *
     * The five properties the audit named are checked in this
     * order (cheapest first):
     *
     * ```text
     *   1. boundCommit == runnerSourceSha   (other-commit guard)
     *   2. fidelity == manifestExpectedHF   (cheap string compare)
     *   3. file exists at repoRoot/sourcePath   (PermanentContract only)
     *   4. content_digest matches file SHA-256   (PermanentContract only)
     *   5. oracleLedger.hasPassed(oracle, boundCommit)
     * ```
     *
     * For [PromotionEvidence.CertifierReceipt] only (1) is
     * checked; the rest are deferred to the certifier. The
     * reason is returned as [Verdict.BlockedExternal].
     *
     * On any failure the verifier returns the first failing
     * reason and stops; the carrier is the source of truth, and
     * the runner refuses the cycle.
     */
    fun verify(
        evidence: PromotionEvidence,
        runnerSourceSha: String,
        manifestExpectedHF: String,
        repoRoot: Path,
        oracleLedger: OracleLedger,
    ): Verdict {
        // (1) commit identity — OtherCommit guard. The runner's
        // sourceSha is the manifest's sourceSha (the runner
        // inherits it); the evidence's boundCommit must match.
        if (evidence.boundCommit != runnerSourceSha) {
            return Verdict.Refused(
                "other commit: evidence bound to ${evidence.boundCommit}, " +
                    "runner saw $runnerSourceSha",
            )
        }
        // (2) fidelity — cheap, check before file I/O.
        if (evidence.fidelity != manifestExpectedHF) {
            return Verdict.Refused(
                "fidelity mismatch: evidence=${evidence.fidelity}, " +
                    "expected=$manifestExpectedHF",
            )
        }

        return when (evidence) {
            is PromotionEvidence.PermanentContract ->
                verifyPermanent(evidence, repoRoot, oracleLedger)
            is PromotionEvidence.CertifierReceipt -> Verdict.BlockedExternal(
                "certifier receipt '${evidence.receiptId}' is bound to ${evidence.boundCommit} " +
                    "but cannot be verified in-process; deferring to certifier",
            )
        }
    }

    private fun verifyPermanent(
        ev: PromotionEvidence.PermanentContract,
        repoRoot: Path,
        oracleLedger: OracleLedger,
    ): Verdict {
        // (3) existence
        val file = repoRoot.resolve(ev.sourcePath).normalize()
        if (!Files.isRegularFile(file)) {
            return Verdict.Refused(
                "contract file missing: '${ev.sourcePath}' (resolved to $file)",
            )
        }
        // (4) digest — the file's SHA-256 must match the manifest.
        val actualDigest = sha256HexOfFile(file)
        if (!actualDigest.equals(ev.contentDigest, ignoreCase = true)) {
            return Verdict.Refused(
                "content_digest mismatch: manifest=${ev.contentDigest}, " +
                    "file=$actualDigest (at ${ev.sourcePath})",
            )
        }
        // (5) oracle — the test that proves the contract must
        // have last passed at the bound commit.
        if (!oracleLedger.hasPassed(ev.oracle, ev.boundCommit)) {
            return Verdict.Refused(
                "oracle not PASS at commit ${ev.boundCommit}: ${ev.oracle}",
            )
        }
        return Verdict.Verified
    }

    /**
     * Streaming SHA-256 of a file. Same algorithm as the
     * runner's existing `sha256HexOfFile` (declared in
     * [CycleRunner] but kept private there); duplicated here
     * so the verifier has no inbound dependency on the runner.
     * If a third caller appears, the helper moves to a shared
     * file with a proper KDoc.
     */
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
}
