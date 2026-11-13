package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceRunStart
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * A run over a real socket, with a real child JVM and the archive of the
 * distribution.
 *
 * ## The guarantee under trial
 *
 * That a child of a service ACCEPTS the archive: it starts with it, it runs
 * the script, and it finishes. And that the archive the first child wrote is
 * the one the next child reads.
 *
 * ## Why the JVM has to be real here
 *
 * Because the archive is the JVM's business and not the product's: the JVM
 * refuses to start when it is told to map an archive AND to write one, and
 * it says so on its stdout, where the service does not read it. The child
 * dies with a code and no reason, and the service can only say that the run
 * ended before it owned itself. A row with a fake port would say nothing
 * about that, because the fake port accepts whatever it is told.
 *
 * The second run is here for the same reason: a child that MAPS an archive
 * the JVM does not accept dies the same way, and only a real JVM says so.
 *
 * ## What this does not freeze
 *
 * How long the run takes, and how big the archive is: the facts are that the
 * child ran and that the archive on disk is the one the child wrote.
 */
@Timeout(value = 600, unit = java.util.concurrent.TimeUnit.SECONDS)
class ServiceSharedArchiveIntegrationTest {

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

    /** The archive the launcher published, and the one the child writes. */
    private lateinit var archive: Path

    private val clock: Clock = FixedClock

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        control = Files.createDirectories(root.resolve("control"))
        scripts = Files.createDirectories(root.resolve("scripts"))
        socket = root.resolve("service.sock")
        archive = root.resolve("pipelinek-cds-21.jsa")
        Files.writeString(
            scripts.resolve("hello.pipeline.kts"),
            "pipeline {\n    stages {\n        stage(\"eager\") {\n            echo(\"hello\")\n" +
                "        }\n    }\n}\n",
        )
        // The archive the launcher published has something in it: an archive
        // of no size is an archive that is not written yet.
        Files.writeString(archive, "the archive the launcher published")
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val writable = (admission as EventStoreAdmission.Admitted).store
        DurableStores.open(writable, control, clock)
        writable.close()
    }

    @Test
    fun `a run over the socket runs with the archive and leaves it written`() {
        val service = start()
        try {
            val first = startRun()
            val terminal = awaitTerminal(first.runId)
            assertInstanceOf(
                TerminalObservation.Succeeded::class.java,
                terminal.terminal,
                "the run the service started with the archive finished",
            )

            // The archive on disk is the one the CHILD wrote: the launcher
            // published a file that said what it was, and the file that says
            // that is gone.
            awaitArchive()
            assertTrue(
                Files.size(archive) > "the archive the launcher published".length,
                "the archive of the distribution is the one the child wrote: ${Files.size(archive)} bytes",
            )

            // The second child MAPS the archive the first one wrote. A JVM
            // that does not accept it dies before it owns itself, and the
            // service can only say that the run ended early.
            val second = startRun()
            val secondTerminal = awaitTerminal(second.runId)
            assertInstanceOf(
                TerminalObservation.Succeeded::class.java,
                secondTerminal.terminal,
                "the run that mapped the archive the first child wrote finished",
            )
        } finally {
            service.stop()
        }
    }

    /**
     * Starts a run over the socket and returns its identity.
     */
    private fun startRun(): ServiceRunStart {
        val answer = client(STARTER_TOKEN).startRun("hello.pipeline.kts")
        val started = assertInstanceOf(ServiceAnswer.Answered::class.java, answer).value
        return assertInstanceOf(ServiceRunStart::class.java, started)
    }

    /**
     * Waits until the archive on disk is not the one the launcher published.
     */
    private fun awaitArchive() {
        val deadline = System.currentTimeMillis() + ARCHIVE_DEADLINE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (Files.size(archive) > "the archive the launcher published".length) return
            Thread.sleep(200L)
        }
        error(
            "the archive was not written: ${Files.size(archive)} bytes\n" +
                "the child said:\n${childLog()}",
        )
    }

    /**
     * What the children said on their error stream, as the service wrote it.
     */
    private fun childLog(): String {
        val supervisions = root.resolve("supervisions")
        if (!Files.isDirectory(supervisions)) return "(no supervision)"
        Files.list(supervisions).use { stream ->
            val logs = stream.filter { Files.isDirectory(it) }
                .map { it.resolve("child.err.log") }
                .filter { Files.isRegularFile(it) }
                .map { Files.readString(it) }
                .toList()
            return logs.joinToString("\n")
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
     * A client that presents [token].
     */
    private fun client(token: String): ServiceClient = ServiceClient(
        endpoint = socket,
        credential = token,
        deadline = java.time.Duration.ofSeconds(240),
        schema = dev.rubentxu.pipeline.v2.service.ServiceProtocol.SCHEMA,
    )

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
     * Starts the service over a real socket, with the real supervisor and the
     * archive of the distribution.
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
                classpath = distributionClasspath(),
                mainClass = "dev.rubentxu.pipeline.v2.application.MainKt",
                // The archive the launcher published: the child maps it, and
                // the first child writes it.
                sharedArchive = archive.toAbsolutePath().toString(),
            ),
            port = ProcessDurableTaskRuntime(supervisionRoot, clock),
            registry = registry,
            limits = ServiceStartLimits(maxSupervisedRuns = 2, startDeadlineMillis = 240_000L),
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
     * The classpath of the DISTRIBUTION, with its jars and nothing else.
     *
     * ## Why the jars and not the classpath of this test
     *
     * Because the JVM refuses to write a shared class archive when the
     * classpath holds a directory that is not empty: it says "Cannot have
     * non-empty directory in paths" and leaves the archive empty. The class
     * of a test is a directory (the compiled classes of the module), so a
     * child launched with it could never write the archive. The
     * distribution is jars, and a distribution is what the service serves.
     *
     * The order is the order of the directory, and it is the same for every
     * child: the archive a child writes is only usable by a child that has
     * the same classpath.
     */
    private fun distributionClasspath(): String {
        val lib = Path.of("build", "install", "pipelinek", "lib").toAbsolutePath()
        val jars = Files.list(lib).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".jar") }
                .map { it.toAbsolutePath().toString() }
                .sorted()
                .toList()
        }
        check(jars.isNotEmpty()) { "the distribution is not installed: $lib" }
        return jars.joinToString(":")
    }

    /**
     * The authorizations this service knows.
     */
    private fun authorizations(): ServiceAuthorizations = ServiceAuthorizations.of(
        listOf(
            ServiceAuthorization(STARTER_TOKEN, "starter", ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start))),
        ),
    )

    /** The clock this lane stamps its material with: it does not move. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        /** The token of the client that may start runs. */
        private const val STARTER_TOKEN: String = "token-of-the-starter"

        /** How long the test waits for a run to finish. */
        private const val TERMINAL_DEADLINE_MILLIS: Long = 240_000L

        /** How long the test waits for the archive to be written. */
        private const val ARCHIVE_DEADLINE_MILLIS: Long = 20_000L
    }
}
