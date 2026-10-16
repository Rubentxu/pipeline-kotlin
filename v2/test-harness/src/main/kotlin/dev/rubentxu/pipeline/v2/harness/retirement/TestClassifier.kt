package dev.rubentxu.pipeline.v2.harness.retirement

/**
 * Pure classifier: a single test file becomes a [DeletionDecision].
 *
 * The classifier is intentionally conservative. It never says
 * `DELETE_READY`; that promotion is owned by [DeletionGuard]. It also
 * does not touch the filesystem or call Gradle. The caller passes in
 * the metadata (kind hint, runner hint, content hash, @Test count,
 * @Disabled count, observed process API, release-scale tag) and the
 * classifier returns one of the seven cases.
 *
 * The classifier is the BQ5 substitute for the addendum's "oleada"
 * (wave) classification, scoped to a single test file. A multi-file
 * wave lives in a different function (not in this commit) that walks
 * the inventory CSV and reports duplicates and unowned paths.
 */
object TestClassifier {
    fun classify(input: TestRecord): DeletionDecision {
        // 1. A helper / abstract / non-test source: not a contract on its own.
        if (input.kindHint == KindHint.SUPPORT_OR_ABSTRACT) {
            return DeletionDecision.TestFixture
        }

        // 2. V1 legacy path: not part of the V2 contract.
        if (input.runnerHint == RunnerHint.LEGACY_NOT_DEFAULT_V2) {
            return DeletionDecision.LegacyNonV2
        }

        // 3. Release-scale tag: this is a tagged smoke that the harness
        //    needs. The classifier cannot prove equivalence; it must
        //    refuse to delete.
        if (input.releaseScaleTag) {
            return DeletionDecision.MigrateExternalPending
        }

        // 4. Process API observed: the test spawns a real binary. This
        //    is a candidate for the harness, not for in-repo deletion.
        if (input.observedProcessApi.isNotEmpty()) {
            return DeletionDecision.MigrateExternalPending
        }

        // 5. UAT/E2E name and the file lives in `src/test` of a
        //    v2/* module and is NOT tagged release-scale and does
        //    NOT touch a process API. This is the gray zone: it
        //    MIGHT be a legacy UAT, it MIGHT be a contract test.
        if (input.kindHint == KindHint.EXPLICIT_UAT_E2E) {
            if (input.testAnnotated == 0) {
                return DeletionDecision.TestFixture
            }
            // No proof of equivalence from the harness: keep but flag.
            return DeletionDecision.Blocked(
                "explicit UAT/E2E name without release-scale tag or process API; " +
                    "needs harness verdict before any KEEP/DELETE decision",
            )
        }

        // 6. Integration candidate: keep as a contract.
        if (input.kindHint == KindHint.INTEGRATION_CANDIDATE) {
            return DeletionDecision.KeepCoreContract
        }

        // 7. Plain test source: a contract. Keep.
        if (input.testAnnotated > 0) {
            return DeletionDecision.KeepCoreContract
        }

        // 8. Default: a non-test source flagged as test (rare). Block.
        return DeletionDecision.Blocked(
            "no @Test methods, no helper, no V1 marker; owner must classify",
        )
    }
}

/** Inputs to [TestClassifier.classify]. A row from the inventory CSV. */
data class TestRecord(
    val path: String,
    val kindHint: KindHint,
    val runnerHint: RunnerHint,
    val observedProcessApi: String,
    val testAnnotated: Int,
    val disabledAnnotations: Int,
    val releaseScaleTag: Boolean,
    val contentSha256: String,
) {
    init {
        require(path.isNotBlank()) { "path must not be blank" }
        require(contentSha256.length == 64) { "contentSha256 must be a SHA-256 hex digest" }
    }
}

enum class KindHint { SUPPORT_OR_ABSTRACT, EXPLICIT_UAT_E2E, INTEGRATION_CANDIDATE, PROCESS_USING_CANDIDATE, OTHER_TEST_SOURCE }
enum class RunnerHint { V2_GRADLE, LEGACY_NOT_DEFAULT_V2 }
