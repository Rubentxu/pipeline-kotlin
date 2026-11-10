package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.InputAnswerCodec
import dev.rubentxu.pipeline.v2.runtime.InputDecision
import dev.rubentxu.pipeline.v2.runtime.InputQuestionCodec
import dev.rubentxu.pipeline.v2.runtime.InputQuestionLayout
import dev.rubentxu.pipeline.v2.runtime.PublishedInputQuestion
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.service.ServiceInputDecision
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The questions of a run that are waiting, as the service lists them.
 */
internal sealed interface ServiceInputListOutcome {

    /** The questions that wait for an answer now, oldest first. Empty when the run asks nothing. */
    data class Listed(val questions: List<PublishedInputQuestion>) : ServiceInputListOutcome

    /** The questions could not be listed, and this says why. */
    data class Refused(val reason: String, val detail: String) : ServiceInputListOutcome
}

/**
 * The answer of a request to answer one question.
 *
 * [Answered] is a question THIS call answered. [AlreadyAnswered] is a question the very same
 * authorization had already answered with the very same decision: it is an answer and not a
 * refusal, because a client that retries an answer whose reply was lost must read a success.
 * [Refused] is an answer that was not recorded, and it carries the NAME of the fact that
 * stopped it.
 */
internal sealed interface ServiceInputAnswerOutcome {

    /** This call recorded the answer. */
    data class Answered(val decision: ServiceInputDecision) : ServiceInputAnswerOutcome

    /** The same authorization had already recorded this very answer. */
    data class AlreadyAnswered(val decision: ServiceInputDecision) : ServiceInputAnswerOutcome

    /** The answer was not recorded, and this says why. */
    data class Refused(val reason: String, val detail: String) : ServiceInputAnswerOutcome
}

/**
 * The questions of the runs, and the answers to them, over the socket.
 *
 * ## The channel and who owns each side
 *
 * A run that reaches an `input` step PUBLISHES its question in a directory of the control root
 * and waits for a valid answer file next to it. This class is the authenticated way to read
 * those questions and to write that file: it reads what the run published and it writes what
 * the run polls, and it never talks to the run itself. The run keeps its channel, so the
 * answer reaches it whether or not the service is up when it arrives.
 *
 * ## Why the answer survives a restart
 *
 * Because nothing is kept in memory. The state of a question is two files on disk, and a
 * service that starts again lists the same pending questions and finds the same answers. A run
 * that is resumed finds the answer file that was written before and does not ask again.
 *
 * ## Who answered
 *
 * The answer is attributed to the AUTHORIZATION the line was authenticated with, never to a
 * name the client wrote: the label of the authorization is recorded as the `submitter` of the
 * decision. The `submitter` the pipeline author declared in the step is attribution and
 * authorizes nobody.
 *
 * ## One answer per question
 *
 * The answer file is created with a hard link, which fails when the file exists: of two
 * answers that race, one is recorded and the other reads it. The service serializes its own
 * answers with a lock so the comparison and the write are one step; a writer outside the
 * service is stopped by the same link.
 *
 * ## What it does not do
 *
 * It does not stop a process of the same operating-system user that writes the answer file
 * directly: the control root is that user's, and the operating system is what keeps others
 * out. It does not list a question published without its run, or one larger than
 * [MAX_FILE_BYTES]: such a question is invisible and fails closed.
 */
internal class ServiceInputAnswers(
    private val inputsRoot: Path,
    private val runState: (String) -> RunStateAnswer,
    private val responderOf: (String?) -> String?,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {

    /** One answer is decided and written at a time, so the comparison and the write are one step. */
    private val answering = ReentrantLock()

    /**
     * The questions of [runId] that wait for an answer.
     *
     * A question is pending when the run published it, it carries the run and the operation its
     * directory names, no valid answer is next to it and it has not expired. A run that reached
     * its terminal has no pending questions. The list is ordered by publication time and holds
     * at most what fits one line of the wire: the questions that do not fit appear once the
     * earlier ones are answered.
     */
    fun pending(runId: String): ServiceInputListOutcome {
        val run = livenessOf(runId)
        if (run is Liveness.Failed) return ServiceInputListOutcome.Refused(run.reason, run.detail)
        if (run == Liveness.Closed) return ServiceInputListOutcome.Listed(emptyList())
        val candidates = candidatesOf(runId)
            ?: return ServiceInputListOutcome.Refused(
                REASON_TOO_MANY,
                "the run has more than $MAX_CANDIDATES questions on disk and they are not listed",
            )
        val now = nowMillis()
        val pending = candidates
            .mapNotNull { dir -> questionIn(dir)?.takeIf { it.runId == runId } }
            .filter { question ->
                !expired(question, now) && responseIn(directoryOf(question.opId)) is Response.Absent
            }
            .sortedWith(compareBy({ it.publishedAtMs }, { it.opId }))
        return ServiceInputListOutcome.Listed(withinBudget(pending))
    }

    /**
     * Records [decision] as the answer of the question [opId] of [runId].
     *
     * The responder is the authorization behind [credentialDigest]; a digest that names none
     * records nothing. The question must belong to [runId], be pending and not have expired,
     * and the run must not have reached its terminal.
     *
     * The state of the run and the deadline of the question are read before the answer is
     * written, so an answer accepted in the last instant may reach a run that has just stopped
     * waiting: the file it leaves is inert, and the answer still reads as recorded.
     */
    fun answer(
        runId: String,
        opId: String,
        decision: ServiceInputDecision,
        message: String?,
        credentialDigest: String?,
    ): ServiceInputAnswerOutcome {
        val responder = responderOf(credentialDigest)
            ?: return refused(REASON_NO_RESPONDER, "the credential of the request names no authorization")
        val run = livenessOf(runId)
        if (run is Liveness.Failed) return refused(run.reason, run.detail)
        if (run == Liveness.Closed) {
            return refused(REASON_RUN_TERMINAL, "the run already reached its terminal and asks nothing")
        }
        val wanted = decisionOf(decision, responder, message)
        return answering.withLock { record(runId, opId, decision, wanted) }
    }

    private fun record(
        runId: String,
        opId: String,
        decision: ServiceInputDecision,
        wanted: InputDecision,
    ): ServiceInputAnswerOutcome {
        val dir = directoryOf(opId)
        val question = questionIn(dir)?.takeIf { it.runId == runId && it.opId == opId }
            ?: return refused(REASON_UNKNOWN_QUESTION, "the run is not waiting on a question with that operation id")
        if (expired(question, nowMillis())) {
            return refused(REASON_EXPIRED, "the run stopped waiting for this question")
        }
        return when (val existing = responseIn(dir)) {
            is Response.Absent -> write(dir, decision, wanted)
            else -> compare(existing, decision, wanted)
        }
    }

    private fun write(dir: Path, decision: ServiceInputDecision, wanted: InputDecision): ServiceInputAnswerOutcome {
        val staged = dir.resolve("${InputQuestionLayout.RESPONSE_FILE}.${UUID.randomUUID()}.tmp")
        return try {
            Files.write(
                staged,
                InputAnswerCodec.encode(wanted).toByteArray(Charsets.UTF_8),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            restrict(staged)
            Files.createLink(dir.resolve(InputQuestionLayout.RESPONSE_FILE), staged)
            ServiceInputAnswerOutcome.Answered(decision)
        } catch (_: FileAlreadyExistsException) {
            compare(responseIn(dir), decision, wanted)
        } catch (_: IOException) {
            refused(REASON_NOT_RECORDED, "the answer could not be written next to the question")
        } catch (_: UnsupportedOperationException) {
            // A file system without hard links cannot give the first-writer guarantee: fail closed.
            refused(REASON_NOT_RECORDED, "the file system of the questions does not support the atomic answer")
        } finally {
            try {
                Files.deleteIfExists(staged)
            } catch (_: IOException) {
                // A staged file that cannot be removed is garbage next to the question and
                // never an answer: only the name `response.json` is read.
            }
        }
    }

    private fun compare(existing: Response, decision: ServiceInputDecision, wanted: InputDecision): ServiceInputAnswerOutcome =
        when (existing) {
            is Response.Valid ->
                if (existing.decision == wanted) {
                    ServiceInputAnswerOutcome.AlreadyAnswered(decision)
                } else {
                    refused(
                        REASON_ALREADY_ANSWERED,
                        "the question was already answered with '${wordOf(existing.decision)}'",
                    )
                }

            is Response.Unreadable -> refused(
                REASON_UNREADABLE_ANSWER,
                "the question has an answer file that is not a valid answer yet",
            )

            is Response.Absent -> refused(REASON_NOT_RECORDED, "the answer was not recorded")
        }

    private fun livenessOf(runId: String): Liveness {
        val answer = try {
            runState(runId)
        } catch (_: Exception) {
            return Liveness.Failed(REASON_RUN_UNREADABLE, "the state of the run could not be read")
        }
        return when (answer) {
            is RunStateAnswer.Observed -> when (answer.observation) {
                is RuntimeObservation.Terminal -> Liveness.Closed
                is RuntimeObservation.Running, is RuntimeObservation.LiveButEmpty -> Liveness.Live
                is RuntimeObservation.Unobservable ->
                    Liveness.Failed(REASON_RUN_UNREADABLE, "the substrate of the run could not be inspected")
            }

            is RunStateAnswer.Refused -> when (answer.refusal) {
                // A lease held by a live owner is exactly a run that waits for its answer.
                is RunStateRefusal.HeldByAnother -> Liveness.Live
                is RunStateRefusal.UnknownRun -> Liveness.Failed(REASON_UNKNOWN_RUN, "the service does not know the run")
                else -> Liveness.Failed(REASON_RUN_UNREADABLE, "the state of the run could not be read")
            }
        }
    }

    /**
     * The directories that may hold a question of [runId], or null when there are too many.
     *
     * The control directory of questions is shared by every run, so the names are filtered by
     * the escaped run id and the stage marker an operation id begins with: the marker keeps the
     * questions of a run whose id merely starts with this one from counting against it. A name
     * that is too long to keep that beginning is not filtered at all: the question inside is
     * validated either way.
     */
    private fun candidatesOf(runId: String): List<Path>? {
        if (!Files.isDirectory(inputsRoot, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val prefix = InputQuestionLayout.directoryName(runId + OP_ID_STAGE_MARKER)
            .takeIf { it.length <= PREFIX_FILTER_MAX && TRUNCATION_MARK !in it }
        val found = mutableListOf<Path>()
        try {
            Files.newDirectoryStream(inputsRoot).use { stream ->
                for (entry in stream) {
                    val name = entry.fileName.toString()
                    if (prefix != null && !name.startsWith(prefix)) continue
                    if (found.size == MAX_CANDIDATES) return null
                    found.add(entry)
                }
            }
        } catch (_: IOException) {
            return emptyList()
        }
        return found
    }

    private fun questionIn(dir: Path): PublishedInputQuestion? {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return null
        val raw = readBounded(dir.resolve(InputQuestionLayout.REQUEST_FILE)) ?: return null
        val question = InputQuestionCodec.decode(raw) ?: return null
        return question.takeIf { directoryOf(it.opId) == dir }
    }

    private fun responseIn(dir: Path): Response {
        val file = dir.resolve(InputQuestionLayout.RESPONSE_FILE)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Response.Absent
        val decision = readBounded(file)?.let(InputAnswerCodec::decode)
        return if (decision == null) Response.Unreadable else Response.Valid(decision)
    }

    private fun directoryOf(opId: String): Path = inputsRoot.resolve(InputQuestionLayout.directoryName(opId))

    private fun withinBudget(questions: List<PublishedInputQuestion>): List<PublishedInputQuestion> {
        var used = 0
        val kept = mutableListOf<PublishedInputQuestion>()
        for (question in questions) {
            val size = questionJson(question).toString().toByteArray(Charsets.UTF_8).size + 1
            if (used + size > LIST_BUDGET_BYTES) continue
            used += size
            kept += question
        }
        return kept
    }

    private fun restrict(file: Path) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // A file system without POSIX permissions keeps the mode it has.
        }
    }

    private fun refused(reason: String, detail: String): ServiceInputAnswerOutcome.Refused =
        ServiceInputAnswerOutcome.Refused(reason, detail)

    private sealed interface Liveness {
        data object Live : Liveness
        data object Closed : Liveness
        data class Failed(val reason: String, val detail: String) : Liveness
    }

    private sealed interface Response {
        data object Absent : Response
        data object Unreadable : Response
        data class Valid(val decision: InputDecision) : Response
    }

    companion object {

        /** The largest question or answer file the service reads. Larger ones are not read. */
        const val MAX_FILE_BYTES: Int = 16 * 1024

        /** The most directories of a run the service looks at before it refuses to list. */
        const val MAX_CANDIDATES: Int = 1024

        /** The most bytes of questions one list carries, so that the answer fits one line. */
        const val LIST_BUDGET_BYTES: Int = 40 * 1024

        /** The word of an answer this call recorded. */
        const val OUTCOME_ANSWERED: String = "answered"

        /** The word of an answer the same authorization had already recorded. */
        const val OUTCOME_ALREADY_ANSWERED: String = "already-answered"

        /** The service has no way to answer questions. */
        const val REASON_NOT_COMPOSED: String = "the-service-may-not-answer"

        /** The run is not one the service knows. */
        const val REASON_UNKNOWN_RUN: String = "the-run-is-unknown"

        /** The state of the run could not be read. */
        const val REASON_RUN_UNREADABLE: String = "the-run-could-not-be-observed"

        /** The run already reached its terminal. */
        const val REASON_RUN_TERMINAL: String = "the-run-is-already-terminal"

        /** The run is not waiting on a question with that operation id. */
        const val REASON_UNKNOWN_QUESTION: String = "the-question-is-unknown"

        /** The run stopped waiting for the question. */
        const val REASON_EXPIRED: String = "the-question-expired"

        /** The question already has a different answer. */
        const val REASON_ALREADY_ANSWERED: String = "the-question-was-already-answered"

        /** The question has an answer file that is not a valid answer. */
        const val REASON_UNREADABLE_ANSWER: String = "the-answer-is-unreadable"

        /** The answer could not be written. */
        const val REASON_NOT_RECORDED: String = "the-answer-could-not-be-recorded"

        /** The credential of the request names no authorization. */
        const val REASON_NO_RESPONDER: String = "the-responder-is-unknown"

        /** The run has more questions on disk than the service lists. */
        const val REASON_TOO_MANY: String = "the-questions-are-too-many"

        /** The failure of an unforeseen kind. */
        const val REASON_FAILED: String = "the-answer-failed"

        private const val PREFIX_FILTER_MAX: Int = 100

        /** What an operation id writes right after the run id it begins with. */
        private const val OP_ID_STAGE_MARKER: String = "-s"
        private const val TRUNCATION_MARK: String = "~~"

        /**
         * The question as it travels on the wire.
         *
         * The `submitter` the author declared travels as `declaredSubmitter` so that nobody
         * reads it as the identity of whoever answered.
         */
        fun questionJson(question: PublishedInputQuestion): JsonObject = buildJsonObject {
            put("opId", JsonPrimitive(question.opId))
            put("message", JsonPrimitive(question.message))
            put("ok", JsonPrimitive(question.ok))
            put("id", question.id?.let { JsonPrimitive(it) } ?: JsonNull)
            put("declaredSubmitter", question.submitter?.let { JsonPrimitive(it) } ?: JsonNull)
            put("publishedAtMs", JsonPrimitive(question.publishedAtMs))
            put("expiresAtMs", question.expiresAtMs?.let { JsonPrimitive(it) } ?: JsonNull)
        }

        /** The decision the run reads for [decision], attributed to [responder]. */
        fun decisionOf(decision: ServiceInputDecision, responder: String, message: String?): InputDecision =
            when (decision) {
                ServiceInputDecision.PROCEED -> InputDecision.Proceed(responder, message)
                ServiceInputDecision.ABORT -> InputDecision.Abort(responder, message)
            }

        private fun expired(question: PublishedInputQuestion, now: Long): Boolean {
            val expiresAt = question.expiresAtMs
            return expiresAt != null && now >= expiresAt
        }

        private fun wordOf(decision: InputDecision): String = when (decision) {
            is InputDecision.Proceed -> ServiceInputDecision.PROCEED.word
            is InputDecision.Abort -> ServiceInputDecision.ABORT.word
        }

        private fun readBounded(file: Path): String? {
            val attributes = try {
                Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                return null
            }
            if (!attributes.isRegularFile || attributes.size() > MAX_FILE_BYTES) return null
            return try {
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                    val bytes = input.readNBytes(MAX_FILE_BYTES + 1)
                    if (bytes.size > MAX_FILE_BYTES) null else String(bytes, Charsets.UTF_8)
                }
            } catch (_: IOException) {
                null
            }
        }
    }
}
