package dev.rubentxu.pipeline.v2.runtime.control

/**
 * M2 — closed ADT of cancel reasons a caller may pass.
 *
 * The reason is recorded in the run's JOURNAL ROW — the terminal operation that marks the
 * cancellation — and NOT in the payload of the terminal `RunFinished` event. The event says
 * that the run was aborted; the journal says why, and the journal is where a reader of the
 * run's history goes to find it.
 *
 * New cases are compile errors at every `when` site that exhausts this ADT.
 */
sealed interface CancelReason {

    /** An operator or a consumer explicitly asked to cancel. */
    data object UserRequested : CancelReason

    /** A consumer-supplied free-text reason (bounded length). */
    data class Annotated(val text: String) : CancelReason {
        init {
            require(text.length <= 256) {
                "CancelReason.Annotated text must be <= 256 chars, got ${text.length}"
            }
            require('\n' !in text) {
                "CancelReason.Annotated text must not contain newlines"
            }
        }
    }

    companion object {
        /** The default reason when the caller does not give one. */
        val Default: CancelReason = UserRequested
    }
}
