package dev.rubentxu.pipeline.v2.application

/**
 * Why a run did not receive a credential, as a fixed word.
 *
 * ## Why a word and not a message
 *
 * Because a refusal travels to places that print it: the answer of the broker,
 * the error of the step that asked, the result the run leaves behind. A free
 * text there could carry a fragment of whatever produced it — a line the broker
 * could not read, an exception of the store — and a fragment of a line that
 * held a secret is a secret on disk. A fixed word carries nothing but itself,
 * so it may travel anywhere.
 *
 * ## Which are said by the broker and which are found by the run
 *
 * The first eight are decisions of the broker and cross the socket. The last
 * three are what the run itself finds when there is no answer to read: nobody
 * to talk to, nobody who answers in time, or an answer that is not the
 * contract. They are words of the same vocabulary so that one sentence
 * explains every way a credential may fail to arrive.
 */
internal enum class CredentialBrokerRefusal(
    /** The word the wire and every message spell the refusal with. */
    val word: String,
    /** What the word means, for the sentence that tells a person why the step did not run. */
    val meaning: String,
) {

    /** The token of the request is not the token of this run. */
    SESSION_NOT_RECOGNIZED("session-not-recognized", "the broker does not recognize the session of this run"),

    /**
     * The client that started the run was not granted the credential.
     *
     * The answer is the same whether the credential exists or not: a client
     * that was not granted a name cannot learn, by asking, whether it exists.
     */
    CREDENTIAL_NOT_GRANTED(
        "credential-not-granted",
        "the credential is not granted to the client that started this run",
    ),

    /** The credential is granted, but not as the kind that was requested, or the broker carries no such kind. */
    CREDENTIAL_KIND_NOT_BROKERED(
        "credential-kind-not-brokered",
        "the broker does not hand out the credential as the kind requested",
    ),

    /** The credential is granted and the store does not hold it. */
    CREDENTIAL_NOT_AVAILABLE("credential-not-available", "the credential is granted but the store does not hold it"),

    /** The credential is bigger than the broker hands out. */
    CREDENTIAL_TOO_LARGE("credential-too-large", "the credential is larger than the broker hands out"),

    /**
     * The credential is granted and its holder, a connected controller, did not hand it out:
     * it denied the request, it was gone, or it did not answer in time.
     *
     * The word does not say which of the three, and nothing falls back to another store: the
     * reason is for the service, which knows it, and not for the run.
     */
    CREDENTIAL_PROVIDER_DENIED(
        "credential-provider-denied",
        "the client that holds the credential did not hand it out",
    ),

    /** The line was not a request of the contract. */
    REQUEST_NOT_READABLE("request-not-readable", "the request was not a request of the broker's contract"),

    /** Something failed in the broker while it answered. */
    BROKER_FAILED("broker-failed", "the broker failed while it answered"),

    /** The run could not connect to the broker of its service. */
    BROKER_UNREACHABLE("broker-unreachable", "the run could not reach the broker of its service"),

    /** The broker did not answer within the time the run waits. */
    BROKER_TIMED_OUT("broker-timed-out", "the broker did not answer within the time the run waits"),

    /** The answer of the broker was not an answer of the contract. */
    BROKER_ANSWER_NOT_READABLE(
        "broker-answer-not-readable",
        "the answer of the broker was not an answer of its contract",
    ),
    ;

    internal companion object {

        /** The refusal spelled [word], or null when the vocabulary has none such. */
        fun ofWord(word: String): CredentialBrokerRefusal? = entries.firstOrNull { it.word == word }
    }
}
