package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.output.OutputTailState
import dev.rubentxu.pipeline.v2.runtime.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceEndReason
import dev.rubentxu.pipeline.v2.service.ServiceRunStart
import dev.rubentxu.pipeline.v2.service.ServiceStreet
import dev.rubentxu.pipeline.v2.service.ServiceStreetItem
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The run whose child the service had to stop.
 *
 * ## The guarantee under trial
 *
 * That a run whose child CANNOT write its own terminal still ENDS: the
 * service closes it, and the terminal says why it was closed.
 *
 * ## Why this guarantee needs a machine
 *
 * Because the child is killed: the watchdog ends it with a signal that gives
 * the JVM no chance to write anything, so the bookend that closes a run is a
 * fact the CHILD cannot write. The run is left open —no terminal in the
 * store, no end to the follow, no answer to a client that waits for one— and
 * the client waits for ever. The service is the only thing alive that can
 * write that fact, and this lane checks that it does.
 *
 * ## Why the row is quick and DETERMINISTIC
 *
 * Because the budget of the run is the instrument: a budget of twenty seconds
 * and a step that sleeps a minute make the watchdog kill the child while the
 * step is still running, every time, with or without a busy machine. The
 * lane does not wait for a hang to happen: it MAKES the child die and then
 * asks the product whether the run ended.
 *
 * ## What this lane does NOT freeze
 *
 * How long the child takes to reach its first step: the watchdog may kill it
 * before the step starts, and the terminal says the same thing either way.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SupervisedRunEndIntegrationTest {

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
        // The step outlives the budget of the run on purpose: the watchdog
        // has to kill the child while this step is still running.
        Files.writeString(
            scripts.resolve("slow.pipeline.kts"),
            "pipeline {\n    stages {\n        stage(\"slow\") {\n" +
                "            sh(\"sleep 60\")\n        }\n    }\n}\n",
        )
        // The same step, but one that WRITES before it hangs: the stream of
        // its output is open when the watchdog kills the child, and an open
        // stream is what the follower of the output waits on.
        Files.writeString(
            scripts.resolve("chatty.pipeline.kts"),
            "pipeline {\n    stages {\n        stage(\"chatty\") {\n" +
                "            sh(\"echo $MARKER; sleep 60\")\n        }\n    }\n}\n",
        )
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val writable = (admission as EventStoreAdmission.Admitted).store
        DurableStores.open(writable, control, clock)
        writable.close()
    }

    @Test
    fun `a run whose child outlives the budget ends with a terminal that says why`() {
        val service = start()
        try {
            val answer = client(STARTER_TOKEN).startRun(
                script = "slow.pipeline.kts",
                idempotencyKey = "key-slow",
                configuration = emptyMap(),
            )
            val started = assertInstanceOf(ServiceRunStart::class.java, assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                answer,
            ).value)

            // The run is asked about until it ends. The wait is the budget of
            // the run plus the room the closing takes: a wait SHORTER than
            // the budget would give up while the product is still working,
            // and a wait LONGER than it would measure the row's patience.
            val terminal = awaitTerminal(started.runId)

            assertEquals(
                TerminalObservation.Aborted::class,
                terminal.terminal::class,
                "the run that the watchdog stopped ended as aborted, and not as a run that " +
                    "is still open: ${describe(started.runId)}",
            )

            val reason = reasonOf(started.runId)
            assertTrue(
                reason.contains("budget"),
                "the terminal of the run says WHY it was closed, and the why names the budget " +
                    "of the run: '$reason'",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `the output of a run whose child was killed ends with the run and is sealed`() {
        val service = start()
        try {
            val answer = client(STARTER_TOKEN).startRun(
                script = "chatty.pipeline.kts",
                idempotencyKey = "key-chatty",
                configuration = emptyMap(),
            )
            val started = assertInstanceOf(ServiceRunStart::class.java, assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                answer,
            ).value)

            val followed = followOutput(started.runId)

            assertTrue(
                followed.stdout.contains(MARKER),
                "the child wrote '$MARKER' before the watchdog killed it, so the stream of its " +
                    "output existed and was open: stdout=${followed.stdout}, ${describe(started.runId)}",
            )
            val end = assertInstanceOf(
                ServiceStreetItem.End::class.java,
                followed.last,
                "the output of a run the watchdog stopped ENDS with the run; a street that is " +
                    "cut instead is the follower waiting for a seal nobody writes: " +
                    "${followed.last}, ${describe(started.runId)}",
            )
            assertEquals(ServiceEndReason.TerminalObserved, end.reason)

            val terminal = terminalFactOf(started.runId)
            assertEquals(
                dev.rubentxu.pipeline.v2.domain.RunOutcome.ABORTED_TOKEN,
                terminal?.outcome,
                "the end of the street says the run ended, and the run's own terminal says how",
            )

            val seals = sealStatesOf(started.runId)
            assertTrue(
                seals.isNotEmpty(),
                "the child wrote to stdout, so the plane holds a stream of this run; a row " +
                    "that finds none is measuring nothing",
            )
            val open = seals.filterValues { sealed -> !sealed }.keys
            assertTrue(
                open.isEmpty(),
                "a run that holds its terminal holds every stream of its output sealed too; " +
                    "these are still open: $open",
            )
        } finally {
            service.stop()
        }
    }

    /**
     * What the output street of a run carried, and how the street ended.
     *
     * @property stdout the lines of stdout the street carried.
     * @property last the item the street ended on, or `null` when the street
     *   was cut without an end.
     */
    private class FollowedOutput(val stdout: String, val last: ServiceStreetItem?)

    /**
     * Follows the output of [runId] until the street ends or is cut.
     *
     * The client's deadline is the room the street gets between two lines:
     * the budget of the run plus the room its closing takes, so a street that
     * is still open after that is a street nobody will close.
     */
    private fun followOutput(runId: String): FollowedOutput {
        val answer = client(STARTER_TOKEN, STREET_DEADLINE).output(runId)
        val street = assertInstanceOf(ServiceStreet::class.java, assertInstanceOf(
            ServiceAnswer.Answered::class.java,
            answer,
        ).value)
        val text = StringBuilder()
        street.use {
            while (true) {
                val item = it.next()
                if (item is ServiceStreetItem.Record) {
                    val document = item.document
                    val channel = document["channel"]?.jsonPrimitive?.content
                    val line = document["text"]?.jsonPrimitive?.content
                    if (channel == "stdout" && line != null) text.append(line).append('\n')
                } else {
                    return FollowedOutput(text.toString(), item)
                }
            }
        }
    }

    /**
     * Every stream of [runId], and whether the output plane holds it sealed.
     *
     * The plane is opened anew, as a second process would open it: a reader
     * that already looked at this run keeps the list of streams it saw then,
     * and the child of the run declares its streams from another process. A
     * stream the plane cannot answer about counts as unsealed: the claim
     * that the bytes are complete needs the seal, not the absence of a
     * complaint.
     */
    private fun sealStatesOf(runId: String): Map<String, Boolean> {
        OutputPlaneProvider.forget(control)
        val plane = OutputPlaneProvider.storeForReading(control)
        return plane.frameIndex().streamsOfRun(runId)
            .associate { stream -> stream.toString() to (plane.tailState(stream) is OutputTailState.Sealed) }
    }

    /**
     * The terminal of [runId], asked for until it arrives.
     *
     * The wait is the budget of the run plus the room the closing of the run
     * takes: the product promises that a run ends within its budget, and a
     * row that waits less than that measures its own impatience.
     */
    private fun awaitTerminal(runId: String): RuntimeObservation.Terminal {
        val deadline = System.currentTimeMillis() + TERMINAL_DEADLINE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            when (val answer = client(READER_TOKEN).runState(runId)) {
                is ServiceAnswer.Answered -> {
                    val observation = answer.value
                    if (observation is RuntimeObservation.Terminal) return observation
                }

                else -> Unit
            }
            Thread.sleep(POLL_MILLIS)
        }
        error("the run $runId did not finish in time: ${describe(runId)}")
    }

    /**
     * The reason the run was closed, as the run's own terminal says it.
     */
    private fun reasonOf(runId: String): String {
        val terminal = terminalFactOf(runId)
        return terminal?.diagnostics?.joinToString(" | ") { it.message }.orEmpty()
    }

    /**
     * The terminal fact of the run, read from the run's own store.
     */
    private fun terminalFactOf(runId: String): RunFinished? =
        store.eventsFor(runId).filterIsInstance<RunFinished>().lastOrNull()

    /**
     * What the product says about the run, for a reader that has to say WHY.
     *
     * It is the run's own facts: the terminal it holds, the lease that says
     * whether its child is alive, and the journal that says how far it got.
     */
    private fun describe(runId: String): String {
        val terminal = terminalFactOf(runId)
        val outcome = terminal?.outcome ?: "no terminal yet"
        val reason = terminal?.diagnostics?.joinToString(" | ") { it.message }.orEmpty()
        val observation = client(READER_TOKEN).runState(runId)
        val state = when (val answered = observation) {
            is ServiceAnswer.Answered -> when (val value = answered.value) {
                is RuntimeObservation.Running ->
                    "the child is alive=${value.leaseHolder?.alive}, " +
                        "it owns the run=${value.leaseHolder != null}, " +
                        "it published ${value.journalPosition.operations} operations"

                is RuntimeObservation.Terminal -> "the run is terminal"
                is RuntimeObservation.LiveButEmpty -> "the run is known but empty: ${value.reason}"
                is RuntimeObservation.Unobservable -> "the run cannot be observed: ${value.reason}"
            }

            is ServiceAnswer.Refused -> "the service refused to answer: ${answered.refusal.kind}"
            is ServiceAnswer.Unreachable -> "the service is not there: ${answered.detail}"
            is ServiceAnswer.Unreadable -> "the answer is not readable: ${answered.detail}"
            ServiceAnswer.TimedOut -> "the service did not answer in time"
        }
        return "outcome=$outcome, reason=$reason, $state"
    }

    /**
     * The store the service reads and the run writes.
     */
    private lateinit var store: dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore

    /**
     * The service, over a real socket, and the way to stop it.
     */
    private inner class Service(
        private val server: ServiceServer,
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
        store = (admission as EventStoreAdmission.Admitted).store
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
                deadlineMillis = RUN_BUDGET_MILLIS,
                allowNetwork = false,
            ),
            command = ServiceRunCommand(
                javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                classpath = System.getProperty("java.class.path"),
                mainClass = "dev.rubentxu.pipeline.v2.application.MainKt",
            ),
            port = ProcessDurableTaskRuntime(supervisionRoot, clock),
            registry = registry,
            limits = ServiceStartLimits(maxSupervisedRuns = 2, startDeadlineMillis = START_DEADLINE_MILLIS),
            supervisionRoot = supervisionRoot,
            closer = EventStoreSupervisedRunCloser(
                db.toAbsolutePath().toString(),
                clock,
                OutputPlaneSupervisedRunOutputSealer(control),
            ),
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
            cancellations = null,
        )
        val stores = DurableStores.open(store, control, clock)
        val streets = ServiceSubscription(
            lanes = ComposeLanes.forService(store, control),
            events = composeEventRecordReadPort(store, stores.lease),
        )
        val transport = UnixSocketTransport.bind(socket)
        return Service(
            ServiceServer(transport, operations, streets, authorizations()),
            supervisor,
        )
    }

    /**
     * The authorizations this service knows.
     *
     * The reader asks about the run and the starter starts it: they are two
     * credentials because they are two rights, and a reader that asked with
     * the credential of the starter would be refused by the service for a
     * reason that has nothing to do with the run.
     */
    private fun authorizations(): ServiceAuthorizations = ServiceAuthorizations.of(
        listOf(
            ServiceAuthorization(READER_TOKEN, "reader", ServiceScope.All),
            ServiceAuthorization(
                STARTER_TOKEN,
                "starter",
                ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start)),
            ),
        ),
    )

    /**
     * A client that presents [token].
     */
    private fun client(
        token: String,
        deadline: java.time.Duration = java.time.Duration.ofSeconds(60),
    ): ServiceClient = ServiceClient(
        endpoint = socket,
        credential = token,
        deadline = deadline,
        schema = dev.rubentxu.pipeline.v2.service.ServiceProtocol.SCHEMA,
    )

    /** The clock this lane stamps its material with: it does not move. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        /** The token of the client that may start runs. */
        private const val STARTER_TOKEN: String = "token-of-the-starter"

        /** The token of the client that may ask about a run. */
        private const val READER_TOKEN: String = "token-of-the-reader"

        /**
         * The budget of the run: the watchdog kills the child when it is over.
         */
        private const val RUN_BUDGET_MILLIS: Long = 20_000L

        /**
         * How long the service waits for the receipt of the child.
         */
        private const val START_DEADLINE_MILLIS: Long = 30_000L

        /**
         * The wait of this lane: the budget of the run plus the room the
         * closing of the run takes.
         */
        private const val TERMINAL_DEADLINE_MILLIS: Long = RUN_BUDGET_MILLIS + 15_000L

        /** How long the lane waits between two questions about the run. */
        private const val POLL_MILLIS: Long = 250L

        /** What the chatty step writes before it hangs: the proof the stream was open. */
        private const val MARKER: String = "marker-before-the-kill"

        /**
         * The room a street gets between two lines: the wait for the terminal
         * plus a margin, so a street still open after it is one nobody closes.
         */
        private val STREET_DEADLINE: java.time.Duration =
            java.time.Duration.ofMillis(TERMINAL_DEADLINE_MILLIS + 10_000L)
    }
}
