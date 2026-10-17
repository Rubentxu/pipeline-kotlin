package dev.rubentxu.pipeline.v2.harness.cycle

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed carrier for the substitute guarantee left behind when an
 * ephemeral cycle retires. Replaces the previous free-form string
 * (`permanent-contract:<id>` / `certifier-receipt:<sha256>`) that
 * the decider validated by string-prefix.
 *
 * BQ4 audit (2026-10-16, P0-D, vertical): the previous shape was
 * unverifiable — a textual reference like `replacement:foo` was a
 * label, not a verified substitute. The audit required the carrier
 * to prove five properties:
 *
 * ```text
 *   1. Existence           — the contract file or receipt is on disk
 *   2. Identity of commit  — the evidence is bound to a specific
 *                            commit, and the runner's sourceSha matches
 *   3. Fidelity (HF)       — the fidelity the evidence is proven at
 *                            matches the cycle's expectedHF
 *   4. Digest              — the SHA-256 of the contract content
 *                            matches the recorded digest
 *   5. Oracle              — the test that proves the contract last
 *                            ran PASS at the bound commit
 * ```
 *
 * [PromotionEvidence] carries all five. The verifier
 * ([PromotionEvidenceVerifier]) is the runner's authority on whether
 * the carrier is valid; the decider is a pure transition once the
 * runner has accepted the evidence. The wire format is a
 * discriminated union with `"type"` as the discriminator and one
 * variant per legitimate shape; no free-form strings cross the
 * boundary.
 */
@Serializable
sealed interface PromotionEvidence {
    /** The commit the evidence is bound to. 40-hex lowercase. */
    val boundCommit: String

    /** The fidelity the evidence is proven at. HF label. */
    val fidelity: Fidelity

    /** The test that proves the contract. */
    val oracle: OracleRef

    /**
     * A permanent in-repo contract (a test, fitness, or property
     * test) that replaces the ephemeral cycle's coverage. The
     * verifier confirms the file at `sourcePath` exists, that its
     * SHA-256 matches `contentDigest`, that the runner's source
     * commit matches `boundCommit`, and that the named oracle
     * (a fitness or JUnit test) has last run PASS at
     * `boundCommit`.
     */
    @Serializable
    @SerialName("permanent_contract")
    data class PermanentContract(
        @SerialName("contract_id") val contractId: String,
        @SerialName("bound_commit") override val boundCommit: String,
        @SerialName("source_path") val sourcePath: String,
        @SerialName("content_digest") val contentDigest: String,
        @SerialName("fidelity") override val fidelity: Fidelity,
        @SerialName("oracle") override val oracle: OracleRef,
    ) : PromotionEvidence {
        init {
            require(contractId.isNotBlank()) { "PermanentContract.contractId must not be blank" }
            require(boundCommit.length == 40) {
                "PermanentContract.boundCommit must be a 40-hex SHA-1; got ${boundCommit.length} chars"
            }
            require(boundCommit.all { it in '0'..'9' || it in 'a'..'f' }) {
                "PermanentContract.boundCommit must be lowercase hex; got '$boundCommit'"
            }
            require(sourcePath.isNotBlank()) { "PermanentContract.sourcePath must not be blank" }
            require(ProbeEntry.isSafeRelativePath(sourcePath)) {
                "PermanentContract.sourcePath must be a safe relative path; got '$sourcePath'"
            }
            require(contentDigest.length == 64) {
                "PermanentContract.contentDigest must be a 64-hex SHA-256; got ${contentDigest.length} chars"
            }
            require(contentDigest.all { it in '0'..'9' || it in 'a'..'f' }) {
                "PermanentContract.contentDigest must be lowercase hex; got '$contentDigest'"
            }
        }
    }

    /**
     * A signed receipt from the external certifier. The runner
     * cannot verify a signed receipt in-process; the cycle is
     * `BlockedExternal` until the certifier signs and the runner
     * is extended to verify the signature. The carrier still
     * carries the bound commit, digest and oracle so the
     * certifier can verify the receipt's evidence on its side
     * (this is the EXACT-VERSION CANDIDATE LAW applied to
     * receipts: the certifier cannot sign a receipt whose
     * fingerprint does not match the byte it claims to
     * authorise).
     */
    @Serializable
    @SerialName("certifier_receipt")
    data class CertifierReceipt(
        @SerialName("receipt_id") val receiptId: String,
        @SerialName("signed_commit") override val boundCommit: String,
        @SerialName("receipt_digest") val receiptDigest: String,
        @SerialName("fidelity") override val fidelity: Fidelity,
        @SerialName("oracle") override val oracle: OracleRef,
    ) : PromotionEvidence {
        init {
            require(receiptId.isNotBlank()) { "CertifierReceipt.receiptId must not be blank" }
            require(boundCommit.length == 40) {
                "CertifierReceipt.boundCommit must be a 40-hex SHA-1; got ${boundCommit.length} chars"
            }
            require(boundCommit.all { it in '0'..'9' || it in 'a'..'f' }) {
                "CertifierReceipt.boundCommit must be lowercase hex; got '$boundCommit'"
            }
            require(receiptDigest.length == 64) {
                "CertifierReceipt.receiptDigest must be a 64-hex SHA-256; got ${receiptDigest.length} chars"
            }
            require(receiptDigest.all { it in '0'..'9' || it in 'a'..'f' }) {
                "CertifierReceipt.receiptDigest must be lowercase hex; got '$receiptDigest'"
            }
        }
    }
}

/**
 * A pointer to the test that proves the substitute contract.
 * The verifier consults the [OracleLedger] to confirm the named
 * test last ran PASS at the evidence's `boundCommit`.
 *
 * Two variants cover the legitimate shapes:
 *
 *   - [OracleRef.Fitness] — an architecture fitness registered
 *     in the test-harness's fitness registry (no JUnit XML
 *     required; the registry is the single source of truth).
 *   - [OracleRef.Junit] — a named JUnit test class + method,
 *     identified by its `(module, testClass, testMethod)` triple.
 *     The ledger reads the test-results XML at the named module's
 *     build directory.
 *
 * A free-form string is not a valid oracle reference. The data
 * class `init` blocks refuse blanks.
 */
@Serializable
sealed interface OracleRef {
    @Serializable
    @SerialName("fitness")
    data class Fitness(
        @SerialName("name") val name: String,
        @SerialName("module") val module: String,
    ) : OracleRef {
        init {
            require(name.isNotBlank()) { "OracleRef.Fitness.name must not be blank" }
            require(module.isNotBlank()) { "OracleRef.Fitness.module must not be blank" }
        }
    }

    @Serializable
    @SerialName("junit")
    data class Junit(
        @SerialName("test_class") val testClass: String,
        @SerialName("test_method") val testMethod: String,
        @SerialName("module") val module: String,
    ) : OracleRef {
        init {
            require(testClass.isNotBlank()) { "OracleRef.Junit.testClass must not be blank" }
            require(testMethod.isNotBlank()) { "OracleRef.Junit.testMethod must not be blank" }
            require(module.isNotBlank()) { "OracleRef.Junit.module must not be blank" }
        }
    }
}
