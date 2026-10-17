package dev.rubentxu.pipeline.v2.harness.cycle

import java.nio.file.Files
import java.nio.file.Path

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

        /**
         * Typed refusal: each subtype is a distinct well-known
         * reason the verifier refused. The previous shape was a
         * single `data class Refused(val reason: String)` and the
         * runner consumed the reason verbatim; a future reason
         * (a new failure mode) silently misrouted to the wrong
         * typed case at the consumer. The new shape makes each
         * case a distinct data class with typed fields. The
         * carrier [reason] is preserved verbatim so the
         * operator-facing text survives the refactor (the
         * companion [CycleRefusalProjectionFitnessTest] in
         * the cycle package pins the absence of the previous
         * shape in production code).
         */
        sealed interface Refused : Verdict {
            val reason: String

            // Typed Refused subtypes (BQ1 v2, 2026-10-17). Each
            // carries the typed payload the audit named. The carrier
            // `reason` is computed from the payload, not stored in
            // parallel — there is no separate "reason string" the
            // projection would have to re-parse.
            data class OtherCommit(
                val observedCommit: String,
                val expectedCommit: String,
            ) : Refused {
                init {
                    require(observedCommit.isNotBlank()) { "OtherCommit.observedCommit must not be blank" }
                    require(expectedCommit.isNotBlank()) { "OtherCommit.expectedCommit must not be blank" }
                }
                override val reason: String get() =
                    "other commit: evidence bound to $observedCommit, runner saw $expectedCommit"
            }
            data class FidelityMismatch(
                val observed: String,
                val expected: String,
            ) : Refused {
                init {
                    require(observed.isNotBlank()) { "FidelityMismatch.observed must not be blank" }
                    require(expected.isNotBlank()) { "FidelityMismatch.expected must not be blank" }
                }
                override val reason: String get() =
                    "fidelity mismatch: evidence=$observed, expected=$expected"
            }
            data class ContractFileMissing(
                val relativePath: String,
                val resolvedPath: String,
            ) : Refused {
                init {
                    require(relativePath.isNotBlank()) { "ContractFileMissing.relativePath must not be blank" }
                    require(resolvedPath.isNotBlank()) { "ContractFileMissing.resolvedPath must not be blank" }
                }
                override val reason: String get() =
                    "contract file missing: '$relativePath' (resolved to $resolvedPath)"
            }
            data class ContentDigestMismatch(
                val manifestDigest: String,
                val actualDigest: String,
                val relativePath: String,
            ) : Refused {
                init {
                    require(manifestDigest.isNotBlank()) { "ContentDigestMismatch.manifestDigest must not be blank" }
                    require(actualDigest.isNotBlank()) { "ContentDigestMismatch.actualDigest must not be blank" }
                    require(relativePath.isNotBlank()) { "ContentDigestMismatch.relativePath must not be blank" }
                }
                override val reason: String get() =
                    "content_digest mismatch: manifest=$manifestDigest, file=$actualDigest (at $relativePath)"
            }
            data class OracleNotPassed(
                val commit: String,
                val oracle: String,
            ) : Refused {
                init {
                    require(commit.isNotBlank()) { "OracleNotPassed.commit must not be blank" }
                    require(oracle.isNotBlank()) { "OracleNotPassed.oracle must not be blank" }
                }
                override val reason: String get() =
                    "oracle not PASS at commit $commit: $oracle"
            }
        }

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
        // BQ1 v2 (typed verifier, 2026-10-17): emit a typed
        // [Verdict.Refused.OtherCommit] rather than a free
        // String. The reason is computed from the typed fields
        // so the runner does not have to re-parse it.
        if (evidence.boundCommit != runnerSourceSha) {
            return Verdict.Refused.OtherCommit(
                observedCommit = evidence.boundCommit,
                expectedCommit = runnerSourceSha,
            )
        }
        // (2) fidelity — cheap, check before file I/O.
        if (evidence.fidelity != manifestExpectedHF) {
            return Verdict.Refused.FidelityMismatch(
                observed = evidence.fidelity,
                expected = manifestExpectedHF,
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
            return Verdict.Refused.ContractFileMissing(
                relativePath = ev.sourcePath,
                resolvedPath = file.toString(),
            )
        }
        // (4) digest — the file's SHA-256 must match the manifest.
        val actualDigest = sha256HexOfFile(file)
        if (!actualDigest.equals(ev.contentDigest, ignoreCase = true)) {
            return Verdict.Refused.ContentDigestMismatch(
                manifestDigest = ev.contentDigest,
                actualDigest = actualDigest,
                relativePath = ev.sourcePath,
            )
        }
        // (5) oracle — the test that proves the contract must
        // have last passed at the bound commit.
        if (!oracleLedger.hasPassed(ev.oracle, ev.boundCommit)) {
            return Verdict.Refused.OracleNotPassed(
                commit = ev.boundCommit,
                oracle = ev.oracle.toString(),
            )
        }
        return Verdict.Verified
    }
}
