package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The broker of a run, on its way from the service to the argv of the child.
 *
 * ## The guarantees under trial
 *
 *  - a service that holds no brokers launches the child it always launched: its argv names no
 *    broker;
 *  - a broker that cannot be opened stops the run BEFORE the child exists, by name, with words
 *    that were not read from the machine, and the slot of the budget goes back;
 *  - the grants a run gets are looked up with the digest of the client that asked, and the lookup
 *    happens only for a run that is going to be launched.
 *
 * ## Why the port is faked here
 *
 * The rows are about the CONVERSATION between the supervisor, the factory and the port: what is
 * asked of each and in which order. Rows that need a broker that really binds and really answers
 * live in the integration lane, next to the sockets they need.
 *
 * ## What this does not freeze
 *
 * The name of the directory of a broker and the position of the option inside the argv: only that
 * the option is there, followed by the directory, when there is a broker, and absent when there
 * is none.
 */
class RunBrokerDeliveryTest {

    @Test
    fun `a service without brokers launches a child whose argv names no broker`(@TempDir root: Path) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port, brokers = null)

        val outcome = supervisor.start(request())

        assertInstanceOf(ServiceStartOutcome.Accepted::class.java, outcome)
        assertFalse(
            argvOf(port.requests.single()).contains(CREDENTIAL_BROKER_OPTION),
            "the argv of a run that was given no broker is the argv it always was",
        )
    }

    @Test
    fun `a broker that cannot be opened stops the run before the child exists and says why in fixed words`(
        @TempDir root: Path,
    ) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val tooDeep = root.resolve("b".repeat(130))
        val supervisor = supervisor(root, port, RunBrokerFactory(tooDeep, null) { emptySet() })

        val outcome = supervisor.start(request())

        val refused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, outcome)
        val refusal = assertInstanceOf(ServiceRefusal.RunStartRefused::class.java, refused.refusal)
        assertEquals("the-credential-broker-could-not-start", refusal.reason)
        assertEquals(
            "the socket path of a run broker is longer than a socket can be bound to",
            refusal.detail,
        )
        assertFalse(refusal.detail.contains(root.toString()), "nothing the machine holds is in the words")
        assertTrue(port.requests.isEmpty(), "the port was not asked to launch a child that has no broker")
        assertEquals(0, supervisor.supervisedRunsInFlight(), "the slot of the budget went back")
        assertFalse(Files.exists(tooDeep), "nothing was created for a broker that was never opened")
    }

    @Test
    fun `the grants of a run are looked up with the digest of the client that asked`(@TempDir root: Path) {
        val looked = mutableListOf<String?>()
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val factory = RunBrokerFactory(root.resolve("b".repeat(130)), null) { digest ->
            looked += digest
            emptySet()
        }
        val supervisor = supervisor(root, port, factory)

        supervisor.start(request(clientDigest = "digest-of-the-first-client"))
        supervisor.start(request(clientDigest = "digest-of-the-second-client"))

        assertEquals(
            listOf<String?>("digest-of-the-first-client", "digest-of-the-second-client"),
            looked,
            "each run is granted what ITS client was granted, not what the last one was",
        )
    }

    @Test
    fun `a start that is refused before the launch looks up no grants and opens no broker`(@TempDir root: Path) {
        val looked = mutableListOf<String?>()
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val factory = RunBrokerFactory(root.resolve("b".repeat(130)), null) { digest ->
            looked += digest
            emptySet()
        }
        val supervisor = supervisor(root, port, factory)

        val outcome = supervisor.start(request(script = "absent.kts"))

        assertInstanceOf(ServiceStartOutcome.Refused::class.java, outcome)
        assertTrue(looked.isEmpty(), "a run that is not going to exist is granted nothing")
        assertTrue(port.requests.isEmpty())
    }

    /**
     * The supervisor of the runs, over a port that is [port] and brokers that are [brokers].
     */
    private fun supervisor(
        root: Path,
        port: DurableTaskRuntime,
        brokers: RunBrokerFactory?,
    ): ServiceRunSupervisor {
        val scripts = Files.createDirectories(root.resolve("scripts"))
        Files.writeString(scripts.resolve("build.kts"), "print(1)")
        val reading = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))
        val registry = assertInstanceOf(ServiceStartRegistryReading.Read::class.java, reading).registry
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
            ),
            port = port,
            registry = registry,
            limits = ServiceStartLimits(
                maxSupervisedRuns = 4,
                startDeadlineMillis = 5_000L,
            ),
            supervisionRoot = root.resolve("supervisions"),
            collaborators = ServiceCollaborators(brokers = brokers),
        )
    }

    /**
     * The request to start a run, asked by the client whose digest is [clientDigest].
     */
    private fun request(
        script: String = "build.kts",
        clientDigest: String = "digest-of-the-client",
    ): ServiceStartRequest = ServiceStartRequest(
        script = script,
        workspace = null,
        idempotencyKey = null,
        credentialDigest = clientDigest,
    )

    /**
     * The argv of the child the supervisor asked for.
     */
    private fun argvOf(request: TaskExecutionRequest): List<String> =
        (request.task as TaskSpec.ExecTask).argv

    /**
     * The receipt the child of [request] printed: it carries the correlation the child was
     * given, as a real child does.
     */
    private fun receiptOf(request: TaskExecutionRequest): OutputChunk {
        val argv = argvOf(request)
        val correlation = argv[argv.indexOf("--correlation-id") + 1]
        return OutputChunk.stdout(
            (
                """{"schema":"pipelinek.launch/1","runId":"run-1","correlationId":"$correlation",""" +
                    """"ownerId":"owner-1","startedAtMs":1700,"status":"Launched"}""" + "\n"
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

    private companion object {
        const val CREDENTIAL_BROKER_OPTION = "--credential-broker"
    }
}
