package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * LFC-2 / B10 W1a..W1d — the durable coordinator's concrete block-Step routing debt is pinned
 * and may only shrink. See [PinnedConcreteBodyRoutingDebt] for the law and the coverage
 * boundary.
 *
 * W1a made the debt visible, counted and impossible to extend (18 items). W1c retired 14 of
 * them by resolving the body policy from the declaration. W1d retires the last 4: the
 * credential bypass is burned into the shared body path, and the two concrete durable
 * identities are reclassified as typed aggregate identities and guarded separately. The
 * ledger is therefore EMPTY, and the empty set is the strongest form of this guard — every
 * item is undeclared, so the first one to come back fails immediately.
 */
class Lfc2ConcreteBodyRoutingDebtFitnessTest {

    private val coordinatorSource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt")

    private fun coordinatorText(): String {
        require(Files.exists(coordinatorSource)) { "Expected source not found: $coordinatorSource" }
        return Files.readString(coordinatorSource)
    }

    private fun scanLoops(text: String = coordinatorText()): BodyChildLoopInventory =
        BodyChildLoopScanner.scan(text)

    /** The real coordinator carries exactly the pinned debt — no more, no less. */
    @Test
    fun `canonical durable coordinator carries only the pinned concrete routing debt`() {
        val discovered = ConcreteBodyRoutingScanner.scan(coordinatorText())
        val pinned = PinnedConcreteBodyRoutingDebt.value

        val verdict = ConcreteBodyRoutingVerdict.decide(discovered, scanLoops(), pinned)

        assertTrue(
            verdict is RoutingDebtVerdict.WithinPinnedDebt,
            "Concrete block routing debt must stay within the pinned ledger: $verdict",
        )
        assertEquals(
            pinned.total,
            (verdict as RoutingDebtVerdict.WithinPinnedDebt).debtTotal,
            "Debt total must match the pinned ledger exactly; a change requires an explicit ledger edit",
        )
    }

    /**
     * W1d: the ledger is empty. Asserted as a literal so that "the debt fell to zero" is a
     * claim in the test report rather than an inference from an empty set.
     */
    @Test
    fun `the concrete routing ledger is empty since W1d`() {
        assertEquals(
            0,
            PinnedConcreteBodyRoutingDebt.value.total,
            "W1d retires the credential bypass and reclassifies the durable aggregate identities",
        )
        assertEquals(
            0,
            ConcreteBodyRoutingScanner.scan(coordinatorText()).total,
            "An empty ledger with a non-empty measurement is the regression this law exists to catch",
        )
    }

    /**
     * No slack: the pinned ledger names every item the coordinator contains, so an undeclared
     * item cannot hide behind a matching total. This is the check that makes the "move two
     * literals from one site to another" false green impossible.
     */
    @Test
    fun `the pinned ledger enumerates every discovered debt item`() {
        val discovered = ConcreteBodyRoutingScanner.scan(coordinatorText()).items()
        val pinned = PinnedConcreteBodyRoutingDebt.value.items()

        assertEquals(
            emptySet<ConcreteRoutingDebtItem>(),
            discovered - pinned,
            "Every concrete routing item in the coordinator must be named in the pinned ledger",
        )
        assertEquals(
            pinned,
            discovered,
            "The pinned ledger must not carry slack: it is the measured debt, not a ceiling",
        )
    }

    /** The burn-down law: the ceiling is the measured state and may never be raised. */
    @Test
    fun `the pinned ledger total is within the historical ceiling`() {
        assertTrue(
            PinnedConcreteBodyRoutingDebt.value.total <= PinnedConcreteBodyRoutingDebt.HISTORICAL_CEILING,
            "The pinned ledger was raised beyond the high-water mark; debt may only decrease",
        )
        assertEquals(
            18,
            PinnedConcreteBodyRoutingDebt.HISTORICAL_CEILING,
            "The ceiling records the debt measured when this guard was introduced; it must not be " +
                "re-pinned, and W1d deliberately did not lower it to follow the ledger to zero",
        )
    }

    /**
     * W1d: the credential lease must be a PREAMBLE of the shared body path, not a parallel
     * path. Exactly one function iterates the body children and exactly one site acquires a
     * lease; a second of either is a block Step with execution semantics the shared engine
     * does not own (ADR-0073).
     */
    @Test
    fun `the body child path is shared and defined exactly once`() {
        assertEquals(
            BodyChildLoopInventory.EXPECTED,
            scanLoops(),
            "The coordinator must dispatch every body through ONE child loop, with ONE credential " +
                "acquisition site: a second loop or acquisition means a Step bypassed the engine",
        )
    }

    /**
     * The pre-existing guard and this ledger must not diverge. `Lfc2DurableCoordinatorScopeFitnessTest`
     * asserts `core.sh`/`core.echo` are absent; if either ever became real routing debt it would
     * have to be declared here too.
     */
    @Test
    fun `the retired ledger is empty and holds no scope-guard token`() {
        val pinned = PinnedConcreteBodyRoutingDebt.value
        assertTrue(
            "core.sh" !in pinned.concreteStepNames && "core.echo" !in pinned.concreteStepNames,
            "core.sh/core.echo are asserted absent by the scope guard and must not appear as pinned debt",
        )
        assertEquals(
            emptySet<String>(),
            pinned.concreteStepNames,
            "No concrete Step name is pinned any more; the identities that remain are typed " +
                "aggregate identities, guarded by Lfc2DurableAggregateIdentityFitnessTest",
        )
    }

    @Nested
    inner class ViolationFixture {

        private val baseline = ConcreteBodyRoutingScanner.scan(coordinatorText())
        private val pinned = PinnedConcreteBodyRoutingDebt.value

        private fun violationsFor(source: String): List<RoutingDebtViolation> {
            val verdict = ConcreteBodyRoutingVerdict.decide(
                ConcreteBodyRoutingScanner.scan(source),
                BodyChildLoopScanner.scan(source),
            )
            assertTrue(
                verdict is RoutingDebtVerdict.DebtMustBeAddressed,
                "Expected the guard to reject this source, got $verdict",
            )
            return (verdict as RoutingDebtVerdict.DebtMustBeAddressed).violations
        }

        private fun assertDeclares(found: List<RoutingDebtViolation>, item: ConcreteRoutingDebtItem) {
            assertTrue(
                RoutingDebtViolation.UndeclaredRouting(item) in found,
                "Expected undeclared routing item $item; violations were $found",
            )
        }

        /** A new concrete Step name in the real production source is rejected. */
        @Test
        fun `real coordinator with an injected concrete step literal is rejected`() {
            val mutated = coordinatorText() + "\nprivate val injected = \"core.echo\"\n"

            assertDeclares(violationsFor(mutated), ConcreteRoutingDebtItem.ConcreteStepName("core.echo"))
        }

        /** A second dispatch*Block seam in the real source is rejected. */
        @Test
        fun `real coordinator with a new block dispatch bypass is rejected`() {
            val mutated = coordinatorText() +
                "\nprivate suspend fun dispatchTimeoutBlock(): Int = 0\n"

            assertDeclares(
                violationsFor(mutated),
                ConcreteRoutingDebtItem.BlockDispatchBypass("dispatchTimeoutBlock"),
            )
        }

        /** Any added step-identity switch in the real source is rejected. */
        @Test
        fun `real coordinator with an added step id switch is rejected`() {
            val mutated = coordinatorText() +
                "\nprivate fun injected(otherStepId: String) = when (otherStepId.value) { else -> 0 }\n"

            assertDeclares(
                violationsFor(mutated),
                ConcreteRoutingDebtItem.StepIdSwitch(baseline.stepIdSwitches + 1),
            )
        }

        /**
         * W1c made canonical body eligibility registry-derived. Re-introducing a hard-coded
         * body allowlist is therefore the regression this fixture pins, and it must be reported
         * twice: as a re-appeared routing site and as unnamed body Step ids.
         */
        @Test
        fun `re-introducing a hard-coded body allowlist is rejected`() {
            val mutated = coordinatorText()
                .plus("\nprivate val canonicalBodyStepIds: Set<String> = setOf(\"core.echo\")\n")

            val found = violationsFor(mutated)
            assertDeclares(found, ConcreteRoutingDebtItem.BodyStepId("core.echo"))
            assertDeclares(found, ConcreteRoutingDebtItem.RoutingSite(BodyRoutingSite.CANONICAL_BODY_STEP_IDS))
        }

        /**
         * W1d: duplicating the body-child loop is the shape the credential bypass had, and it
         * can be spelled with NO literal at all. It must therefore be rejected by the inventory
         * law, independently of the name-based scan.
         */
        @Test
        fun `duplicating the body child loop is rejected even without any literal`() {
            val mutated = coordinatorText() +
                "\nprivate suspend fun secondBodyPath(block: BlockStepNode) {\n" +
                "    for ((index, child) in block.body.withIndex()) { dispatch(child, index) }\n" +
                "}\n"

            val found = violationsFor(mutated)
            assertTrue(
                found.any { it is RoutingDebtViolation.BodyPathNotShared },
                "A second body-child loop must be reported as a non-shared body path; got $found",
            )
            assertDeclares(
                found,
                ConcreteRoutingDebtItem.RoutingSite(BodyRoutingSite.DISPATCH_WITH_CREDENTIALS_BLOCK),
            )
        }

        /** A second credential acquisition site is the same regression, seen from the lease. */
        @Test
        fun `a second credential acquisition site is rejected`() {
            val mutated = coordinatorText() +
                "\nprivate suspend fun acquireAgain(bindings: List<CredentialBindingSpec>) = " +
                "credentialScopePort.acquire(bindings, runId)\n"

            val found = violationsFor(mutated)
            assertTrue(
                found.any {
                    it is RoutingDebtViolation.BodyPathNotShared &&
                        it.discovered.credentialAcquisitions == 2
                },
                "A second lease acquisition must be reported; got $found",
            )
        }

        /** Removing a site must drag the ledger down with it, not silently reduce the debt. */
        @Test
        fun `a ledger that still pins a site the source no longer contains is rejected`() {
            // W1d retired the credential bypass from the coordinator, so the drift direction is
            // now the inverse: a ledger naming a site that is not there is a ledger that does
            // not describe the file. The comparison is symmetric, which is why one fixture
            // covers both directions.
            val stale = pinned.copy(sites = setOf(BodyRoutingSite.PROJECT_SHELL_SCOPE))
            val verdict = ConcreteBodyRoutingVerdict.decide(baseline, scanLoops(), stale)

            assertTrue(
                verdict is RoutingDebtVerdict.DebtMustBeAddressed,
                "A ledger naming an absent site must be rejected, got $verdict",
            )
            assertTrue(
                (verdict as RoutingDebtVerdict.DebtMustBeAddressed).violations.any {
                    it is RoutingDebtViolation.SiteInventoryDrift
                },
                "Expected SiteInventoryDrift; violations were ${verdict.violations}",
            )
        }

        /**
         * Since W1c the ledger sat far below the high-water mark; since W1d it is empty, so a
         * ledger padded with an item the coordinator does not contain is caught by the total
         * comparison rather than by the ceiling.
         */
        @Test
        fun `a pinned ledger padded with a phantom item is rejected`() {
            val padded = pinned.copy(concreteStepNames = setOf("core.echo"))
            val verdict = ConcreteBodyRoutingVerdict.decide(baseline, scanLoops(), padded)

            assertTrue(
                verdict is RoutingDebtVerdict.DebtMustBeAddressed,
                "A ledger that does not describe the measured source must be rejected, got $verdict",
            )
            assertTrue(
                (verdict as RoutingDebtVerdict.DebtMustBeAddressed).violations.any {
                    it is RoutingDebtViolation.LedgerOutOfSync
                },
                "Expected LedgerOutOfSync; violations were ${verdict.violations}",
            )
        }

        /** The burn-down ceiling is absolute: no ledger may be pinned above the high-water mark. */
        @Test
        fun `a pinned ledger above the historical ceiling is rejected`() {
            val inflated = pinned.copy(
                concreteStepNames = (1..PinnedConcreteBodyRoutingDebt.HISTORICAL_CEILING + 1)
                    .map { "core.injected$it" }.toSet(),
            )
            val verdict = ConcreteBodyRoutingVerdict.decide(baseline, scanLoops(), inflated)

            assertTrue(
                verdict is RoutingDebtVerdict.DebtMustBeAddressed,
                "A ledger above the high-water mark must be rejected, got $verdict",
            )
            assertTrue(
                (verdict as RoutingDebtVerdict.DebtMustBeAddressed).violations.any {
                    it is RoutingDebtViolation.LedgerRaisedBeyondCeiling
                },
                "Expected LedgerRaisedBeyondCeiling; violations were ${verdict.violations}",
            )
        }

        /**
         * The real production source is not merely within the ledger by total: it matches the
         * enumerated item set exactly, so the ledger describes this file and nothing else.
         */
        @Test
        fun `the real source matches the pinned item set exactly`() {
            assertEquals(pinned.items(), baseline.items())
            assertEquals(pinned.total, baseline.total)
        }
    }
}
