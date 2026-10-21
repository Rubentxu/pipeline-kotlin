package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.RetryChildRowSnapshot
import dev.rubentxu.pipeline.v2.domain.durable.RetryControlRowSnapshot

/**
 * Pure snapshot returned by [FileBasedRetryControlJournal.readState].
 * The retry reconciliation input is assembled by:
 *  1. Reading the persisted retry control rows (one per attempt).
 * 2. Grouping child rows by attempt ordinal
 *  3. Routing children (those without a control row) into a
 *     separate legacy bucket so the Reconciler can apply the legacy
 *     compat policy.
 * ## Why pre-control children are bucketed separately
 * The Reconciler's legacy compat branch classifies those
 * rows as either safe-to-reconstruct (single-attempt success) or
 * ambiguous (multi-attempt, single-attempt failure). Mixing them with
 * current-format rows would silently hide the divergence detector behind a
 * collapse to the legacy compat policy.
*/
data class RetryControlState(
    /**
     * Persisted control rows for this retry logical invocation, sorted by
     * attempt ordinal ascending.
*/
    val controlRows: List<RetryControlRowSnapshot>,

    /**
     * Child rows grouped by attempt ordinal
     * Empty for a fresh retry that has not yet run a body.
*/
    val childrenByAttempt: Map<Int, List<RetryChildRowSnapshot>>,

    /**
     * Those persisted under the old
     * schema that did not write a control row. The Reconciler applies
     * The legacy compat policy to this bucket
*/
    val preControlChildren: List<RetryChildRowSnapshot>,
)


