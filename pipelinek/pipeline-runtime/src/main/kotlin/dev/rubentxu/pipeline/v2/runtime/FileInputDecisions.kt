package dev.rubentxu.pipeline.v2.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/**
 * [InputDecisions] over the control directory
 * ```text
 * <root>/<name of the operation>/request.json    written by this adapter
 * <root>/<name of the operation>/response.json   written by whoever answers
 * ```
 * The adapter is bound to ONE [InputOperation]: the durable identity of the dispatch, not the
 * run and not the step index alone. Two `input` steps in one run, in two stages or in two
 * branches of a `parallel`, are two questions and must not share a file. The name of the
 * directory is [InputQuestionLayout.directoryName], which never maps two operations to one.
 * ## The law this adapter exists to enforce
 * **A file that is not yet a complete, valid answer is not an answer.**
 * A human answering through a filesystem is a writer, and writers are not atomic
 * by default: a two-kilobyte answer truncated at the halfway point looks exactly
 * like a refusal to anyone who is impatient. So [awaitDecision] decodes what it
 * finds and, on anything incomplete or unrecognised, takes another turn instead of
 * ending the wait. The only things that end it are a valid answer, the bound
 * expiring, or cancellation.
 * ## Cancellation is control flow, not an outcome
 * Coroutine cancellation PROPAGATES as [CancellationException] instead of being
 * flattened into a typed denial, and this adapter is the second place in the
 * codebase to make that choice (the first is `FileLockCoordinator`).
 * The reason is not stylistic. A cancelled coroutine cannot deliver a value to the
 * thing that cancelled it: the promise it would have completed is already
 * cancelled, so the "typed" result is discarded and the caller carries on with
 * nothing. Flattening it only hides the signal, and a caller that is being
 * cancelled must be allowed to stop.
 * [InputDenialReason.Cancelled] therefore stays for the interrupt path, where the
 * thread is interrupted while the coroutine is still live — the same producer
 * `FileLockCoordinator` has.
 * ## What this class does NOT do
 * It does not authorise the answer, and it does not decide what a valid decision
 * is: that vocabulary belongs to [InputAnswerCodec], which is the authority for
 * the answer wire and lives next to the request wire on purpose, because the two
 * travel in opposite directions and are owned by different parties.
*/
class FileInputDecisions(
    private val root: Path,
    private val operation: InputOperation,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : InputDecisions {

    override suspend fun awaitDecision(request: InputRequest, waitMillis: Long?): InputResolution {
        val dir = root.resolve(InputQuestionLayout.directoryName(operation.opId))
        val requestFile = dir.resolve(InputQuestionLayout.REQUEST_FILE)
        val responseFile = dir.resolve(InputQuestionLayout.RESPONSE_FILE)
        val startedAt = nowMillis()
        val deadline = waitMillis?.let { startedAt + it }
        val resolution: InputResolution = try {
            publish(dir, request, requestFile, startedAt, deadline)

            var answer: InputResolution? = null
            while (answer == null) {
                // Read first: an answer that landed while we were publishing must
                // not be missed by a deadline that has not fired yet.
                val found = readAnswer(responseFile)
                answer = when {
                    found != null -> InputResolution.Answered(found)
                    deadline != null && nowMillis() >= deadline -> InputResolution.Denied(
                        InputDenialReason.TimedOut(nowMillis() - startedAt),
                    )
                    else -> {
                        delay(pollIntervalMs)
                        null
                    }
                }
            }
            answer
        } catch (e: CancellationException) {
            // Propagated, deliberately. See the class doc: a cancelled coroutine
            // cannot deliver a value to its canceller, so converting it would only
            // hide the signal.
            throw e
        } catch (e: InterruptedException) {
            // The thread is interrupted while this coroutine is still live: a stop
            // that did NOT cancel us, so it is a fact about the wait rather than
            // about the coroutine. Same producer as FileLockCoordinator.
            Thread.currentThread().interrupt()
            InputResolution.Denied(InputDenialReason.Cancelled)
        }
        return resolution
    }

    /**
     * Publishes the question. Idempotent by construction: the same op re-publishes
     * the same content, which is what a resumed run does when it re-asks for a
     * decision it has not obtained yet.
     *
     * The file is written beside its destination and moved over it in one step, so a
     * reader that lists the questions while a resumed run re-publishes one sees the old
     * document or the new one and never half of either.
     */
    private fun publish(
        dir: Path,
        request: InputRequest,
        requestFile: Path,
        publishedAtMs: Long,
        expiresAtMs: Long?,
    ) {
        var staged: Path? = null
        try {
            Files.createDirectories(dir)
            val payload = InputQuestionCodec.encode(
                PublishedInputQuestion(
                    runId = operation.runId,
                    opId = operation.opId,
                    message = request.message,
                    ok = request.ok,
                    submitter = request.submitter,
                    id = request.id,
                    publishedAtMs = publishedAtMs,
                    expiresAtMs = expiresAtMs,
                ),
            )
            staged = Files.createTempFile(dir, "request-", ".tmp")
            Files.writeString(staged, payload)
            Files.move(staged, requestFile, StandardCopyOption.ATOMIC_MOVE)
            staged = null
        } catch (e: Exception) {
            // A question nobody can read is not a question. This is the one place
            // the port reports "I could not even ask", and it is a denial, not an
            // exception, so the Step can fail with a reason an operator can read.
            throw UnanswerableException(
                "core.input could not publish its request at $requestFile: ${e.message ?: e::class.java.simpleName}",
            )
        } finally {
            staged?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /**
     * Reads an answer, or `null` when the file is absent, unreadable, incomplete or
     * not a decision this Step recognises.
*/
    private fun readAnswer(responseFile: Path): InputDecision? {
        val raw = try {
            if (!Files.exists(responseFile)) return null
            Files.readString(responseFile)
        } catch (_: Exception) {
            return null
        }
        return InputAnswerCodec.decode(raw)
    }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 50L
    }
}

/** The question could not be published; carries the diagnostic a reader needs. */
class UnanswerableException(message: String) : RuntimeException(message)
