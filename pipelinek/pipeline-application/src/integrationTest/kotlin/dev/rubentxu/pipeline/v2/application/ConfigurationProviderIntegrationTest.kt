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
import dev.rubentxu.pipeline.v2.service.ServiceProviderKey
import dev.rubentxu.pipeline.v2.service.ServiceProviderPoll
import dev.rubentxu.pipeline.v2.service.ServiceProviderReference
import dev.rubentxu.pipeline.v2.service.ServiceProviderSession
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceRunConfiguration
import dev.rubentxu.pipeline.v2.service.ServiceRunStart
import dev.rubentxu.pipeline.v2.service.ServiceStreet
import dev.rubentxu.pipeline.v2.service.ServiceStreetItem
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A controller that supplies the configuration of a run, over a real socket and with a real
 * child.
 *
 * ## The guarantee under trial
 *
 * That the values a controller answers are the values the STEPS of the run see, that the run
 * reports where each one came from without ever repeating one, and that a controller which
 * disappears, stays silent or is somebody else's gets a bounded and named refusal instead of a
 * run that started with configuration nobody supplied.
 *
 * ## The capacity this serves
 *
 * An operator who asks a controller for the configuration of a run does not copy a file into
 * the service: the run starts when the controller has answered. A service that started the run
 * anyway when the controller was gone would run the steps under conditions nobody chose, and a
 * service that took the answer of ANOTHER session with the same name would run them under
 * conditions chosen by someone who was not asked.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * The facts are about a MACHINE: a real socket that carries five operations of a session, a
 * second connection per call, a long poll that holds a thread of the service and a real child
 * whose process environment is the one the answer composed. The rules of the session are
 * proved by the unit lane against doubles; whether the five operations compose into a start is
 * only observable here.
 *
 * ## What this does not freeze
 *
 * How long a resolution takes, and the words of the detail of a refusal: a refusal is read by
 * its name.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ConfigurationProviderIntegrationTest {

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
        // The step prints the value of the variable and NOTHING else: a step that found no
        // variable fails, because `printenv` exits with a code that is not zero when the name is
        // not in the environment.
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
    fun `the values a controller answers reach the step and the run says where they came from`() {
        val service = start()
        val pool = Executors.newFixedThreadPool(1)
        try {
            val session = registered(STARTER_TOKEN, "vault", PROFILE_KEY)
            val controller = pool.submit(Callable { answerWhenAsked(session, STARTER_TOKEN, "r7", PROFILE_VALUE) })

            val answer = client(STARTER_TOKEN).startRun(
                script = "config.pipeline.kts",
                idempotencyKey = "key-provider",
                configurationProvider = ServiceProviderReference("vault", listOf("BUILD_PROFILE")),
            )

            val run = started(answer)
            assertTrue(controller.get(60, TimeUnit.SECONDS), "the controller was asked and answered once")
            awaitTerminal(run.runId)

            val output = outputOf(run.runId)
            assertTrue(output.contains("from-vault"), "the step saw the value the controller answered: $output")

            val described = client(READER_TOKEN).runConfiguration(run.runId)
            val configuration = assertInstanceOf(
                ServiceRunConfiguration::class.java,
                assertInstanceOf(ServiceAnswer.Answered::class.java, described).value,
            )
            assertEquals("vault", configuration.provider, "the run says which provider supplied it")
            assertEquals("r7", configuration.revision, "the run says at which revision of the source")
            assertNotNull(configuration.sourceDigest, "the run says the digest of what the source gave")
            assertEquals(
                listOf("BUILD_PROFILE" to "provider"),
                configuration.entries.map { it.name to it.origin },
                "the origin of each key is told",
            )
            assertEquals("string", configuration.entries.single().type, "the type of each key is told")
            assertFalse(
                described.toString().contains("from-vault"),
                "the description of the configuration never carries a value",
            )
        } finally {
            pool.shutdownNow()
            service.stop()
        }
    }

    @Test
    fun `a controller that closes its session while the run waits gets the run refused at once`() {
        val service = start()
        val pool = Executors.newFixedThreadPool(1)
        try {
            val session = registered(STARTER_TOKEN, "vault", PROFILE_KEY)
            val waiting = pool.submit(Callable {
                client(STARTER_TOKEN).startRun(
                    script = "config.pipeline.kts",
                    idempotencyKey = "key-gone",
                    configurationProvider = ServiceProviderReference("vault", listOf("BUILD_PROFILE")),
                )
            })

            awaitAsked(session, STARTER_TOKEN)
            val closedAt = System.nanoTime()
            val closed = client(STARTER_TOKEN).closeProvider(session.sessionId)
            assertInstanceOf(ServiceAnswer.Answered::class.java, closed)

            val refusal = refusalOf(waiting.get(60, TimeUnit.SECONDS))
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closedAt)
            assertEquals(
                "provider-gone",
                assertInstanceOf(ServiceRefusal.RunConfigurationRefused::class.java, refusal).reason,
                "the run is refused by the name of what happened to its controller",
            )
            assertTrue(
                elapsedMillis < GONE_BOUND_MILLIS,
                "the refusal does not wait for the deadline: it took $elapsedMillis ms",
            )
        } finally {
            pool.shutdownNow()
            service.stop()
        }
    }

    @Test
    fun `a controller that never answers gets the run refused when the deadline passes`() {
        val service = start(ConfigurationProviderLimits(resolveDeadlineMillis = SHORT_DEADLINE_MILLIS))
        try {
            registered(STARTER_TOKEN, "vault", PROFILE_KEY)

            val begun = System.nanoTime()
            val answer = client(STARTER_TOKEN).startRun(
                script = "config.pipeline.kts",
                idempotencyKey = "key-silent",
                configurationProvider = ServiceProviderReference("vault", listOf("BUILD_PROFILE")),
            )
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun)

            assertEquals(
                "provider-timeout",
                assertInstanceOf(ServiceRefusal.RunConfigurationRefused::class.java, refusalOf(answer)).reason,
                "a silent controller is a timeout, and the refusal says so",
            )
            assertTrue(
                elapsedMillis >= SHORT_DEADLINE_MILLIS && elapsedMillis < TIMEOUT_BOUND_MILLIS,
                "the wait is the deadline and no longer: it took $elapsedMillis ms",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a session of another credential with the same name is never asked`() {
        val service = start()
        try {
            val other = registered(OTHER_STARTER_TOKEN, "vault", PROFILE_KEY)

            val answer = client(STARTER_TOKEN).startRun(
                script = "config.pipeline.kts",
                idempotencyKey = "key-stranger",
                configurationProvider = ServiceProviderReference("vault", listOf("BUILD_PROFILE")),
            )

            assertEquals(
                "provider-not-registered",
                assertInstanceOf(ServiceRefusal.RunConfigurationRefused::class.java, refusalOf(answer)).reason,
                "the name belongs to the credential that registered it: nobody else gets its answer",
            )
            val poll = client(OTHER_STARTER_TOKEN).pollProvider(other.sessionId, 200L)
            assertEquals(
                ServiceProviderPoll.Idle,
                assertInstanceOf(ServiceAnswer.Answered::class.java, poll).value,
                "the other session was not asked anything",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a key labelled as a secret is not accepted as configuration`() {
        val service = start()
        try {
            val secret = client(STARTER_TOKEN).registerProvider(
                "vault",
                listOf(ServiceProviderKey("BUILD_PROFILE", "string", "secret")),
            )
            val unknown = client(STARTER_TOKEN).registerProvider(
                "vault",
                listOf(ServiceProviderKey("BUILD_PROFILE", "string", "internal")),
            )

            assertEquals(
                "a-secret-is-not-configuration",
                assertInstanceOf(ServiceRefusal.ConfigurationProviderRefused::class.java, refusalOf(secret)).reason,
                "a key that says it is a secret is refused by name",
            )
            assertEquals(
                "sensitivity-label-unknown",
                assertInstanceOf(ServiceRefusal.ConfigurationProviderRefused::class.java, refusalOf(unknown)).reason,
                "a label the service does not know is refused, and it is not read as non-secret",
            )
            val start = client(STARTER_TOKEN).startRun(
                script = "config.pipeline.kts",
                idempotencyKey = "key-refused-provider",
                configurationProvider = ServiceProviderReference("vault", listOf("BUILD_PROFILE")),
            )
            assertEquals(
                "provider-not-registered",
                assertInstanceOf(ServiceRefusal.RunConfigurationRefused::class.java, refusalOf(start)).reason,
                "a registration that was refused registered nothing",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a client that may not start runs cannot open a session`() {
        val service = start()
        try {
            val answer = client(READER_TOKEN).registerProvider("vault", listOf(PROFILE_KEY))

            assertEquals(
                ServiceRefusal.StartNotAuthorized,
                refusalOf(answer),
                "opening a session is a right of the credentials that may start runs",
            )
        } finally {
            service.stop()
        }
    }

    /** The session [token] opened for [provider], which has to be a success. */
    private fun registered(token: String, provider: String, vararg keys: ServiceProviderKey): ServiceProviderSession =
        assertInstanceOf(
            ServiceProviderSession::class.java,
            assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                client(token).registerProvider(provider, keys.toList()),
            ).value,
        )

    /**
     * Polls [session] until a run asks for values, then answers [value] for the one key at
     * [revision]. Whether it answered is what it returns.
     */
    private fun answerWhenAsked(session: ServiceProviderSession, token: String, revision: String, value: String): Boolean {
        val asked = awaitAsked(session, token)
        val answered = client(token).answerProvider(
            session.sessionId,
            asked.requestId,
            revision,
            asked.keys.associateWith { value },
        )
        return answered is ServiceAnswer.Answered
    }

    /** Polls [session] until a run asks for values. */
    private fun awaitAsked(session: ServiceProviderSession, token: String): ServiceProviderPoll.Asked {
        val deadline = System.currentTimeMillis() + ASKED_DEADLINE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val poll = client(token).pollProvider(session.sessionId, 1_000L)
            if (poll is ServiceAnswer.Answered) {
                val state = poll.value
                if (state is ServiceProviderPoll.Asked) return state
            }
        }
        error("nobody asked the session ${session.sessionId} for values")
    }

    /** The refusal [answer] is, which it has to be. */
    private fun refusalOf(answer: ServiceAnswer<*>): ServiceRefusal =
        assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal

    /** The run the answer names. */
    private fun started(answer: ServiceAnswer<ServiceRunStart>): ServiceRunStart =
        assertInstanceOf(
            ServiceRunStart::class.java,
            assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value,
        )

    /** The stdout of [runId], read over the socket. */
    private fun outputOf(runId: String): String {
        val answer = client(STARTER_TOKEN).output(runId)
        val street = assertInstanceOf(
            ServiceStreet::class.java,
            assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value,
        )
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

    /** The terminal observation of [runId], asked for until it arrives. */
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

    /** The service, over a real socket, and the way to stop it. */
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

    /** Starts the service over a real socket, with the real supervisor and the sessions. */
    private fun start(limits: ConfigurationProviderLimits = ConfigurationProviderLimits()): Service {
        val admission = admitEventStore(db.toString(), EventStorePurpose.Read)
        val store = (admission as EventStoreAdmission.Admitted).store
        val supervisionRoot = root.resolve("supervisions")
        val registry = when (val read = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))) {
            is ServiceStartRegistryReading.Read -> read.registry
            is ServiceStartRegistryReading.Refused -> error(read.detail)
        }
        val providers = ConfigurationProviderSessions(limits = limits)
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
            configurations = providers,
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
            providers = providers,
            configurations = supervisor,
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
     * The authorizations this service knows: a client that only reads and two that may also
     * start runs, and so open provider sessions.
     */
    private fun authorizations(): ServiceAuthorizations = ServiceAuthorizations.of(
        listOf(
            ServiceAuthorization(READER_TOKEN, "reader", ServiceScope.All),
            ServiceAuthorization(
                STARTER_TOKEN,
                "starter",
                ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start)),
            ),
            ServiceAuthorization(
                OTHER_STARTER_TOKEN,
                "other-starter",
                ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start)),
            ),
        ),
    )

    /** A client that presents [token]. */
    private fun client(token: String): ServiceClient = ServiceClient(
        endpoint = socket,
        credential = token,
        deadline = java.time.Duration.ofSeconds(240),
        schema = ServiceProtocol.SCHEMA,
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

        /** The token of another client that may start runs. */
        private const val OTHER_STARTER_TOKEN: String = "token-of-the-other-starter"

        /** The value the controller answers. */
        private const val PROFILE_VALUE: String = "from-vault"

        /** The one key the controllers of this lane declare. */
        private val PROFILE_KEY = ServiceProviderKey("BUILD_PROFILE", "string", "non-secret")

        /** How long the test waits for a run to finish. */
        private const val TERMINAL_DEADLINE_MILLIS: Long = 180_000L

        /** How long the test waits for a run to ask a session for values. */
        private const val ASKED_DEADLINE_MILLIS: Long = 60_000L

        /** The deadline of the service when the controller is silent. */
        private const val SHORT_DEADLINE_MILLIS: Long = 1_500L

        /** The longest a refusal for a silent controller may take, deadline included. */
        private const val TIMEOUT_BOUND_MILLIS: Long = 20_000L

        /** The longest a refusal for a closed session may take after the close. */
        private const val GONE_BOUND_MILLIS: Long = 5_000L
    }
}
