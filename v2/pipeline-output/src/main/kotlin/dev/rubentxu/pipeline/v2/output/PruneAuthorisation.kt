package dev.rubentxu.pipeline.v2.output

/**
 * M3 — the result of [OutputRetentionPort.canPrune].
 *
 * Closed ADT: a consult either returns a `Granted` / `Consulted` answer
 * OR a `Refused` answer. Mirrors the M1 refusal-extension pattern: a
 * sealed interface per port, no `Either`/`Result` re-use.
 *
 * Distinct from the existing [OutputPruneReport] (which reports what
 * `prune(intent)` actually did); [PruneAuthorisation] is a
 * point-in-time OBSERVATION, not a report on what happened.
 */
sealed interface PruneAuthorisation {

    /**
     * The intent may proceed; no active pin covers the intent's
     * ranges. [OutputRetentionPort.prune] is now safe to call.
     */
    data object Granted : PruneAuthorisation

    /**
     * Active pins cover part of the intent's ranges; the consumer
     * may decide to release them and retry. The pins are named by
     * id so the consumer can act on them.
     */
    data class Consulted(
        val stream: OutputStreamId,
        val range: LongRange,
        val pinsAtConsult: List<OutputPin>,
    ) : PruneAuthorisation

    /**
     * The consult was refused; [reason] names the closed cause.
     */
    data class Refused(val reason: PruneRefusal) : PruneAuthorisation
}

/**
 * M3 — closed ADT of reasons [OutputRetentionPort.canPrune] or
 * [OutputRetentionPort.prune] refused.
 *
 * Closed on purpose: a consumer that handles every refusal case
 * cannot miss a new failure mode (compile error at every `when` site).
 */
sealed interface PruneRefusal {

    /** The run is not known to this PK instance. */
    data class UnknownStream(val stream: OutputStreamId) : PruneRefusal

    /** The substrate cannot be reached right now; mirrors `OutputRefusal.Unavailable`. */
    data object SubstrateUnavailable : PruneRefusal

    /**
     * The underlying storage failed during the consult; [cause] is
     * a short diagnostic.
     */
    data class StorageError(val cause: String) : PruneRefusal {
        init {
            require(cause.length <= PruneRefusal.REFUSAL_REASON_MAX_LEN) {
                "PruneRefusal.StorageError cause must be <= ${PruneRefusal.REFUSAL_REASON_MAX_LEN} chars"
            }
            require('\n' !in cause) { "PruneRefusal.StorageError cause must not contain newlines" }
        }
    }

    companion object {
        const val REFUSAL_REASON_MAX_LEN: Int = 256
    }
}