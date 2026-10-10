package dev.rubentxu.pipeline.v2.runtime.control

/**
 * M2 — closed ADT of cancel reasons a caller may pass.
 *
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

    companion object {
        /** The default `reason` carried in the terminal `RunFinished` event. */
        val Default: CancelReason = UserRequested
    }
}
