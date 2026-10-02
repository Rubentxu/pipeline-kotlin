package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.http.HttpHeader
import dev.rubentxu.pipeline.v2.domain.step.http.HttpMethod
import dev.rubentxu.pipeline.v2.domain.step.http.StatusRange

/**
 * The Step's result.
 *
 * **Implements [TypedStepOutput] and that is not decoration.** `RegistryExecutionBoundary`
 * projects `(produced as? TypedStepOutput)?.outcome ?: Success`, so a plain data class
 * here would report Success for every failure this Step can produce. WU-092 measured the
 * same defect in `core.input` — a refused question closed the run green with exit 0 — and
 * this carrier is the fix applied at the type rather than at the call site.
 */
data class CoreHttpResponse(
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
) : TypedStepOutput {

    override val outcome: StepOutcome get() = StepOutcome.Success

    /**
     * Whether [status] is one the author said they would accept.
     *
     * Pure, and it lives on the response rather than in the handler, so the same
     * question cannot be answered two ways by two callers.
     */
    fun isAccepted(accepted: List<StatusRange>): Boolean =
        accepted.any { it.contains(status) }
}

/** The outcome of a request that never produced a response: each case is a different fact. */
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
            "credential '$id' is a $found; core.httpRequest supports " +
                supported.joinToString(" and ")
    }
}

/**
 * What a request did, expressed as one closed set.
 *
 * `Refused` is separate from the two failure cases on purpose: the network worked and
 * the server answered with a status the author did not accept. That is not a
 * `NETWORK` failure, it is a consequence of a policy the author wrote, so it carries
 * [FailureKind.USER].
 */
sealed interface HttpAttempt {
    val url: String
    val method: HttpMethod
    val durationMs: Long

    data class Answered(
        override val url: String,
        override val method: HttpMethod,
        override val durationMs: Long,
        val response: CoreHttpResponse,
    ) : HttpAttempt

    data class Refused(
        override val url: String,
        override val method: HttpMethod,
        override val durationMs: Long,
        val status: Int,
        val accepted: List<StatusRange>,
    ) : HttpAttempt

    data class Failed(
        override val url: String,
        override val method: HttpMethod,
        override val durationMs: Long,
        val failure: HttpFailure,
    ) : HttpAttempt

    data class Unauthorized(
        override val url: String,
        override val method: HttpMethod,
        override val durationMs: Long,
        val rejection: CredentialRejection,
    ) : HttpAttempt
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
            message = "core.httpRequest: $method $url answered $status, which is not among " +
                "the accepted ${accepted.joinToString(", ")}",
        ),
    )
    is HttpAttempt.Failed -> when (failure) {
        is HttpFailure.Expired -> StepOutcome.Failure(
            PipelineFailure(
                kind = FailureKind.TIMEOUT,
                message = "core.httpRequest: $method $url gave up after ${failure.afterMs}ms",
            ),
        )
        is HttpFailure.Unreachable -> StepOutcome.Failure(
            PipelineFailure(
                kind = FailureKind.NETWORK,
                message = "core.httpRequest: $method $url could not be reached (${failure.diagnostic})",
            ),
        )
    }
    is HttpAttempt.Unauthorized -> StepOutcome.Failure(
        PipelineFailure(
            kind = FailureKind.USER,
            message = "core.httpRequest: $method $url — ${rejection.diagnostic}",
        ),
    )
}

/** The carrier this Step produces for a request that never answered. */
fun HttpAttempt.toCoreHttpResponse(): CoreHttpResponse = when (this) {
    is HttpAttempt.Answered -> response
    // A request that has no response still produces a carrier: the durable record has
    // to say WHAT was attempted, or a reader of the journal cannot tell a refused
    // request from a step that never ran.
    is HttpAttempt.Refused -> CoreHttpResponse(
        url = url,
        method = method,
        status = status,
        body = "",
        bodyTruncated = false,
    )
    is HttpAttempt.Failed -> CoreHttpResponse(url = url, method = method, status = 0)
    is HttpAttempt.Unauthorized -> CoreHttpResponse(url = url, method = method, status = 0)
}
