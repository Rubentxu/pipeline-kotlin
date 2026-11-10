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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The distribution is MEASURED, and not described.
 *
 * ## The guarantee under trial
 *
 * The ZIP that the installer publishes is the artifact an operator holds, and
 * the questions here are about ITS bytes: which verbs the installed launcher
 * offers, whether the service is one of them, and whether the client the SDK
 * publishes can talk to the service the distribution carries.
 *
 * ## Why the launcher is RUN and not only listed
 *
 * Listing the entries of a ZIP proves that a file is inside it, and nothing
 * more: a launcher that starts nothing, or a service that answers nothing, is
 * a distribution that carries the NAME of the service. So the launcher is
 * started, it is asked what it can do, and the service it starts is asked a
 * question over a real socket by the client this repository publishes.
 *
 * ## Why the service is asked over a SOCKET
 *
 * The client of the SDK talks to the service over a Unix socket, and the
 * socket is the boundary the operator grants access at. A service that was
 * asked in-process would prove that the answer travels; it would not prove
 * that the answer travels TO A CLIENT.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * Every fact under trial is about a PROCESS and a SOCKET: a ZIP written by
 * another task, a launcher that has to find a JVM, a service that has to
 * bind, and a client that has to travel over the wire. A fake of any of them
 * would fake the thing under trial.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class DistributionArtifactIntegrationTest {

    /** Where the measurement happens: the ZIP, the installation and the service. */
    @TempDir
    lateinit var root: Path

    @Test
    fun `the distribution carries the launcher, the application and the client that talks to the service`() {
        val entries = entriesOfDistribution()

        assertTrue(entries.contains("bin/pipelinek"), "the distribution carries no launcher: $entries")
        assertTrue(entries.contains("bin/pipelinek.bat"), "the distribution carries no launcher for Windows: $entries")
        assertTrue(
            entries.contains("lib/pipeline-application-${version()}.jar"),
            "the distribution carries no application: $entries",
        )
        assertTrue(
            entries.contains("lib/pipeline-service-client-${version()}.jar"),
            "the distribution carries no client of the service: $entries",
        )
        assertTrue(
            entries.none { it.contains("docker") },
            "the distribution carries something named after a Docker socket: $entries",
        )
    }

    @Test
    fun `the installer does not need a Docker socket to install the distribution`() {
        val installer = repositoryRoot().resolve("scripts").resolve("install-pipelinek.sh")
        assertTrue(Files.isRegularFile(installer), "the installer is not where the build says it is: $installer")

        val text = Files.readString(installer)
        assertFalse(text.contains("docker.sock"), "the installer names a Docker socket")
        assertFalse(
            text.lineSequence().any { it.trim().startsWith("docker ") },
            "the installer invokes the Docker command line",
        )
    }

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

            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(socket),
                "the socket of the service is not private",
            )

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
     * The entries of the distribution, with the directory of the version taken off.
     *
     * The name of the directory is the version the distribution was built as, and
     * the entries are read WITHOUT it: what is under trial is what the
     * installation carries, and the name of the folder it was unpacked in is
     * not part of that.
     */
    private fun entriesOfDistribution(): List<String> {
        val prefix = "pipelinek-${version()}/"
        val entries = ZipFile(distribution().toFile()).use { archive ->
            archive.entries().toList().map { it.name }
        }
        return entries.map { it.removePrefix(prefix) }
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
     * listens on, and the token is the first word of its first line. The file is
     * the service's own, and it is read here because it is the way the operator
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

    /** The root of the repository, as the build handed it over. */
    private fun repositoryRoot(): Path = Path.of(
        requireNotNull(System.getProperty("pipelinek.repository.root")) {
            "the lane was run without the repository it measures"
        },
    )

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

        /** How long the launcher may take to print its help. */
        const val HELP_TIMEOUT_SECONDS: Long = 120

        /** How long the service may take to bind its socket. */
        const val LISTEN_TIMEOUT_SECONDS: Long = 120

        /** How long the client waits for an answer. */
        const val CLIENT_TIMEOUT_SECONDS: Long = 30

        /** How long the service may take to stop. */
        const val STOP_TIMEOUT_SECONDS: Long = 60

        /** How long the lane waits between two looks at the socket. */
        const val POLL_MILLIS: Long = 200
    }
}
