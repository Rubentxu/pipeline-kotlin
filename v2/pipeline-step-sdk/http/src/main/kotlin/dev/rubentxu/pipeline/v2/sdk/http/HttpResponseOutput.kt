package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput

/**
 * What the server actually said, as facts.
 *
 * Deliberately NOT a [TypedStepOutput] and deliberately NOT the Step's carrier.
 * The separation is what breaks a cycle that otherwise hides a defect: if this
 * type were the carrier, then `HttpAttempt.Answered` would have to hold the
 * carrier that holds the attempt, and the only way out of that recursion is for
 * the carrier to report a hardcoded outcome.
 *
 * A hardcoded `Success` is exactly the defect WU-092 measured in `core.input`:
 * a refused question closed the run green with exit 0. The previous shape of this
 * file carried `override val outcome get() = StepOutcome.Success` while its KDoc
 * claimed that implementing `TypedStepOutput` "is not decoration" — the
 * annotation was there and the honesty was not. A refused status would have
 * reported Success. Three types instead of one, because the honest shape needs
 * the room.
 */
data class HttpResponse(
    val url: String,
    val method: HttpMethod,
    val status: Int,
    val headers: List<HttpHeader> = emptyList(),
    val body: String = "",
    /** Digest of the COMPLETE body, even when [body] was truncated. */
    val bodySha256: String = "",
    val bodySizeBytes: Long = 0L,
    /** True when the response exceeded the declared cap and [body] is a prefix. */
    val bodyTruncated: Boolean = false,
) {
    /**
     * Whether [status] is one the author said they would accept.
     *
     * Pure, and it lives on the response rather than in the handler, so the same
     * question cannot be answered two ways by two callers.
     */
    fun isAccepted(accepted: List<StatusRange>): Boolean =
        accepted.any { it.contains(status) }
}

/** What a request did, expressed as one closed set. */
sealed interface HttpAttempt {
    val url: String
    val method: HttpMethod
    val durationMs: Long

    /** The network worked and the server answered with a status the author accepted. */
    data class Answered(
        override val url: String,
        override val method: HttpMethod,
        override val durationMs: Long,
        val response: HttpResponse,
    ) : HttpAttempt

    /**
     * The network worked and the server answered with a status the author did
     * NOT accept.
     *
     * Separate from the failure cases on purpose: that is not a `NETWORK`
     * failure, it is a consequence of a policy the author wrote, so it carries
     * [FailureKind.USER].
     */
    data class Refused(
        override val url: String,
        override val method: HttpMethod,
        override val durationMs: Long,
        val status: Int,
        val accepted: List<StatusRange>,
    ) : HttpAttempt

    /** No usable answer at all. */
    data class Failed(
        override val url: String,
        override val method: HttpMethod,
        override val durationMs: Long,
        val failure: HttpFailure,
    ) : HttpAttempt

    /** A named credential could not be used. The request was NOT sent. */
    data class Unauthorized(
        override val url: String,
        override val method: HttpMethod,
        override val durationMs: Long,
        val rejection: CredentialRejection,
    ) : HttpAttempt
}

/** Why a request never produced an acceptable answer: each case is a different fact. */
sealed interface HttpFailure {
    val diagnostic: String

    /** DNS, refused connection, reset, TLS handshake. The request may or may not have left. */
    data class Unreachable(val reason: String) : HttpFailure {
        override val diagnostic: String get() = reason
    }

    /** The bound elapsed before a response. Says how long, because "it was slow" is a fact. */
    data class Expired(val afterMs: Long) : HttpFailure {
        override val diagnostic: String get() = "the ${afterMs}ms bound elapsed with no answer"
    }

    /**
     * The DECLARATION was unusable, so nothing was sent.
     *
     * A `durationMs` of zero accompanies this case, and that is the point: no
     * socket was opened, so no time was spent and no request reached the world.
     * A reader of the journal can tell "we decided not to send" from "we sent
     * and it failed", which are very different facts about a pipeline.
     */
    data class Rejected(val rejection: HttpRejection) : HttpFailure {
        override val diagnostic: String get() = rejection.diagnostic
    }

    /**
     * The runtime refused egress.
     *
     * Normally unreachable: the runtime withholds the egress capability rather
     * than handing out a refusal, so admission rejects the Step before the
     * handler runs. This case exists for a runtime that chooses to be explicit,
     * and it is honoured rather than assumed away.
     */
    data object EgressDenied : HttpFailure {
        override val diagnostic: String get() =
            "this run may not reach the network; start it with --allow-network to permit egress"
    }
}

/** Why a named credential could not be used. Never "it doesn't exist anymore" (see below). */
sealed interface CredentialRejection {
    val diagnostic: String

    data class NotFound(val id: String) : CredentialRejection {
        override val diagnostic: String get() = "no credential named '$id'"
    }

    /**
     * The credential exists but its kind is out of scope.
     *
     * Jenkins reports this as *"Authentication 'X' doesn't exist anymore"*, which is a
     * lie: it blames a missing credential for a present one of the wrong shape, and the
     * operator goes looking for a credential they can already see. This says what
     * arrived and what would have worked.
     */
    data class KindUnsupported(
        val id: String,
        val found: String,
        val supported: List<String>,
    ) : CredentialRejection {
        override val diagnostic: String get() =
            "credential '$id' is a $found; http.request supports " +
                supported.joinToString(" and ")
    }
}

/**
 * The carrier this Step produces.
 *
 * **Implements [TypedStepOutput] and that is not decoration.**
 * `RegistryExecutionBoundary` projects `(produced as? TypedStepOutput)?.outcome
 * ?: Success`, so this type MUST carry the real outcome or a refusal closes the
 * run green. The outcome is DERIVED from the attempt, never stored separately,
 * so the carrier cannot be constructed in a state where the two disagree.
 */
data class HttpResponseOutput(val attempt: HttpAttempt) : TypedStepOutput {

    override val outcome: StepOutcome get() = attempt.toStepOutcome()

    val url: String get() = attempt.url
    val method: HttpMethod get() = attempt.method

    /** The response, or `null` when there was none. Never a fabricated default. */
    val response: HttpResponse? get() = (attempt as? HttpAttempt.Answered)?.response

    /** The status actually received, or `null` when nothing answered. */
    val status: Int? get() = response?.status
}

/**
 * The canonical outcome of the Step, derived from what happened.
 *
 * Pure interpretation of a closed ADT, so no caller can disagree about what a
 * refused request or an unreachable host means.
 */
fun HttpAttempt.toStepOutcome(): StepOutcome = when (this) {
    is HttpAttempt.Answered -> StepOutcome.Success
    is HttpAttempt.Refused -> StepOutcome.Failure(
        PipelineFailure(
            kind = FailureKind.USER,
            message = "http.request: $method $url answered $status, which is not among " +
                "the accepted ${accepted.joinToString(", ")}",
        ),
    )
    is HttpAttempt.Failed -> when (failure) {
        is HttpFailure.Expired -> StepOutcome.Failure(
            PipelineFailure(
                kind = FailureKind.TIMEOUT,
                message = "http.request: $method $url gave up after ${failure.afterMs}ms",
            ),
        )
        is HttpFailure.Unreachable -> StepOutcome.Failure(
            PipelineFailure(
                kind = FailureKind.NETWORK,
                message = "http.request: $method $url could not be reached (${failure.diagnostic})",
            ),
        )
        // A declaration error and an egress denial are BOTH the author's problem
        // as far as the run is concerned: nothing about the world was wrong, and
        // neither would a retry fix either one. Reporting them as USER rather
        // than inventing a fifth kind keeps the failure algebra closed.
        is HttpFailure.Rejected -> StepOutcome.Failure(
            PipelineFailure(
                kind = FailureKind.USER,
                message = "http.request: $method $url was not sent — ${failure.diagnostic}",
            ),
        )
        is HttpFailure.EgressDenied -> StepOutcome.Failure(
            PipelineFailure(
                kind = FailureKind.USER,
                message = "http.request: $method $url was not sent — ${failure.diagnostic}",
            ),
        )
    }
    is HttpAttempt.Unauthorized -> StepOutcome.Failure(
        PipelineFailure(
            kind = FailureKind.USER,
            message = "http.request: $method $url — ${rejection.diagnostic}",
        ),
    )
}
