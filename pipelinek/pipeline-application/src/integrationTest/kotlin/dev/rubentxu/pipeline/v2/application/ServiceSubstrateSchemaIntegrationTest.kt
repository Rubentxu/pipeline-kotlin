package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.SqliteConnectionFactory
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
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
 * The schema of the substrate the service opens.
 *
 * ## The guarantee under trial
 *
 * That the service SERVES a substrate whose schema is not the one the producer creates —the
 * substrate another tool opened, with the table of the promises missing— and that the refusal it
 * does give names the table that is missing instead of repeating the engine's error.
 *
 * ## The capacity this serves
 *
 * An operator who points the service at a store that another tool wrote has a substrate with the
 * producer's schema and without the table of the promises. The service used to refuse it with
 * `[SQLITE_READONLY] Attempt to write a readonly database`: the name of the ENGINE, and not the
 * name of the table. The operator was sent to look at the wrong thing, and the service could
 * not serve a store it was perfectly able to read.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * The subject is a MACHINE: a real SQLite file, the real engine, the real door the engine
 * refuses to write through, and the real composition the operator runs. Whether a `CREATE TABLE`
 * is a write or a no-op on a read-only connection is a fact about the engine, and a double of
 * the composition would answer whatever it was told.
 *
 * ## What this does not freeze
 *
 * The words of the refusal, beyond the table it names, and the order in which the service
 * composes its parts.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ServiceSubstrateSchemaIntegrationTest {

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
    fun `the service serves a substrate whose table of promises is missing`() {
        // A substrate another tool opened: the producer's schema is in it, and the table of the
        // promises is not, because nobody armed a promise there.
        val producer = (admitEventStore(db.toString(), EventStorePurpose.Produce) as EventStoreAdmission.Admitted).store
        producer.close()
        assertFalse(
            tableIsThere(db, PROMISE_TABLE),
            "the substrate starts without the table of the promises",
        )

        val composed = compose()

        val service = assertInstanceOf(
            ServeComposition.Composed::class.java,
            composed,
            "the service refused a substrate it can read: $errors",
        )
        service.close()
        assertFalse(
            tableIsThere(db, PROMISE_TABLE),
            "the service created nothing in the substrate it reads",
        )
    }

    @Test
    fun `the refusal of a substrate with no event log names the table that is missing`() {
        // A file that is a database and holds no table at all.
        Files.createFile(db)

        val composed = compose()

        assertInstanceOf(
            ServeComposition.Refused::class.java,
            composed,
            "a substrate with no event log is refused: $errors",
        )
        assertTrue(
            errors.toString().contains(EVENT_TABLE),
            "the refusal names the table that is missing: $errors",
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
