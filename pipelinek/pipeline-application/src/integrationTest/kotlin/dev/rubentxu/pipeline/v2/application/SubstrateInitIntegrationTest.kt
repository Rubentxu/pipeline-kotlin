package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.durable.DurableSubstratePreparation
import dev.rubentxu.pipeline.v2.events.durable.SqliteConnectionFactory
import dev.rubentxu.pipeline.v2.events.durable.prepareDurableSubstrate
import dev.rubentxu.pipeline.v2.runtime.NoopPipelineTelemetry
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The preparation of the substrate the service serves.
 *
 * ## The guarantee under trial
 *
 * That an EXPLICIT preparation leaves a substrate the service can serve: the
 * file is there, it carries the events table AND the table of the promises,
 * and the service opens it and answers. The service itself still refuses a
 * substrate that is not there, because the preparation is a step of its own
 * and not something the service does on the way to serving.
 *
 * ## The capacity this serves
 *
 * The operator starts the container against volumes that are empty, and the
 * service starts. Without the preparation the first start failed with a
 * refusal about a file that was not there, and the recipe published the
 * failure instead of the service.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * The subject is a MACHINE: a real SQLite file, the real engine, the real
 * transaction, and the real composition the operator runs. Whether the two
 * shapes are created in ONE transaction —and therefore whether a failed
 * preparation leaves half of one behind— is a fact about the engine, and a
 * double of the preparation would answer whatever it was told.
 *
 * ## What this does not freeze
 *
 * The words of the refusal, beyond what it names, and the order in which the
 * tables are created.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SubstrateInitIntegrationTest {

    @TempDir
    lateinit var root: Path

    private lateinit var db: Path
    private lateinit var control: Path
    private lateinit var scripts: Path
    private lateinit var socket: Path

    private val errors = ByteArrayOutputStream()

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        control = Files.createDirectories(root.resolve("control"))
        scripts = Files.createDirectories(root.resolve("scripts"))
        socket = root.resolve("service.sock")
    }

    @Test
    fun `the preparation creates the substrate with the events and the table of the promises`() {
        // The directory of the substrate is not there either: the place the
        // preparation writes into is its own to make.
        val target = root.resolve("state").resolve("store.db")

        val preparation = prepareDurableSubstrate(target.toString())

        val ready = assertInstanceOf(
            DurableSubstratePreparation.Ready::class.java,
            preparation,
            "the preparation of a substrate in an empty directory was refused",
        )
        assertTrue(ready.created, "the preparation says it created the substrate")
        assertTrue(
            ready.tables.contains(EVENT_TABLE),
            "the substrate carries the table of the events: ${ready.tables}",
        )
        assertTrue(
            ready.tables.contains(PROMISE_TABLE),
            "the substrate carries the table of the promises: ${ready.tables}",
        )
        assertTrue(Files.exists(target), "the substrate is there")
        assertTrue(tableIsThere(target, EVENT_TABLE), "the file carries the table of the events")
        assertTrue(tableIsThere(target, PROMISE_TABLE), "the file carries the table of the promises")
    }

    @Test
    fun `the preparation of a substrate that is already there says so`() {
        prepareDurableSubstrate(db.toString())

        val preparation = prepareDurableSubstrate(db.toString())

        val ready = assertInstanceOf(
            DurableSubstratePreparation.Ready::class.java,
            preparation,
            "the preparation of a substrate that is already there was refused",
        )
        assertFalse(ready.created, "the second preparation found the substrate that was there")
        assertTrue(
            tableIsThere(db, PROMISE_TABLE),
            "the substrate still carries the table of the promises",
        )
    }

    @Test
    fun `the service serves a substrate that was prepared`() {
        prepareDurableSubstrate(db.toString())

        val composed = compose()

        val service = assertInstanceOf(
            ServeComposition.Composed::class.java,
            composed,
            "the service refused a substrate that was prepared: $errors",
        )
        service.close()
    }

    @Test
    fun `the service refuses a substrate that was never prepared`() {
        // The service READS the substrate it is given and never creates one:
        // the preparation is a step of its own, and a service that prepared
        // the file it was asked to read would answer about runs that are not
        // there.
        val composed = compose()

        assertInstanceOf(
            ServeComposition.Refused::class.java,
            composed,
            "a substrate that is not there is served",
        )
        assertTrue(
            errors.toString().contains("the substrate cannot be served"),
            "the refusal names what is missing: $errors",
        )
        assertFalse(Files.exists(db), "the refusal must not leave an empty store behind")
    }

    @Test
    fun `a preparation that cannot be completed leaves the file it found as it was`() {
        // A file another tool wrote: a database whose events table has another
        // shape, so the index over the run and the sequence cannot be built.
        SqliteConnectionFactory.open(db.toString()).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE events (id TEXT)")
            }
        }

        val preparation = prepareDurableSubstrate(db.toString())

        assertInstanceOf(
            DurableSubstratePreparation.Refused::class.java,
            preparation,
            "a substrate whose shape is not this product's was prepared",
        )
        assertFalse(
            tableIsThere(db, PROMISE_TABLE),
            "the failed preparation left the table of the promises behind",
        )
    }

    // --- fixtures ---

    private fun authorizations(): ServiceAuthorizations =
        ServiceAuthorizations.of(listOf(ServiceAuthorization(TOKEN, "operator", ServiceScope.All)))

    private fun compose(): ServeComposition {
        val registry = when (val read = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))) {
            is ServiceStartRegistryReading.Read -> read.registry
            is ServiceStartRegistryReading.Refused -> error(read.detail)
        }
        return composeServiceToServe(
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
                registry = registry,
            ),
            authorizations = authorizations(),
            clock = SystemClock(),
            err = PrintStream(errors, true),
            openings = ServeOpenings(
                environment = emptyMap(),
                brokerOpening = {
                    ServiceCredentialAccess.Refused("a service with no grant never opens the credential store")
                },
                telemetryOpening = { NoopPipelineTelemetry },
            ),
        )
    }

    /**
     * Whether the database at [db] holds a table of that name.
     *
     * The question is asked through the reading door, which is a door the engine refuses to
     * write through: asking costs nothing and changes nothing.
     */
    private fun tableIsThere(db: Path, table: String): Boolean =
        SqliteConnectionFactory.openReadOnly(db.toString()).use { connection ->
            connection.prepareStatement(SELECT_TABLE).use { statement ->
                statement.setString(1, table)
                statement.executeQuery().use { rs -> rs.next() }
            }
        }

    private companion object {

        /**
         * The name of the table the promises live in.
         *
         * It is written here, and not taken from the product, because the fact under trial is
         * about the FILE: a row that read the product's own constant would agree with the
         * product whatever the product did.
         */
        const val PROMISE_TABLE = "saga_obligation"

        /** The name of the table the history of the runs lives in. */
        const val EVENT_TABLE = "events"

        const val SELECT_TABLE = """
            SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?
        """

        const val TOKEN = "token-of-the-operator"

        const val RUN_DEADLINE_MILLIS = 60_000L
        const val START_DEADLINE_MILLIS = 60_000L
    }
}
