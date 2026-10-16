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
 */
object TestClassifier {
    fun classify(input: TestRecord): DeletionDecision {
        if (input.kindHint == KindHint.SUPPORT_OR_ABSTRACT) {
            return DeletionDecision.TestFixture
        }
        if (input.runnerHint == RunnerHint.LEGACY_NOT_DEFAULT_V2) {
            return DeletionDecision.LegacyNonV2
        }
        if (input.releaseScaleTag) {
            return DeletionDecision.MigrateExternalPending
        }
        if (input.observedProcessApi.isNotEmpty()) {
            return DeletionDecision.MigrateExternalPending
        }
        if (input.kindHint == KindHint.EXPLICIT_UAT_E2E) {
            if (input.testAnnotated == 0) {
                return DeletionDecision.TestFixture
            }
            return DeletionDecision.Blocked(
                "explicit UAT/E2E name without release-scale tag or process API; " +
                    "needs harness verdict before any KEEP/DELETE decision",
            )
        }
        if (input.kindHint == KindHint.INTEGRATION_CANDIDATE) {
            return DeletionDecision.KeepCoreContract
        }
        if (input.testAnnotated > 0) {
            return DeletionDecision.KeepCoreContract
        }
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

enum class KindHint {
    SUPPORT_OR_ABSTRACT,
    EXPLICIT_UAT_E2E,
    INTEGRATION_CANDIDATE,
    PROCESS_USING_CANDIDATE,
    OTHER_TEST_SOURCE,
}

enum class RunnerHint { V2_GRADLE, LEGACY_NOT_DEFAULT_V2 }
