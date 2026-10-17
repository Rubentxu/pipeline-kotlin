package dev.rubentxu.pipeline.v2.harness.lanes

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity

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
 * The execution environment a test requires.
 *
 * The previous shape was a pair of booleans (`requiresInstalledBinary`,
 * `requiresExternalCertifier`). That pair is a 2×2 state space, but the
 * `(installed=false, certifier=true)` corner is impossible: a certifier
 * runs against an installed binary, so the two booleans are not
 * independent. The boolean pair let a hand-built `LaneSpec` describe a
 * physically unrealisable test, and the decider had to fall through
 * without a typed reason.
 *
 * The [LaneAccess] ADT names the three legitimate cases. The impossible
 * combination is no longer representable; adding a fourth (e.g. a
 * future "service-only" lane) is one new case in the sealed type plus
 * one new arm in [LanePlanner.laneFor].
 */
sealed interface LaneAccess {
    /**
     * HF0/HF1: no install, no certifier. The unit / contract
     * tests that the V2 module classpath runs without a
     * distribution build.
     */
    data object InProcess : LaneAccess

    /**
     * HF2/HF3/HF4: the installed CLI is required but the
     * certifier does not run. CANDIDATE lane, RESTART lane,
     * SERVICE_SANDBOX lane.
     */
    data object Installed : LaneAccess

    /**
     * HF5/HF6: installed CLI plus an external certifier. A
     * test in this lane runs against the harness's verdict
     * path, not just the producer's byte-checked output.
     */
    data object InstalledWithCertifier : LaneAccess
}

/**
 * A contract / capability pair the lane decider partitions.
 *
 * The caller composes this from whichever catalogue they trust (H0's
 * `ContractCatalogue` for the canonical product, an ad-hoc descriptor
 * during migration, etc.). The lane decider here is intentionally
 * ignorant of the catalogue so it can be reused and tested in isolation.
 *
 * BQ4 v5 (typed fidelity discriminator, 2026-10-17): the
 * previous shape was `minimumFidelity: Int` and the planner
 * dispatched on numeric ranges (`>= 3`, `== 2`, `>= 5`,
 * `>= 2`). That dual shape (typed source mapped to Int,
 * then `Int` compared) let a future HF7 silently land in
 * `RESTART` (`>= 3`) and a stray `Int = 99` (out of band)
 * land in `InstalledWithCertifier` (`>= 5`). The new
 * shape is a closed [Fidelity] enum: the compiler forces
 * the planner's `when` to be exhaustive and a future HF
 * cannot be added without a deliberate enum extension
 * that the fitness can pin.
 */
data class LaneSpec(
    val contractId: String,
    val capability: String,
    val minimumFidelity: Fidelity,
    val access: LaneAccess,
) {
    init {
        require(contractId.isNotBlank()) { "contractId must not be blank" }
        require(capability.isNotBlank()) { "capability must not be blank" }
        // The Fidelity enum is closed (HF0..HF6); the type
        // system already rules out every other value. The
        // previous 0..6 range check is gone because the
        // closed enum makes it uncompilable to construct
        // a `LaneSpec` with a fidelity outside the set.
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
