package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ParameterSpec
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceRunStart
import dev.rubentxu.pipeline.v2.service.ServiceStreet
import dev.rubentxu.pipeline.v2.service.ServiceStreetItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The parameters of a run, over a real socket and with real children.
 *
 * ## The guarantee under trial
 *
 * That the values a client delivers reach the CHILD, and that the child checks them against
 * the DECLARATION of the script —not against the contract the client carried— before the
 * body of the pipeline runs. The unit lane proves that the validator refuses what it must
 * refuse; this lane proves that the values TRAVEL, and that the refusal the client reads
 * names the rule that broke.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * Because the facts are about a MACHINE: a real socket, a real snapshot on the disk, a real
 * child JVM that reads it, and the real declaration that the child's own compilation
 * produced. A fake port would prove that the supervisor writes a file; it would not prove
 * that anybody READS it, nor that the values that arrived are the ones the client sent.
 *
 * ## What this does not freeze
 *
 * How long the runs take, and the words around the step's output: the observation is the
 * run's own terminal, and everything else the run prints is the run's own.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class RunParametersIntegrationTest {

    @TempDir
    lateinit var root: Path

    /** The store the service reads and the run writes. */
    private lateinit var db: Path

    /** The control root the service reads and the run writes. */
    private lateinit var control: Path

    /** The directory of the scripts a client may name. */
    private lateinit var scripts: Path

    /** The socket the service listens on. */
    private lateinit var socket: Path

    private val clock: Clock = FixedClock

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        control = Files.createDirectories(root.resolve("control"))
        scripts = Files.createDirectories(root.resolve("scripts"))
        socket = root.resolve("service.sock")
        // The script DECLARES the parameters it accepts, and the step prints a word and
        // nothing else: a step that found nothing would fail, because `echo` exits with a
        // code that is not zero only when the shell itself is broken.
        Files.writeString(
            scripts.resolve("parameters.pipeline.kts"),
            "pipeline {\n" +
                "    parameters {\n" +
                "        string(\"target\", default = \"staging\")\n" +
                "        boolean(\"dryRun\")\n" +
                "    }\n" +
                "    stages {\n" +
                "        stage(\"eager\") {\n" +
                "            sh(\"echo the-body-ran\")\n" +
                "        }\n" +
                "    }\n" +
                "}\n",
        )
        // A second script, whose step asks the ENVIRONMENT for the name of a parameter.
        // The step says ABSENT when there is none, so the answer is a WORD and not a
        // failure: a step that found no variable would fail, and a failed step says
        // nothing about where the value went.
        Files.writeString(
            scripts.resolve("environment.pipeline.kts"),
            "pipeline {\n" +
                "    parameters {\n" +
                "        string(\"BUILD_TARGET\")\n" +
                "    }\n" +
                "    stages {\n" +
                "        stage(\"eager\") {\n" +
                "            sh(\"printenv BUILD_TARGET || echo the-value-is-absent\")\n" +
                "        }\n" +
                "    }\n" +
                "}\n",
        )
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val writable = (admission as EventStoreAdmission.Admitted).store
        DurableStores.open(writable, control, clock)
        writable.close()
    }

    @Test
    fun `the values a client delivers reach the child and the run runs`() {
        val service = start()
        try {
            val answer = client(STARTER_TOKEN).startRun(
                script = "parameters.pipeline.kts",
                idempotencyKey = "key-with-parameters",
                parameters = mapOf("dryRun" to "false"),
                parameterContract = CONTRACT,
            )

            val started = assertInstanceOf(ServiceRunStart::class.java, assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                answer,
            ).value)
            assertEquals(
                mapOf("dryRun" to "false"),
                started.parameters,
                "the answer carries the values the client delivered",
            )

            val terminal = awaitTerminal(started.runId)
            assertInstanceOf(
                TerminalObservation.Succeeded::class.java,
                terminal,
                "the child checked the values against the declaration of the script and ran the body",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a value that does not fit the type is refused by name over the wire`() {
        val service = start()
        try {
            val answer = client(STARTER_TOKEN).startRun(
                script = "parameters.pipeline.kts",
                idempotencyKey = "key-wrong-type",
                parameters = mapOf("dryRun" to "yes"),
                parameterContract = CONTRACT,
            )

            val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
            val named = assertInstanceOf(ServiceRefusal.ParametersRefused::class.java, refusal)
            assertEquals(
                "the-parameter-has-the-wrong-type",
                named.reason,
                "the wire carries the NAME of the rule the value broke",
            )
            assertTrue(
                named.detail.contains("dryRun"),
                "and the detail says WHICH parameter: ${named.detail}",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `a refused request does not occupy the key and the same key starts a good run`() {
        val service = start()
        try {
            val refused = client(STARTER_TOKEN).startRun(
                script = "parameters.pipeline.kts",
                idempotencyKey = "key-reused",
                parameters = mapOf("dryRun" to "yes"),
                parameterContract = CONTRACT,
            )
            assertInstanceOf(ServiceAnswer.Refused::class.java, refused)

            val answer = client(STARTER_TOKEN).startRun(
                script = "parameters.pipeline.kts",
                idempotencyKey = "key-reused",
                parameters = mapOf("dryRun" to "false"),
                parameterContract = CONTRACT,
            )

            val started = assertInstanceOf(ServiceRunStart::class.java, assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                answer,
            ).value)
            assertInstanceOf(
                TerminalObservation.Succeeded::class.java,
                awaitTerminal(started.runId),
                "the refusal left the key free, and the client could correct and repeat",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `values without a contract are refused by name`() {
        val service = start()
        try {
            val answer = client(STARTER_TOKEN).startRun(
                script = "parameters.pipeline.kts",
                idempotencyKey = "key-without-contract",
                parameters = mapOf("dryRun" to "false"),
            )

            val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
            val named = assertInstanceOf(ServiceRefusal.ParametersRefused::class.java, refusal)
            assertEquals(
                "the-service-has-no-contract-for-these-parameters",
                named.reason,
                "the service cannot check what it cannot know, and it says so by name",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `the service does not describe the parameters of a script and says so by name`() {
        val service = start()
        try {
            val answer = client(STARTER_TOKEN).describeParameters("parameters.pipeline.kts")

            val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer).refusal
            val named = assertInstanceOf(ServiceRefusal.DescribeParametersRefused::class.java, refusal)
            assertEquals(
                "the-service-does-not-describe-parameters",
                named.reason,
                "describing a script is compiling it, and the service does not compile scripts",
            )
        } finally {
            service.stop()
        }
    }

    @Test
    fun `the values of the parameters do not become the environment of the steps`() {
        val service = start()
        try {
            val answer = client(STARTER_TOKEN).startRun(
                script = "environment.pipeline.kts",
                idempotencyKey = "key-environment",
                parameters = mapOf("BUILD_TARGET" to "prod"),
                parameterContract = listOf(ParameterSpec(name = "BUILD_TARGET", type = "string")),
            )

            val started = assertInstanceOf(ServiceRunStart::class.java, assertInstanceOf(
                ServiceAnswer.Answered::class.java,
                answer,
            ).value)
            awaitTerminal(started.runId)

            val output = outputOf(started.runId)
            assertTrue(
                output.contains("the-value-is-absent"),
                "the step asked the environment for the name of a parameter and found none: $output",
            )
            assertFalse(
                output.contains("prod"),
                "the value of a parameter did not become a variable of the step: $output",
            )
        } finally {
            service.stop()
        }
    }

    /**
     * The stdout of [runId], read over the socket.
     */
    private fun outputOf(runId: String): String {
        val answer = client(STARTER_TOKEN).output(runId)
        val street = assertInstanceOf(ServiceStreet::class.java, assertInstanceOf(
            ServiceAnswer.Answered::class.java,
            answer,
        ).value)
        val text = StringBuilder()
        street.use {
            while (true) {
                when (val item = it.next()) {
                    is ServiceStreetItem.Record -> {
                        val document = item.document
                        val channel = document["channel"]?.jsonPrimitive?.content
                        val line = document["text"]?.jsonPrimitive?.content
                        if (channel == "stdout" && line != null) text.append(line).append('\n')
                    }

                    is ServiceStreetItem.End -> return text.toString()
                    is ServiceStreetItem.Refused -> error("the street was refused: ${item.refusal}")
                    null -> return text.toString()
                }
            }
        }
    }

    /**
     * The terminal observation of [runId], asked for until it arrives.
     */
    private fun awaitTerminal(runId: String): TerminalObservation {
        val deadline = System.currentTimeMillis() + TERMINAL_DEADLINE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            when (val answer = client(STARTER_TOKEN).runState(runId)) {
                is ServiceAnswer.Answered -> {
                    val observation = answer.value
                    if (observation is RuntimeObservation.Terminal) return observation.terminal
                }

                else -> Unit
            }
            Thread.sleep(500L)
        }
        error("the run $runId did not finish in time")
    }

    /**
     * The service, over a real socket, and the way to stop it.
     */
    private inner class Service(
        private val server: ServiceServer,
        private val store: dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore,
        private val supervisor: ServiceRunSupervisor,
    ) {

        private val thread = Thread({ server.serve() }, "service-under-test").apply {
            isDaemon = true
            start()
        }

        /** Stops the service and the runs it supervises. */
        fun stop() {
            server.close()
            supervisor.close()
            thread.join(10_000L)
            store.close()
        }
    }

    /**
     * Starts the service over a real socket, with the real supervisor.
     */
    private fun start(): Service {
        val admission = admitEventStore(db.toString(), EventStorePurpose.Read)
        val store = (admission as EventStoreAdmission.Admitted).store
        val supervisionRoot = root.resolve("supervisions")
        val registry = when (val read = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))) {
            is ServiceStartRegistryReading.Read -> read.registry
            is ServiceStartRegistryReading.Refused -> error(read.detail)
        }
        val supervisor = ServiceRunSupervisor(
            admission = ServiceStartAdmission(scripts, null),
            runPolicy = ServiceRunPolicy(
                eventStore = db.toAbsolutePath().toString(),
                controlRoot = control.toAbsolutePath().toString(),
                deadlineMillis = 240_000L,
                allowNetwork = false,
            ),
            command = ServiceRunCommand(
                javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                classpath = System.getProperty("java.class.path"),
                mainClass = "dev.rubentxu.pipeline.v2.application.MainKt",
            ),
            port = ProcessDurableTaskRuntime(supervisionRoot, clock),
            registry = registry,
            limits = ServiceStartLimits(maxSupervisedRuns = 2, startDeadlineMillis = 120_000L),
            supervisionRoot = supervisionRoot,
        )
        val operations = ServiceOperations(
            introspection = composeRuntimeIntrospectionPort(
                eventStore = store,
                controlRoot = control,
                clock = clock,
            ),
            substrate = {
                ServiceSubstrate(
                    eventStore = db.toString(),
                    controlRoot = control.toString(),
                    eventStorePresent = Files.exists(db),
                    controlRootPresent = Files.exists(control),
                )
            },
            starts = supervisor,
            cancellations = null,
        )
        val stores = DurableStores.open(store, control, clock)
        val streets = ServiceSubscription(
            lanes = ComposeLanes.forService(store, control),
            events = composeEventRecordReadPort(store, stores.lease),
        )
        val transport = UnixSocketTransport.bind(socket)
        return Service(
            ServiceServer(transport, operations, streets, authorizations()),
            store,
            supervisor,
        )
    }

    /**
     * The authorizations this service knows.
     */
    private fun authorizations(): ServiceAuthorizations = ServiceAuthorizations.of(
        listOf(
            ServiceAuthorization(READER_TOKEN, "reader", ServiceScope.All),
            ServiceAuthorization(
                STARTER_TOKEN,
                "starter",
                ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start)),
            ),
        ),
    )

    /**
     * A client that presents [token].
     */
    private fun client(token: String): ServiceClient = ServiceClient(
        endpoint = socket,
        credential = token,
        deadline = java.time.Duration.ofSeconds(240),
        schema = dev.rubentxu.pipeline.v2.service.ServiceProtocol.SCHEMA,
    )

    /** The clock this lane stamps its material with: it does not move. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {

        /** The token of the client that may only read. */
        private const val READER_TOKEN: String = "token-of-the-reader"

        /** The token of the client that may start runs. */
        private const val STARTER_TOKEN: String = "token-of-the-starter"

        /** How long the test waits for a run to finish. */
        private const val TERMINAL_DEADLINE_MILLIS: Long = 180_000L

        /**
         * The declaration the client carries: the one the script declares.
         *
         * It travels with the request because the service does not compile the script, and
         * it is DATA: the child checks the values against the declaration of the SCRIPT.
         */
        private val CONTRACT: List<ParameterSpec> = listOf(
            ParameterSpec(name = "target", type = "string", defaultValue = "staging"),
            ParameterSpec(name = "dryRun", type = "boolean"),
        )
    }
}
