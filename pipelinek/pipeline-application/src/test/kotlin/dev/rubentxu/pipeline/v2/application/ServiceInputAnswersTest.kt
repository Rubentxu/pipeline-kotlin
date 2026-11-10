package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.AttemptId
import dev.rubentxu.pipeline.v2.runtime.FileInputDecisions
import dev.rubentxu.pipeline.v2.runtime.InputAnswerCodec
import dev.rubentxu.pipeline.v2.runtime.InputDecision
import dev.rubentxu.pipeline.v2.runtime.InputOperation
import dev.rubentxu.pipeline.v2.runtime.InputQuestionCodec
import dev.rubentxu.pipeline.v2.runtime.InputQuestionLayout
import dev.rubentxu.pipeline.v2.runtime.InputRequest
import dev.rubentxu.pipeline.v2.runtime.InputResolution
import dev.rubentxu.pipeline.v2.runtime.PublishedInputQuestion
import dev.rubentxu.pipeline.v2.runtime.durable.OpId
import dev.rubentxu.pipeline.v2.runtime.inspect.IntrospectionFailure
import dev.rubentxu.pipeline.v2.runtime.inspect.JournalPosition
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.service.ServiceInputDecision
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The questions of a run and the answers to them, read and written by the service.
 *
 * ## The guarantee under trial
 *
 * That the service lists the questions a run published and nothing else; that an answer reaches
 * the run through the very channel the run polls and is attributed to the authorization that
 * made it; that of two answers to one question exactly one is recorded; and that the state is
 * the two files on disk, so a service that starts again lists and answers consistently.
 *
 * ## The capacity this serves
 *
 * A pipeline that reached an `input` step waits for a person. The person answers from outside,
 * with a token the operator issued, and the pipeline goes on —or stops— without anybody having
 * to write into its control directory by hand.
 *
 * ## Why this instrument
 *
 * The questions are published by the REAL producer of the run (`FileInputDecisions`) in a
 * temporary directory, because what the service must agree with is that producer's layout and
 * not a copy of it; the state of the run is a double, because what it decides —whether the run
 * is alive— needs no store. The unusual shapes (a question of another run, a link, a file too
 * large) are written by hand since the producer never writes them.
 *
 * ## What this does not freeze
 *
 * The sentences of the refusals, the order of the files inside a directory and the size of the
 * staged file the answer is written through.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ServiceInputAnswersTest {

    @TempDir
    lateinit var tmp: Path

    private val clock = AtomicLong(NOW)

    @Test
    fun `a question the run published is listed with what the author wrote and nothing else`() {
        publish("run-1", op(0), message = "Deploy?", submitter = "alice", id = "gate-1")

        val listed = listedBy(answers(), "run-1")

        assertEquals(1, listed.size, "one question was published")
        val question = listed.single()
        assertEquals(op(0), question.opId)
        assertEquals("Deploy?", question.message)
        assertEquals("alice", question.submitter, "what the author declared is attribution and travels as declared")
        assertEquals("gate-1", question.id)
        assertEquals("run-1", question.runId)
    }

    @Test
    fun `a run that asks nothing lists nothing and a root that is not there lists nothing`() {
        assertEquals(emptyList<PublishedInputQuestion>(), listedBy(answers(), "run-1"), "the root is empty")

        val absent = ServiceInputAnswers(
            inputsRoot = tmp.resolve("not-there"),
            runState = { live() },
            responderOf = ::operatorOf,
            nowMillis = clock::get,
        )
        assertEquals(emptyList<PublishedInputQuestion>(), listedBy(absent, "run-1"), "a root nobody created is no question")
    }

    @Test
    fun `the questions of another run are not listed and cannot be answered through this run`() {
        publish("run-1", op(0))
        publish("run-2", op(0, runId = "run-2"))

        val service = answers()

        assertEquals(listOf("run-1"), listedBy(service, "run-1").map { it.runId }, "only the questions of the run asked")
        listOf(op(1), op(0, runId = "run-2")).forEach { foreign ->
            val crossed = service.answer("run-1", foreign, ServiceInputDecision.PROCEED, null, DIGEST)
            assertEquals(
                ServiceInputAnswers.REASON_UNKNOWN_QUESTION,
                assertInstanceOf(ServiceInputAnswerOutcome.Refused::class.java, crossed).reason,
                "$foreign is not a question of run-1: it is unknown or belongs to another run",
            )
        }
        assertFalse(Files.exists(responseOf(op(0, runId = "run-2"))), "the question of run-2 stays unanswered")
    }

    @Test
    fun `a question whose directory names another operation is invisible`() {
        publish("run-1", op(0))
        val forged = InputQuestionLayout.directoryName(op(0))
        val other = tmp.resolve(InputQuestionLayout.directoryName(op(25, 9)))
        Files.createDirectories(other)
        Files.copy(
            tmp.resolve(forged).resolve(InputQuestionLayout.REQUEST_FILE),
            other.resolve(InputQuestionLayout.REQUEST_FILE),
        )

        val service = answers()

        assertEquals(1, listedBy(service, "run-1").size, "the copy sits in a directory that does not name its operation")
        val outcome = service.answer("run-1", op(25, 9), ServiceInputDecision.PROCEED, null, DIGEST)
        assertInstanceOf(
            ServiceInputAnswerOutcome.Refused::class.java,
            outcome,
            "the service writes only where the producer would have published the question",
        )
    }

    @Test
    fun `a question larger than the bound or reached through a link is invisible`() {
        val big = tmp.resolve(InputQuestionLayout.directoryName("run-1:big"))
        Files.createDirectories(big)
        Files.writeString(
            big.resolve(InputQuestionLayout.REQUEST_FILE),
            InputQuestionCodec.encode(question("run-1", "run-1:big", message = "x".repeat(ServiceInputAnswers.MAX_FILE_BYTES))),
        )
        val real = tmp.resolve("elsewhere")
        Files.createDirectories(real)
        Files.writeString(
            real.resolve(InputQuestionLayout.REQUEST_FILE),
            InputQuestionCodec.encode(question("run-1", "run-1:linked")),
        )
        Files.createSymbolicLink(tmp.resolve(InputQuestionLayout.directoryName("run-1:linked")), real)

        assertEquals(
            emptyList<PublishedInputQuestion>(),
            listedBy(answers(), "run-1"),
            "a file larger than the bound is not read and a directory that is a link is not followed",
        )
    }

    @Test
    fun `a question that expired or was answered is not pending`() {
        publish("run-1", op(0), expiresAtMs = NOW + 1_000)
        publish("run-1", op(1))
        val service = answers()
        assertEquals(2, listedBy(service, "run-1").size)

        clock.set(NOW + 1_000)
        assertEquals(
            listOf(op(1)),
            listedBy(service, "run-1").map { it.opId },
            "the run stopped waiting at the instant it set",
        )

        service.answer("run-1", op(1), ServiceInputDecision.PROCEED, null, DIGEST)
        assertEquals(emptyList<PublishedInputQuestion>(), listedBy(service, "run-1"), "an answered question waits for nobody")
    }

    @Test
    fun `the questions are listed oldest first`() {
        publish("run-1", op(2), publishedAtMs = NOW + 20)
        publish("run-1", op(1), publishedAtMs = NOW + 10)

        assertEquals(
            listOf(op(1), op(2)),
            listedBy(answers(), "run-1").map { it.opId },
        )
    }

    @Test
    fun `a list holds at most what fits one line of the wire`() {
        repeat(8) { index ->
            publish("run-1", op(index), message = "m".repeat(8_000), publishedAtMs = NOW + index)
        }

        val listed = listedBy(answers(), "run-1")

        assertTrue(listed.isNotEmpty() && listed.size < 8, "some questions fit and the rest wait")
        assertTrue(
            listed.sumOf { ServiceInputAnswers.questionJson(it).toString().toByteArray().size + 1 } <=
                ServiceInputAnswers.LIST_BUDGET_BYTES,
            "the answer fits the budget the client reads",
        )
        assertEquals(
            (0 until listed.size).map { op(it) },
            listed.map { it.opId },
            "the oldest are the ones that stay",
        )
    }

    @Test
    fun `a run that reached its terminal has no pending questions and takes no answer`() {
        publish("run-1", op(0))
        val service = answers(state = { terminal() })

        assertEquals(emptyList<PublishedInputQuestion>(), listedBy(service, "run-1"))
        assertEquals(
            ServiceInputAnswers.REASON_RUN_TERMINAL,
            assertInstanceOf(
                ServiceInputAnswerOutcome.Refused::class.java,
                service.answer("run-1", op(0), ServiceInputDecision.PROCEED, null, DIGEST),
            ).reason,
            "a closed run asks nothing, and an answer written for it would be a lie",
        )
        assertFalse(Files.exists(responseOf(op(0))), "nothing was written")
    }

    @Test
    fun `a run the service does not know or cannot read is refused by name`() {
        publish("run-1", op(0))
        val rows = listOf<Pair<RunStateAnswer, String>>(
            RunStateAnswer.Refused(RunStateRefusal.UnknownRun("run-1")) to ServiceInputAnswers.REASON_UNKNOWN_RUN,
            RunStateAnswer.Refused(RunStateRefusal.StorageFailed("disk")) to ServiceInputAnswers.REASON_RUN_UNREADABLE,
            RunStateAnswer.Observed(RuntimeObservation.Unobservable(IntrospectionFailure.StorageError("disk"))) to
                ServiceInputAnswers.REASON_RUN_UNREADABLE,
        )

        rows.forEach { (state, reason) ->
            val service = answers(state = { state })

            assertEquals(
                reason,
                assertInstanceOf(ServiceInputListOutcome.Refused::class.java, service.pending("run-1")).reason,
                "listing, for $state",
            )
            assertEquals(
                reason,
                assertInstanceOf(
                    ServiceInputAnswerOutcome.Refused::class.java,
                    service.answer("run-1", op(0), ServiceInputDecision.PROCEED, null, DIGEST),
                ).reason,
                "answering, for $state",
            )
        }
    }

    @Test
    fun `a run held by a live owner is a run that waits for its answer`() {
        publish("run-1", op(0))
        val service = answers(state = { RunStateAnswer.Refused(RunStateRefusal.HeldByAnother("owner-1", 7L)) })

        assertEquals(1, listedBy(service, "run-1").size, "a live owner holding the lease is the run asking")
        assertInstanceOf(
            ServiceInputAnswerOutcome.Answered::class.java,
            service.answer("run-1", op(0), ServiceInputDecision.PROCEED, null, DIGEST),
        )
    }

    @Test
    fun `a state that throws is a refusal and not a failure of the service`() {
        publish("run-1", op(0))
        val service = answers(state = { throw IllegalStateException("the store is on fire") })

        assertEquals(
            ServiceInputAnswers.REASON_RUN_UNREADABLE,
            assertInstanceOf(ServiceInputListOutcome.Refused::class.java, service.pending("run-1")).reason,
        )
    }

    @Test
    fun `an answer reaches the run through the channel the run polls and names the authorization`() {
        val opId = op(0)
        val resolution = askInBackground("run-1", opId)
        awaitPublished(opId)

        val outcome = answers().answer("run-1", opId, ServiceInputDecision.PROCEED, "go ahead", DIGEST)

        assertEquals(ServiceInputAnswerOutcome.Answered(ServiceInputDecision.PROCEED), outcome)
        val answered = assertInstanceOf(InputResolution.Answered::class.java, resolution.get(10, TimeUnit.SECONDS))
        assertEquals(
            InputDecision.Proceed("operator", "go ahead"),
            answered.decision,
            "the run reads the decision and the label the OPERATOR gave the token, not a name the client chose",
        )
    }

    @Test
    fun `an abort reaches the run as an abort`() {
        val opId = op(0)
        val resolution = askInBackground("run-1", opId)
        awaitPublished(opId)

        answers().answer("run-1", opId, ServiceInputDecision.ABORT, null, DIGEST)

        val answered = assertInstanceOf(InputResolution.Answered::class.java, resolution.get(10, TimeUnit.SECONDS))
        assertEquals(InputDecision.Abort("operator", null), answered.decision)
    }

    @Test
    fun `the submitter the author declared does not decide who answered`() {
        val opId = op(0)
        publish("run-1", opId, submitter = "alice")

        answers().answer("run-1", opId, ServiceInputDecision.PROCEED, null, DIGEST)

        val recorded = InputAnswerCodec.decode(Files.readString(responseOf(opId)))
        assertEquals(
            InputDecision.Proceed("operator", null),
            recorded,
            "alice is attribution declared by the pipeline; the responder is the authorization that answered",
        )
    }

    @Test
    fun `a credential that names no authorization records nothing`() {
        publish("run-1", op(0))

        val outcome = answers().answer("run-1", op(0), ServiceInputDecision.PROCEED, null, "not-a-digest")

        assertEquals(
            ServiceInputAnswers.REASON_NO_RESPONDER,
            assertInstanceOf(ServiceInputAnswerOutcome.Refused::class.java, outcome).reason,
        )
        assertFalse(Files.exists(responseOf(op(0))), "no responder, no file")
        assertEquals(
            ServiceInputAnswers.REASON_NO_RESPONDER,
            assertInstanceOf(
                ServiceInputAnswerOutcome.Refused::class.java,
                answers().answer("run-1", op(0), ServiceInputDecision.PROCEED, null, null),
            ).reason,
            "a request that carries no credential is the same fact",
        )
    }

    @Test
    fun `the same authorization answering the same way again reads a success and writes nothing new`() {
        publish("run-1", op(0))
        val service = answers()
        service.answer("run-1", op(0), ServiceInputDecision.PROCEED, "ok", DIGEST)
        val before = Files.readString(responseOf(op(0)))

        val again = service.answer("run-1", op(0), ServiceInputDecision.PROCEED, "ok", DIGEST)

        assertEquals(
            ServiceInputAnswerOutcome.AlreadyAnswered(ServiceInputDecision.PROCEED),
            again,
            "a client that retries an answer whose reply was lost must read a success",
        )
        assertEquals(before, Files.readString(responseOf(op(0))), "the recorded answer is untouched")
    }

    @Test
    fun `a different decision, message or authorization finds the question answered and changes nothing`() {
        publish("run-1", op(0))
        val service = answers(responders = mapOf(DIGEST to "operator", OTHER_DIGEST to "auditor"))
        service.answer("run-1", op(0), ServiceInputDecision.PROCEED, "ok", DIGEST)
        val before = Files.readString(responseOf(op(0)))

        val rows = listOf(
            Triple(ServiceInputDecision.ABORT, "ok", DIGEST),
            Triple(ServiceInputDecision.PROCEED, "other words", DIGEST),
            Triple(ServiceInputDecision.PROCEED, "ok", OTHER_DIGEST),
        )
        rows.forEach { (decision, message, digest) ->
            val outcome = service.answer("run-1", op(0), decision, message, digest)

            assertEquals(
                ServiceInputAnswers.REASON_ALREADY_ANSWERED,
                assertInstanceOf(ServiceInputAnswerOutcome.Refused::class.java, outcome).reason,
                "$decision / $message / $digest is not the answer that was recorded",
            )
        }
        assertEquals(before, Files.readString(responseOf(op(0))), "the first answer stands")
    }

    @Test
    fun `two answers racing for one question record exactly one and the run reads that one`() {
        val service = answers(responders = mapOf(DIGEST to "operator", OTHER_DIGEST to "auditor"))
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(RACES) { round ->
                val racedOp = op(round + 10)
                publish("run-1", racedOp)
                val start = CountDownLatch(1)
                val results = listOf(
                    pool.submit<ServiceInputAnswerOutcome> {
                        start.await()
                        service.answer("run-1", racedOp, ServiceInputDecision.PROCEED, null, DIGEST)
                    },
                    pool.submit<ServiceInputAnswerOutcome> {
                        start.await()
                        service.answer("run-1", racedOp, ServiceInputDecision.ABORT, null, OTHER_DIGEST)
                    },
                )
                start.countDown()
                val outcomes = results.map { it.get(10, TimeUnit.SECONDS) }

                assertEquals(
                    1,
                    outcomes.count { it is ServiceInputAnswerOutcome.Answered },
                    "of two answers to one question exactly one is recorded (round $round)",
                )
                assertEquals(
                    1,
                    outcomes.count { it is ServiceInputAnswerOutcome.Refused },
                    "and the other is told the question was already answered (round $round)",
                )
                val winner = outcomes.filterIsInstance<ServiceInputAnswerOutcome.Answered>().single().decision
                val recorded = checkNotNull(InputAnswerCodec.decode(Files.readString(responseOf(racedOp))))
                assertEquals(
                    winner == ServiceInputDecision.PROCEED,
                    recorded is InputDecision.Proceed,
                    "the file holds the answer of the call that was told it won (round $round)",
                )
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a service that starts again lists and answers consistently with what is on disk`() {
        publish("run-1", op(0))
        publish("run-1", op(1))
        answers().answer("run-1", op(0), ServiceInputDecision.PROCEED, null, DIGEST)

        val restarted = answers()

        assertEquals(
            listOf(op(1)),
            listedBy(restarted, "run-1").map { it.opId },
            "the answered question stays answered and the other one is still pending",
        )
        assertEquals(
            ServiceInputAnswerOutcome.AlreadyAnswered(ServiceInputDecision.PROCEED),
            restarted.answer("run-1", op(0), ServiceInputDecision.PROCEED, null, DIGEST),
            "the new instance knows no more than the files and the files are enough",
        )
    }

    @Test
    fun `a run that asks again after it was answered finds the answer and does not wait`() {
        val opId = op(0)
        publish("run-1", opId)
        answers().answer("run-1", opId, ServiceInputDecision.PROCEED, null, DIGEST)

        val resolution = askInBackground("run-1", opId)

        val answered = assertInstanceOf(InputResolution.Answered::class.java, resolution.get(10, TimeUnit.SECONDS))
        assertEquals(
            InputDecision.Proceed("operator", null),
            answered.decision,
            "the decision memoized before the restart is the one the run reads",
        )
    }

    @Test
    fun `an answer file that is not a valid answer is not overwritten and is refused by name`() {
        publish("run-1", op(0))
        Files.writeString(responseOf(op(0)), "not an answer")

        val service = answers()
        val outcome = service.answer("run-1", op(0), ServiceInputDecision.PROCEED, null, DIGEST)

        assertEquals(
            ServiceInputAnswers.REASON_UNREADABLE_ANSWER,
            assertInstanceOf(ServiceInputAnswerOutcome.Refused::class.java, outcome).reason,
        )
        assertEquals("not an answer", Files.readString(responseOf(op(0))), "the file is not replaced")
        assertEquals(
            emptyList<PublishedInputQuestion>(),
            listedBy(service, "run-1"),
            "a question with a response file is not offered as pending",
        )
    }

    @Test
    fun `the answer is written with owner-only permissions and leaves no staged file`() {
        publish("run-1", op(0))

        answers().answer("run-1", op(0), ServiceInputDecision.PROCEED, null, DIGEST)

        val names = Files.list(responseOf(op(0)).parent).use { stream ->
            stream.map { it.fileName.toString() }.toList()
        }
        assertTrue(names.none { it.endsWith(".tmp") && it.startsWith("response") }, "no staged file stays: $names")
        assertEquals(
            "rw-------",
            java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(responseOf(op(0))),
            ),
        )
    }

    @Test
    fun `a run with more questions on disk than the service lists is refused by name`() {
        repeat(ServiceInputAnswers.MAX_CANDIDATES + 1) { index ->
            Files.createDirectories(tmp.resolve(InputQuestionLayout.directoryName("run-1:$index")))
        }

        assertEquals(
            ServiceInputAnswers.REASON_TOO_MANY,
            assertInstanceOf(ServiceInputListOutcome.Refused::class.java, answers().pending("run-1")).reason,
        )
    }

    @Test
    fun `the questions of other runs on the same root do not count against this run`() {
        repeat(ServiceInputAnswers.MAX_CANDIDATES + 1) { index ->
            Files.createDirectories(tmp.resolve(InputQuestionLayout.directoryName("run-2:$index")))
        }
        publish("run-1", "run-1:0")

        assertEquals(
            listOf("run-1:0"),
            listedBy(answers(), "run-1").map { it.opId },
            "the name prefix keeps a busy neighbour from hiding this run",
        )
    }

    @Test
    fun `the question travels with the declared submitter named as declared`() {
        val json = ServiceInputAnswers.questionJson(question("run-1", op(0), submitter = "alice", id = "gate"))

        assertEquals("alice", json["declaredSubmitter"]?.toString()?.trim('"'))
        assertTrue("submitter" !in json, "the word that would read as the identity of the responder is not on the wire")
        assertTrue("runId" !in json, "the run is what the client asked about")
    }

    @Test
    fun `the decision the run reads carries the responder and the message`() {
        assertEquals(
            InputDecision.Proceed("operator", "m"),
            ServiceInputAnswers.decisionOf(ServiceInputDecision.PROCEED, "operator", "m"),
        )
        assertEquals(
            InputDecision.Abort("operator", null),
            ServiceInputAnswers.decisionOf(ServiceInputDecision.ABORT, "operator", null),
        )
    }

    // ---- the fixtures ---------------------------------------------------------------------

    private fun answers(
        state: (String) -> RunStateAnswer = { live() },
        responders: Map<String, String> = mapOf(DIGEST to "operator"),
    ): ServiceInputAnswers = ServiceInputAnswers(
        inputsRoot = tmp,
        runState = state,
        responderOf = { digest -> responders[digest] },
        nowMillis = clock::get,
    )

    /** The operation id the run gives the step [step] of stage [stage]: it begins with the run id. */
    private fun op(stage: Int, step: Int = 0, runId: String = "run-1"): String = OpId(runId, stage, step).format()

    private fun operatorOf(digest: String?): String? = if (digest == DIGEST) "operator" else null

    private fun listedBy(service: ServiceInputAnswers, runId: String): List<PublishedInputQuestion> =
        assertInstanceOf(ServiceInputListOutcome.Listed::class.java, service.pending(runId)).questions

    private fun responseOf(opId: String): Path =
        tmp.resolve(InputQuestionLayout.directoryName(opId)).resolve(InputQuestionLayout.RESPONSE_FILE)

    private fun question(
        runId: String,
        opId: String,
        message: String = "Proceed?",
        submitter: String? = null,
        id: String? = null,
        publishedAtMs: Long = NOW,
        expiresAtMs: Long? = null,
    ): PublishedInputQuestion = PublishedInputQuestion(
        runId = runId,
        opId = opId,
        message = message,
        ok = "Proceed",
        submitter = submitter,
        id = id,
        publishedAtMs = publishedAtMs,
        expiresAtMs = expiresAtMs,
    )

    /** Publishes a question the way the run does: through the real producer, then stops waiting. */
    private fun publish(
        runId: String,
        opId: String,
        message: String = "Proceed?",
        submitter: String? = null,
        id: String? = null,
        publishedAtMs: Long = NOW,
        expiresAtMs: Long? = null,
    ) {
        val dir = tmp.resolve(InputQuestionLayout.directoryName(opId))
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve(InputQuestionLayout.REQUEST_FILE),
            InputQuestionCodec.encode(question(runId, opId, message, submitter, id, publishedAtMs, expiresAtMs)),
        )
    }

    /** A run that asks, in a thread of its own, through the real channel. */
    private fun askInBackground(runId: String, opId: String): java.util.concurrent.Future<InputResolution> {
        val pool = Executors.newSingleThreadExecutor()
        val future = pool.submit<InputResolution> {
            runBlocking {
                FileInputDecisions(
                    root = tmp,
                    operation = InputOperation(runId, opId),
                    pollIntervalMs = POLL_MS,
                ).awaitDecision(InputRequest("Proceed?", "Proceed", null, null), waitMillis = WAIT_MS)
            }
        }
        pool.shutdown()
        return future
    }

    private fun awaitPublished(opId: String) {
        val request = tmp.resolve(InputQuestionLayout.directoryName(opId)).resolve(InputQuestionLayout.REQUEST_FILE)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!Files.exists(request)) {
            check(System.nanoTime() < deadline) { "the run did not publish its question" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun live(): RunStateAnswer = RunStateAnswer.Observed(
        RuntimeObservation.Running(
            attempt = AttemptId(1),
            leaseHolder = null,
            fencingToken = 1L,
            journalPosition = JournalPosition(operations = 0, latestOpId = null, latestTerminalAtMs = null),
            outputTails = emptyList(),
        ),
    )

    private fun terminal(): RunStateAnswer = RunStateAnswer.Observed(
        RuntimeObservation.Terminal(
            attempt = AttemptId(1),
            terminal = TerminalObservation.Succeeded(0L),
            terminalAtMs = 0L,
        ),
    )

    private companion object {
        const val NOW: Long = 1_700_000_000_000L
        const val DIGEST: String = "digest-of-the-operator"
        const val OTHER_DIGEST: String = "digest-of-the-auditor"
        const val POLL_MS: Long = 10L
        const val WAIT_MS: Long = 30_000L
        const val RACES: Int = 20
    }
}
