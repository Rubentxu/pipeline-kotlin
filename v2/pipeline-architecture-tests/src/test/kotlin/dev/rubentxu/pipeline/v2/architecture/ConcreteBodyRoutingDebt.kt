package dev.rubentxu.pipeline.v2.architecture

/**
 * LFC-2 / B10 W1a — concrete block-Step routing debt in the canonical durable coordinator.
 *
 * ADR-0073 requires block Steps to re-enter the engine through the body machinery, and
 * AGENTS.md forbids a central concrete-Step switch. The coordinator does not satisfy that
 * yet: `CanonicalDurableRunCoordinator` carries `canonicalBodyStepIds`, a
 * `when (pluginStepId.value)` in `projectShellScope`, and a `dispatchWithCredentialsBlock`
 * bypass beside `dispatchBody`.
 *
 * The pre-existing guard (`Lfc2DurableCoordinatorScopeFitnessTest`) names this principle but
 * asserts only `core.sh` and `core.echo`, which are not routed there at all, so it is green
 * while the debt stands (see `docs/v2/07-uat/B10_W1_PREFLIGHT.md` §2). This model replaces
 * "the literal count is 15" with an enumerated, typed ledger:
 *
 * ```text
 * new concrete step name in the coordinator   -> FAIL
 * new step-id switch                          -> FAIL
 * new dispatch*Block bypass                   -> FAIL
 * removing a known routing site               -> FAIL until the ledger is lowered
 * raising the ledger beyond the high-water    -> FAIL
 * ```
 *
 * Every debt item is named individually, so the false green this model exists to prevent —
 * delete two literals in one site, add two Step-specific names in another — fails on the
 * undeclared names rather than passing on an unchanged total.
 *
 * ## Coverage boundary (what this scan does NOT see)
 *
 * The scan recognises: any `"core.<name>"` string literal, any `when (...stepId...)` switch,
 * any `dispatch*Block` identifier, and three named structural sites. It does NOT see routing
 * that builds a Step name at runtime (concatenation, lookup, or a value flowing in from a
 * caller) and it does NOT see routing on an enum ordinal or a numeric id. A closed scanner
 * cannot decide those; if such a shape appears, this guard must be extended rather than
 * assumed to cover it.
 *
 * The scan is deliberately conservative in the other direction: a site is detected when its
 * identifier appears anywhere in the file, including a call site or a comment, so it can
 * over-report rather than under-report. Over-reporting fails the guard, which is the safe way
 * to be wrong. Prose is inside the scanned surface by design: do not spell a forbidden routing
 * form even in a comment that says it is forbidden (`W1c` had to rephrase one).
 */

/** A structural site in the durable coordinator known to route on concrete block identities. */
enum class BodyRoutingSite {
    /**
     * Retired by W1c: the coordinator derives body eligibility from declared ownership. The
     * case stays so that RE-INTRODUCING a hard-coded body id set is detected and fails.
     */
    CANONICAL_BODY_STEP_IDS,

    /**
     * Retired by W1c: scope projection is keyed by the declared policy. The case stays so
     * that RE-INTRODUCING a keyed scope projection is detected and fails.
     */
    PROJECT_SHELL_SCOPE,

    DISPATCH_WITH_CREDENTIALS_BLOCK,
}

/** One enumerated unit of concrete routing debt. Named, so the ledger can only shrink. */
sealed interface ConcreteRoutingDebtItem {
    data class ConcreteStepName(val id: String) : ConcreteRoutingDebtItem
    data class BodyStepId(val id: String) : ConcreteRoutingDebtItem
    data class RoutingSite(val site: BodyRoutingSite) : ConcreteRoutingDebtItem
    data class BlockDispatchBypass(val name: String) : ConcreteRoutingDebtItem
    data class StepIdSwitch(val count: Int) : ConcreteRoutingDebtItem
}

/** An explicit, enumerated accounting of concrete block routing in one coordinator source. */
data class ConcreteBodyRoutingDebt(
    val concreteStepNames: Set<String>,
    val bodyStepIds: Set<String>,
    val sites: Set<BodyRoutingSite>,
    val blockBypasses: Set<String>,
    val stepIdSwitches: Int,
) {
    val total: Int
        get() = concreteStepNames.size + bodyStepIds.size + sites.size +
            blockBypasses.size + stepIdSwitches

    fun items(): Set<ConcreteRoutingDebtItem> = buildSet {
        concreteStepNames.forEach { add(ConcreteRoutingDebtItem.ConcreteStepName(it)) }
        bodyStepIds.forEach { add(ConcreteRoutingDebtItem.BodyStepId(it)) }
        sites.forEach { add(ConcreteRoutingDebtItem.RoutingSite(it)) }
        blockBypasses.forEach { add(ConcreteRoutingDebtItem.BlockDispatchBypass(it)) }
        if (stepIdSwitches > 0) add(ConcreteRoutingDebtItem.StepIdSwitch(stepIdSwitches))
    }
}

sealed interface RoutingDebtViolation {
    data class UndeclaredRouting(val item: ConcreteRoutingDebtItem) : RoutingDebtViolation
    data class SiteInventoryDrift(
        val discovered: Set<BodyRoutingSite>,
        val pinned: Set<BodyRoutingSite>,
    ) : RoutingDebtViolation
    data class LedgerOutOfSync(val discoveredTotal: Int, val pinnedTotal: Int) : RoutingDebtViolation
    data class LedgerRaisedBeyondCeiling(val pinnedTotal: Int, val ceiling: Int) : RoutingDebtViolation
}

sealed interface RoutingDebtVerdict {
    data class WithinPinnedDebt(val debtTotal: Int) : RoutingDebtVerdict
    data class DebtMustBeAddressed(val violations: List<RoutingDebtViolation>) : RoutingDebtVerdict
}

/**
 * The pinned ledger. These values are the state measured at `1afb4799`; every one of them is
 * an item that ADR-0073's burn-down is supposed to remove.
 *
 * W1c re-measured and lowered the ledger: the step-id switch, the hard-coded body step id set,
 * the five Step names only the switch and the messages used, and the two sites that carried them
 * are gone: 14 of the 18 items retired, 4 left. What remains is the credential-lifecycle
 * dispatcher, which is still a separate execution path, plus two concrete durable identities
 * that are NOT body routing (`core.parallel` names the PAR-D stage aggregate operation row,
 * `core.retry` names the RETRY-D retry control row).
 */
object PinnedConcreteBodyRoutingDebt {

    /**
     * High-water mark. This number is never raised: it records how much debt existed when the
     * guard was introduced, and the burn-down law is that it may only fall.
     */
    const val HISTORICAL_CEILING = 18

    val value = ConcreteBodyRoutingDebt(
        concreteStepNames = setOf(
            "core.parallel",
            "core.retry",
        ),
        // W1c: empty. Body eligibility is derived from declared ownership
        // (`StepDescriptorRegistry.bodyStepIds`), so there is no hard-coded set left to pin.
        bodyStepIds = emptySet(),
        // Named explicitly, not BodyRoutingSite.entries.toSet(): "all entries" would
        // silently absorb a fourth site the day someone adds one, which is exactly the
        // slack this ledger exists to prevent.
        sites = setOf(
            BodyRoutingSite.DISPATCH_WITH_CREDENTIALS_BLOCK,
        ),
        blockBypasses = setOf("dispatchWithCredentialsBlock"),
        stepIdSwitches = 0,
    )
}

/** Pure scan of one coordinator source into an enumerated debt. */
object ConcreteBodyRoutingScanner {

    private val concreteStepLiteral = Regex("\"core\\.[A-Za-z0-9_]+\"")
    private val bodyStepIdsBlock =
        Regex("canonicalBodyStepIds\\s*:\\s*Set<String>\\s*=\\s*setOf\\(([^)]*)\\)")
    private val stepIdSwitch = Regex("when\\s*\\(\\s*[A-Za-z0-9_.]*[Ss]tepId[A-Za-z0-9_.]*\\s*\\)")
    private val blockDispatchIdentifier = Regex("\\bdispatch[A-Za-z0-9_]*Block\\b")
    private val projectShellScope = Regex("projectShellScope\\s*\\(")
    private val withCredentialsBypass =
        Regex("private\\s+suspend\\s+fun\\s+dispatchWithCredentialsBlock\\(")

    fun scan(source: String): ConcreteBodyRoutingDebt {
        val bodyStepIds = bodyStepIdsBlock.find(source)
            ?.groupValues?.get(1)
            ?.let { block -> concreteStepLiteral.findAll(block).map { unquote(it.value) }.toSet() }
            ?: emptySet()

        val sites = buildSet {
            if (bodyStepIdsBlock.containsMatchIn(source)) add(BodyRoutingSite.CANONICAL_BODY_STEP_IDS)
            if (projectShellScope.containsMatchIn(source)) add(BodyRoutingSite.PROJECT_SHELL_SCOPE)
            if (withCredentialsBypass.containsMatchIn(source)) add(BodyRoutingSite.DISPATCH_WITH_CREDENTIALS_BLOCK)
        }

        return ConcreteBodyRoutingDebt(
            concreteStepNames = concreteStepLiteral.findAll(source).map { unquote(it.value) }.toSet(),
            bodyStepIds = bodyStepIds,
            sites = sites,
            blockBypasses = blockDispatchIdentifier.findAll(source).map { it.value }.toSet(),
            stepIdSwitches = stepIdSwitch.findAll(source).count(),
        )
    }

    private fun unquote(literal: String): String = literal.removeSurrounding("\"")
}

/** Pure verdict. Total: every discovered item either is pinned or is a violation. */
object ConcreteBodyRoutingVerdict {

    fun decide(
        discovered: ConcreteBodyRoutingDebt,
        pinned: ConcreteBodyRoutingDebt = PinnedConcreteBodyRoutingDebt.value,
        ceiling: Int = PinnedConcreteBodyRoutingDebt.HISTORICAL_CEILING,
    ): RoutingDebtVerdict {
        val violations = buildList {
            if (pinned.total > ceiling) add(RoutingDebtViolation.LedgerRaisedBeyondCeiling(pinned.total, ceiling))
            (discovered.items() - pinned.items())
                .sortedBy { it.toString() }
                .forEach { add(RoutingDebtViolation.UndeclaredRouting(it)) }
            if (discovered.sites != pinned.sites) {
                add(RoutingDebtViolation.SiteInventoryDrift(discovered.sites, pinned.sites))
            }
            if (discovered.total != pinned.total) {
                add(RoutingDebtViolation.LedgerOutOfSync(discovered.total, pinned.total))
            }
        }
        return if (violations.isEmpty()) {
            RoutingDebtVerdict.WithinPinnedDebt(discovered.total)
        } else {
            RoutingDebtVerdict.DebtMustBeAddressed(violations)
        }
    }
}
