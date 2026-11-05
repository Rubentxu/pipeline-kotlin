package dev.rubentxu.pipeline.v2.runtime.control

/**
 * Closed ADT of cancel reasons a caller may pass.
 * The reason is carried in the terminal `RunFinished` event payload and surfaces
 * in the journal row. New cases are compile errors at every `when` site that
 * exhausts this ADT.
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

    /**
     * The reason a caller may leave out.
     *
     * It holds the one reason the product substitutes when a caller cancels
     * without stating a cause, so the terminal event of every cancelled run
     * names a reason instead of carrying an empty field, and every consumer
     * reads the same word.
     */
    companion object {
        /** The default `reason` carried in the terminal `RunFinished` event. */
        val Default: CancelReason = UserRequested
    }
}
