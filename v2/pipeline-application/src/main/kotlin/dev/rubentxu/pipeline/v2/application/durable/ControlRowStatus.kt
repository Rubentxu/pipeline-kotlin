package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import java.nio.file.Path

/**
 * Reads a persisted [OperationStatus] token, or refuses it in the caller's own vocabulary.
 *
 * ## Why this exists rather than a `try`/`catch` at each call site
 *
 * Both durable control journals read a status back with the same shape, and both used to let
 * `Enum.valueOf` throw:
 *
 * ```kotlin
 * status = ao["status"]?.jsonPrimitive?.content
 *     ?.let { OperationStatus.valueOf(it) }
 *     ?: throw RetryControlJournalDivergenceException("status missing in $file")
 * ```
 *
 * The `?:` refuses a **missing** field in the journal's own type. The `?.let` refuses an
 * **unrecognised** one in `IllegalArgumentException` — a type the parser's contract never
 * mentions, so a caller that catches the declared divergence does not catch it. Wrapping it in a
 * `try`/`catch` at each site would have been the wrong shape twice over: the two journals would
 * each grow their own copy, and a third journal would silently reintroduce the raw throw.
 *
 * [operationStatusOrThrow] takes the refusal as a **function**, so each journal keeps ownership of
 * its own exception type and its own message wording. The knowledge of "an unknown token is not a
 * default" lives once; the knowledge of "a retry divergence says `Retry…`" stays with the retry
 * journal.
 *
 * ## Why refusing is the right reading, not defaulting
 *
 * A control row is durable state that decides whether an effect runs again. Defaulting an
 * unrecognised token to `PENDING` would re-run an attempt whose child effect may already have
 * happened; defaulting it to `SUCCEEDED` would swallow a failure. Neither default is safe, so
 * there is no default: the only total functions here are "recognised" and "refuse".
 *
 * ## What a refusal must carry
 *
 * The value **and** the file. The control file is named by a SHA-256 of the controlOpId, so the
 * path cannot be reconstructed from the token, and an operator with a retry and a waitUntil in
 * flight otherwise has no way to say which durable row is unreadable.
 *
 * @param file the control file being read, quoted in the refusal so the operator can find it.
 * @throws T the caller's divergence exception, carrying the offending token.
 */
internal inline fun <T : Throwable> operationStatusOrThrow(
    raw: String?,
    file: Path,
    divergence: (String) -> T,
): OperationStatus {
    val token = raw
        ?: throw divergence(
            "control row status missing in $file; every attempt must carry an OperationStatus",
        )
    return OperationStatus.entries.firstOrNull { it.name == token }
        ?: throw divergence(
            "control row status '$token' in $file is not a known OperationStatus " +
                "(${OperationStatus.entries.joinToString { it.name }}). Refusing it rather than " +
                "defaulting: this row decides whether the effect runs again, and no default is " +
                "safe — PENDING re-runs an attempt whose child effect may have happened, " +
                "SUCCEEDED swallows a failure.",
        )
}
