package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.SelectorCompileResult
import dev.rubentxu.pipeline.v2.application.observation.compileQuery
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.ParameterSpec
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptCache
import dev.rubentxu.pipeline.v2.service.ServiceAnswer
import dev.rubentxu.pipeline.v2.service.ServiceClient
import dev.rubentxu.pipeline.v2.service.ServiceParameterDescription
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceRunStart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The description of a script's parameters, served by the composition the operator runs.
 *
 * ## The guarantee under trial
 *
 * That the manifest `pipelinek validate --parameter-manifest` writes for a script is the one the
 * service describes the script with, over a real socket; that the declaration the description
 * carries starts a run the child accepts; that describing a script runs none of its code; and
 * that a script without an approved manifest, or one that changed after its manifest was
 * approved, is refused by name.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * Because the chain crosses the machine: the verb an operator runs writes a file, the service
 * the verb `serve` composes reads it next to the script, a client reads the answer from a
 * socket, and a child JVM checks the values against the declaration its own compilation built.
 * The unit lane proves each link; this lane proves that the links are the same ones.
 *
 * ## How "runs none of its code" is observed
 *
 * The script writes a marker file from its top level. `validate` evaluates the script, so the
 * marker appears when the manifest is written: that is the proof that the marker WOULD appear
 * if anything evaluated the script. It is then removed, and a description that compiled or
 * evaluated the script would write it again.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ServiceParameterDescriptionIntegrationTest {

    @TempDir
    lateinit var root: Path

    private lateinit var db: Path
    private lateinit var control: Path
    private lateinit var scripts: Path
    private lateinit var socket: Path
    private lateinit var marker: Path

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        control = Files.createDirectories(root.resolve("control"))
        scripts = Files.createDirectories(root.resolve("scripts"))
        socket = root.resolve("service.sock")
        marker = root.resolve("the-top-level-ran")
        Files.writeString(scripts.resolve(SCRIPT), declaring(marker))
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val writable = (admission as EventStoreAdmission.Admitted).store
        DurableStores.open(writable, control, SystemClock())
        writable.close()
    }

    @Test
    fun `the manifest validate writes describes the script, and the described declaration starts the run`() {
        approve(SCRIPT)
        val service = serve()
        try {
            val described = assertInstanceOf(
                ServiceParameterDescription::class.java,
                assertInstanceOf(ServiceAnswer.Answered::class.java, client().describeParameters(SCRIPT)).value,
            )

            assertEquals(DECLARED, described.contract, "the description carries the declaration the script builds")
            assertEquals(
                JsonArray(listOf(JsonPrimitive("dryRun"))),
                described.description["required"],
                "the parameter without a default is the one a caller has to deliver",
            )
            assertEquals(
                JsonArray(listOf(JsonPrimitive("eu"), JsonPrimitive("us"))),
                described.description["properties"]?.jsonObject?.get("region")?.jsonObject?.get("enum"),
                "a choice is described with the texts it accepts",
            )

            val answer = client().startRun(
                script = SCRIPT,
                idempotencyKey = "key-of-the-described-run",
                parameters = mapOf("dryRun" to "false"),
                parameterContract = described.contract,
            )
            val started = assertInstanceOf(
                ServiceRunStart::class.java,
                assertInstanceOf(ServiceAnswer.Answered::class.java, answer, "the start was refused: $answer").value,
            )
            assertInstanceOf(
                TerminalObservation.Succeeded::class.java,
                awaitTerminal(started.runId),
                "the child checked the values against its own declaration and ran the body",
            )
        } finally {
            service.close()
        }
    }

    @Test
    fun `describing a script runs none of its code`() {
        approve(SCRIPT)
        assertTrue(Files.exists(marker), "validate evaluates the script, so its top level wrote the marker")
        Files.delete(marker)
        val service = serve()
        try {
            assertInstanceOf(ServiceAnswer.Answered::class.java, client().describeParameters(SCRIPT))

            assertFalse(Files.exists(marker), "the description evaluated the script: its top level ran")
        } finally {
            service.close()
        }
    }

    @Test
    fun `a script without an approved manifest is refused by name`() {
        val service = serve()
        try {
            assertRefusedWith("the-script-has-no-approved-manifest", client().describeParameters(SCRIPT))
        } finally {
            service.close()
        }
    }

    @Test
    fun `a script that changed after its manifest was approved is refused by name`() {
        approve(SCRIPT)
        Files.writeString(scripts.resolve(SCRIPT), declaring(marker) + "// a change after the approval\n")
        val service = serve()
        try {
            assertRefusedWith(
                "the-script-changed-since-its-manifest-was-approved",
                client().describeParameters(SCRIPT),
            )
        } finally {
            service.close()
        }
    }

    /**
     * Writes the manifest of [name] the way an operator does: `validate --parameter-manifest`
     * prints it, and the operator places it next to the script.
     */
    private fun approve(name: String) {
        val script = scripts.resolve(name)
        val config = (
            CliExecutionParser.parse(arrayOf("validate", "--parameter-manifest", script.toString())) as CliParseResult.Parsed
            ).flags
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = validateScript(
            config = config,
            scriptPath = script,
            secretPatternRegistry = SecretPatternRegistry(),
            compiledQuery = (compileQuery(config.query) as SelectorCompileResult.Ok).value,
            outputProtocol = OutputProtocol.Published,
            cache = CompiledScriptCache.Disabled,
            out = PrintStream(out, true, Charsets.UTF_8.name()),
            err = PrintStream(err, true, Charsets.UTF_8.name()),
        )
        assertEquals(CliExit.SUCCESS, exit, "validate wrote no manifest: ${err.toString(Charsets.UTF_8)}")
        Files.writeString(scripts.resolve(name + ParameterManifest.SUFFIX), out.toString(Charsets.UTF_8))
    }

    /** The service the verb `serve` composes, serving on its socket. */
    private fun serve(): ServeComposition.Composed {
        val registry = when (val read = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))) {
            is ServiceStartRegistryReading.Read -> read.registry
            is ServiceStartRegistryReading.Refused -> error(read.detail)
        }
        val errors = ByteArrayOutputStream()
        val composed = composeServiceToServe(
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
                deadlineMillis = 240_000L,
                startDeadlineMillis = 120_000L,
                maxSupervisedRuns = 2,
                allowNetwork = false,
                registry = registry,
            ),
            authorizations = ServiceAuthorizations.of(
                listOf(
                    ServiceAuthorization(
                        TOKEN,
                        "starter",
                        ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start)),
                    ),
                ),
            ),
            clock = SystemClock(),
            err = PrintStream(errors, true),
            openings = ServeOpenings(environment = emptyMap()),
        )
        val service = assertInstanceOf(ServeComposition.Composed::class.java, composed, "$errors")
        Thread({ service.server.serve() }, "service-under-test").apply {
            isDaemon = true
            start()
        }
        return service
    }

    private fun client(): ServiceClient = ServiceClient(
        endpoint = socket,
        credential = TOKEN,
        deadline = Duration.ofSeconds(240),
        schema = ServiceProtocol.SCHEMA,
    )

    /** The terminal observation of [runId], asked for until it arrives. */
    private fun awaitTerminal(runId: String): TerminalObservation {
        val deadline = System.currentTimeMillis() + TERMINAL_DEADLINE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val answer = client().runState(runId)
            if (answer is ServiceAnswer.Answered) {
                val observation = answer.value
                if (observation is RuntimeObservation.Terminal) return observation.terminal
            }
            Thread.sleep(500L)
        }
        error("the run $runId did not finish in time")
    }

    private fun assertRefusedWith(reason: String, answer: ServiceAnswer<*>) {
        val refusal = assertInstanceOf(ServiceAnswer.Refused::class.java, answer, "the answer: $answer").refusal
        val named = assertInstanceOf(ServiceRefusal.DescribeParametersRefused::class.java, refusal)
        assertEquals(reason, named.reason, "the refusal names another reason: ${named.detail}")
    }

    private companion object {
        const val TOKEN: String = "token-of-the-starter"
        const val SCRIPT: String = "described.pipeline.kts"
        const val TERMINAL_DEADLINE_MILLIS: Long = 180_000L

        /**
         * A script that declares its parameters and writes [marker] from its top level, so the
         * evaluation of the script is observable from outside it.
         */
        fun declaring(marker: Path): String =
            "java.io.File(\"$marker\").writeText(\"ran\")\n" +
                "pipeline {\n" +
                "    parameters {\n" +
                "        string(\"target\", default = \"staging\")\n" +
                "        boolean(\"dryRun\")\n" +
                "        choice(\"region\", listOf(\"eu\", \"us\"), default = \"eu\")\n" +
                "    }\n" +
                "    stages {\n" +
                "        stage(\"eager\") {\n" +
                "            sh(\"echo the-body-ran\")\n" +
                "        }\n" +
                "    }\n" +
                "}\n"

        val DECLARED: List<ParameterSpec> = listOf(
            ParameterSpec("target", "string", "staging"),
            ParameterSpec("dryRun", "boolean"),
            ParameterSpec("region", "choice", "eu", listOf("eu", "us")),
        )
    }
}
