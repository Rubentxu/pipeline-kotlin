package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceRunCancel
import dev.rubentxu.pipeline.v2.service.ServiceRunStart
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
 * A run cancelled over a real socket, with a real child process.
 *
 * ## The guarantee under trial
 *
 * That a cancellation CLOSES the run and does not merely kill it: the child is
 * ended, the terminal is written in the durable facts, and the run is
 * terminal for every reader afterwards. And that the cancellation touches ONE
 * run: a run that goes on next to it is untouched.
 *
 * ## The capacity this serves
 *
 * A client that starts a run over a socket has no other way to stop it: the
 * run outlives the connection that asked for it, and the command line is a
 * different product with a different boundary. A cancellation that killed the
 * process without writing the terminal would leave the run with no end at
 * all, and the client would read an EOF where the product guarantees a fact.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * Because the facts under trial are about a MACHINE: a real child JVM that
 * holds the run's lease, a real OS lock that the cancellation has to cross
 * only after the child is gone, and a real SQLite store that the service
 * writes the terminal into. A fake port would prove that the service asks
 * the port; it would not prove that the port MAY be asked.
 *
 * ## What this does not freeze
 *
 * How long the child takes to die, and the words of the run's outcome: the
 * run's outcome vocabulary is the product's, and a cancelled run reaches the
 * terminal the product gives to a run that reached no outcome of its own.
 *
 * ## What a CRASH means in this lane
 *
 * The service lives in the JVM of this test, so it cannot die the way a
 * process dies. What a crash leaves behind is therefore what this lane
 * reproduces: the durable claim of a key, and no answer for it. The record
 * of the starts is the product's own file and the claim is taken through the
 * product's own registry, so the service that finds it behaves exactly as it
 * would after a restart.
 */
@Timeout(value = 300, unit = java.util.concurrent.TimeUnit.SECONDS)
class ServiceCancelIntegrationTest {

    @TempDir
    lateinit var root: Path

    /** The store the service reads, writes and the run writes. */
    private lateinit var db: Path

    /** The control root the service reads and the run writes. */
    private lateinit var control: Path

    /** The directory of the scripts a client may name. */
    private lateinit var scripts: Path

    /** The socket the service listens on. */
    private lateinit var socket: Path

    /**
     * The directory of the children the service launched: the result the
     * port writes and the error output the child leaves.
     */
    private val supervisionRoot: Path get() = root.resolve("supervisions")

    /**
     * The clock this lane stamps its material with: it MOVES.
     *
     * A cancel says WHEN the run reached its terminal, and a terminal
     * stamped at the epoch would be a moment that never happened. The
     * run's own writer uses the machine's clock, so this lane does too.
     */
    private val clock: Clock = SystemClock()

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        control = Files.createDirectories(root.resolve("control"))
        scripts = Files.createDirectories(root.resolve("scripts"))
        socket = root.resolve("service.sock")
        Files.writeString(
            scripts.resolve("sleeper.pipeline.kts"),
            "pipeline {\n    stages {\n        stage(\"sleeper\") {\n            sleep(300)\n" +
                "        }\n    }\n}\n",
        )
        Files.writeString(
            scripts.resolve("quick.pipeline.kts"),
            "pipeline {\n    stages {\n        stage(\"eager\") {\n            echo(\"hello\")\n" +
                "        }\n    }\n}\n",
        )
        // The store has to EXIST before the service opens it: the service
        // reads a substrate it was given and never creates one. It also has
        // to be COMPLETE: the service opens its doors read-write when it may
        // cancel runs, and a door that cannot create what is missing refuses
        // to open.
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val writable = (admission as EventStoreAdmission.Admitted).store
        DurableStores.open(writable, control, clock)
        writable.close()
    }

    @Test
    fun `the cancel of a live run closes it and the terminal is a durable fact`() {
        val service = start()
        try {
            val run = startRun(CANCELLER_TOKEN, "sleeper.pipeline.kts")

            val answer = client(CANCELLER_TOKEN).cancelRun(run.runId)

            val cancelled = assertInstanceOf(ServiceRunCancel::class.java, assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                answer,
            ).value)
            assertEquals(run.runId, cancelled.runId, "the answer is about the run that was closed")
            assertEquals(
                "cancelled",
                cancelled.outcome,
                "the answer says that THIS call closed the run",
            )
            assertTrue(cancelled.terminalAtMs > 0L, "the answer says when the run reached its terminal")

            val state = client(CANCELLER_TOKEN).runState(run.runId)
            val observation = assertInstanceOf(
                RuntimeObservation.Terminal::class.java,
                assertInstanceOf(ServiceAnswer.Answered::class.java, state).value,
                "the cancelled run is terminal for every reader afterwards: $state",
            )
            assertTrue(
                observation.terminalAtMs > 0L,
                "the terminal the reader sees is the one the facts carry",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `the cancel of a live run leaves the run next to it alone`() {
        val service = start()
        try {
            val cancelled = startRun(CANCELLER_TOKEN, "sleeper.pipeline.kts")
            val survivor = startRun(CANCELLER_TOKEN, "sleeper.pipeline.kts")

            client(CANCELLER_TOKEN).cancelRun(cancelled.runId)

            val state = client(CANCELLER_TOKEN).runState(survivor.runId)
            assertInstanceOf(
                ServiceAnswer.Refused::class.java,
                state,
                "the run next to the cancelled one is still published by its own child: $state",
            )
            val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, state).refusal
            assertTrue(
                refusal is ServiceRefusal.HeldByAnother,
                "the service says WHO holds the run that is still alive: $refusal",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a repeated cancel is answered with the terminal that is already there`() {
        val service = start()
        try {
            val run = startRun(CANCELLER_TOKEN, "sleeper.pipeline.kts")

            val first = client(CANCELLER_TOKEN).cancelRun(run.runId)
            System.err.println("DIAG events after first cancel: " + service.eventsOf(run.runId).toList())
            val second = client(CANCELLER_TOKEN).cancelRun(run.runId)
            System.err.println("DIAG events after second cancel: " + service.eventsOf(run.runId).toList())

            val one = assertInstanceOf(ServiceRunCancel::class.java, assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                first,
            ).value)
            val two = assertInstanceOf(ServiceRunCancel::class.java, assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                second,
            ).value)
            assertEquals("cancelled", one.outcome, "the first call closed the run")
            assertEquals(
                "already-cancelled",
                two.outcome,
                "a cancellation repeated is an answer and not an error; the facts are " +
                    service.eventsOf(run.runId),
            )
            assertEquals(
                one.terminalAtMs,
                two.terminalAtMs,
                "both calls read the SAME terminal: a run is closed once and not twice",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a client that may not cancel runs is refused by name`() {
        val service = start()
        try {
            val answer = client(READER_TOKEN).cancelRun("run-1")

            val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
            assertEquals(
                ServiceRefusal.CancelNotAuthorized,
                refusal,
                "a client that may read but not cancel is told which right it lacks",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `the cancel of a run nobody knows is refused by name`() {
        val service = start()
        try {
            val answer = client(CANCELLER_TOKEN).cancelRun("a-run-nobody-started")

            val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
            assertEquals(
                "the-run-is-unknown",
                (refusal as ServiceRefusal.RunCancelRefused).reason,
                "a cancel may not invent a run, and the refusal says so by name",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `the cancel of a run that already finished is refused by name`() {
        val service = start()
        try {
            val run = startRun(CANCELLER_TOKEN, "quick.pipeline.kts")
            awaitTerminal(run.runId)

            val answer = client(CANCELLER_TOKEN).cancelRun(run.runId)

            val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
            assertEquals(
                "the-run-is-already-terminal",
                (refusal as ServiceRefusal.RunCancelRefused).reason,
                "a run that reached a terminal of its own is not cancelled again",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a start whose effect is unknown is not started again after the service dies`() {
        // What a service that DIED leaves behind: the claim of a key, and no
        // answer for it. The claim is written before the child is launched,
        // so a service that dies in that window leaves a key whose effect is
        // unknown, and the run it may have started may still be alive.
        claimTheKey("key-1")

        val service = start()
        try {
            val answer = client(CANCELLER_TOKEN).startRun("sleeper.pipeline.kts", idempotencyKey = "key-1")

            val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
            assertEquals(
                "the-effect-of-this-key-is-unknown",
                (refusal as ServiceRefusal.RunStartRefused).reason,
                "the run may still be alive, and a second run for the same key is exactly " +
                    "what the key promises there is not",
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

        /** The facts the store holds about a run, as the store serves them. */
        fun eventsOf(runId: String): List<Any> = store.eventsFor(runId).toList()

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
        val admission = admitEventStore(db.toString(), EventStorePurpose.Write)
        val store = (admission as EventStoreAdmission.Admitted).store
        val supervisionRoot = this.supervisionRoot
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
            cancellations = ServiceRunCancellation(
                ender = supervisor,
                control = composeRuntimeControlPort(
                    eventStore = store,
                    controlRoot = control,
                    clock = clock,
                    ownerLabel = "service-cancel",
                ),
            ),
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
     * One token that may only read, and one that may read, start and cancel.
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
        ),
    )

    /**
     * Starts the run of [script] and returns its identity.
     */
    private fun startRun(token: String, script: String, key: String? = null): ServiceRunStart {
        val answer = client(token).startRun(script, idempotencyKey = key)
        val answered = assertInstanceOf(
            ServiceAnswer.Answered::class.java,
            answer,
            "the start of $script was answered: $answer\n${childLogs()}",
        )
        return assertInstanceOf(ServiceRunStart::class.java, answered.value)
    }

    /**
     * What the children left behind: the operator's own log of why a child
     * ended before it owned itself.
     */
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
     * Leaves in the record of the starts what a service that DIED leaves.
     *
     * The claim is taken through the registry itself, with the key and the
     * fingerprint derived the way the service derives them, so the file is
     * exactly the file the service would have written: a claim with no run
     * and no answer.
     */
    private fun claimTheKey(key: String) {
        val registry = when (val read = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))) {
            is ServiceStartRegistryReading.Read -> read.registry
            is ServiceStartRegistryReading.Refused -> error(read.detail)
        }
        val credentialDigest = requireNotNull(authorizations().digestOfAuthorized(CANCELLER_TOKEN)) {
            "the client of this lane presents a token the service knows"
        }
        val claim = registry.claim(
            key = ServiceRunSupervisor.registryKeyOf(credentialDigest, key),
            digest = ServiceRunSupervisor.digestOf(SLEEPER, null),
            correlationId = "the-correlation-of-the-lost-start",
        )
        assertInstanceOf(
            ServiceStartClaim.Owner::class.java,
            claim,
            "the key was free, and the claim took it",
        )
    }

    /**
     * The terminal observation of [runId], asked for until it arrives.
     */
    private fun awaitTerminal(runId: String): RuntimeObservation.Terminal {
        val deadline = System.currentTimeMillis() + TERMINAL_DEADLINE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            when (val answer = client(CANCELLER_TOKEN).runState(runId)) {
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
     * A client that presents [token].
     */
    private fun client(token: String): ServiceClient = ServiceClient(
        endpoint = socket,
        credential = token,
        deadline = java.time.Duration.ofSeconds(240),
        schema = dev.rubentxu.pipeline.v2.service.ServiceProtocol.SCHEMA,
    )

    private companion object {
        /** The token of the client that may only read. */
        private const val READER_TOKEN: String = "token-of-the-reader"

        /** The token of the client that may start and cancel runs. */
        private const val CANCELLER_TOKEN: String = "token-of-the-canceller"

        /** How long the test waits for a run to finish. */
        private const val TERMINAL_DEADLINE_MILLIS: Long = 120_000L

        /** How much of a child's log travels in a failure message. */
        private const val CHILD_LOG_CHARS: Int = 2_000

        /** The script of the run that sleeps. */
        private const val SLEEPER: String = "sleeper.pipeline.kts"
    }
}
