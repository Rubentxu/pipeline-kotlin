package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceRunStart
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * A run started over a real socket, with a real child process.
 *
 * ## The guarantee under trial
 *
 * That the answer "started" means a run EXISTS: the child took its lease,
 * minted its own run id and printed the receipt that says so, and the run
 * lives in the store the service reads. And that the run does not belong to
 * the connection that asked for it: the client may go away the moment the
 * answer arrives, and the run keeps going and can still be asked about.
 *
 * ## The capacity this serves
 *
 * A client that starts a run over a socket is a client that does not own the
 * run: it asked for it, it was told which run it is, and the run then belongs
 * to the operator. A service that answered "started" for a run that never
 * took its lease would leave that client watching a run that will never
 * answer, and a service whose run died with the connection would turn every
 * disconnect into a lost run.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * The facts under trial are about a MACHINE: a real socket that binds, a real
 * child JVM that compiles a script and takes a lease, a real SQLite store
 * that the child writes and the service reads, and a real client that travels
 * over the wire. A fake port would prove that the supervisor reads a receipt;
 * it would not prove that the product PRINTS one, nor that the run survives
 * the connection that asked for it.
 *
 * ## What this does not freeze
 *
 * How long the run takes, and the words the run prints: the receipt is
 * read by its schema, and everything else the child says is the run's own.
 */
@Timeout(value = 300, unit = java.util.concurrent.TimeUnit.SECONDS)
class ServiceStartIntegrationTest {

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

    private val clock: Clock = FixedClock

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        control = Files.createDirectories(root.resolve("control"))
        scripts = Files.createDirectories(root.resolve("scripts"))
        socket = root.resolve("service.sock")
        Files.writeString(
            scripts.resolve("hello.pipeline.kts"),
            "pipeline {\n    stages {\n        stage(\"eager\") {\n            echo(\"hello\")\n" +
                "        }\n    }\n}\n",
        )
        Files.writeString(
            scripts.resolve("other.pipeline.kts"),
            "pipeline {\n    stages {\n        stage(\"eager\") {\n            echo(\"other\")\n" +
                "        }\n    }\n}\n",
        )
        // The store has to EXIST before the service opens it: the service
        // reads a substrate it was given and never creates one. It also has
        // to be COMPLETE: the service opens its doors read-only, and a door
        // that cannot create what is missing refuses to open. Opening it for
        // writing and closing it again leaves the whole schema in place, and
        // the run that starts later writes into the very same file.
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val writable = (admission as EventStoreAdmission.Admitted).store
        DurableStores.open(writable, control, clock)
        writable.close()
    }

    @Test
    fun `an authorized start runs the pipeline and answers with the run's identity`() {
        val service = start()
        try {
            val answer = client(STARTER_TOKEN).startRun("hello.pipeline.kts")

            val started = assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value
            val run = assertInstanceOf(ServiceRunStart::class.java, started)
            assertTrue(run.runId.isNotBlank(), "the run has an identity of its own")
            assertTrue(run.correlationId.isNotBlank(), "the run is correlatable")
            assertTrue(run.ownerId.isNotBlank(), "the run has an owner")
            assertTrue(run.startedAtMs > 0L, "the run says when it took its ownership")

            val state = client(STARTER_TOKEN).runState(run.runId)
            assertTrue(
                knowsTheRun(state),
                "the run the service started is a run the service can answer about: $state",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `the run outlives the client that asked for it`() {
        val service = start()
        try {
            val answer = client(STARTER_TOKEN).startRun("hello.pipeline.kts")
            val started = assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value
            val run = assertInstanceOf(ServiceRunStart::class.java, started)

            // The client that asked for the run is gone: it asked, it was
            // answered, and it left. The run does not belong to it, and the
            // question that follows travels on another connection.
            val state = client(STARTER_TOKEN).runState(run.runId)
            assertTrue(
                knowsTheRun(state),
                "the run is still there after the client that asked for it went away: $state",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `the same key with the same request answers with the same run`() {
        val service = start()
        try {
            val first = client(STARTER_TOKEN).startRun("hello.pipeline.kts", idempotencyKey = "key-1")
            val second = client(STARTER_TOKEN).startRun("hello.pipeline.kts", idempotencyKey = "key-1")

            val one = assertInstanceOf(ServiceRunStart::class.java, assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                first,
            ).value)
            val two = assertInstanceOf(ServiceRunStart::class.java, assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                second,
            ).value)

            assertEquals(one, two, "a key means one run, and the retry gets the same one")
        } finally {
            service.stop()
        }
    }

    @Test
    fun `the same key with another request is refused`() {
        val service = start()
        try {
            client(STARTER_TOKEN).startRun("hello.pipeline.kts", idempotencyKey = "key-1")

            val answer = client(STARTER_TOKEN).startRun("other.pipeline.kts", idempotencyKey = "key-1")

            val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
            assertEquals(
                ServiceRefusal.IdempotencyConflict("key-1"),
                refusal,
                "a key names a request, so the same key with another request is a conflict",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a client that may not start runs is refused by name`() {
        val service = start()
        try {
            val answer = client(READER_TOKEN).startRun("hello.pipeline.kts")

            val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
            assertEquals(
                ServiceRefusal.StartNotAuthorized,
                refusal,
                "a client that may read but not start is told which right it lacks",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a script that the operator did not admit is refused by name`() {
        val service = start()
        try {
            val answer = client(STARTER_TOKEN).startRun("../../../etc/passwd")

            val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
            assertEquals(
                ServiceRefusal.ScriptNotAdmitted("../../../etc/passwd"),
                refusal,
                "a name that leaves the directory of the scripts is refused, and it is refused by name",
            )
        } finally {
            service.stop()
        }
    }

    /**
     * The service, over a real socket, and the way to stop it.
     */
    private inner class Service(
        private val server: ServiceServer,
        private val store: dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore,
        private val supervisor: ServiceRunSupervisor,
    ) {

        private val thread = Thread({ server.serve() }, "service-under-test").apply {
            isDaemon = true
            start()
        }

        /** Stops the service and the runs it supervises. */
        fun stop() {
            server.close()
            supervisor.close()
            thread.join(10_000L)
            store.close()
        }
    }

    /**
     * Starts the service over a real socket, with the real supervisor.
     */
    private fun start(): Service {
        val admission = admitEventStore(db.toString(), EventStorePurpose.Read)
        val store = (admission as EventStoreAdmission.Admitted).store
        val supervisionRoot = root.resolve("supervisions")
        val registry = when (val read = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))) {
            is ServiceStartRegistryReading.Read -> read.registry
            is ServiceStartRegistryReading.Refused -> error(read.detail)
        }
        val supervisor = ServiceRunSupervisor(
            admission = ServiceStartAdmission(scripts, null),
            runPolicy = ServiceRunPolicy(
                eventStore = db.toAbsolutePath().toString(),
                controlRoot = control.toAbsolutePath().toString(),
                deadlineMillis = 240_000L,
                allowNetwork = false,
            ),
            command = ServiceRunCommand(
                javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                classpath = System.getProperty("java.class.path"),
                mainClass = "dev.rubentxu.pipeline.v2.application.MainKt",
            ),
            port = ProcessDurableTaskRuntime(supervisionRoot, clock),
            registry = registry,
            limits = ServiceStartLimits(maxSupervisedRuns = 2, startDeadlineMillis = 120_000L),
            supervisionRoot = supervisionRoot,
        )
        val operations = ServiceOperations(
            introspection = composeRuntimeIntrospectionPort(
                eventStore = store,
                controlRoot = control,
                clock = clock,
            ),
            substrate = {
                ServiceSubstrate(
                    eventStore = db.toString(),
                    controlRoot = control.toString(),
                    eventStorePresent = Files.exists(db),
                    controlRootPresent = Files.exists(control),
                )
            },
            starts = supervisor,
        )
        val stores = DurableStores.open(store, control, clock)
        val streets = ServiceSubscription(
            lanes = ComposeLanes.forService(store, control),
            events = composeEventRecordReadPort(store, stores.lease),
        )
        val transport = UnixSocketTransport.bind(socket)
        return Service(
            ServiceServer(transport, operations, streets, authorizations()),
            store,
            supervisor,
        )
    }

    /**
     * The authorizations this service knows.
     *
     * Two tokens: one that may read and one that may read AND start. The
     * second one is the one the card talks about, and the first one is the
     * client that must be refused.
     */
    private fun authorizations(): ServiceAuthorizations = ServiceAuthorizations.of(
        listOf(
            ServiceAuthorization(READER_TOKEN, "reader", ServiceScope.All),
            ServiceAuthorization(STARTER_TOKEN, "starter", ServiceScope.MayStart(ServiceScope.All)),
        ),
    )

    @Test
    fun `the run the service started ends up in the store the service reads`() {
        val service = start()
        try {
            val answer = client(STARTER_TOKEN).startRun("hello.pipeline.kts")
            val started = assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value
            val run = assertInstanceOf(ServiceRunStart::class.java, started)

            // The run keeps going after the answer: the answer is the
            // identity of a run that is still in flight. The service is
            // asked about it again and again until it finishes, and the
            // answer it gives then comes out of the store the run wrote.
            val finished = awaitTerminal(run.runId)

            assertInstanceOf(
                TerminalObservation.Succeeded::class.java,
                finished.terminal,
                "the run the service started finished in the store the service reads",
            )
        } finally {
            service.stop()
        }
    }

    /**
     * The terminal observation of [runId], asked for until it arrives.
     */
    private fun awaitTerminal(runId: String): RuntimeObservation.Terminal {
        val deadline = System.currentTimeMillis() + TERMINAL_DEADLINE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            when (val answer = client(STARTER_TOKEN).runState(runId)) {
                is ServiceAnswer.Answered -> {
                    val observation = answer.value
                    if (observation is RuntimeObservation.Terminal) return observation
                }

                else -> Unit
            }
            Thread.sleep(500L)
        }
        error("the run $runId did not finish in time")
    }

    /**
     * Whether the service KNOWS the run the answer is about.
     *
     * A run that is still going is refused with the name of the authority
     * that holds it —the child that took its lease— and a run that finished
     * is answered with its own facts. Both are the service KNOWING the run,
     * and both are different from the refusal that says the run is not
     * there.
     */
    private fun knowsTheRun(answer: ServiceAnswer<*>): Boolean = when (answer) {
        is ServiceAnswer.Answered -> true
        is ServiceAnswer.Refused -> answer.refusal !is ServiceRefusal.UnknownRun
        else -> false
    }

    /**
     * A client that presents [token].
     */
    private fun client(token: String): ServiceClient = ServiceClient(
        endpoint = socket,
        credential = token,
        deadline = java.time.Duration.ofSeconds(240),
        schema = dev.rubentxu.pipeline.v2.service.ServiceProtocol.SCHEMA,
    )

    /** The clock this lane stamps its material with: it does not move. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        /** The token of the client that may only read. */
        private const val READER_TOKEN: String = "token-of-the-reader"

        /** The token of the client that may start runs. */
        private const val STARTER_TOKEN: String = "token-of-the-starter"

        /** How long the test waits for a run to finish. */
        private const val TERMINAL_DEADLINE_MILLIS: Long = 180_000L
    }
}
