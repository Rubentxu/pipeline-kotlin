package dev.rubentxu.pipeline.v2.harness.retirement

/**
 * BQ5 wave classification. The package plan prescribes four waves of
 * retirement, ordered from least to most sensitive. Each wave demands
 * a different level of evidence before a test can be retired.
 *
 * The wave is **derived from the test record**, not assigned by the
 * classifier. The classifier's [DeletionDecision] may further restrict
 * the wave (e.g. a V1 legacy test can never be retired before wave D is
 * authorised by the certifier).
 *
 * The waves, in the order the plan prescribes:
 *
 * - **A — smoke duplicates.** Tests that are exact-content duplicates of
 *   a retained test. Least risky; a SHA-256 match is enough proof. The
 *   mutation the surviving test carries is the migration guarantee.
 *
 * - **B — CLI / distribution UATs.** Tests that fork the installed
 *   `pipelinek` binary (or carry the release-scale tag). These belong
 *   to the harness; they are not retired by a hash alone. A
 *   cross-module `pipelinek-runner` execution is required to call
 *   them equivalent.
 *
 * - **C — kill / restart / sandbox / secrets.** Tests that own
 *   subprocesses, sandbox profiles, or secrets. The most critical wave;
 *   requires a sandbox certifier run against the same ZIP. The
 *   [DeletionGuard] refuses Wave C retirement unless
 *   `retirementAuthorised=true` AND the authority is `sandbox` or
 *   `release-harness`.
 *
 * - **D — V1 legacy / scripts.** Tests from the legacy V1 module or
 *   V1-style scripts. The plan defers them to "tras equivalencia"; in
 *   practice they are not retired by the classifier at all — V1 tests
 *   carry `LEGACY` disposition and the guard rejects retirement until
 *   the operator marks the wave D autoritative.
 */
enum class Wave(val label: String, val ordinalRank: Int) {
    A("smoke duplicates", 0),
    B("cli / distribution UAT", 1),
    C("kill / restart / sandbox / secrets", 2),
    D("v1 legacy / scripts", 3);

    companion object {
        /** Pure: derive the wave from the test record metadata. */
        fun forRecord(record: TestRecord): Wave = when {
            // Wave D first: V1 is the lowest-fidelity authority; if the
            // record is V1 it never participates in earlier waves.
            record.runnerHint == RunnerHint.LEGACY_NOT_DEFAULT_V2 -> D
            // Wave C: tests that own subprocesses or sandbox profiles.
            // These carry the highest blast radius on retirement.
            record.observedProcessApi.contains("ProcessBuilder(") -> C
            record.observedProcessApi.contains("AppBinSupport") -> C
            record.observedProcessApi.contains("OwnedSubprocess") -> C
            // Wave B: tests that are explicitly marked release-scale
            // or carry an UAT/E2E name AND live in the V2 Gradle
            // runner. These belong to the harness.
            record.releaseScaleTag -> B
            record.kindHint == KindHint.EXPLICIT_UAT_E2E -> B
            // Wave A: the default. Integration candidates and plain
            // tests fall here.
            else -> A
        }
    }
}
