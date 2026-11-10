package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.runtime.durable.RunOwnerId
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import dev.rubentxu.pipeline.v2.service.ServiceClient
import java.time.Duration

/**
 * The remote path of `inspect --state`, over a real socket, over a real store.
 *
 * ## The guarantee under trial
 *
 * The command line answers about a run over a socket EXACTLY as it answers
 * about the same run locally: the same line, the same code, and the same
 * refusal when the run is unknown. And when the service is not there, the
 * command line says so and stops — it does NOT quietly answer from the local
 * store, because an answer from a source nobody named is a lie an operator
 * cannot see.
 *
 * ## The capacity this serves
 *
 * A supervisor that drives this command line has to be able to point it at a
 * service and get the same answer it would get locally, and it has to be able
 * to tell "the service is down" from "the run is unknown" — two facts that
 * lead to opposite actions and that must not share an exit code.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * The two facts under trial are about a MACHINE: a real socket that really
 * binds, a real store that really holds the run, and a real client that
 * really travels over the wire. A fake connection would prove that the
 * client reads what it is given; it would not prove that the two paths
 * agree.
 */
class ServiceEndpointIntegrationTest {

    @TempDir
    lateinit var root: Path

    /** The durable store the service reads. */
    private lateinit var db: Path

    /** The socket the service listens on. */
    private lateinit var socket: Path

    private val runId = "run-endpoint-1"

    private val clock: Clock = FixedClock

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        socket = root.resolve("service.sock")
        val workspace = Files.createDirectories(root.resolve("workspace"))
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val store = (admission as EventStoreAdmission.Admitted).store
        store.append(
            RunStarted(
                eventId = "event-1",
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.EPOCH,
                scriptPath = workspace.resolve("pipeline.kts").toString(),
            ),
        )
        store.append(
            RunFinished(
                eventId = "event-2",
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.EPOCH,
                outcome = "success",
                diagnostics = emptyList(),
            ),
        )
        store.flush()
        // The file the credential is read from, next to the socket: this is
        // where the command line looks for it when the operator names no
        // file, and the service is not started by `serve` here, so nothing
        // else would write it.
        Files.writeString(root.resolve(ServiceAuthorizationStore.OWNED_FILE_NAME), "$TOKEN operator all\n")
        val stores = DurableStores.open(store, root, clock)
        val owner = requireNotNull(RunOwnerId.of("owner-of-the-fixture"))
        val identity = requireNotNull(RunId.of(runId))
        stores.lease.acquire(LeaseRequest(runId = identity, ownerId = owner))
        stores.lease.release(owner)
        store.close()
    }

    @Test
    fun `the remote path prints the same line and ends with the same code as the local one`() {
        val service = start()
        try {
            val local = inspect(arrayOf("--state", "--db", db.toString(), "--control-root", root.toString(), runId))
            val remote = inspect(arrayOf("--state", "--endpoint", socket.toString(), runId))

            assertEquals(local.stdout, remote.stdout, "the two paths did not print the same line")
            assertEquals(local.exit, remote.exit, "the two paths did not end with the same code")
            assertEquals(0, remote.exit, "a run that finished is reported with the success code")
            assertTrue(
                remote.stdout.contains("run-state terminal outcome=succeeded"),
                "the remote line says what the run is: ${remote.stdout}",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a run nobody knows is refused the same way over the socket and locally`() {
        val service = start()
        try {
            val local = inspect(
                arrayOf("--state", "--db", db.toString(), "--control-root", root.toString(), "run-nobody-wrote"),
            )
            val remote = inspect(arrayOf("--state", "--endpoint", socket.toString(), "run-nobody-wrote"))

            assertEquals(local.stderr, remote.stderr, "the two paths refused the run differently")
            assertEquals(local.exit, remote.exit, "the two paths refused the run with different codes")
            assertEquals(2, remote.exit, "a refused run is the invalid-argument code, as it is locally")
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a service that is not there is named and there is no local answer behind it`() {
        val absent = root.resolve("nobody-is-listening.sock")

        val remote = inspect(arrayOf("--state", "--endpoint", absent.toString(), runId))

        assertEquals(4, remote.exit, "a service that is not there is the runtime-not-available code")
        assertTrue(
            remote.stderr.contains("there is no service at"),
            "the refusal names what is missing: ${remote.stderr}",
        )
        assertEquals("", remote.stdout, "there is no answer at all, and no local one either")
    }

    @Test
    fun `a remote request never demands the local substrate`() {
        val absent = root.resolve("nobody-is-listening.sock")

        val remote = inspect(arrayOf("--state", "--endpoint", absent.toString(), runId))

        assertFalse(
            remote.stderr.contains("--db"),
            "the remote path did not ask for a local store: ${remote.stderr}",
        )
    }

    @Test
    fun `a mode the service cannot answer is refused by name`() {
        val service = start()
        try {
            val remote = inspect(
                arrayOf("--profile", "--endpoint", socket.toString(), "--db", db.toString(), runId),
            )

            assertEquals(2, remote.exit, "a request the service cannot answer is the invalid-argument code")
            assertTrue(
                remote.stderr.contains("--endpoint is admitted only with --state"),
                "the refusal names the option and the mode: ${remote.stderr}",
            )
            assertTrue(
                remote.stderr.contains("--profile"),
                "the refusal names the mode that was asked for: ${remote.stderr}",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `the client of the SDK reads the same fact the command line prints`() {
        val service = start()
        try {
            val client = ServiceClient(socket, TOKEN, Duration.ofSeconds(10))
            val observation = client.runState(runId)

            val observed = requireNotNull(
                (observation as? dev.rubentxu.pipeline.v2.service.ServiceAnswer.Answered)?.value,
            ) { "the client did not get an observation: $observation" }
            assertEquals(
                InspectionPresentation.renderRunState(observed),
                inspect(arrayOf("--state", "--endpoint", socket.toString(), runId)).stdout.trim(),
                "the client and the command line did not read the same fact",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a client that goes away leaves the service serving and the run untouched`() {
        val service = start()
        try {
            val before = Files.readAllBytes(db)

            // A client with a deadline that goes by: it closes its connection
            // and the service keeps serving the next one.
            val abandoned = ServiceClient(socket, TOKEN, Duration.ofMillis(50L))
            abandoned.runState(runId)

            val after = ServiceClient(socket, TOKEN, Duration.ofSeconds(10)).runState(runId)
            assertTrue(after is dev.rubentxu.pipeline.v2.service.ServiceAnswer.Answered, "the service answered")

            assertTrue(
                before.contentEquals(Files.readAllBytes(db)),
                "asking about the run changed the bytes of the store",
            )
        } finally {
            service.stop()
        }
    }

    /**
     * The answer of one run of the command line.
     */
    private data class Answer(val exit: Int, val stdout: String, val stderr: String)

    /**
     * Runs the inspect verb over [args] and captures what it said.
     */
    private fun inspect(args: Array<String>): Answer {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8.name())
        val err = PrintStream(errBytes, true, Charsets.UTF_8.name())

        val exit = MainInspectCli.main(args, out, err)
        out.flush()
        err.flush()

        return Answer(exit, outBytes.toString(Charsets.UTF_8), errBytes.toString(Charsets.UTF_8))
    }

    /**
     * The service, over a real socket, and the way to stop it.
     */
    private inner class Service(
        private val server: ServiceServer,
        private val store: dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore,
    ) {

        private val thread = Thread({ server.serve() }, "service-under-test").apply {
            isDaemon = true
            start()
        }

        /** Stops the service. */
        fun stop() {
            server.close()
            thread.join(5_000L)
            store.close()
        }
    }

    /**
     * Starts the service over a real socket.
     */
    private fun start(): Service {
        val admission = admitEventStore(db.toString(), EventStorePurpose.Read)
        val store = (admission as EventStoreAdmission.Admitted).store
        val operations = ServiceOperations(
            introspection = composeRuntimeIntrospectionPort(
                eventStore = store,
                controlRoot = root,
                clock = clock,
            ),
            substrate = {
                ServiceSubstrate(
                    eventStore = db.toString(),
                    controlRoot = root.toString(),
                    eventStorePresent = Files.exists(db),
                    controlRootPresent = Files.exists(root),
                )
            },
            starts = ServiceIntegrationStarter(),
            cancellations = null,
        )
        val stores = DurableStores.open(store, root, clock)
        val streets = ServiceSubscription(
            lanes = ComposeLanes.forService(store, root),
            events = composeEventRecordReadPort(store, stores.lease),
        )
        val transport = UnixSocketTransport.bind(socket)
        return Service(ServiceServer(transport, operations, streets, authorizations()), store)
    }

    /**
     * The authorizations this service knows.
     *
     * One token with the scope `all`, and it is the same token the file next
     * to the socket carries: the command line and the client of the SDK both
     * present it.
     */
    private fun authorizations(): ServiceAuthorizations = ServiceAuthorizations.of(
        listOf(ServiceAuthorization(TOKEN, "operator", ServiceScope.All)),
    )

    private companion object {
        /** The token this service knows. */
        private const val TOKEN: String = "token-of-the-service"
    }

    /** The clock this lane stamps its material with: it does not move. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }
}
