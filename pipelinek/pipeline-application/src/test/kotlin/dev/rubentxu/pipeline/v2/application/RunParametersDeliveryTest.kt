package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ParameterSpec
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.dsl.RunInputs
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
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * The parameters of a run, on their way from the client to the steps.
 *
 * ## The guarantees under trial
 *
 *  - a value that does not fit the type of its parameter is refused BY NAME, and it does not
 *    occupy the client's idempotency key: the client can fix it and repeat with the same key;
 *  - the fingerprint of a request covers the parameters, so the same key with another value is
 *    a conflict and not "the same request";
 *  - the values reach the child as a FILE, and the file is private to its owner;
 *  - the child checks the values against the DECLARATION of the script, which is the truth,
 *    because the child is the one that compiles it.
 *
 * ## Why the port is faked here
 *
 * Because the supervisor's own behaviour is about the CONVERSATION with the port: what it
 * writes, in which order, and what it does when the writing fails. Whether a real process
 * starts and reads the snapshot is a fact about the machine, and the integration lane is where
 * it is observed.
 *
 * ## Why the DECLARATION is checked in two places
 *
 * Because there are two authorities and two moments: the service checks the values against the
 * contract the CLIENT carried, before it claims anything; the child checks the same values
 * against the declaration of the SCRIPT, before the body runs. The rows of this file cover
 * both, because a value that only one of them checks is a value that half the product trusts.
 */
class RunParametersDeliveryTest {

    private val contract = listOf(
        ParameterSpec(name = "target", type = RunInputs.TYPE_STRING, defaultValue = "staging"),
        ParameterSpec(name = "dryRun", type = RunInputs.TYPE_BOOLEAN),
    )

    @Test
    fun `a value that does not fit the type of its parameter is refused by name`(@TempDir root: Path) {
        val supervisor = supervisor(root, FakePort { request, sink -> sink.append(receiptOf(request)) })

        val outcome = supervisor.start(
            request(parameters = mapOf("dryRun" to "yes"), contract = contract),
        )

        val refused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, outcome)
        val refusal = assertInstanceOf(ServiceRefusal.ParametersRefused::class.java, refused.refusal)
        assertEquals("the-parameter-has-the-wrong-type", refusal.reason)
        assertTrue(refusal.detail.contains("dryRun"), "the refusal names the parameter to fix")
    }

    @Test
    fun `a refused set of parameters does not occupy the idempotency key`(@TempDir root: Path) {
        val supervisor = supervisor(root, FakePort { request, sink -> sink.append(receiptOf(request)) })

        val refused = supervisor.start(
            request(key = "order-42", parameters = mapOf("dryRun" to "yes"), contract = contract),
        )
        assertInstanceOf(ServiceStartOutcome.Refused::class.java, refused)

        // The SAME key, with values that fit: the client corrects its request and repeats,
        // and the key is still free.
        val accepted = supervisor.start(
            request(key = "order-42", parameters = mapOf("dryRun" to "true"), contract = contract),
        )
        assertInstanceOf(ServiceStartOutcome.Accepted::class.java, accepted)
    }

    @Test
    fun `the same key with another value is a conflict and not the same request`(@TempDir root: Path) {
        val supervisor = supervisor(root, FakePort { request, sink -> sink.append(receiptOf(request)) })

        val first = supervisor.start(
            request(key = "order-42", parameters = mapOf("dryRun" to "true"), contract = contract),
        )
        assertInstanceOf(ServiceStartOutcome.Accepted::class.java, first)

        val second = supervisor.start(
            request(key = "order-42", parameters = mapOf("dryRun" to "false"), contract = contract),
        )
        val refused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, second)
        assertEquals(ServiceRefusal.IdempotencyConflict("order-42"), refused.refusal)
    }

    @Test
    fun `values that arrive without a declaration are refused`(@TempDir root: Path) {
        val supervisor = supervisor(root, FakePort { request, sink -> sink.append(receiptOf(request)) })

        val outcome = supervisor.start(request(parameters = mapOf("dryRun" to "true")))

        val refused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, outcome)
        val refusal = assertInstanceOf(ServiceRefusal.ParametersRefused::class.java, refused.refusal)
        assertEquals("the-service-has-no-contract-for-these-parameters", refusal.reason)
    }

    @Test
    fun `a parameter the declaration does not declare is refused`(@TempDir root: Path) {
        val supervisor = supervisor(root, FakePort { request, sink -> sink.append(receiptOf(request)) })

        val outcome = supervisor.start(
            request(parameters = mapOf("dryRun" to "true", "extra" to "x"), contract = contract),
        )

        val refused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, outcome)
        val refusal = assertInstanceOf(ServiceRefusal.ParametersRefused::class.java, refused.refusal)
        assertEquals("the-parameter-is-unknown", refusal.reason)
    }

    @Test
    fun `the record of the starts carries the fingerprint of the parameters and not their values`(
        @TempDir root: Path,
    ) {
        val supervisor = supervisor(root, FakePort { request, sink -> sink.append(receiptOf(request)) })

        supervisor.start(
            request(key = "order-42", parameters = mapOf("dryRun" to "true"), contract = contract),
        )

        val recorded = Files.readString(root.resolve(ServiceStartRegistry.FILE_NAME))

        assertFalse(recorded.contains("\"true\""), "the values are not written in the record")
        assertFalse(recorded.contains("dryRun"), "the names are not written in the record")
        assertTrue(recorded.contains("\"digest\""), "what the record carries is the fingerprint")
    }

    @Test
    fun `the values of the parameters reach the child as a private file`(@TempDir root: Path) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port)

        supervisor.start(
            request(parameters = mapOf("dryRun" to "true", "target" to "prod"), contract = contract),
        )

        val argv = argvOf(port.requests.single())
        val option = argv.indexOf("--run-inputs")
        assertTrue(option > 0, "the child is told where its values are")
        val snapshot = Path.of(argv[option + 1])

        assertTrue(Files.exists(snapshot), "the snapshot exists before the child reads it")
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(snapshot),
            "the snapshot is readable by its owner and by nobody else",
        )
        val text = Files.readString(snapshot)
        assertTrue(text.contains("\"dryRun\":\"true\""), "the snapshot carries the values")
        assertTrue(text.contains("\"target\":\"prod\""), "the snapshot carries every value")
    }

    @Test
    fun `the snapshot of the parameters carries the DELIVERED values and not the defaults`(
        @TempDir root: Path,
    ) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port)

        supervisor.start(
            request(parameters = mapOf("dryRun" to "true"), contract = contract),
        )

        val argv = argvOf(port.requests.single())
        val snapshot = Path.of(argv[argv.indexOf("--run-inputs") + 1])

        assertFalse(
            Files.readString(snapshot).contains("staging"),
            "the default of the contract is the child's to apply, and not the service's to invent",
        )
    }

    @Test
    fun `a run with no parameters has no snapshot and no option in its argv`(@TempDir root: Path) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port)

        supervisor.start(request())

        val argv = argvOf(port.requests.single())
        assertFalse(
            argv.contains("--run-inputs"),
            "a run that was given nothing is the run that always was",
        )
    }

    @Test
    fun `a snapshot that cannot be written refuses the start`(@TempDir root: Path) {
        // The supervision's own directory is a FILE, so the directory of the supervision
        // cannot be made and the snapshot cannot be written.
        Files.writeString(root.resolve("supervisions"), "not a directory")
        val supervisor = supervisor(root, FakePort { request, sink -> sink.append(receiptOf(request)) })

        val refused = supervisor.start(
            request(key = "order-42", parameters = mapOf("dryRun" to "true"), contract = contract),
        )

        val refusal = assertInstanceOf(
            ServiceRefusal.ParametersRefused::class.java,
            assertInstanceOf(ServiceStartOutcome.Refused::class.java, refused).refusal,
        )
        assertEquals("the-parameters-cannot-be-written", refusal.reason)
    }

    @Test
    fun `a snapshot that cannot be written frees the key of the client`(@TempDir root: Path) {
        Files.writeString(root.resolve("supervisions"), "not a directory")
        val supervisor = supervisor(root, FakePort { request, sink -> sink.append(receiptOf(request)) })

        supervisor.start(request(key = "order-42", parameters = mapOf("dryRun" to "true"), contract = contract))

        val again = supervisor.start(
            request(key = "order-42", parameters = mapOf("dryRun" to "false"), contract = contract),
        )
        val refusal = assertInstanceOf(
            ServiceRefusal.ParametersRefused::class.java,
            assertInstanceOf(ServiceStartOutcome.Refused::class.java, again).refusal,
        )
        assertEquals("the-parameters-cannot-be-written", refusal.reason)
    }

    @Test
    fun `the parameters of a request reach the door of the starts`() {
        val starter = ServiceTestStarter()
        val operations = operations(starter)

        operations.answer(
            ServiceRequest.StartRun(
                ServiceProtocol.SCHEMA,
                "build.kts",
                null,
                null,
                emptyMap(),
                mapOf("dryRun" to "true"),
                contract,
            ),
            credentialDigest = "digest",
        )

        val asked = starter.asked.single()
        assertEquals(mapOf("dryRun" to "true"), asked.parameters)
        assertEquals(contract, asked.parameterContract)
    }

    @Test
    fun `the answer of a start carries the values the client delivered`() {
        // The echo: a service that does not know the field leaves it out and starts the run
        // without the values, and a client that cannot tell the two apart believes in values
        // that are not there. The echo turns that silence into a fact.
        val starter = ServiceTestStarter(
            ServiceStartOutcome.Accepted(
                runId = "run-1",
                correlationId = "corr-1",
                ownerId = "owner-1",
                startedAtMs = 1700,
                parameters = mapOf("dryRun" to "true"),
            ),
        )
        val operations = operations(starter)

        val answer = operations.answer(
            ServiceRequest.StartRun(
                ServiceProtocol.SCHEMA,
                "build.kts",
                null,
                null,
                emptyMap(),
                mapOf("dryRun" to "true"),
                contract,
            ),
            credentialDigest = "digest",
        )

        val answered = assertInstanceOf(ServiceResponse.Answered::class.java, answer)
        val echoed = answered.result["parameters"] as? kotlinx.serialization.json.JsonObject
        assertEquals(
            mapOf("dryRun" to "true"),
            echoed?.mapValues { (_, value) -> value.jsonPrimitive.content },
        )
    }

    @Test
    fun `the answer of a start carries no values when the client delivered none`() {
        val starter = ServiceTestStarter(
            ServiceStartOutcome.Accepted(
                runId = "run-1",
                correlationId = "corr-1",
                ownerId = "owner-1",
                startedAtMs = 1700,
            ),
        )
        val operations = operations(starter)

        val answer = operations.answer(
            ServiceRequest.StartRun(
                ServiceProtocol.SCHEMA,
                "build.kts",
                null,
                null,
                emptyMap(),
                emptyMap(),
                emptyList(),
            ),
            credentialDigest = "digest",
        )

        val answered = assertInstanceOf(ServiceResponse.Answered::class.java, answer)
        val echoed = answered.result["parameters"] as? kotlinx.serialization.json.JsonObject
        assertNotNull(echoed, "the field travels even when it carries nothing")
        assertTrue(echoed!!.isEmpty(), "and it carries nothing")
    }

    @Test
    fun `the command line refuses the snapshot of the parameters on the verb that runs nothing`() {
        val parsed = CliExecutionParser.parse(
            arrayOf("validate", "--run-inputs", "/tmp/run-inputs.json", "build.kts"),
        )

        val rejected = assertInstanceOf(CliParseResult.Rejected::class.java, parsed)
        val error = assertInstanceOf(CliError.OptionBelongsToVerb::class.java, rejected.error)
        assertEquals("--run-inputs", error.option)
        assertEquals("run", error.owner)
    }

    @Test
    fun `the command line refuses the values of the parameters on the verb that runs nothing`() {
        val parsed = CliExecutionParser.parse(
            arrayOf("validate", "--param", "target=prod", "build.kts"),
        )

        val rejected = assertInstanceOf(CliParseResult.Rejected::class.java, parsed)
        val error = assertInstanceOf(CliError.OptionBelongsToVerb::class.java, rejected.error)
        assertEquals("--param", error.option)
        assertEquals("run", error.owner)
    }

    @Test
    fun `the command line accepts the values of the parameters on the verb that runs`() {
        val parsed = CliExecutionParser.parse(
            arrayOf("run", "--param", "target=prod", "--param", "dryRun=true", "build.kts"),
        )

        val flags = assertInstanceOf(CliParseResult.Parsed::class.java, parsed).flags
        assertEquals(mapOf("target" to "prod", "dryRun" to "true"), flags.parameters)
    }

    @Test
    fun `a parameter without a value is refused by the command line`() {
        val parsed = CliExecutionParser.parse(arrayOf("run", "--param", "target", "build.kts"))

        val rejected = assertInstanceOf(CliParseResult.Rejected::class.java, parsed)
        val error = assertInstanceOf(CliError.InvalidOptionValue::class.java, rejected.error)
        assertEquals("--param", error.option)
    }

    @Test
    fun `a parameter without a name is refused by the command line`() {
        val parsed = CliExecutionParser.parse(arrayOf("run", "--param", "=prod", "build.kts"))

        val rejected = assertInstanceOf(CliParseResult.Rejected::class.java, parsed)
        val error = assertInstanceOf(CliError.InvalidOptionValue::class.java, rejected.error)
        assertEquals("--param", error.option)
    }

    @Test
    fun `a parameter delivered twice is refused by the command line`() {
        val parsed = CliExecutionParser.parse(
            arrayOf("run", "--param", "target=prod", "--param", "target=staging", "build.kts"),
        )

        val rejected = assertInstanceOf(CliParseResult.Rejected::class.java, parsed)
        val error = assertInstanceOf(CliError.InvalidOptionValue::class.java, rejected.error)
        assertEquals("--param", error.option)
    }

    @Test
    fun `the command line accepts the snapshot of the parameters on the verb that runs`(
        @TempDir root: Path,
    ) {
        val snapshot = root.resolve("run-inputs.json")
        Files.writeString(snapshot, """{"schema":"pipelinek.runinputs/1","values":{}}""")

        val parsed = CliExecutionParser.parse(
            arrayOf("run", "--run-inputs", snapshot.toString(), "build.kts"),
        )

        val flags = assertInstanceOf(CliParseResult.Parsed::class.java, parsed).flags
        assertEquals(snapshot.toString(), flags.runInputsPath)
    }

    @Test
    fun `the reader of the snapshot refuses a file that speaks another schema`(@TempDir root: Path) {
        val snapshot = root.resolve("run-inputs.json")
        Files.writeString(snapshot, """{"schema":"pipelinek.runinputs/99","values":{}}""")

        val refused = assertInstanceOf(
            CommandRefused::class.java,
            runCatching { RunInputsReader.read(snapshot.toString()) }.exceptionOrNull(),
        )
        assertEquals(2, refused.exit.code)
    }

    @Test
    fun `the reader of the snapshot refuses a file it cannot read`(@TempDir root: Path) {
        val refused = assertInstanceOf(
            CommandRefused::class.java,
            runCatching { RunInputsReader.read(root.resolve("absent.json").toString()) }
                .exceptionOrNull(),
        )
        assertEquals(2, refused.exit.code)
    }

    @Test
    fun `the reader of the snapshot accepts a snapshot that breaks no rule`(@TempDir root: Path) {
        val snapshot = root.resolve("run-inputs.json")
        Files.writeString(
            snapshot,
            """{"schema":"pipelinek.runinputs/1","values":{"dryRun":"true"}}""",
        )

        val values = RunInputsReader.read(snapshot.toString())

        assertEquals(mapOf("dryRun" to "true"), values)
    }

    @Test
    fun `the child refuses a value that does not fit the declaration of the script`() {
        val flags = flags(parameters = mapOf("dryRun" to "yes"))

        val refused = assertInstanceOf(
            CommandRefused::class.java,
            runCatching { RunParameters.of(flags, contract) }.exceptionOrNull(),
        )
        assertEquals(2, refused.exit.code, "a value that does not fit refuses the run")
    }

    @Test
    fun `the child refuses a required parameter that was not delivered`() {
        val flags = flags(parameters = mapOf("target" to "prod"))

        val refused = assertInstanceOf(
            CommandRefused::class.java,
            runCatching { RunParameters.of(flags, contract) }.exceptionOrNull(),
        )
        assertEquals(2, refused.exit.code)
    }

    @Test
    fun `the child accepts the values that fit the declaration of the script`() {
        val flags = flags(parameters = mapOf("dryRun" to "true"))

        val inputs = RunParameters.of(flags, contract)

        assertEquals(
            mapOf("target" to "staging", "dryRun" to "true"),
            inputs.values,
            "the defaults of the declaration are applied by the run that owns the declaration",
        )
    }

    @Test
    fun `the child refuses the values that came from two places`(@TempDir root: Path) {
        val snapshot = root.resolve("run-inputs.json")
        Files.writeString(
            snapshot,
            """{"schema":"pipelinek.runinputs/1","values":{"dryRun":"true"}}""",
        )
        val flags = flags(parameters = mapOf("target" to "prod"), runInputsPath = snapshot.toString())

        val refused = assertInstanceOf(
            CommandRefused::class.java,
            runCatching { RunParameters.of(flags, contract) }.exceptionOrNull(),
        )
        assertEquals(2, refused.exit.code)
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
     * The request to start a run, with the values the client delivered.
     */
    private fun request(
        key: String? = null,
        parameters: Map<String, String> = emptyMap(),
        contract: List<ParameterSpec> = emptyList(),
    ): ServiceStartRequest = ServiceStartRequest(
        script = "build.kts",
        workspace = null,
        idempotencyKey = key,
        credentialDigest = "digest-of-the-client",
        parameters = parameters,
        parameterContract = contract,
    )

    /**
     * The flags of a run that delivers its values with the options.
     */
    private fun flags(
        parameters: Map<String, String> = emptyMap(),
        runInputsPath: String? = null,
    ): CliFlags = CliFlags(
        command = CliCommand.RUN,
        dbPath = null,
        durableRunPolicy = DurableRunPolicy.ReusePriorRun,
        scriptPath = "build.kts",
        runInputsPath = runInputsPath,
        parameters = parameters,
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

    /**
     * A port of the introspection that is never asked: the rows here are about a request to
     * START a run, and a start does not read the state of anything.
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
