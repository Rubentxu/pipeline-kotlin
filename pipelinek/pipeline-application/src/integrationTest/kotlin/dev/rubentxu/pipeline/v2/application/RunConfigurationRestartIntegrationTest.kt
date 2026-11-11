package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceConfigurationEntry
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceRunConfiguration
import dev.rubentxu.pipeline.v2.service.ServiceRunStart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
 * Where the configuration of a run came from, asked of a service that was restarted.
 *
 * ## The guarantee under trial
 *
 * That a client that asks a service for the configuration of a run gets the same answer after
 * the service restarted as before it: the same digest, the same entries, the same origins. The
 * service that answers is NOT the one that started the run, and everything the first one held in
 * memory died with it, so the answer is derived from what the substrate keeps.
 *
 * ## The capacity this serves
 *
 * A run outlives the service that started it, and so does the question about the run. A service
 * that answered "I did not start that run" after a restart would be telling a client that a run
 * it started a moment ago was never there, and the client would take it for a fact about the run
 * when it is a fact about the service's memory.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * Because the restart is a fact about a MACHINE: two services, one after the other, that share a
 * store, a control root and a directory of supervisions on a real disk, and a real child that
 * published the event the second service finds the first one's work through. A double of the
 * store would prove that the locator reads what the double holds; it would not prove that the
 * child publishes what the locator reads.
 *
 * ## What it distinguishes
 *
 * A run that was given a configuration, a run that was given NOTHING, and a run that never was:
 * the first is described by its entries, the second is described as holding nothing, and the
 * third is refused by name. The answers for the second and the third are different facts, and a
 * service that confused them would say "unknown" about a run that simply had no configuration.
 */
@Timeout(value = 480, unit = TimeUnit.SECONDS)
class RunConfigurationRestartIntegrationTest {

    @TempDir
    lateinit var root: Path

    /** The store both services read and the runs write. */
    private lateinit var db: Path

    /** The control root both services read and the runs write. */
    private lateinit var control: Path

    /** The directory of the scripts a client may name. */
    private lateinit var scripts: Path

    /** The socket each service listens on in turn. */
    private lateinit var socket: Path

    private val clock: Clock = FixedClock

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        control = Files.createDirectories(root.resolve("control"))
        scripts = Files.createDirectories(root.resolve("scripts"))
        socket = root.resolve("service.sock")
        Files.writeString(
            scripts.resolve("config.pipeline.kts"),
            "pipeline {\n    stages {\n        stage(\"eager\") {\n" +
                "            sh(\"printenv BUILD_PROFILE || true\")\n        }\n    }\n}\n",
        )
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val writable = (admission as EventStoreAdmission.Admitted).store
        DurableStores.open(writable, control, clock)
        writable.close()
    }

    @Test
    fun `the configuration of a run is described again after the service restarts`() {
        val first = start()
        val given: ServiceRunStart
        val nothing: ServiceRunStart
        val describedBefore: ServiceRunConfiguration
        val describedNothingBefore: ServiceRunConfiguration
        try {
            val pool = Executors.newFixedThreadPool(2)
            try {
                val withConfiguration = pool.submit<ServiceAnswer<ServiceRunStart>> {
                    client(STARTER_TOKEN).startRun(
                        script = "config.pipeline.kts",
                        idempotencyKey = "key-given",
                        configuration = mapOf("BUILD_PROFILE" to "alpha"),
                    )
                }
                val withoutConfiguration = pool.submit<ServiceAnswer<ServiceRunStart>> {
                    client(STARTER_TOKEN).startRun(
                        script = "config.pipeline.kts",
                        idempotencyKey = "key-nothing",
                    )
                }
                given = started(withConfiguration.get(240, TimeUnit.SECONDS))
                nothing = started(withoutConfiguration.get(240, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }
            awaitTerminal(given.runId)
            awaitTerminal(nothing.runId)
            describedBefore = described(given.runId)
            describedNothingBefore = described(nothing.runId)
        } finally {
            first.stop()
        }

        assertEquals(
            listOf(ServiceConfigurationEntry("BUILD_PROFILE", "request", "string")),
            describedBefore.entries,
            "the service that started the run describes it before the restart",
        )

        val second = start()
        try {
            val describedAfter = described(given.runId)
            val describedNothingAfter = described(nothing.runId)

            assertEquals(
                describedBefore,
                describedAfter,
                "the service that did NOT start the run describes it as the one that did",
            )
            assertEquals(
                configurationDigest(mapOf("BUILD_PROFILE" to "alpha")),
                describedAfter.digest,
                "the digest is the digest of the very values the run was given",
            )
            assertEquals(
                describedNothingBefore,
                describedNothingAfter,
                "a run that was given nothing is still described as holding nothing",
            )
            assertEquals(emptyList<ServiceConfigurationEntry>(), describedNothingAfter.entries)
            assertEquals(configurationDigest(emptyMap()), describedNothingAfter.digest)
            assertFalse(
                describedAfter.toString().contains("alpha"),
                "the description holds no value",
            )
        } finally {
            second.stop()
        }
    }

    @Test
    fun `a run that never existed is refused by name after a restart`() {
        val first = start()
        first.stop()
        val second = start()
        try {
            val answer = client(READER_TOKEN).runConfiguration("run-that-never-was")

            val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
            val named = assertInstanceOf(ServiceRefusal.RunConfigurationRefused::class.java, refusal)
            assertEquals(
                "run-configuration-unknown",
                named.reason,
                "the service says it did not start the run, which is the fact",
            )
        } finally {
            second.stop()
        }
    }

    @Test
    fun `a run the store knows but this service did not start is refused as unknown`() {
        val first = start()
        val foreign = try {
            // A run of ANOTHER service that shares the substrate: its supervision
            // is not in this service's own directory, so this service did not
            // start it, however well the store knows it.
            val stranger = admitEventStore(db.toString(), EventStorePurpose.Produce)
            val foreignStore = (stranger as EventStoreAdmission.Admitted).store
            try {
                publishCorrelation(foreignStore, "run-of-another-service", "correlation-of-another-service")
            } finally {
                foreignStore.close()
            }
            client(READER_TOKEN).runConfiguration("run-of-another-service")
        } finally {
            first.stop()
        }

        val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, foreign).refusal
        val named = assertInstanceOf(ServiceRefusal.RunConfigurationRefused::class.java, refusal)
        assertEquals("run-configuration-unknown", named.reason)
    }

    /**
     * The `RunCorrelated` event a child publishes when it opens a run.
     */
    private fun publishCorrelation(store: SqliteEventStore, runId: String, correlationId: String) {
        store.append(
            dev.rubentxu.pipeline.v2.events.RunCorrelated(
                eventId = "e-correlated-$runId",
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.EPOCH,
                correlationId = dev.rubentxu.pipeline.v2.domain.CorrelationId(correlationId),
                ownerId = "owner-of-$runId",
                startedAtMs = 1L,
            ),
        )
    }

    /**
     * The run the answer names.
     */
    private fun started(answer: ServiceAnswer<ServiceRunStart>): ServiceRunStart =
        assertInstanceOf(
            ServiceRunStart::class.java,
            assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value,
        )

    /**
     * The configuration the service describes for [runId].
     */
    private fun described(runId: String): ServiceRunConfiguration {
        val answer = client(READER_TOKEN).runConfiguration(runId)
        return assertInstanceOf(
            ServiceRunConfiguration::class.java,
            assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                answer,
                "the service answers the question about $runId: $answer",
            ).value,
        )
    }

    /**
     * The terminal observation of [runId], asked for until it arrives.
     *
     * The wait is the budget of the run plus the room the closing takes, and not a number of its
     * own: a wait shorter than the budget gives up while the product is still working.
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
            Thread.sleep(POLL_MILLIS)
        }
        error("the run $runId did not finish in time")
    }

    /**
     * The service, over a real socket, and the way to stop it.
     */
    private class Service(
        private val server: ServiceServer,
        private val supervisor: ServiceRunSupervisor,
        private val store: SqliteEventStore,
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
     * Starts a service over a real socket, with the real supervisor.
     *
     * Every call composes a NEW service over the SAME substrate: the directory of supervisions,
     * the registry of starts, the store and the control root are the ones the service before it
     * left behind, and nothing else is shared.
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
            configurations = RunConfigurationLocator(
                supervisionRoot = supervisionRoot,
                correlations = EventStoreRunCorrelationReader(db.toAbsolutePath().toString()),
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
            supervisor,
            store,
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

        /** The budget of the runs this lane starts: the product's promise of how long a run may live. */
        private const val RUN_BUDGET_MILLIS: Long = 240_000L

        /** How long the test waits for a run to finish: the budget of the run plus the room the closing takes. */
        private const val TERMINAL_DEADLINE_MILLIS: Long = RUN_BUDGET_MILLIS + 30_000L

        /** How long the lane waits between two questions about the run. */
        private const val POLL_MILLIS: Long = 500L
    }
}
