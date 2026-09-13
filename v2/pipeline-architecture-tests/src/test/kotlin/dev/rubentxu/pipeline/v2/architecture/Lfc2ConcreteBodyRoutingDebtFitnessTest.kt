package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * LFC-2 / B10 W1a — the durable coordinator's concrete block-Step routing debt is pinned and
 * may only shrink. See [PinnedConcreteBodyRoutingDebt] for the law and the coverage boundary.
 *
 * W1a is fitness-only: it does not migrate `projectShellScope` or
 * `dispatchWithCredentialsBlock`. It makes the existing debt visible, counted, and impossible
 * to extend, so that W1b..W1d can demonstrate the debt falling against a guard that can
 * actually detect the problem.
 */
class Lfc2ConcreteBodyRoutingDebtFitnessTest {

    private val coordinatorSource = ScannerSupport.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt")

    private fun coordinatorText(): String {
        require(Files.exists(coordinatorSource)) { "Expected source not found: $coordinatorSource" }
        return Files.readString(coordinatorSource)
    }

    /** The real coordinator carries exactly the pinned debt — no more, no less. */
    @Test
    fun `canonical durable coordinator carries only the pinned concrete routing debt`() {
        val discovered = ConcreteBodyRoutingScanner.scan(coordinatorText())
        val pinned = PinnedConcreteBodyRoutingDebt.value

        val verdict = ConcreteBodyRoutingVerdict.decide(discovered, pinned)

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
            "The ceiling records the debt measured when this guard was introduced; it must not be re-pinned",
        )
    }

    /**
     * The pre-existing guard and this ledger must not diverge. `Lfc2DurableCoordinatorScopeFitnessTest`
     * asserts `core.sh`/`core.echo` are absent; if either ever became real routing debt it would
     * have to be declared here too.
     */
    @Test
    fun `the pre-existing scope guard tokens are consistent with the pinned ledger`() {
        val pinnedNames = PinnedConcreteBodyRoutingDebt.value.concreteStepNames
        assertTrue(
            "core.sh" !in pinnedNames && "core.echo" !in pinnedNames,
            "core.sh/core.echo are asserted absent by the scope guard and must not appear as pinned debt",
        )
        assertTrue(
            PinnedConcreteBodyRoutingDebt.value.bodyStepIds.all { it in pinnedNames },
            "Every pinned body step id must also be a pinned concrete step name",
        )
    }

    @Nested
    inner class ViolationFixture {

        private val baseline = ConcreteBodyRoutingScanner.scan(coordinatorText())
        private val pinned = PinnedConcreteBodyRoutingDebt.value

        private fun violationsFor(source: String): List<RoutingDebtViolation> {
            val verdict = ConcreteBodyRoutingVerdict.decide(ConcreteBodyRoutingScanner.scan(source))
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
         * body allowlist is therefore the regression this fixture pins, and it must be
         * reported twice: as a re-appeared routing site and as unnamed body Step ids.
         */
        @Test
        fun `re-introducing a hard-coded body allowlist is rejected`() {
            val mutated = coordinatorText()
                .plus("\nprivate val canonicalBodyStepIds: Set<String> = setOf(\"core.echo\")\n")

            val found = violationsFor(mutated)
            assertDeclares(found, ConcreteRoutingDebtItem.BodyStepId("core.echo"))
            assertDeclares(found, ConcreteRoutingDebtItem.RoutingSite(BodyRoutingSite.CANONICAL_BODY_STEP_IDS))
        }

        /** Removing a site must drag the ledger down with it, not silently reduce the debt. */
        @Test
        fun `removing a routing site without lowering the ledger is rejected`() {
            // Rename EVERY occurrence: the detector keys on the identifier appearing anywhere
            // in the file (declaration, call, comment), so renaming only the declaration leaves
            // the site detectable and the fixture would pass for the wrong reason.
            val mutated = coordinatorText().replace("dispatchWithCredentialsBlock", "removedCredentialsDispatch")

            val found = violationsFor(mutated)
            assertTrue(
                found.any { it is RoutingDebtViolation.SiteInventoryDrift },
                "Dropping a known site must be reported as site inventory drift; got $found",
            )
        }

        /**
         * Since W1c the ledger (4) sits far below the high-water mark (18), so a ledger
         * padded with an item the coordinator does not contain is caught by the total
         * comparison rather than by the ceiling.
         */
        @Test
        fun `a pinned ledger padded with a phantom item is rejected`() {
            val padded = pinned.copy(
                concreteStepNames = pinned.concreteStepNames + "core.echo",
            )
            val verdict = ConcreteBodyRoutingVerdict.decide(baseline, pinned = padded)

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
                concreteStepNames = pinned.concreteStepNames +
                    (1..PinnedConcreteBodyRoutingDebt.HISTORICAL_CEILING).map { "core.injected$it" }.toSet(),
            )
            val verdict = ConcreteBodyRoutingVerdict.decide(baseline, pinned = inflated)

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
