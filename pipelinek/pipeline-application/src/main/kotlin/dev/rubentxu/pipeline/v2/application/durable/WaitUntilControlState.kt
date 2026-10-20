package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilControlRowSnapshot

/**
 * Pure snapshot returned by [FileBasedWaitUntilControlJournal.readState].
 *
 * WU-G5R.5 — mirrors [RetryControlState] but for waitUntil's backoff-based
 * polling model (no attempt budget, no child row tracking).
 */
data class WaitUntilControlState(
    /**
     * Persisted control rows for this waitUntil logical invocation, sorted by
     * attempt ordinal ascending.
     */
    val controlRows: List<WaitUntilControlRowSnapshot>,
)
