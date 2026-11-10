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
import dev.rubentxu.pipeline.v2.service.ServiceRunStart
import dev.rubentxu.pipeline.v2.service.ServiceStreet
import dev.rubentxu.pipeline.v2.service.ServiceStreetItem
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The configuration of a run, over a real socket and with real children.
 *
 * ## The guarantee under trial
 *
 * That the configuration a client delivers is the configuration the STEPS of
 * that run see, and that two runs that carry two configurations do not mix
 * them. The unit lane proves that the composition of the environment is a
 * pure function of the layers; this lane proves that the value travels the
 * whole way —the wire, the snapshot on the disk, the child, the process of
 * the step— and arrives where the client asked for it.
 *
 * ## The capacity this serves
 *
 * A client that starts a run with a configuration is a client that asked for
 * the run to be executed under some conditions. A service that dropped the
 * configuration on the way would answer "started" and run the steps under
 * conditions nobody asked for, and the operator would read the answer as a
 * promise. The echo of the answer is the first half of that promise; this
 * lane is the second one.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * Because the facts are about a MACHINE: a real socket, a real child JVM
 * that reads a real file, and a real process whose environment is the one the
 * configuration composed. A fake port would prove that the supervisor writes
 * a file; it would not prove that anybody READS it.
 *
 * ## What this does not freeze
 *
 * How long the runs take, and the words around the step's output: the
 * observation is the step's own stdout, and everything else the run prints
 * is the run's own.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class RunConfigurationIntegrationTest {

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
        // The step prints the value of the variable and NOTHING else: a step
        // that found no variable would fail, because `printenv` exits with a
        // code that is not zero when the name is not in the environment.
        Files.writeString(
            scripts.resolve("config.pipeline.kts"),
            "pipeline {\n    stages {\n        stage(\"eager\") {\n" +
                "            sh(\"printenv BUILD_PROFILE\")\n        }\n    }\n}\n",
        )
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val writable = (admission as EventStoreAdmission.Admitted).store
        DurableStores.open(writable, control, clock)
        writable.close()
    }

    @Test
    fun `two runs with two configurations each see their own`() {
        val service = start()
        try {
            val pool = Executors.newFixedThreadPool(2)
            try {
                val alpha = pool.submit<ServiceAnswer<ServiceRunStart>> {
                    client(STARTER_TOKEN).startRun(
                        script = "config.pipeline.kts",
                        idempotencyKey = "key-alpha",
                        configuration = mapOf("BUILD_PROFILE" to "alpha"),
                    )
                }
                val beta = pool.submit<ServiceAnswer<ServiceRunStart>> {
                    client(STARTER_TOKEN).startRun(
                        script = "config.pipeline.kts",
                        idempotencyKey = "key-beta",
                        configuration = mapOf("BUILD_PROFILE" to "beta"),
                    )
                }

                val one = started(alpha.get(240, TimeUnit.SECONDS))
                val two = started(beta.get(240, TimeUnit.SECONDS))

                assertEquals(
                    mapOf("BUILD_PROFILE" to "alpha"),
                    one.configuration,
                    "the answer of the first run echoes the configuration of the first request",
                )
                assertEquals(
                    mapOf("BUILD_PROFILE" to "beta"),
                    two.configuration,
                    "the answer of the second run echoes the configuration of the second request",
                )

                awaitTerminal(one.runId)
                awaitTerminal(two.runId)

                val firstOutput = outputOf(one.runId)
                val secondOutput = outputOf(two.runId)

                assertTrue(
                    firstOutput.contains("alpha"),
                    "the first run's step saw the value of the first configuration: $firstOutput",
                )
                assertFalse(
                    firstOutput.contains("beta"),
                    "the first run's step did not see the value of the other run: $firstOutput",
                )
                assertTrue(
                    secondOutput.contains("beta"),
                    "the second run's step saw the value of the second configuration: $secondOutput",
                )
                assertFalse(
                    secondOutput.contains("alpha"),
                    "the second run's step did not see the value of the other run: $secondOutput",
                )
            } finally {
                pool.shutdownNow()
            }
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a configuration that breaks a rule is refused by name over the wire`() {
        val service = start()
        try {
            val answer = client(STARTER_TOKEN).startRun(
                script = "config.pipeline.kts",
                idempotencyKey = "key-denied",
                configuration = mapOf("LD_PRELOAD" to "/tmp/evil.so"),
            )

            val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
            val named = assertInstanceOf(ServiceRefusal.RunConfigurationRefused::class.java, refusal)
            assertEquals(
                "the-configuration-name-is-denied",
                named.reason,
                "the wire carries the NAME of the rule the configuration broke",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a configuration that breaks no rule but does not fit the line is refused by the transport`() {
        // The rules bound the CHARACTER count, and the wire escapes: a value
        // made of control characters travels as six bytes per character. The
        // biggest configuration that breaks no rule is bigger than the line
        // the protocol bounds, and the refusal that says so is the
        // transport's —it says nothing about the configuration, and that is
        // what the client is told.
        val service = start()
        try {
            val maximal = (1..32).associate { index ->
                "K$index".padEnd(64, 'K') to "".repeat(512)
            }
            val answer = client(STARTER_TOKEN).startRun(
                script = "config.pipeline.kts",
                idempotencyKey = "key-too-large",
                configuration = maximal,
            )

            val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
            assertEquals(
                ServiceRefusal.RequestTooLarge,
                refusal,
                "the transport refuses the line it cannot carry, and its word is its own",
            )
        } finally {
            service.stop()
        }
    }

    /**
     * The run the answer names.
     */
    private fun started(answer: ServiceAnswer<ServiceRunStart>): ServiceRunStart =
        assertInstanceOf(ServiceRunStart::class.java, assertInstanceOf(
            ServiceAnswer.Answered::class.java,
            answer,
        ).value)

    /**
     * The stdout of [runId], read over the socket.
     */
    private fun outputOf(runId: String): String {
        val answer = client(STARTER_TOKEN).output(runId)
        val street = assertInstanceOf(ServiceStreet::class.java, assertInstanceOf(
            ServiceAnswer.Answered::class.java,
            answer,
        ).value)
        val text = StringBuilder()
        street.use {
            while (true) {
                when (val item = it.next()) {
                    is ServiceStreetItem.Record -> {
                        val document = item.document
                        val channel = document["channel"]?.jsonPrimitive?.content
                        val line = document["text"]?.jsonPrimitive?.content
                        if (channel == "stdout" && line != null) text.append(line).append('\n')
                    }

                    is ServiceStreetItem.End -> return text.toString()
                    is ServiceStreetItem.Refused -> error("the street was refused: ${item.refusal}")
                    null -> return text.toString()
                }
            }
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
            store,
            supervisor,
        )
    }

    /**
     * The authorizations this service knows.
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
