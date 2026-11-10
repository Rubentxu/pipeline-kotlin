package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.runtime.inspect.IntrospectionRefusal
import dev.rubentxu.pipeline.v2.runtime.inspect.OperationInspectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RunStructureResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPort
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.StructureRunOptions
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceRequest
import dev.rubentxu.pipeline.v2.service.ServiceResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * The configuration of a run, on its way from the client to the steps.
 *
 * ## The guarantees under trial
 *
 *  - a configuration that breaks a rule is refused BY NAME, and it does not
 *    occupy the client's idempotency key: the client can fix it and repeat
 *    with the same key;
 *  - the fingerprint of a request covers the configuration, so the same key
 *    with another configuration is a conflict and not "the same request";
 *  - the snapshot of the configuration reaches the child as a FILE, and a
 *    snapshot that cannot be written refuses the start instead of starting
 *    a run whose configuration does not exist;
 *  - the answer carries the configuration, on every path, so a client can
 *    compare it with what it sent.
 *
 * ## Why the port is faked here
 *
 * Because the supervisor's own behaviour is about the CONVERSATION with the
 * port: what it writes, in which order, and what it does when the writing
 * fails. Whether a real process starts and reads the snapshot is a fact
 * about the machine, and the integration lane is where it is observed.
 *
 * ## Why the fake child ECHOES the correlation
 *
 * Because that is what a real child does: it prints the correlation it was
 * given. A fake that printed a constant would make the receipt of every
 * launch look like the receipt of another request, and the check that the
 * receipt IS this request's could not be told apart from the check that it
 * is not.
 */
class RunConfigurationDeliveryTest {

    @Test
    fun `a configuration that breaks a rule is refused by name`(@TempDir root: Path) {
        val supervisor = supervisor(root, FakePort { request, sink -> sink.append(receiptOf(request)) })

        val outcome = supervisor.start(request(configuration = mapOf("LD_PRELOAD" to "/tmp/x.so")))

        val refused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, outcome)
        val refusal = assertInstanceOf(ServiceRefusal.RunConfigurationRefused::class.java, refused.refusal)
        assertEquals("the-configuration-name-is-denied", refusal.reason)
    }

    @Test
    fun `a refused configuration does not occupy the idempotency key`(@TempDir root: Path) {
        val supervisor = supervisor(root, FakePort { request, sink -> sink.append(receiptOf(request)) })

        val refused = supervisor.start(
            request(key = "order-42", configuration = mapOf("LD_PRELOAD" to "/tmp/x.so")),
        )
        assertInstanceOf(ServiceStartOutcome.Refused::class.java, refused)

        // The SAME key, with a configuration that breaks no rule: the client
        // corrects its request and repeats, and the key is still free.
        val accepted = supervisor.start(
            request(key = "order-42", configuration = mapOf("BUILD_PROFILE" to "release")),
        )
        assertInstanceOf(ServiceStartOutcome.Accepted::class.java, accepted)
    }

    @Test
    fun `the same key with another configuration is a conflict and not the same request`(
        @TempDir root: Path,
    ) {
        val supervisor = supervisor(root, FakePort { request, sink -> sink.append(receiptOf(request)) })

        val first = supervisor.start(
            request(key = "order-42", configuration = mapOf("BUILD_PROFILE" to "release")),
        )
        assertInstanceOf(ServiceStartOutcome.Accepted::class.java, first)

        val second = supervisor.start(
            request(key = "order-42", configuration = mapOf("BUILD_PROFILE" to "debug")),
        )
        val refused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, second)
        assertEquals(ServiceRefusal.IdempotencyConflict("order-42"), refused.refusal)
    }

    @Test
    fun `the snapshot of the configuration reaches the child as a private file`(
        @TempDir root: Path,
    ) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port)

        val accepted = assertInstanceOf(
            ServiceStartOutcome.Accepted::class.java,
            supervisor.start(request(configuration = mapOf("BUILD_PROFILE" to "release"))),
        )

        val argv = argvOf(port.requests.single())
        val option = argv.indexOf("--run-config")
        assertTrue(option > 0, "the child is told where its configuration is")
        val snapshot = Path.of(argv[option + 1])

        assertTrue(Files.exists(snapshot), "the snapshot exists before the child reads it")
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(snapshot),
            "the snapshot is readable by its owner and by nobody else",
        )
        assertTrue(
            Files.readString(snapshot).contains("\"BUILD_PROFILE\":\"release\""),
            "the snapshot carries the values the client sent",
        )
        assertEquals(
            mapOf("BUILD_PROFILE" to "release"),
            accepted.configuration.values,
            "the answer carries the configuration the run will have",
        )
    }

    @Test
    fun `a run with no configuration has no snapshot and no option in its argv`(
        @TempDir root: Path,
    ) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port)

        supervisor.start(request())

        val argv = argvOf(port.requests.single())
        assertFalse(
            argv.contains("--run-config"),
            "a run that was given nothing is the run that always was",
        )
    }

    @Test
    fun `a snapshot that cannot be written refuses the start`(@TempDir root: Path) {
        // The supervision's own directory is a FILE, so the directory of the
        // supervision cannot be made and the snapshot cannot be written.
        Files.writeString(root.resolve("supervisions"), "not a directory")
        val supervisor = supervisor(root, FakePort { request, sink -> sink.append(receiptOf(request)) })

        val refused = supervisor.start(
            request(key = "order-42", configuration = mapOf("BUILD_PROFILE" to "release")),
        )

        val refusal = assertInstanceOf(
            ServiceRefusal.RunConfigurationRefused::class.java,
            assertInstanceOf(ServiceStartOutcome.Refused::class.java, refused).refusal,
        )
        assertEquals("the-configuration-cannot-be-written", refusal.reason)
    }

    @Test
    fun `a snapshot that cannot be written frees the key of the client`(@TempDir root: Path) {
        Files.writeString(root.resolve("supervisions"), "not a directory")
        val supervisor = supervisor(root, FakePort { request, sink -> sink.append(receiptOf(request)) })

        supervisor.start(request(key = "order-42", configuration = mapOf("BUILD_PROFILE" to "release")))

        // The SAME key, with another configuration: a key that was still
        // claimed would answer with a conflict, and a conflict would say
        // that the client may not repeat. The refusal of the snapshot says
        // the opposite: nothing started, so the key is free.
        val again = supervisor.start(
            request(key = "order-42", configuration = mapOf("BUILD_PROFILE" to "debug")),
        )
        val refusal = assertInstanceOf(
            ServiceRefusal.RunConfigurationRefused::class.java,
            assertInstanceOf(ServiceStartOutcome.Refused::class.java, again).refusal,
        )
        assertEquals("the-configuration-cannot-be-written", refusal.reason)
    }

    @Test
    fun `the answer carries the configuration of the request that is answered`(@TempDir root: Path) {
        val supervisor = supervisor(root, FakePort { request, sink -> sink.append(receiptOf(request)) })
        val operations = operations(supervisor)

        val response = operations.answer(
            ServiceRequest.StartRun(
                ServiceProtocol.SCHEMA,
                "build.kts",
                null,
                "order-42",
                mapOf("BUILD_PROFILE" to "release"),
            ),
            credentialDigest = "digest",
        )

        val result = assertInstanceOf(ServiceResponse.Answered::class.java, response).result
        val configuration = assertInstanceOf(JsonObject::class.java, result["configuration"])
        assertEquals(
            "release",
            configuration["BUILD_PROFILE"]?.jsonPrimitive?.content,
            "the answer carries the configuration the client sent",
        )
    }

    @Test
    fun `the configuration of a request reaches the door of the starts`() {
        val starter = ServiceTestStarter()
        val operations = operations(starter)

        operations.answer(
            ServiceRequest.StartRun(
                ServiceProtocol.SCHEMA,
                "build.kts",
                null,
                null,
                mapOf("BUILD_PROFILE" to "release"),
            ),
            credentialDigest = "digest",
        )

        assertEquals(
            mapOf("BUILD_PROFILE" to "release"),
            starter.asked.single().configuration,
            "the configuration travels with the request to the door",
        )
    }

    @Test
    fun `the command line refuses the snapshot of a configuration on the verb that runs nothing`() {
        val parsed = CliExecutionParser.parse(
            arrayOf("validate", "--run-config", "/tmp/run-config.json", "build.kts"),
        )

        val rejected = assertInstanceOf(CliParseResult.Rejected::class.java, parsed)
        val error = assertInstanceOf(CliError.OptionBelongsToVerb::class.java, rejected.error)
        assertEquals("--run-config", error.option)
        assertEquals("run", error.owner)
    }

    @Test
    fun `the command line accepts the snapshot of a configuration on the verb that runs`(
        @TempDir root: Path,
    ) {
        val snapshot = root.resolve("run-config.json")
        Files.writeString(
            snapshot,
            """{"schema":"pipelinek.runconfig/1","values":{"BUILD_PROFILE":"release"}}""",
        )

        val parsed = CliExecutionParser.parse(
            arrayOf("run", "--run-config", snapshot.toString(), "build.kts"),
        )

        val flags = assertInstanceOf(CliParseResult.Parsed::class.java, parsed).flags
        assertEquals(snapshot.toString(), flags.runConfigPath)
    }

    @Test
    fun `the reader of the snapshot applies the rules of the configuration`(@TempDir root: Path) {
        val snapshot = root.resolve("run-config.json")
        Files.writeString(
            snapshot,
            """{"schema":"pipelinek.runconfig/1","values":{"LD_PRELOAD":"/tmp/x.so"}}""",
        )

        val refused = assertInstanceOf(
            CommandRefused::class.java,
            runCatching { RunConfigurationReader.read(snapshot.toString()) }.exceptionOrNull(),
        )
        assertEquals(2, refused.exit.code, "a snapshot that breaks a rule refuses the run")
    }

    @Test
    fun `the reader of the snapshot refuses a file that speaks another schema`(@TempDir root: Path) {
        val snapshot = root.resolve("run-config.json")
        Files.writeString(snapshot, """{"schema":"pipelinek.runconfig/99","values":{}}""")

        val refused = assertInstanceOf(
            CommandRefused::class.java,
            runCatching { RunConfigurationReader.read(snapshot.toString()) }.exceptionOrNull(),
        )
        assertEquals(2, refused.exit.code)
    }

    @Test
    fun `the reader of the snapshot refuses a file it cannot read`(@TempDir root: Path) {
        val refused = assertInstanceOf(
            CommandRefused::class.java,
            runCatching { RunConfigurationReader.read(root.resolve("absent.json").toString()) }
                .exceptionOrNull(),
        )
        assertEquals(2, refused.exit.code)
    }

    @Test
    fun `the reader of the snapshot accepts a snapshot that breaks no rule`(@TempDir root: Path) {
        val snapshot = root.resolve("run-config.json")
        Files.writeString(
            snapshot,
            """{"schema":"pipelinek.runconfig/1","values":{"BUILD_PROFILE":"release"}}""",
        )

        val configuration = RunConfigurationReader.read(snapshot.toString())

        assertEquals(mapOf("BUILD_PROFILE" to "release"), configuration.values)
    }

    /**
     * The operations over a door that is [starts].
     */
    private fun operations(starts: ServiceRunStarter): ServiceOperations = ServiceOperations(
        introspection = UnaskedPort,
        substrate = {
            ServiceSubstrate(
                eventStore = "/tmp/state/pipelinek.db",
                controlRoot = "/tmp/state/ctl",
                eventStorePresent = true,
                controlRootPresent = true,
            )
        },
        starts = starts,
        cancellations = null,
    )

    /**
     * The supervisor of the runs, over a port that is [port].
     */
    private fun supervisor(root: Path, port: DurableTaskRuntime): ServiceRunSupervisor {
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
        )
    }

    /**
     * The request to start a run, with the configuration the client sent.
     */
    private fun request(
        key: String? = null,
        configuration: Map<String, String> = emptyMap(),
    ): ServiceStartRequest = ServiceStartRequest(
        script = "build.kts",
        workspace = null,
        idempotencyKey = key,
        credentialDigest = "digest-of-the-client",
        configuration = configuration,
    )

    /**
     * The argv of the child the supervisor asked for.
     */
    private fun argvOf(request: TaskExecutionRequest): List<String> =
        (request.task as TaskSpec.ExecTask).argv

    /**
     * The receipt the child of [request] printed: it carries the correlation
     * the child was given, as a real child does.
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

    /**
     * A port of the introspection that is never asked: the rows here are
     * about a request to START a run, and a start does not read the state of
     * anything.
     */
    private object UnaskedPort : RuntimeIntrospectionPort {

        override fun inspect(runId: String): RuntimeIntrospectionResult =
            RuntimeIntrospectionResult.Refused(IntrospectionRefusal.UnknownRun(runId))

        override fun runStructure(
            runId: String,
            options: StructureRunOptions,
        ): RunStructureResult =
            RunStructureResult.Refused(IntrospectionRefusal.UnknownRun(runId))

        override fun inspectOperation(operationId: String, attempt: Int): OperationInspectionResult =
            OperationInspectionResult.Refused(IntrospectionRefusal.UnknownRun(operationId))
    }
}
