package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * RP6-C / WU-093 G3-A4.2 — the ONE network seam of the runtime.
 *
 * `JdkHttpOperations` is the only class in the repository that opens a socket, exactly
 * as `FileLockCoordinator` is the only one that takes a POSIX lock and
 * `WorkspaceOperationsAdapter` the only one that emits `FileWritten`. A Step that
 * needed a socket for a second reason would be a second seam, and the second seam is
 * where the next untyped `URL(...)` call goes to live.
 */
val HTTP_OPERATIONS_CAPABILITY: StepCapability = StepCapability("httpOperations")

/**
 * Whether this execution may reach the network at all.
 *
 * A per-RUN decision, not a per-step or per-scope one, and it defaults to [Denied] in
 * the data class itself — so a constructor that forgets to mention it is fail-closed by
 * construction rather than by a condition somebody has to remember to write.
 *
 * It rides on `ShOptions` because that type already carries a per-execution environment
 * policy (`sandbox`), is set from the CLI, has no DSL surface, and survives the stage
 * re-projection. Anything that cannot be reconstructed downstream has to cross the
 * transport: `workspaceOwnership` says so in its own KDoc, for the same reason.
 */
sealed interface NetworkPolicy {
    data object Denied : NetworkPolicy
    data object Allowed : NetworkPolicy
}

/**
 * What to send, and how long the send may take.
 *
 * `authorization` is ALREADY RESOLVED. The handler never holds a `CredentialsId` and
 * never sees a secret: it asks for one by name and the adapter decides what header (if
 * any) that becomes. This is the same line WU-092 drew for `submitter` — a name is
 * attribution, and the decision to emit it is the adapter's.
 */
data class HttpSendRequest(
    val url: String,
    val method: HttpMethod,
    val headers: List<HttpHeader>,
    val body: String?,
    /** `null` means no bound at all, which only `timeoutSeconds = 0` produces. */
    val timeoutMs: Long?,
    val authorization: HttpAuthorization?,
    val maxBodyBytes: Long,
)

/** A resolved credential header, ready to write. */
sealed interface HttpAuthorization {
    data object None : HttpAuthorization
    data class Basic(val base64UserPassword: String) : HttpAuthorization
}

/**
 * Everything a send can produce, as a closed set.
 *
 * A dropped connection is not an exception crossing the Step: it is a fact about the
 * world, and the Step has to classify it into a `FailureKind`. Returning `null` or
 * throwing would push that decision out of the type.
 */
sealed interface HttpSendOutcome {
    data class Answered(
        val status: Int,
        val headers: List<HttpHeader>,
        val body: String,
        val bodySha256: String,
        val bodySizeBytes: Long,
        val bodyTruncated: Boolean,
    ) : HttpSendOutcome

    data class Unreachable(val reason: String) : HttpSendOutcome
    data class Expired(val afterMs: Long) : HttpSendOutcome
}

/**
 * The transport port. Knows about HTTP and about nothing else: no journal, no events,
 * no credentials store, no clock of its own.
 *
 * It measures its own duration because it is the only party that can: the same split
 * `ShExecution` uses for `durationMs`. A handler that timed its own I/O would be
 * measuring its own overhead, and a port that took a `Clock` would be a clock the
 * caller has to keep in step.
 */
interface HttpOperations {
    suspend fun send(request: HttpSendRequest): HttpTransportResult
}

/** What the transport reports back: the outcome plus how long it actually took. */
data class HttpTransportResult(
    val outcome: HttpSendOutcome,
    val durationMs: Long,
)
