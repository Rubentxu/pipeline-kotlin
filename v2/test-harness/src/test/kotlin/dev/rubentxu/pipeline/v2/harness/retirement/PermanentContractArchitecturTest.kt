package dev.rubentxu.pipeline.v2.harness.retirement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * H0.5 (2026-10-18). Why this file exists.
 *
 * `RESPONSIBILITY_MIGRATION_ROADMAP.md` §3 names six families of tests that
 * "viven en pipeline-kotlin indefinidamente". The retirement package encoded
 * that as a hand-written set of **one** path — and the one path was
 * `UatLocal005BannedImportsTest`, the only row the roadmap marks **SE QUEDA**
 * in §2, i.e. the only one whose *name* hides that it is a permanent contract.
 *
 * The five other §3 families were unprotected, and the classifier's
 * `observedProcessApis` branch is the branch they all reach: five of them
 * observe the installed binary. `classify` therefore returned
 * `MigrateExternalPending` for all five, a state §4.1 may promote to
 * `RedundantWithProof` — the only shape `DeletionGuard` accepts. Two of the
 * five are the fitnesses that police the installed-distribution contract
 * itself, so the guard that protects the lane could have been classified as
 * migratable by the classifier that implements it.
 *
 * Two separate properties are asserted, because they are separately
 * defeatable and the second is the one that mattered:
 *
 *  1. the classifier no longer routes a §3 family into a retiring state;
 *  2. the GUARD refuses a permanent contract even when handed a
 *     `RedundantWithProof` that satisfies every other condition — so a
 *     promotion that skips the classifier cannot delete it.
 *
 * Property 2 is the invariant. Property 1 is only a nicety: the classifier is
 * a heuristic that returns a decision, and §4.1 is exactly the path that
 * upgrades that decision.
 */
class PermanentContractArchitecturTest {

    private val sha = "b".repeat(64)

    private fun rec(
        path: String,
        observed: List<ObservedProcessApi> = emptyList(),
    ) = TestRecord(
        path = path,
        kindHint = KindHint.OTHER_TEST_SOURCE,
        runnerHint = RunnerHint.V2_GRADLE,
        observedProcessApis = observed,
        testAnnotated = 3,
        disabledAnnotations = 0,
        releaseScaleTag = false,
        contentSha256 = sha,
    )

    // ---- 1. the classifier keeps §3 families ---------------------------------

    /** The five §3 tests that observe the installed binary, measured 2026-10-18. */
    private val processObservingPermanentContracts = listOf(
        "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/" +
            "Lfc2WaitUntilCanonicalReentryFitnessTest.kt" to listOf(ObservedProcessApi.AppBinSupport),
        "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/" +
            "ObsC23NoChannelFusionFitnessTest.kt" to listOf(ObservedProcessApi.ProcessBuilder),
        "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/support/" +
            "InstalledContractTagMembershipFitnessTest.kt" to listOf(ObservedProcessApi.AppBinSupport),
        "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/support/" +
            "InstalledDistributionHarnessFitnessTest.kt" to
            listOf(ObservedProcessApi.AppBinSupport, ObservedProcessApi.OwnedSubprocess),
        "v2/test-harness/src/test/kotlin/dev/rubentxu/pipeline/v2/harness/catalog/" +
            "ContractCatalogueFitnessTest.kt" to listOf(ObservedProcessApi.AppBinSupport),
    )

    @Test
    fun `a §3 family that observes a process is not MigrateExternalPending`() {
        for ((path, observed) in processObservingPermanentContracts) {
            val decision = TestClassifier.classify(rec(path, observed))
            assertEquals(
                DeletionDecision.KeepCoreContract,
                decision,
                "$path is a permanent §3 contract; touching the binary must not make it migratable",
            )
        }
    }

    @Test
    fun `every §3 family name is recognised, including the ones with no process API`() {
        val families = listOf(
            "v2/pipeline-step-sdk/src/test/kotlin/.../CoreShStepContractSuiteTest.kt",
            "v2/pipeline-application/src/test/kotlin/.../CoreEchoStepUnitTest.kt",
            "v2/pipeline-architecture-tests/src/test/kotlin/.../FArch011V2NoCompileExcludesTest.kt",
            "v2/pipeline-architecture-tests/src/test/kotlin/.../LayerArchitecturRulesTest.kt",
            "v2/pipeline-application/src/test/kotlin/.../CompatCorpusTestFixturesTest.kt",
        )
        for (path in families) {
            assertTrue(isPermanentContract(path), "$path is a §3 family and must be protected")
        }
    }

    /** The one §2 **SE QUEDA** row: permanent, and invisible to both §3 families. */
    @Test
    fun `the §2 SE QUEDA row stays protected because its name is not a §3 family`() {
        val path =
            "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/" +
                "UatLocal005BannedImportsTest.kt"
        assertEquals(
            DeletionDecision.KeepCoreContract,
            TestClassifier.classify(rec(path, listOf(ObservedProcessApi.ProcessBuilder))),
        )
    }

    /** A process UAT is NOT a §3 family, and must still be migratable. */
    @Test
    fun `a process UAT outside §3 is still MigrateExternalPending`() {
        val path =
            "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/" +
                "UatLocal001KillDuringShTest.kt"
        assertEquals(
            DeletionDecision.MigrateExternalPending,
            TestClassifier.classify(rec(path, listOf(ObservedProcessApi.ProcessBuilder))),
            "the §3 families must not swallow the UATs they are meant to exclude",
        )
    }

    // ---- 2. the GUARD closes independently of the classifier ------------------

    /**
     * A `RedundantWithProof` that satisfies EVERY other `DeletionGuard`
     * condition: matching content digest, Wave C, sandbox-grade authority,
     * both waves authorised. The permanence refusal must be the only thing
     * standing between this input and a deletion of a permanent §3 contract.
     */
    private val fullyAuthorisedExceptPermanence = DeletionDecision.RedundantWithProof(
        originalFileSha256 = sha,
        proofDigest = "c".repeat(64),
        authority = "sandbox",
    )

    @Test
    fun `the guard refuses a permanent contract even with a fully valid proof`() {
        for ((path, observed) in processObservingPermanentContracts) {
            val outcome = DeletionGuard.check(
                decision = fullyAuthorisedExceptPermanence,
                record = rec(path, observed),
                retirementAuthorised = true,
                retirementAuthorisedD = true,
            )
            assertTrue(outcome is GuardOutcome.Refused) {
                "$path must not be retirable by any proof; got $outcome"
            }
            assertTrue(
                (outcome as GuardOutcome.Refused).reason.contains("permanent"),
            ) { "the refusal must name the reason; got '${outcome.reason}'" }
        }
    }

    /** The same input on a NON-permanent path is authorised — so the guard's teeth are
     *  the permanence rule and not a blanket refusal that would pass this test vacuously. */
    @Test
    fun `the guard still authorises a non-permanent record with the identical proof`() {
        val outcome = DeletionGuard.check(
            decision = fullyAuthorisedExceptPermanence,
            record = rec(
                "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/" +
                    "UatLocal001KillDuringShTest.kt",
                listOf(ObservedProcessApi.ProcessBuilder),
            ),
            retirementAuthorised = true,
            retirementAuthorisedD = true,
        )
        assertEquals(GuardOutcome.Authorised, outcome)
    }
}