package dev.rubentxu.pipeline.v2.runtime.input

/**
 * A human answer. Closed: there are exactly two possible answers, and the pipeline
 * author cannot express a third one. The free text a case carries is the answer's
 * own text (Jenkins returns `input`'s value to the caller), not the question.
 */
sealed interface InputDecision {

    /** The question was answered affirmatively: the run continues. */
    data class Proceed(val submitter: String?, val message: String?) : InputDecision

    /** The question was answered negatively: the run does not continue. */
    data class Abort(val submitter: String?, val message: String?) : InputDecision
}

/**
 * Why there is no answer. Closed, and every case carries the evidence needed to
 * act on it: how long we waited, whether we were cancelled, or what made the
 * question unaskable.
 *
 * A malformed response and a lost race are deliberately NOT cases here. A
 * malformed file is ignored and the wait continues; a lost race is a filesystem
 * fact on the answering side. Neither can be an outcome of the Step.
 */
sealed interface InputDenialReason {

    /** The wait reached its bound and nobody answered. */
    data class TimedOut(val waitedMillis: Long) : InputDenialReason

    /** The caller was cancelled while it waited for the answer. */
    data object Cancelled : InputDenialReason

    /** The question could not be asked at all, and [diagnostic] says why. */
    data class Unanswerable(val diagnostic: String) : InputDenialReason
}
