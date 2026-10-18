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
        if (isPermanentContract(input.path)) {
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
        if (input.observedProcessApis.isNotEmpty()) {
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
    val observedProcessApis: List<ObservedProcessApi>,
    val testAnnotated: Int,
    val disabledAnnotations: Int,
    val releaseScaleTag: Boolean,
    val contentSha256: String,
    val migratedPending: Boolean = false,
) {
    init {
        require(path.isNotBlank()) { "path must not be blank" }
        // BQ4 v6 (strict digest carrier, 2026-10-17):
        // the previous shape checked length but NOT hex
        // characters. An uppercase or non-hex string of
        // length 64 was accepted as a "SHA-256" and
        // compared by string equality against the
        // harness's lowercase digest, silently never
        // matching. The carrier now mirrors the format
        // requirement (64-char lowercase hex) that the
        // harness-emitted digests follow and that the
        // rest of the retirement package already
        // enforces.
        require(contentSha256.length == 64 && contentSha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "contentSha256 must be a 64-char lowercase hex SHA-256"
        }
    }
}

/**
 * The process API the test exercises.
 *
 * The previous shape was a `String` field on [TestRecord] that the
 * Wave classifier matched with a list of substrings. The list was
 * the dual-emitter defect class: a typo at either site silently
 * misrouted the outcome. The new shape is a sealed ADT with one
 * case per known API. The CSV column at the source is still a
 * free-form string (the AST scanner concatenates detected APIs
 * with no delimiter); the [parser] on the companion converts the
 * raw string to the typed list at the Kotlin boundary, where the
 * compiler can enforce the rest of the protocol.
 */
sealed interface ObservedProcessApi {
    /** The test calls `ProcessBuilder(...)`. The trailing `(` excludes reference sites like
     *  `ProcessBuilder::class`. */
    data object ProcessBuilder : ObservedProcessApi

    /** The test uses `AppBinSupport` (the harness's installed-binary discovery helper). */
    data object AppBinSupport : ObservedProcessApi

    /** The test owns a long-lived subprocess via the harness's `OwnedSubprocess`. */
    data object OwnedSubprocess : ObservedProcessApi

    companion object {
        /**
         * Parse the raw CSV column into a typed list.
         *
         * The raw string is the AST scanner's concatenation of detected
         * call sites; the parser looks for each known signature and
         * returns the matching typed cases in stable order (the
         * declaration order in this companion). A future API is
         * one new case in the sealed type plus one new `if` here.
         */
        fun parser(raw: String): List<ObservedProcessApi> = buildList {
            if ("ProcessBuilder(" in raw) add(ProcessBuilder)
            if ("AppBinSupport" in raw) add(AppBinSupport)
            if ("OwnedSubprocess" in raw) add(OwnedSubprocess)
        }
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
 * Test paths that are permanent gates and MUST never be retired by any
 * heuristic, because the name alone does not identify them as such.
 *
 * Membership is ADR-protected: `UatLocal005BannedImportsTest` is a test of
 * the *codebase* (JGit ban, hardcoded git path ban) wearing a UAT name, so
 * neither [PERMANENT_CONTRACT_PATTERNS] nor the process heuristic sees it.
 * It is the single row `docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md`
 * §2 marks **SE QUEDA**, and it is here for that reason alone.
 */
val PERMANENT_FITNESS_PATHS: Set<String> = setOf(
    "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal005BannedImportsTest.kt",
)

/**
 * The permanent-contract *families* of
 * `RESPONSIBILITY_MIGRATION_ROADMAP.md` §3 — "tests de producto, no de
 * certificación; viven en pipeline-kotlin indefinidamente".
 *
 * These were spelled out as families in the roadmap and were NOT spelled out
 * here: the previous shape hardcoded ONE path and treated that as the whole
 * protection, so every other §3 test fell through to the heuristics below.
 * That is not a hypothetical, because §3's first rule is "prefer the rule over
 * the list": five of those tests observe the installed binary and the
 * `observedProcessApis` branch classified all five as
 * `MigrateExternalPending` — a state §4.1 may promote to a deletion.
 *
 * Two of the five are the fitnesses that police the installed-distribution
 * contract itself (`InstalledContractTagMembershipFitnessTest`,
 * `InstalledDistributionHarnessFitnessTest`). A guard that classifies its own
 * guard as migratable is the `green-row-that-certifies-the-defect` shape, so
 * the rule is expressed as a family: a new §3 fitness that touches a process
 * is protected without anybody editing a list.
 *
 * The fitness family has TWO spellings in this corpus and both are matched,
 * because they are counted separately and the first attempt covered only one:
 * `*FitnessTest` (92 files) and `FArch*Test` (57 files — the ADR-numbered
 * architecture gates, `FArch011V2NoCompileExcludesTest` and siblings). A
 * pattern that names a convention instead of the concept protects whichever
 * one the author remembered.
 *
 * The roadmap's §3 also lists "tests in `pipeline-domain`, `pipeline-events`,
 * `pipeline-step-sdk`, etc." The `etc.` is not enumerable and is deliberately
 * NOT approximated here — a guessed module list would be a second authority
 * that can disagree with the first. Those tests are protected anyway, because
 * `classify` ends in `KeepCoreContract` for anything carrying `@Test`.
 */
val PERMANENT_CONTRACT_PATTERNS: List<Regex> = listOf(
    Regex("""(^|/)StepContractSuite[A-Za-z0-9_]*\.kt$"""),
    Regex("""(^|/)Core[A-Za-z0-9_]*Step(Unit|ContractSuite)Test\.kt$"""),
    Regex("""[A-Za-z0-9_]FitnessTest\.kt$"""),
    Regex("""(^|/)FArch[A-Za-z0-9_]*Test\.kt$"""),
    Regex("""[A-Za-z0-9_]Architectur[A-Za-z0-9_]*\.kt$"""),
    Regex("""(^|/)CompatCorpusTest[A-Za-z0-9_]*\.kt$"""),
)

/**
 * Whether [path] is a permanent in-repo contract. Fail-closed by shape: only a
 * `.kt` path can match, and an empty path matches nothing, which [TestRecord]
 * already refuses at construction.
 */
fun isPermanentContract(path: String): Boolean =
    path in PERMANENT_FITNESS_PATHS || PERMANENT_CONTRACT_PATTERNS.any { it.containsMatchIn(path) }
