package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The shared class archive the children of a service map.
 *
 * ## The guarantee under trial
 *
 * That a child of a service reads the Kotlin compiler from the SAME archive
 * the command line reads it from, and that the archive of the distribution
 * learns the compiler without anybody having to write it by hand.
 *
 * ## Why this is worth a lane of its own
 *
 * Because the difference is worth more than a second before the run starts,
 * and because it is invisible in every other fact of the product: the run
 * that starts a second later is a run that succeeded. The lanes that watch
 * the runs would not see this one.
 *
 * ## Why the archive is written by the FIRST child
 *
 * Because the child is the one that compiles, and the archive is a record of
 * the classes a JVM loaded: the service never compiles, so a service that
 * never trains the archive leaves every child reading the compiler from the
 * jar. The first child loads those classes anyway, and the archive it leaves
 * behind is the one the next children read.
 *
 * ## Why the child that writes it does not MAP it
 *
 * Because the JVM refuses to start when it is told to do both: it says
 * "-XX:ArchiveClassesAtExit is unsupported when a dynamic CDS archive is
 * specified in -XX:SharedArchiveFile", it says it on its STDOUT, where the
 * service does not read it, and the child dies with a code and no reason.
 * The child that writes the archive therefore reads the compiler from the
 * jar, like the children before the archive existed, and the children that
 * follow are the ones that read it.
 *
 * ## Why the archive is published with a RENAME
 *
 * Because the child writes it at its exit, and a reader that finds the
 * archive half written finds a JVM that refuses to start. The child writes
 * to a file of its own and the supervisor renames it over the archive.
 *
 * ## What this does not freeze
 *
 * The names of the files, and the JVM's own behaviour with an archive it
 * cannot use: the lane is about the argv the supervisor builds and about the
 * file it publishes.
 */
class ServiceSharedArchiveTest {

    @Test
    fun `the child that writes the archive does not map it`(@TempDir root: Path) {
        val archive = Files.writeString(root.resolve("pipelinek-cds-21.jsa"), "archive")
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port, sharedArchive = archive.toString())

        supervisor.start(request())

        val argv = argvOf(port.requests.single())
        assertTrue(
            argv.any { it.startsWith("-XX:ArchiveClassesAtExit=") },
            "the first child writes the archive: $argv",
        )
        assertFalse(
            argv.any { it.startsWith("-XX:SharedArchiveFile=") },
            "the child that writes the archive does not map it: a JVM told to do " +
                "both refuses to start, and says so where nobody reads it: $argv",
        )
    }

    @Test
    fun `a child that only reads the archive maps it`(@TempDir root: Path) {
        val archive = Files.writeString(root.resolve("pipelinek-cds-21.jsa"), "archive")
        val port = FakePort { request, sink ->
            // What a JVM does at its exit: it writes the archive it was told
            // to write.
            val training = argvOf(request).firstOrNull { it.startsWith("-XX:ArchiveClassesAtExit=") }
            training?.let { Files.writeString(Path.of(it.removePrefix("-XX:ArchiveClassesAtExit=")), "trained") }
            sink.append(receiptOf(request))
        }
        val supervisor = supervisor(root, port, sharedArchive = archive.toString())

        supervisor.start(request())
        awaitTraining(supervisor)
        supervisor.start(request("second.kts"))

        val argv = argvOf(port.requests[1])
        assertTrue(
            argv.any { it.startsWith("-XX:SharedArchiveFile=") },
            "the second child maps the archive: $argv",
        )
        assertFalse(
            argv.any { it.startsWith("-XX:ArchiveClassesAtExit=") },
            "the second child only reads it: $argv",
        )
    }

    @Test
    fun `a child of a service with no archive maps none`(@TempDir root: Path) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port, sharedArchive = null)

        supervisor.start(request())

        val argv = argvOf(port.requests.single())
        assertFalse(
            argv.any { it.startsWith("-XX:SharedArchiveFile=") },
            "a service with no archive does not name one: $argv",
        )
        assertFalse(
            argv.any { it.startsWith("-XX:ArchiveClassesAtExit=") },
            "and it does not write one either: $argv",
        )
    }

    @Test
    fun `the archive the child wrote is published when the child ends`(@TempDir root: Path) {
        val archive = Files.writeString(root.resolve("pipelinek-cds-21.jsa"), "the old archive")
        val port = FakePort { request, sink ->
            // What a JVM does at its exit: it writes the archive it was told
            // to write, and it writes it whole.
            val training = argvOf(request).firstOrNull { it.startsWith("-XX:ArchiveClassesAtExit=") }
            training?.let { Files.writeString(Path.of(it.removePrefix("-XX:ArchiveClassesAtExit=")), "the new archive") }
            sink.append(receiptOf(request))
        }
        val supervisor = supervisor(root, port, sharedArchive = archive.toString())

        supervisor.start(request())

        awaitArchive(archive, "the new archive")
        assertEquals(
            "the new archive",
            Files.readString(archive),
            "the archive of the distribution is the one the child wrote",
        )
        val leftovers = Files.list(archive.parent).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tmp") }.count()
        }
        assertEquals(0L, leftovers, "the file the child wrote is not left behind")
    }

    @Test
    fun `a child that wrote nothing leaves the archive as it was`(@TempDir root: Path) {
        val archive = Files.writeString(root.resolve("pipelinek-cds-21.jsa"), "the old archive")
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port, sharedArchive = archive.toString())

        supervisor.start(request())

        awaitTrainingToEnd(root)
        assertEquals(
            "the old archive",
            Files.readString(archive),
            "an archive that nobody trained is the one the distribution had",
        )
    }

    @Test
    fun `an archive with nothing in it is not an archive`(@TempDir root: Path) {
        val empty = Files.writeString(root.resolve("empty.jsa"), "")
        val written = Files.writeString(root.resolve("written.jsa"), "archive")

        assertNull(
            ServiceRunCommand.sharedArchiveOf(
                mapOf(ServiceRunCommand.SHARED_ARCHIVE_ENV_VAR to empty.toString()),
            ),
            "the launcher creates the file before the JVM fills it: an empty one is not an archive",
        )
        assertEquals(
            written.toAbsolutePath().toString(),
            ServiceRunCommand.sharedArchiveOf(
                mapOf(ServiceRunCommand.SHARED_ARCHIVE_ENV_VAR to written.toString()),
            ),
            "the archive the launcher published is the one the children map",
        )
    }

    /**
     * Waits until the archive holds [expected], or says that it does not.
     *
     * The archive is published when the CHILD ends, and the child ends after
     * the receipt that answered the start: the row that asks about the
     * published archive has to wait for the machine, not assume it.
     */
    private fun awaitArchive(archive: Path, expected: String) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (Files.exists(archive) && Files.readString(archive) == expected) return
            Thread.sleep(10L)
        }
        error("the archive was not published: ${Files.readString(archive)}")
    }

    /**
     * Waits until the supervisor knows that the archive is trained.
     */
    private fun awaitTraining(supervisor: ServiceRunSupervisor) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (supervisor.archiveIsTrained) return
            Thread.sleep(10L)
        }
        error("the archive was not trained")
    }

    /**
     * Waits until the child that was training the archive is over.
     */
    private fun awaitTrainingToEnd(root: Path) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            val leftovers = Files.list(root).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".tmp") }.count()
            }
            if (leftovers == 0L) return
            Thread.sleep(10L)
        }
        error("the child that was training the archive did not end")
    }

    /**
     * The supervisor of [root], with the [port] that launches the children.
     */
    private fun supervisor(
        root: Path,
        port: DurableTaskRuntime,
        sharedArchive: String?,
    ): ServiceRunSupervisor {
        val scripts = Files.createDirectories(root.resolve("scripts"))
        Files.writeString(scripts.resolve("build.kts"), "print(1)")
        Files.writeString(scripts.resolve("second.kts"), "print(2)")
        val registry = when (val read = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))) {
            is ServiceStartRegistryReading.Read -> read.registry
            is ServiceStartRegistryReading.Refused -> error(read.detail)
        }
        return ServiceRunSupervisor(
            admission = ServiceStartAdmission(scripts, null),
            runPolicy = ServiceRunPolicy(
                eventStore = "/tmp/store.db",
                controlRoot = "/tmp/control",
                deadlineMillis = 60_000L,
                allowNetwork = false,
            ),
            command = ServiceRunCommand(
                javaBinary = "/usr/bin/java",
                classpath = "/tmp/classpath",
                mainClass = "dev.rubentxu.pipeline.v2.application.MainKt",
                sharedArchive = sharedArchive,
            ),
            port = port,
            registry = registry,
            limits = ServiceStartLimits(maxSupervisedRuns = 4, startDeadlineMillis = 5_000L),
            supervisionRoot = root.resolve("supervisions"),
        )
    }

    /**
     * The request to start [script], of a client whose credential has a digest.
     */
    private fun request(script: String = "build.kts"): ServiceStartRequest = ServiceStartRequest(
        script = script,
        workspace = null,
        idempotencyKey = null,
        credentialDigest = "digest-of-the-client",
    )

    /**
     * The argv the supervisor asked for.
     */
    private fun argvOf(request: TaskExecutionRequest): List<String> =
        (request.task as TaskSpec.ExecTask).argv

    /**
     * The receipt the child of [request] printed.
     */
    private fun receiptOf(request: TaskExecutionRequest): OutputChunk {
        val argv = argvOf(request)
        val correlation = argv[argv.indexOf("--correlation-id") + 1]
        return OutputChunk.stdout(
            (
                """{"schema":"pipelinek.launch/1","runId":"run-minted-by-the-child",""" +
                    """"correlationId":"$correlation","ownerId":"owner-1",""" +
                    """"startedAtMs":1700,"status":"Launched"}""" + "\n"
                ).toByteArray(),
        )
    }

    /**
     * A port that launches a child that behaves the way the test says.
     */
    private class FakePort(
        private val behaviour: suspend (TaskExecutionRequest, ExecutionOutputSink) -> Unit,
    ) : DurableTaskRuntime {

        /** The requests the supervisor asked for. */
        val requests = mutableListOf<TaskExecutionRequest>()

        override suspend fun execute(
            request: TaskExecutionRequest,
            outputSink: ExecutionOutputSink,
        ): TaskExecutionResult {
            synchronized(requests) { requests += request }
            behaviour(request, outputSink)
            return TaskExecutionResult(exitCode = 0, startedAtEpochMs = 0L, endedAtEpochMs = 0L)
        }
    }
}
