package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.InputAborted
import dev.rubentxu.pipeline.v2.events.InputProceed
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceInputAnswer
import dev.rubentxu.pipeline.v2.service.ServiceInputDecision
import dev.rubentxu.pipeline.v2.service.ServiceInputQuestion
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceRunStart
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * A run that asks a question, answered by a client over a real socket.
 *
 * ## The guarantee under trial
 *
 * That the answer of a client REACHES the run that asked: the body of the
 * question runs only after a `PROCEED` that an authorized client sent, an
 * `ABORT` closes the question with the body never run, and the facts of the run
 * name the responder that the SERVICE authenticated and not one the client
 * claimed.
 *
 * ## The capacity this serves
 *
 * A run that reaches an `input` waits for a human. Before this channel the
 * only human who could answer was one with a shell on the machine and write
 * access to the control root; a client of the service had no way to see the
 * question or to answer it, and a run waiting for it would wait until its
 * budget ran out.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * Because the facts under trial cross a MACHINE boundary: a real child JVM
 * publishes the question through the files of the control root and polls for
 * the answer, and the service in this JVM reads and writes those same files.
 * A fake child would prove that the service and the test agree; it would not
 * prove that the product's own producer reads what the product's own service
 * writes.
 *
 * ## What this does not freeze
 *
 * How long the child takes to ask or to finish, and the words of a run's
 * outcome after an abort.
 *
 * ## What a RESTART means in this lane
 *
 * The service lives in the JVM of this test, so it cannot die the way a
 * process dies. The listener is closed and a new one is bound over the same
 * socket path and the same control root, with every object of the first
 * service replaced: what the new service knows about the question is only what
 * the files say.
 */
@Timeout(value = 300, unit = java.util.concurrent.TimeUnit.SECONDS)
class ServiceInputIntegrationTest {

    @TempDir
    lateinit var root: Path

    /** The store the service reads and the run writes. */
    private lateinit var db: Path

    /** The control root the service reads and the run writes. */
    private lateinit var control: Path

    /** The directory of the scripts a client may name. */
    private lateinit var scripts: Path

    /** The socket the service listens on. */
    private lateinit var socket: Path

    /** The directory of the children the service launched. */
    private val supervisionRoot: Path get() = root.resolve("supervisions")

    /** The machine's clock: the run's own writer uses it too. */
    private val clock: SystemClock = SystemClock()

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        control = Files.createDirectories(root.resolve("control"))
        scripts = Files.createDirectories(root.resolve("scripts"))
        socket = root.resolve("service.sock")
        Files.writeString(
            scripts.resolve(GATE),
            "pipeline {\n    stages {\n        stage(\"gate\") {\n" +
                "            input(\"$QUESTION\") {\n                echo(\"$BODY_TEXT\")\n" +
                "            }\n        }\n    }\n}\n",
        )
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val writable = (admission as EventStoreAdmission.Admitted).store
        DurableStores.open(writable, control, clock)
        writable.close()
    }

    @Test
    fun `the body of a question runs only after the proceed of an authorized client`() {
        val service = start()
        try {
            val run = startRun(GATE)
            val question = awaitQuestion(run.runId)

            assertEquals(QUESTION, question.message, "the client reads the question the script asked")
            assertTrue(
                client(ANSWERER_TOKEN).runState(run.runId).refusal() is ServiceRefusal.HeldByAnother,
                "the run waits for the answer: its own child still holds it and it has not finished",
            )

            listOf(READER_TOKEN, CANCELLER_TOKEN).forEach { token ->
                assertEquals(
                    refusedAsNotAuthorized(),
                    client(token).answerInput(run.runId, question.opId, ServiceInputDecision.PROCEED).refusal(),
                    "a client without the right to answer does not answer, whatever else it may do",
                )
                assertEquals(
                    refusedAsNotAuthorized(),
                    client(token).pendingInputs(run.runId).refusal(),
                    "a client without the right to answer does not even see the questions",
                )
            }
            assertEquals(
                listOf(question.opId),
                pendingOpIds(run.runId),
                "the refused clients changed nothing: the question still waits",
            )

            val answered = assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                client(ANSWERER_TOKEN).answerInput(run.runId, question.opId, ServiceInputDecision.PROCEED),
            )
            assertEquals("answered", (answered.value as ServiceInputAnswer).outcome)

            val terminal = awaitTerminal(run.runId)
            assertInstanceOf(TerminalObservation.Succeeded::class.java, terminal.terminal)

            val events = service.eventsOf(run.runId)
            val proceeded = events.filterIsInstance<InputProceed>()
            assertEquals(1, proceeded.size, "one answer, one continuation: $events")
            assertEquals(
                ANSWERER_LABEL,
                proceeded.single().submitter,
                "the facts name the responder the service authenticated, not one the client claimed",
            )
            val echoes = events.filterIsInstance<EchoOutputCaptured>().filter { BODY_TEXT in it.content }
            assertEquals(1, echoes.size, "the body ran once: $events")
            assertTrue(
                events.indexOf(proceeded.single()) < events.indexOf(echoes.single()),
                "the body ran AFTER the proceed and not before",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `an abort closes the question and the body never runs`() {
        val service = start()
        try {
            val run = startRun(GATE)
            val question = awaitQuestion(run.runId)

            val answered = assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                client(ANSWERER_TOKEN).answerInput(run.runId, question.opId, ServiceInputDecision.ABORT),
            )
            assertEquals("answered", (answered.value as ServiceInputAnswer).outcome)

            awaitTerminal(run.runId)

            val events = service.eventsOf(run.runId)
            val aborted = events.filterIsInstance<InputAborted>()
            assertEquals(1, aborted.size, "the abort was recorded once: $events")
            assertEquals(ANSWERER_LABEL, aborted.single().submitter, "the abort names the authenticated responder")
            assertTrue(events.filterIsInstance<InputProceed>().isEmpty(), "an abort is not a proceed: $events")
            assertTrue(
                events.filterIsInstance<EchoOutputCaptured>().none { BODY_TEXT in it.content },
                "the body of an aborted question never ran: $events",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a restarted service lists the question that still waits and takes the answer once`() {
        val service = start()
        try {
            val run = startRun(GATE)
            val question = awaitQuestion(run.runId)

            service.restartServer()

            assertEquals(
                listOf(question.opId),
                pendingOpIds(run.runId),
                "the new service knows the question only from the files, and the files still hold it",
            )
            val answered = assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                client(ANSWERER_TOKEN).answerInput(run.runId, question.opId, ServiceInputDecision.PROCEED),
            )
            assertEquals("answered", (answered.value as ServiceInputAnswer).outcome)

            assertInstanceOf(TerminalObservation.Succeeded::class.java, awaitTerminal(run.runId).terminal)
            val proceeded = service.eventsOf(run.runId).filterIsInstance<InputProceed>()
            assertEquals(1, proceeded.size, "the answer after the restart produced one continuation")

            val again = client(ANSWERER_TOKEN).answerInput(run.runId, question.opId, ServiceInputDecision.PROCEED)
            val refusal = (again as? ServiceAnswer.Refused)?.refusal
            assertEquals(
                "the-run-is-already-terminal",
                (refusal as? ServiceRefusal.InputAnswerRefused)?.reason,
                "a finished run takes no answer: $again",
            )
        } finally {
            service.stop()
        }
    }

    /** The refusal a client without the right to answer receives. */
    private fun refusedAsNotAuthorized(): ServiceRefusal = ServiceRefusal.AnswerNotAuthorized

    /** The refusal the answer carries, or null when it carries none. */
    private fun ServiceAnswer<*>.refusal(): ServiceRefusal? = (this as? ServiceAnswer.Refused)?.refusal

    /** The operations of the questions that wait for [runId], as the answerer sees them. */
    private fun pendingOpIds(runId: String): List<String> {
        val answer = client(ANSWERER_TOKEN).pendingInputs(runId)
        val answered = assertInstanceOf(ServiceAnswer.Answered::class.java, answer, "the list was answered: $answer")
        @Suppress("UNCHECKED_CAST")
        return (answered.value as List<ServiceInputQuestion>).map { it.opId }
    }

    /**
     * The question of [runId], asked for until the run has published it.
     */
    private fun awaitQuestion(runId: String): ServiceInputQuestion {
        val deadline = System.currentTimeMillis() + QUESTION_DEADLINE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val answer = client(ANSWERER_TOKEN).pendingInputs(runId)
            if (answer is ServiceAnswer.Answered) {
                @Suppress("UNCHECKED_CAST")
                val questions = answer.value as List<ServiceInputQuestion>
                if (questions.isNotEmpty()) return questions.single()
            }
            Thread.sleep(POLL_MILLIS)
        }
        error("the run $runId published no question in time\n${childLogs()}")
    }

    /**
     * The terminal observation of [runId], asked for until it arrives.
     */
    private fun awaitTerminal(runId: String): RuntimeObservation.Terminal {
        val deadline = System.currentTimeMillis() + TERMINAL_DEADLINE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            when (val answer = client(ANSWERER_TOKEN).runState(runId)) {
                is ServiceAnswer.Answered -> {
                    val observation = answer.value
                    if (observation is RuntimeObservation.Terminal) return observation
                }

                else -> Unit
            }
            Thread.sleep(POLL_MILLIS)
        }
        error("the run $runId did not finish in time\n${childLogs()}")
    }

    /** A client that presents [token]. */
    private fun client(token: String): ServiceClient = ServiceClient(
        endpoint = socket,
        credential = token,
        deadline = java.time.Duration.ofSeconds(240),
        schema = dev.rubentxu.pipeline.v2.service.ServiceProtocol.SCHEMA,
    )

    /** Starts the run of [script] as the answerer, who may also start. */
    private fun startRun(script: String): ServiceRunStart {
        val answer = client(ANSWERER_TOKEN).startRun(script)
        val answered = assertInstanceOf(
            ServiceAnswer.Answered::class.java,
            answer,
            "the start of $script was answered: $answer\n${childLogs()}",
        )
        return assertInstanceOf(ServiceRunStart::class.java, answered.value)
    }

    /** What the children left behind: the operator's own log of why a child ended. */
    private fun childLogs(): String {
        if (!Files.exists(supervisionRoot)) return "no child was launched"
        return Files.walk(supervisionRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .map { path ->
                    val text = runCatching { Files.readString(path) }.getOrDefault("unreadable")
                    "${path.fileName}:\n${text.take(CHILD_LOG_CHARS)}"
                }
                .sorted()
                .toList()
                .joinToString("\n")
        }
    }

    /**
     * The authorizations this service knows.
     *
     * One token that may only read, one that may read, start and cancel but
     * not answer, and one that may read, start and answer.
     */
    private fun authorizations(): ServiceAuthorizations = ServiceAuthorizations.of(
        listOf(
            ServiceAuthorization(READER_TOKEN, "reader", ServiceScope.All),
            ServiceAuthorization(
                CANCELLER_TOKEN,
                "canceller",
                ServiceScope.WithRights(
                    ServiceScope.All,
                    setOf(ServiceScope.Right.Start, ServiceScope.Right.Cancel),
                ),
            ),
            ServiceAuthorization(
                ANSWERER_TOKEN,
                ANSWERER_LABEL,
                ServiceScope.WithRights(
                    ServiceScope.All,
                    setOf(ServiceScope.Right.Start, ServiceScope.Right.Answer),
                ),
            ),
        ),
    )

    /**
     * The service the verb composes, over a real socket, and the way to stop
     * it or to compose a new one over the same files.
     */
    private inner class Service {

        /** The compositions that were made, the last one being the one that listens. */
        private val composed = mutableListOf<ServeComposition.Composed>()
        private var thread: Thread = launch(compose())

        private fun compose(): ServeComposition.Composed {
            val composition = assertInstanceOf(
                ServeComposition.Composed::class.java,
                composeServiceToServe(
                    substrate = ServeSubstrate(
                        dbPath = db.toString(),
                        dbAbsolute = db,
                        controlRoot = control.toString(),
                        controlAbsolute = control,
                        controlRootPath = control,
                        socketAbsolute = socket,
                        socketPath = socket.toString(),
                    ),
                    policy = ServiceRunDeclaration(
                        scriptsRoot = scripts,
                        workspacesRoot = null,
                        deadlineMillis = RUN_DEADLINE_MILLIS,
                        startDeadlineMillis = START_DEADLINE_MILLIS,
                        maxSupervisedRuns = 2,
                        allowNetwork = false,
                        registry = registry(),
                    ),
                    authorizations = authorizations(),
                    clock = clock,
                    err = PrintStream(errors, true),
                    openings = ServeOpenings(environment = emptyMap()),
                ),
                "the service was composed: ${errors.toString(Charsets.UTF_8)}",
            )
            composed.add(composition)
            return composition
        }

        private fun launch(listener: ServeComposition.Composed): Thread =
            Thread({ listener.server.serve() }, "service-under-test").apply {
                isDaemon = true
                start()
            }

        /** The facts the store holds about a run, in the order the store serves them. */
        fun eventsOf(runId: String): List<DomainEvent> = composed.last().store.eventsFor(runId).toList()

        /**
         * Closes the listener and composes a new service over the same files.
         *
         * The runs the first service started are NOT ended: a restart of the
         * service is not the end of the runs it supervised.
         */
        fun restartServer() {
            composed.last().server.close()
            thread.join(JOIN_MILLIS)
            thread = launch(compose())
        }

        /** Stops the service and the runs it supervises. */
        fun stop() {
            composed.last().server.close()
            thread.join(JOIN_MILLIS)
            composed.asReversed().forEach { it.close() }
        }
    }

    /** The record of the starts the service keeps in [root]. */
    private fun registry(): ServiceStartRegistry =
        when (val read = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))) {
            is ServiceStartRegistryReading.Read -> read.registry
            is ServiceStartRegistryReading.Refused -> error(read.detail)
        }

    /** What the composition printed when it refused. */
    private val errors = ByteArrayOutputStream()

    /** Starts the service the verb composes, over a real socket and with the real supervisor. */
    private fun start(): Service = Service()

    private companion object {
        /** The token of the client that may only read. */
        private const val READER_TOKEN: String = "token-of-the-reader"

        /** The token of the client that may start and cancel runs, but not answer. */
        private const val CANCELLER_TOKEN: String = "token-of-the-canceller"

        /** The token of the client that may start runs and answer their questions. */
        private const val ANSWERER_TOKEN: String = "token-of-the-answerer"

        /** The label the service gives the answerer: the name of the responder in the facts. */
        private const val ANSWERER_LABEL: String = "answerer"

        /** The script of the run that asks. */
        private const val GATE: String = "gate.pipeline.kts"

        /** The question the script asks. */
        private const val QUESTION: String = "Deploy to production?"

        /** What the body of the question echoes. */
        private const val BODY_TEXT: String = "the-body-of-the-question-ran"

        /** How long the test waits for a run to publish its question. */
        private const val QUESTION_DEADLINE_MILLIS: Long = 120_000L

        /** How long the test waits for a run to finish. */
        private const val TERMINAL_DEADLINE_MILLIS: Long = 120_000L

        /** How often the test asks the service again. */
        private const val POLL_MILLIS: Long = 250L

        /** The budget of a run the service starts. */
        private const val RUN_DEADLINE_MILLIS: Long = 240_000L

        /** How long a start may take before the service refuses it. */
        private const val START_DEADLINE_MILLIS: Long = 120_000L

        /** How long the test waits for the listener's thread to end. */
        private const val JOIN_MILLIS: Long = 10_000L

        /** How much of a child's log travels in a failure message. */
        private const val CHILD_LOG_CHARS: Int = 2_000
    }
}
