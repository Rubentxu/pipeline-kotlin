package dev.rubentxu.pipeline.v2.harness.lanes

import dev.rubentxu.pipeline.v2.harness.plan.HarnessFidelity

/**
 * Test lanes. The decider that maps a lane to a [HarnessFidelity] is the
 * single source of truth for "which test runs in which lane".
 *
 * Spec: docs/v2/03-specifications/TEST_EXECUTION_LANES_SPEC.md.
 *
 * The mapping is closed: there is no "Other" case. New lanes require an
 * ADR.
 */
enum class TestLane(val tag: String, val targetFidelity: HarnessFidelity) {
    /** In-process unit / contract tests, no installDist, no SDK rebuild. */
    FAST("fast", HarnessFidelity.HF1),

    /** Cross-module integration, may build SDK plugins. */
    INTEGRATION("integration", HarnessFidelity.HF2),

    /** Installed-distribution tests, requires `installDist` and an actual ZIP. */
    CANDIDATE("candidate", HarnessFidelity.HF2),

    /** Restart / resume / sandbox / secrets — these need the heaviest isolation. */
    RESTART("restart", HarnessFidelity.HF3),

    /** Service-sandbox tests, often need a real consumer. */
    SERVICE_SANDBOX("service-sandbox", HarnessFidelity.HF4),

    /** Online smoke against a real running service. */
    ONLINE_SMOKE("online-smoke", HarnessFidelity.HF6);

    companion object {
        fun fromTag(tag: String): TestLane? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * A contract that declares which lane a test suite belongs to. The
 * catalogue-side mapping lives in [dev.rubentxu.pipeline.v2.harness.catalog.ContractEntry];
 * the lane assignment is a derived property of the catalogue owner.
 *
 * The model here is the answer to "what kind of test is this?", not
 * "where does it run?". The runner is responsible for the latter.
 */
data class LaneAssignment(
    val suiteFqcn: String,
    val lane: TestLane,
    val reason: String,
) {
    init {
        require(suiteFqcn.isNotBlank()) { "suiteFqcn must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
    }
}
