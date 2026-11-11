package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
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
 * The rows that START the installed product, and the gate does not call them.
 *
 * ## The guarantee under trial
 *
 * That the ZIP the installer publishes really is a product that starts: its
 * launcher offers the verbs of the service, and the service it starts answers
 * the client this repository publishes over a private socket.
 *
 * ## Why these rows are NOT in the gate
 *
 * Because they start a PRODUCT, and a row that starts a product can go red
 * for a reason the code does not have: the machine is busy, the port is
 * taken, the clock of the machine is off. A row like that measures the
 * machine as much as the code, and a gate that calls it publishes a verdict
 * about the machine under the name of a verdict about the tree.
 *
 * The gate certifies THIS TREE, so it calls no row that starts the product.
 * These rows stay in the tree, they are declared in
 * `scripts/check-product-boot-layer.py` with the reason they are here, and
 * they are run on demand:
 *
 *     ./pipelinek/gradlew -p pipelinek :pipeline-application:integrationTest \
 *         --tests '*InstalledProductBoot*'
 *
 * The certification of the PUBLISHED artefact is the harness's, and it is
 * there that the bytes are certified by their digest: this tree certifies
 * the tree, and this tree's rows are not that.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * Every fact under trial is about a PROCESS and a SOCKET: a ZIP written by
 * the build, a launcher that has to find a JVM, a service that has to bind,
 * and a client that has to travel over the wire. A fake of any of them would
 * fake the thing under trial.
 *
 * ## What this does not freeze
 *
 * The words the launcher prints, and the order in which the product answers.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
@Tag("boot-check")
class InstalledProductBootIntegrationTest {

    /** Where the measurement happens: the ZIP, the installation and the service. */
    @TempDir
    lateinit var root: Path

    @Test
    fun `the installed launcher offers the service and the verbs around it`() {
        val verbs = verbsOf(run(launcher(), "--help"))

        val offered = listOf(
            "run",
            "observe",
            "doctor",
            "version",
            "sdk-info",
            "serve",
            "runs cancel",
            "runs recover",
        )
        val missing = offered.filterNot { verbs.contains(it) }
        assertTrue(missing.isEmpty(), "the installed launcher does not offer: $missing (it offers $verbs)")
    }

    @Test
    fun `the installed service answers the client of the SDK over a private socket`() {
        val launcher = launcher()
        val home = Files.createDirectories(root.resolve("service"))
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"))
        val control = Files.createDirectories(home.resolve("control"))
        val database = root.resolve("store.db")
        createSubstrate(database, control)
        val socket = home.resolve("service.sock")
        val log = root.resolve("serve.log")
        val process = start(
            launcher,
            "serve",
            "--db", database.toString(),
            "--control-root", control.toString(),
            "--socket", socket.toString(),
            output = log,
        )
        try {
            val token = tokenOfTheService(process, socket, log)
            awaitAPrivateSocket(socket)

            val answer = ServiceClient(socket, token, Duration.ofSeconds(CLIENT_TIMEOUT_SECONDS)).version()
            val answered = when (answer) {
                is ServiceAnswer.Answered -> answer
                else -> refuse("the service did not answer the client: $answer")
            }
            assertEquals(ServiceProtocol.SCHEMA, answered.value.schema, "the service speaks another protocol")
            assertEquals(version(), answered.value.productVersion, "the service is another build")
        } finally {
            process.destroy()
            assertTrue(process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS), "the service did not stop")
        }
        assertFalse(Files.exists(socket), "the service left its socket behind")
    }

    /**
     * The launcher of the unpacked distribution, made executable as the installer makes it.
     *
     * The distribution is unpacked first, because a ZIP is a container of names
     * and bytes and not a thing that runs: the launcher is measured in the
     * state an installation leaves it in, and not in the state a container
     * holds it in.
     *
     * The ZIP carries no executable bit —a ZIP is a container of names and bytes,
     * not of modes— and the installer sets it on the file it installs. This does
     * the same thing to the file it unpacked.
     */
    private fun launcher(): Path {
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
        val launchers = Files.newDirectoryStream(unpacked).use { directories ->
            directories.filter { Files.isDirectory(it) }
                .map { it.resolve("bin").resolve("pipelinek") }
                .filter { Files.isRegularFile(it) }
                .toList()
        }
        assertEquals(1, launchers.size, "the distribution does not carry exactly one launcher: $launchers")
        val launcher = launchers.first()
        val permissions = Files.getPosixFilePermissions(launcher).toMutableSet()
        permissions += PosixFilePermission.OWNER_EXECUTE
        Files.setPosixFilePermissions(launcher, permissions)
        return launcher
    }

    /** What the launcher prints, and whether it finished. */
    private fun run(launcher: Path, vararg arguments: String): String {
        val process = start(launcher, *arguments)
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(HELP_TIMEOUT_SECONDS, TimeUnit.SECONDS), "the launcher did not finish")
        return output
    }

    /**
     * The launcher, started with the arguments it is given.
     *
     * The JVM is named to the launcher, and it is the JVM the lane itself runs
     * on: a launcher that had to find a JVM on the PATH would be measured
     * against whatever the machine that runs the lane happens to have, and the
     * distribution would be measured against the machine instead of against
     * itself.
     */
    private fun start(launcher: Path, vararg arguments: String, output: Path? = null): Process {
        val builder = ProcessBuilder(listOf(launcher.toString()) + arguments).redirectErrorStream(true)
        if (output != null) {
            builder.redirectOutput(output.toFile())
        }
        builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        return builder.start()
    }

    /**
     * The verbs the launcher prints, read from the column its own help gives them.
     *
     * The help writes the name of the verb in a column of its own and the
     * description after it, and the column is what says which of the two a word
     * is: `runs cancel` is a verb and `close a run that no live process is
     * working on` is not.
     */
    private fun verbsOf(help: String): Set<String> =
        help.lines()
            .dropWhile { it.trim() != VERBS_HEADING }
            .drop(1)
            .takeWhile { it.startsWith("  ") || it.isBlank() }
            .filter { it.trim().isNotEmpty() }
            .map { it.substring(2, minOf(VERB_COLUMN_END, it.length)).trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * The token of the service, read from the file the service owns next to its socket.
     *
     * The service writes the authorizations it serves next to the socket it
     * listens on, and the token is the first word of its first line. The file
     * is the service's own, and it is read here because it is the way the operator
     * finds the token too.
     */
    private fun tokenOfTheService(process: Process, socket: Path, log: Path): String {
        val authorizations = socket.parent.resolve(ServiceAuthorizationStore.OWNED_FILE_NAME)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LISTEN_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                refuse("the service stopped before it listened:\n${Files.readString(log)}")
            }
            if (Files.exists(socket) && Files.exists(authorizations)) {
                val line = Files.readString(authorizations).lineSequence().first { it.isNotBlank() }
                return line.split(WHITESPACE).first()
            }
            Thread.sleep(POLL_MILLIS)
        }
        refuse("the service did not listen on $socket:\n${Files.readString(log)}")
    }

    /**
     * Waits for the socket to become PRIVATE.
     *
     * The service binds its socket and hardens it just afterwards, and the
     * window between the two is the window a reader that looks too early walks
     * into: the row read the mode of a socket the service had not hardened yet
     * and went red for a reason that has nothing to do with the product. The
     * guarantee under trial is that the socket ENDS private, and a row that
     * reads it while it is being written measures the writing.
     */
    private fun awaitAPrivateSocket(socket: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HARDEN_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (Files.exists(socket) && Files.getPosixFilePermissions(socket) == PRIVATE_SOCKET) {
                return
            }
            Thread.sleep(POLL_MILLIS)
        }
        refuse("the socket of the service is not private: ${Files.getPosixFilePermissions(socket)}")
    }

    /**
     * The end of a measurement that could not be made.
     *
     * A refusal and not an assertion: the fact under trial could not be
     * observed, and a lane that says so has said more than a lane that failed
     * on a value nobody could read.
     */
    private fun refuse(message: String): Nothing = throw AssertionError(message)

    /**
     * The substrate the service reads, created the way the product creates it.
     *
     * The file is not enough: the durable stores of a substrate —the journal of
     * the operations, the replay cursor and the obligations of the sagas— keep
     * their tables in the same file, and the service composes them when it
     * starts. A substrate that was opened without them cannot be served, so the
     * lane composes them too: the fixture is then the substrate a run leaves
     * behind, and not a file with the table of the events in it.
     */
    private fun createSubstrate(database: Path, controlRoot: Path) {
        val admission = admitEventStore(database.toString(), EventStorePurpose.Produce)
        val admitted = admission as? EventStoreAdmission.Admitted
            ?: refuse("the substrate could not be created: $admission")
        DurableStores.open(admitted.store, controlRoot, FixedClock)
        admitted.store.close()
    }

    /** The ZIP the installer publishes, as the build handed it over. */
    private fun distribution(): Path = Path.of(
        requireNotNull(System.getProperty("pipelinek.distribution.zip")) {
            "the lane was run without the distribution: the build has to hand over the ZIP it measured"
        },
    )

    /** The version the distribution was built as. */
    private fun version(): String = requireNotNull(System.getProperty("pipelinek.distribution.version")) {
        "the lane was run without the version of the distribution"
    }

    /** A clock that does not move: the lane measures the artifact, not the time. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {

        /** The heading of the section of the help that names the verbs. */
        const val VERBS_HEADING: String = "Verbs:"

        /** The column the help ends the name of a verb in. */
        const val VERB_COLUMN_END: Int = 16

        /** What separates the words of an authorization. */
        val WHITESPACE: Regex = Regex("\\s+")

        /** The mode a socket of the service has to end with. */
        val PRIVATE_SOCKET: Set<PosixFilePermission> = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )

        /** How long the launcher may take to print its help. */
        const val HELP_TIMEOUT_SECONDS: Long = 120

        /** How long the service may take to bind its socket. */
        const val LISTEN_TIMEOUT_SECONDS: Long = 120

        /** How long the service may take to harden the socket it bound. */
        const val HARDEN_TIMEOUT_SECONDS: Long = 30

        /** How long the client waits for an answer. */
        const val CLIENT_TIMEOUT_SECONDS: Long = 30

        /** How long the service may take to stop. */
        const val STOP_TIMEOUT_SECONDS: Long = 60

        /** How long the lane waits between two looks at the socket. */
        const val POLL_MILLIS: Long = 200
    }
}
