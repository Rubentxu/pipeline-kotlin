package dev.rubentxu.pipeline.v2.harness.cycle

/**
 * The runner's authority on whether a named oracle (a fitness
 * or JUnit test) has last executed PASS at a specific commit.
 *
 * BQ4 audit (2026-10-16, P0-D): the oracle is the only way the
 * cycle knows the substitute contract is actually exercised. A
 * contract whose oracle has never run, or has last run FAIL, is
 * not a valid substitute — the previous shape accepted any
 * free-form string. The [PromotionEvidenceVerifier] consults
 * the ledger to confirm the oracle's last recorded verdict.
 *
 * The ledger is a pure function interface. Production wiring
 * would read from `<module>/build/test-results/.../TEST-*.xml`
 * (JUnit) or `.pipelinek/fitness-ledger.json` (fitness); tests
 * inject an in-memory implementation. The default
 * [EmptyOracleLedger] refuses every oracle, which is the safe
 * fallback when no ledger is wired up.
 */
fun interface OracleLedger {
    /**
     * @param oracle the test to look up
     * @param boundCommit the commit the contract claims to be
     *        bound to
     * @return `true` if the ledger has a PASS row for the oracle
     *         at `boundCommit`; `false` otherwise (including when
     *         the ledger has no record of the oracle at all)
     */
    fun hasPassed(oracle: OracleRef, boundCommit: String): Boolean
}

/**
 * The "no record" ledger: returns `false` for every query. Use
 * in tests that do not exercise the oracle check, or in
 * environments where the ledger has not been wired up. A cycle
 * whose verifier consults this ledger and gets `false` is
 * refused on "oracle not PASS"; this is the safe default — a
 * missing ledger must not silently mark a contract as
 * exercised.
 */
object EmptyOracleLedger : OracleLedger {
    override fun hasPassed(oracle: OracleRef, boundCommit: String): Boolean = false
}

/**
 * In-memory ledger keyed by oracle reference, with each value
 * the set of commits at which the oracle last passed. Used by
 * tests; production wiring is a file-backed or HTTP-backed
 * implementation with the same `hasPassed` contract.
 */
class MapOracleLedger(
    private val passes: Map<OracleRef, Set<String>>,
) : OracleLedger {
    override fun hasPassed(oracle: OracleRef, boundCommit: String): Boolean =
        passes[oracle]?.contains(boundCommit) == true
}
