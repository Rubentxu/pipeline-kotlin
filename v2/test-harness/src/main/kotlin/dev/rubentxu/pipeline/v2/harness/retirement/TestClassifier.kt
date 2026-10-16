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
        // Permanent architectural fitness gates are protected by
        // ADR, not by content. They MUST classify as KeepCoreContract
        // regardless of heuristics (migrated-pending, UAT name, etc.)
        // because removing them would silently disable a permanent
        // internal guarantee. The set is fail-closed: adding a
        // permanent fitness requires a new ADR.
        if (isPermanentFitness(input.path)) {
            return DeletionDecision.KeepCoreContract
        }
        if (input.migratedPending) {
            // A file marked @Disabled("migrated-pending") is the
            // legitimate DEFER state for a UAT that awaits the
            // harness. It is owned by the harness and the test is
            // already skipped at runtime; classify as
            // MigrateExternalPending so the deletion guard still
            // refuses retirement and the inventory shows the
            // transition from Blocked to MigrateExternalPending.
            return DeletionDecision.MigrateExternalPending
        }
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
    val migratedPending: Boolean = false,
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

/**
 * The set of test paths that are permanent architectural fitness
 * gates and MUST never be retired by any heuristic. Membership is
 * ADR-protected: `UatLocal005BannedImportsTest` is the only entry
 * because it is the only V2 UAT that `docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md`
 * §2/§3 declares as **SE QUEDA** (it is a test of the
 * codebase, not of the installed binary — JGit ban, hardcoded
 * git path ban).
 */
val PERMANENT_FITNESS_PATHS: Set<String> = setOf(
    "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal005BannedImportsTest.kt",
)

fun isPermanentFitness(path: String): Boolean = path in PERMANENT_FITNESS_PATHS
