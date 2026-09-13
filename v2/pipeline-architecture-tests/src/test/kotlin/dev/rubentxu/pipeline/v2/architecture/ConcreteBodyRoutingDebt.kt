package dev.rubentxu.pipeline.v2.architecture

/**
 * LFC-2 / B10 W1a..W1d — concrete block-Step routing debt in the canonical durable
 * coordinator.
 *
 * ADR-0073 requires block Steps to re-enter the engine through the body machinery, and
 * AGENTS.md forbids a central concrete-Step switch. W1a measured what the coordinator did
 * instead and pinned it as an enumerated ledger:
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
 * ## W1d: the ledger is EMPTY, and that is a claim with content
 *
 * W1b declared the execution shape, W1c resolved it from the descriptor instead of from a
 * StepKey, and W1d removed the last two things the ledger counted:
 *
 *  - the credential-lifecycle bypass, which iterated and dispatched children on its own.
 *    The lease is now a body PREAMBLE: it acquires, re-enters the shared body path, then
 *    releases, and folds the two typed outcomes through a pure function. See
 *    [BodyChildLoopInventory], which is the law that keeps it that way.
 *  - the two concrete durable identities (`core.retry`, `core.parallel`), which were never
 *    routing branches. They are now typed, pinned, and separately guarded as
 *    [dev.rubentxu.pipeline.v2.domain.step.BodyAggregateIdentity] — see
 *    `Lfc2DurableAggregateIdentityFitnessTest`. This is a RECLASSIFICATION, not a
 *    syntactic disappearance: the identities still exist, still name durable rows, and are
 *    still impossible to add by accident.
 *
 * An empty ledger is the strongest form of this guard, because every item is now
 * undeclared: the first `"core.*"` literal, the first `when (stepId)`, the first
 * `dispatch*Block` and the first hard-coded body id set all fail immediately.
 *
 * ## Coverage boundary (what this scan does NOT see)
 *
 * The scan recognises: any `"core.<name>"` string literal, any `when (...stepId...)` switch,
 * any `dispatch*Block` identifier, a hard-coded body id set, and the body-child loop
 * inventory. It does NOT see routing that builds a Step name at runtime (concatenation,
 * lookup, or a value flowing in from a caller) and it does NOT see routing on an enum
 * ordinal or a numeric id. A closed scanner cannot decide those; if such a shape appears,
 * this guard must be extended rather than assumed to cover it.
 *
 * The scan is deliberately conservative in the other direction: a site is detected when its
 * identifier appears anywhere in the file, including a call site or a comment, so it can
 * over-report rather than under-report. Over-reporting fails the guard, which is the safe
 * way to be wrong. Prose is inside the scanned surface by design: do not spell a forbidden
 * routing form even in a comment that says it is forbidden (W1c and W1d both had to
 * rephrase one).
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

    /**
     * Retired by W1d: a second, credential-specific path that acquired a lease AND iterated
     * and dispatched body children itself. The case stays so that RE-INTRODUCING a parallel
     * body path is detected: it is reported whenever the single-shared-path invariant in
     * [BodyChildLoopInventory] is violated.
     */
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

/**
 * The body-child invocation loop, counted (B10 / W1d).
 *
 * Not debt: a structural invariant. Every body-bearing Step — a plain scope, a retry
 * attempt, a credential lease — must dispatch its children through ONE shared function. A
 * second definition of the loop (the shape W1a counted as `dispatch*Block` debt) means a
 * block Step has acquired an execution path the shared engine does not own, which is exactly
 * what ADR-0073 forbids. Credential acquisitions are counted for the same reason: exactly one
 * acquisition site means the lease is a preamble of that shared path.
 */
data class BodyChildLoopInventory(
    val loopDefinitions: Int,
    val credentialAcquisitions: Int,
) {
    val isSingleSharedPath: Boolean
        get() = loopDefinitions == EXPECTED.loopDefinitions &&
            credentialAcquisitions == EXPECTED.credentialAcquisitions

    companion object {
        /**
         * One loop, one acquisition: the shared body path with a credential preamble. A
         * change here is a structural change to the engine, not a refactor.
         */
        val EXPECTED = BodyChildLoopInventory(loopDefinitions = 1, credentialAcquisitions = 1)
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

    /**
     * The shared body path was duplicated: [discovered] loops or credential acquisitions
     * were found where [expected] was required. Reported separately from routing debt,
     * because a duplicated body path can contain no literal at all and would otherwise be
     * invisible to a scanner that only looks for names.
     */
    data class BodyPathNotShared(
        val discovered: BodyChildLoopInventory,
        val expected: BodyChildLoopInventory,
    ) : RoutingDebtViolation
}

sealed interface RoutingDebtVerdict {
    data class WithinPinnedDebt(val debtTotal: Int) : RoutingDebtVerdict
    data class DebtMustBeAddressed(val violations: List<RoutingDebtViolation>) : RoutingDebtVerdict
}

/**
 * The pinned ledger: EMPTY since W1d.
 *
 * The history is the point of the empty set. W1a measured 18 items, W1c retired 14, and W1d
 * retired the last 4 — 2 by burning the credential bypass and 2 by reclassifying the durable
 * aggregate identities as what they always were. Nothing was deleted to reach zero: the
 * identities moved to [dev.rubentxu.pipeline.v2.domain.step.BodyAggregateIdentity], where
 * they are typed and pinned, and the bypass moved into the shared body path, where
 * [BodyChildLoopInventory] counts it.
 */
object PinnedConcreteBodyRoutingDebt {

    /**
     * High-water mark. This number is never raised: it records how much debt existed when the
     * guard was introduced, and the burn-down law is that it may only fall. It is NOT lowered
     * to follow the pin either — it is the provenance of the guard, and collapsing it into
     * the living state would destroy the only record of the original debt.
     */
    const val HISTORICAL_CEILING = 18

    val value = ConcreteBodyRoutingDebt(
        // W1d: empty. The two durable identities are typed and guarded elsewhere
        // (BodyAggregateIdentity + Lfc2DurableAggregateIdentityFitnessTest), not counted
        // here as routing.
        concreteStepNames = emptySet(),
        // W1c: empty. Body eligibility is derived from declared ownership.
        bodyStepIds = emptySet(),
        // Named explicitly, not BodyRoutingSite.entries.toSet(): "all entries" would silently
        // absorb a fourth site the day someone adds one, which is exactly the slack this
        // ledger exists to prevent. Empty is a MEASUREMENT, not a relaxation.
        sites = emptySet(),
        blockBypasses = emptySet(),
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

    fun scan(source: String): ConcreteBodyRoutingDebt {
        val bodyStepIds = bodyStepIdsBlock.find(source)
            ?.groupValues?.get(1)
            ?.let { block -> concreteStepLiteral.findAll(block).map { unquote(it.value) }.toSet() }
            ?: emptySet()

        val loops = BodyChildLoopScanner.scan(source)
        val sites = buildSet {
            if (bodyStepIdsBlock.containsMatchIn(source)) add(BodyRoutingSite.CANONICAL_BODY_STEP_IDS)
            if (projectShellScope.containsMatchIn(source)) add(BodyRoutingSite.PROJECT_SHELL_SCOPE)
            if (!loops.isSingleSharedPath) add(BodyRoutingSite.DISPATCH_WITH_CREDENTIALS_BLOCK)
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

/**
 * Pure scan of one coordinator source into the body-child loop inventory.
 *
 * `block.body.withIndex()` is the shape of "this function iterates the children itself";
 * `credentialScopePort.acquire(` is the shape of "this function enters a credential lease".
 * Exactly one of each is the shared path.
 */
object BodyChildLoopScanner {

    private val bodyChildLoop = Regex("\\.body\\.withIndex\\(\\)")
    private val credentialAcquisition = Regex("credentialScopePort\\.acquire\\(")

    fun scan(source: String): BodyChildLoopInventory = BodyChildLoopInventory(
        loopDefinitions = bodyChildLoop.findAll(source).count(),
        credentialAcquisitions = credentialAcquisition.findAll(source).count(),
    )
}

/** Pure verdict. Total: every discovered item either is pinned or is a violation. */
object ConcreteBodyRoutingVerdict {

    /**
     * [discoveredLoops] is REQUIRED and has no default: a caller cannot reach a verdict
     * without scanning the body-child loop inventory, so the duplication law cannot be
     * skipped by omission.
     */
    fun decide(
        discovered: ConcreteBodyRoutingDebt,
        discoveredLoops: BodyChildLoopInventory,
        pinned: ConcreteBodyRoutingDebt = PinnedConcreteBodyRoutingDebt.value,
        ceiling: Int = PinnedConcreteBodyRoutingDebt.HISTORICAL_CEILING,
        expectedLoops: BodyChildLoopInventory = BodyChildLoopInventory.EXPECTED,
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
            if (discoveredLoops != expectedLoops) {
                add(RoutingDebtViolation.BodyPathNotShared(discoveredLoops, expectedLoops))
            }
        }
        return if (violations.isEmpty()) {
            RoutingDebtVerdict.WithinPinnedDebt(discovered.total)
        } else {
            RoutingDebtVerdict.DebtMustBeAddressed(violations)
        }
    }
}
