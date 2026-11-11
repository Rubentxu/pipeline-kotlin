package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.durable.SqliteConnectionFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The entrypoint of the OCI profile, measured on the distribution it carries.
 *
 * ## The guarantee under trial
 *
 * That a container started against EMPTY volumes comes up: the entrypoint
 * prepares the substrate the command names, and it does it BEFORE the service
 * is asked to serve. And that the preparation is not a thing the entrypoint
 * does to everything: a form that only reads is handed over untouched, and the
 * store it was pointed at is still not there afterwards.
 *
 * ## Why the entrypoint is measured on the REAL distribution
 *
 * Because the entrypoint finds the launcher NEXT TO ITSELF, and the only way
 * to know that the resolution works is to put the script where the image puts
 * it — in the root of an unpacked distribution — and start it there. A double
 * of the script, in a directory of its own, would answer whatever the double
 * was told, and the resolution is exactly the thing under trial.
 *
 * ## Why the order is measured and not assumed
 *
 * The preparation has to happen BEFORE the service binds: a service that binds
 * first and prepares later has a window in which it answers about a substrate
 * that is not there. So the lane reads the log the container writes and asks
 * which of the two lines came first.
 *
 * ## Why this instrument is an INTEGRATION test, and why the gate does not call it
 *
 * Every fact under trial is about PROCESSES and a FILE: a script that has to
 * find a launcher, a launcher that has to find a JVM, a SQLite file that has
 * to be created with two shapes in it and a service that has to bind a socket.
 * A double of any of them would fake the thing under trial.
 *
 * And because it STARTS the product, the gate does not call it: a row that
 * starts a product can go red for a reason the code does not have. The rows
 * stay in the tree, they are declared in
 * `scripts/check-product-boot-layer.py`, and they are run on demand.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
@Tag("boot-check")
class OciEntrypointIntegrationTest {

    /** Where the measurement happens: the distribution, the substrate and the socket. */
    @TempDir
    lateinit var root: Path

    @Test
    fun `the cold start prepares the substrate and then serves it`() {
        val entrypoint = installation()
        val home = Files.createDirectories(root.resolve("socket"))
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"))
        val control = Files.createDirectories(root.resolve("control"))
        // The directory of the substrate is NOT created here: the preparation is
        // the thing that has to make the place it writes into.
        val database = root.resolve("state").resolve("store.db")
        val socket = home.resolve("pipelinek.sock")
        val log = root.resolve("serve.log")

        val process = start(
            entrypoint,
            listOf(
                "serve",
                "--db", database.toString(),
                "--control-root", control.toString(),
                "--socket", socket.toString(),
            ),
            log,
        )
        try {
            awaitListening(process, log)
            val printed = Files.readString(log)

            val preparation = printed.indexOf(PREPARED)
            val listening = printed.indexOf(LISTENING)

            assertTrue(preparation >= 0, "the entrypoint did not prepare the substrate:\n$printed")
            assertTrue(listening >= 0, "the service did not listen:\n$printed")
            assertTrue(
                preparation < listening,
                "the service was asked to serve before the substrate was prepared:\n$printed",
            )
            assertTrue(
                printed.contains(CREATED),
                "the preparation did not say that it created the substrate:\n$printed",
            )
            assertTrue(
                printed.contains(TABLES),
                "the preparation did not say which tables the substrate carries:\n$printed",
            )
            assertTrue(tableIsThere(database, EVENT_TABLE), "the substrate carries no table of the events")
            assertTrue(tableIsThere(database, PROMISE_TABLE), "the substrate carries no table of the promises")
            assertTrue(Files.exists(socket), "the service did not bind its socket")
        } finally {
            process.destroy()
            assertTrue(process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS), "the service did not stop")
        }
    }

    @Test
    fun `a form that only reads does not prepare a substrate`() {
        val entrypoint = installation()
        val database = root.resolve("state").resolve("store.db")
        val log = root.resolve("read.log")

        val process = start(
            entrypoint,
            listOf("saga", "inspect", "a-run-nobody-started", "--db", database.toString()),
            log,
        )

        assertTrue(process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS), "the read did not finish")
        val printed = Files.readString(log)

        assertFalse(Files.exists(database), "a form that only reads created the substrate")
        assertFalse(process.exitValue() == 0, "a read of a store that is not there was answered as a success")
        assertTrue(
            printed.contains(NO_DATABASE),
            "the read was refused for another reason:\n$printed",
        )
    }

    // --- fixtures ---

    /**
     * The entrypoint, in the place the image puts it.
     *
     * The distribution is unpacked first, because a ZIP is a container of names
     * and bytes and not a thing that runs. The script is copied into the root
     * of that distribution — beside the launcher, exactly where the image
     * leaves it — and both are made executable, because the ZIP carries no
     * executable bit and the installer is the one that sets it.
     */
    private fun installation(): Path {
        val unpacked = Files.createDirectories(root.resolve("distribution"))
        ZipFile(distribution().toFile()).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val destination = unpacked.resolve(entry.name).normalize()
                check(destination.startsWith(unpacked)) {
                    "the distribution carries an entry that leaves the directory it is unpacked in: ${entry.name}"
                }
                if (entry.isDirectory) {
                    Files.createDirectories(destination)
                    continue
                }
                Files.createDirectories(destination.parent)
                archive.getInputStream(entry).use { bytes -> Files.copy(bytes, destination) }
            }
        }
        val homes = Files.newDirectoryStream(unpacked).use { directories ->
            directories.filter { Files.isRegularFile(it.resolve("bin").resolve("pipelinek")) }.toList()
        }
        assertEquals(1, homes.size, "the distribution does not carry exactly one launcher: $homes")
        val home = homes.first()
        executable(home.resolve("bin").resolve("pipelinek"))

        val entrypoint = home.resolve("entrypoint.sh")
        Files.copy(
            repositoryRoot().resolve("pipelinek").resolve("pipeline-application").resolve("oci").resolve("entrypoint.sh"),
            entrypoint,
            StandardCopyOption.REPLACE_EXISTING,
        )
        executable(entrypoint)
        return entrypoint
    }

    /** Whether the file can be run. */
    private fun executable(file: Path) {
        val permissions = Files.getPosixFilePermissions(file).toMutableSet()
        permissions += PosixFilePermission.OWNER_EXECUTE
        Files.setPosixFilePermissions(file, permissions)
    }

    /**
     * The entrypoint, started with the arguments it is given.
     *
     * The JVM is named to the launcher, and it is the JVM the lane itself runs
     * on: a launcher that had to find a JVM on the PATH would be measured
     * against whatever the machine that runs the lane happens to have.
     */
    private fun start(entrypoint: Path, arguments: List<String>, output: Path): Process {
        val builder = ProcessBuilder(listOf(entrypoint.toString()) + arguments).redirectErrorStream(true)
        builder.redirectOutput(output.toFile())
        builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        return builder.start()
    }

    /**
     * Waits for the service to say that it listens.
     *
     * The line is read from the log the container writes, because that is what
     * an operator reads: a service that is up and says nothing is not a
     * service an operator can find.
     */
    private fun awaitListening(process: Process, log: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LISTEN_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                refuse("the service stopped before it listened:\n${Files.readString(log)}")
            }
            if (Files.exists(log) && Files.readString(log).contains(LISTENING)) {
                return
            }
            Thread.sleep(POLL_MILLIS)
        }
        refuse("the service did not listen:\n${Files.readString(log)}")
    }

    /**
     * Whether the database at [database] holds a table of that name.
     *
     * The question is asked through the reading door, which is a door the
     * engine refuses to write through: asking costs nothing and changes
     * nothing.
     */
    private fun tableIsThere(database: Path, table: String): Boolean =
        SqliteConnectionFactory.openReadOnly(database.toString()).use { connection ->
            connection.prepareStatement(SELECT_TABLE).use { statement ->
                statement.setString(1, table)
                statement.executeQuery().use { rs -> rs.next() }
            }
        }

    /**
     * The end of a measurement that could not be made.
     *
     * A refusal and not an assertion: the fact under trial could not be
     * observed, and a lane that says so has said more than a lane that failed
     * on a value nobody could read.
     */
    private fun refuse(message: String): Nothing = throw AssertionError(message)

    /** The ZIP the installer publishes, as the build handed it over. */
    private fun distribution(): Path = Path.of(
        requireNotNull(System.getProperty("pipelinek.distribution.zip")) {
            "the lane was run without the distribution: the build has to hand over the ZIP it measured"
        },
    )

    /** The root of the repository, as the build handed it over. */
    private fun repositoryRoot(): Path = Path.of(
        requireNotNull(System.getProperty("pipelinek.repository.root")) {
            "the lane was run without the repository it measures"
        },
    )

    private companion object {

        /** The line the preparation prints when the substrate is ready. */
        const val PREPARED: String = ": ready"

        /** The line the preparation prints when it created the substrate. */
        const val CREATED: String = "substrate-state created"

        /** The line the preparation prints with the tables of the substrate. */
        const val TABLES: String = "tables "

        /** The line the service prints when it binds its socket. */
        const val LISTENING: String = "service listening on"

        /** The words a read of a store that is not there answers with. */
        const val NO_DATABASE: String = "there is no database at"

        /**
         * The name of the table the history of the runs lives in.
         *
         * It is written here, and not taken from the product, because the fact
         * under trial is about the FILE: a row that read the product's own
         * constant would agree with the product whatever the product did.
         */
        const val EVENT_TABLE: String = "events"

        /** The name of the table the promises of the sagas live in. */
        const val PROMISE_TABLE: String = "saga_obligation"

        const val SELECT_TABLE: String = """
            SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?
        """

        /** How long the service may take to bind its socket. */
        const val LISTEN_TIMEOUT_SECONDS: Long = 120

        /** How long the service may take to stop. */
        const val STOP_TIMEOUT_SECONDS: Long = 60

        /** How long the lane waits between two looks at the log. */
        const val POLL_MILLIS: Long = 200
    }
}
