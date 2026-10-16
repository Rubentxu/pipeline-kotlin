package dev.rubentxu.pipeline.v2.harness.lanes

/**
 * Test lanes. The mapping from a contract's metadata to a [TestLane] is
 * the single source of truth for "which test runs in which lane".
 *
 * This module is self-contained: it does NOT import from
 * `dev.rubentxu.pipeline.v2.harness.catalog` or `…harness.plan` because
 * those packages are owned by the canonical H0 test-harness (origin/main).
 * The lane decider works on a [LaneSpec] record the caller composes
 * from whatever authority they trust.
 *
 * Spec: docs/v2/03-specifications/TEST_EXECUTION_LANES_SPEC.md.
 */
enum class TestLane(val tag: String) {
    /** In-process unit / contract tests, no installDist, no SDK rebuild. */
    FAST("fast"),

    /** Cross-module integration, may build SDK plugins. */
    INTEGRATION("integration"),

    /** Installed-distribution tests, requires `installDist` and an actual ZIP. */
    CANDIDATE("candidate"),

    /** Restart / resume / sandbox / secrets — these need the heaviest isolation. */
    RESTART("restart"),

    /** Service-sandbox tests, often need a real consumer. */
    SERVICE_SANDBOX("service-sandbox"),

    /** Online smoke against a real running service. */
    ONLINE_SMOKE("online-smoke");

    companion object {
        fun fromTag(tag: String): TestLane? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * A contract / capability pair the lane decider partitions.
 *
 * The caller composes this from whichever catalogue they trust (H0's
 * `ContractCatalogue` for the canonical product, an ad-hoc descriptor
 * during migration, etc.). The lane decider here is intentionally
 * ignorant of the catalogue so it can be reused and tested in isolation.
 */
data class LaneSpec(
    val contractId: String,
    val capability: String,
    val minimumFidelity: Int,
    val requiresInstalledBinary: Boolean,
    val requiresExternalCertifier: Boolean,
) {
    init {
        require(contractId.isNotBlank()) { "contractId must not be blank" }
        require(capability.isNotBlank()) { "capability must not be blank" }
        require(minimumFidelity in 0..6) { "minimumFidelity must be 0..6" }
    }
}

/** What the runner reported for one lane assignment. */
data class LaneAssignment(
    val contractId: String,
    val lane: TestLane,
    val reason: String,
) {
    init {
        require(contractId.isNotBlank()) { "contractId must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
    }
}
