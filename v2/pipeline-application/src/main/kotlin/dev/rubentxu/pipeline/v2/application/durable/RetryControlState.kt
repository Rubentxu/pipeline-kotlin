package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.RetryChildRowSnapshot
import dev.rubentxu.pipeline.v2.domain.durable.RetryControlRowSnapshot

/**
 * Pure snapshot returned by [FileBasedRetryControlJournal.readState].
 *
 * ADR-0075 §4 — the retry reconciliation input is assembled by:
 *  1. Reading the persisted retry control rows (one per attempt).
 *  2. Grouping post-ADR-0075 child rows by attempt ordinal.
 *  3. Routing pre-ADR-0075 children (those without a control row) into a
 *     separate legacy bucket so the Reconciler can apply the legacy
 *     compat policy.
 *
 * ## Why pre-control children are bucketed separately
 * The Reconciler's legacy compat branch (ADR-0075 §8) classifies those
 * rows as either safe-to-reconstruct (single-attempt success) or
 * ambiguous (multi-attempt, single-attempt failure). Mixing them with
 * post-ADR rows would silently hide the divergence detector behind a
 * collapse to the legacy compat policy.
 */
data class RetryControlState(
    /**
     * Persisted control rows for this retry logical invocation, sorted by
     * attempt ordinal ascending.
     */
    val controlRows: List<RetryControlRowSnapshot>,

    /**
     * Post-ADR-0075 child rows grouped by attempt ordinal.
     * Empty for a fresh retry that has not yet run a body.
     */
    val childrenByAttempt: Map<Int, List<RetryChildRowSnapshot>>,

    /**
     * Pre-ADR-0075 legacy child rows — those persisted under the old
     * schema that did not write a control row. The Reconciler applies
     * the legacy compat policy (ADR-0075 §8) to this bucket.
     */
    val preControlChildren: List<RetryChildRowSnapshot>,
)
