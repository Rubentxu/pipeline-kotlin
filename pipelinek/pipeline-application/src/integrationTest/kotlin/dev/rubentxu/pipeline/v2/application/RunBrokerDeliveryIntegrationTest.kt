package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The broker of a run, as the supervisor of a service hands it to the child it launches.
 *
 * ## The guarantee under trial
 *
 * That every run the service launches finds, in the directory its argv names, a broker of its own
 * that answers with the grants of the client that asked and with nothing else; that the argv
 * carries the place and never the token; and that the broker is gone when the run is over,
 * however the run ended.
 *
 * ## The capacity this serves
 *
 * A step that enters `withCredentials` receives the secret on arriving there, from a broker whose
 * answers are the operator's grants for the client that started the run, and a run that never
 * started or already ended leaves no broker for anybody to reach.
 *
 * ## Why this is an INTEGRATION test
 *
 * The brokers here really bind and really answer: what is under trial is the hand-over between the
 * supervisor and a socket that exists, and what is left on the disk after each way a run can end.
 * The child is the one thing faked, because its behaviour is a fact of its own lane.
 *
 * ## What this does not freeze
 *
 * The name of the directory of a broker, and the place of the option inside the argv.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class RunBrokerDeliveryIntegrationTest {

    @TempDir
    lateinit var root: Path

    private val supervisors = CopyOnWriteArrayList<ServiceRunSupervisor>()

    @AfterEach
    fun closeSupervisors() {
        supervisors.forEach { it.close() }
    }

    @Test
    fun `a run finds a private broker in the directory its argv names and the argv carries no token`() {
        val seen = CopyOnWriteArrayList<Found>()
        val port = FakePort { request, sink ->
            // Recorded BEFORE the receipt: the start returns the moment the receipt is read.
            seen += foundBy(request)
            sink.append(receiptOf(request))
            awaitCancellation()
        }
        val supervisor = supervisor(port, factory(emptyMap(), StoreDouble(emptyMap())))

        val outcome = supervisor.start(request())

        assertInstanceOf(ServiceStartOutcome.Accepted::class.java, outcome)
        val found = seen.single()
        assertEquals(
            setOf(RunBrokerLease.SOCKET_FILE_NAME, RunBrokerLease.TOKEN_FILE_NAME),
            found.names,
            "the broker holds the socket and the token and nothing else",
        )
        assertEquals("rwx------", found.mode, "the directory is private to the service")
        assertEquals(brokersRoot(), found.directory.parent, "the broker lives below the root of the service")
        assertTrue(Files.isDirectory(found.directory), "the broker is alive while the child is")
        val argv = argvOf(port.requests.single())
        assertTrue(argv.none { it.contains(found.token) }, "the token travels in a file and never in the argv")
    }

    @Test
    fun `the broker is gone when the child ends`() {
        val seen = CopyOnWriteArrayList<Found>()
        val port = FakePort { request, sink ->
            // Recorded BEFORE the receipt: the start returns the moment the receipt is read.
            seen += foundBy(request)
            sink.append(receiptOf(request))
        }
        val supervisor = supervisor(port, factory(emptyMap(), null))

        assertInstanceOf(ServiceStartOutcome.Accepted::class.java, supervisor.start(request()))
        awaitNoRunInFlight(supervisor)

        assertFalse(Files.exists(seen.single().directory), "the lease of a run that ended is not left behind")
    }

    @Test
    fun `the broker is gone when the run never owned itself`() {
        for (staysSilent in listOf(true, false)) {
            val name = if (staysSilent) "silent until cancelled" else "ended without a receipt"
            val seen = CopyOnWriteArrayList<Path>()
            val port = FakePort { request, _ ->
                seen.add(directoryOf(request))
                if (staysSilent) awaitCancellation()
            }
            val supervisor = supervisor(port, factory(emptyMap(), null), startDeadlineMillis = 400L)

            val outcome = supervisor.start(request())

            assertInstanceOf(ServiceStartOutcome.Refused::class.java, outcome, name)
            awaitNoRunInFlight(supervisor)
            assertFalse(Files.exists(seen.single()), "$name: the broker of a run that never started is gone")
        }
    }

    @Test
    fun `closing the supervisor removes the brokers of the runs it still watches`() {
        val seen = CopyOnWriteArrayList<Found>()
        val port = FakePort { request, sink ->
            // Recorded BEFORE the receipt: the start returns the moment the receipt is read.
            seen += foundBy(request)
            sink.append(receiptOf(request))
            awaitCancellation()
        }
        val supervisor = supervisor(port, factory(emptyMap(), null))
        assertInstanceOf(ServiceStartOutcome.Accepted::class.java, supervisor.start(request()))
        assertTrue(Files.isDirectory(seen.single().directory))

        supervisor.close()

        assertFalse(Files.exists(seen.single().directory), "a stopped service leaves no broker for the child that survived")
    }

    @Test
    fun `each run is served with the grants of its own client and a stranger is granted nothing`() {
        val store = StoreDouble(mapOf("a-key" to SECRET_OF_A, "b-key" to SECRET_OF_B))
        val port = FakePort { request, sink ->
            sink.append(receiptOf(request))
            awaitCancellation()
        }
        val supervisor = supervisor(
            port,
            factory(mapOf("digest-a" to setOf(grant("a-key")), "digest-b" to setOf(grant("b-key"))), store),
        )

        supervisor.start(request(clientDigest = "digest-a"))
        supervisor.start(request(clientDigest = "digest-b"))
        supervisor.start(request(clientDigest = "digest-of-a-stranger"))
        val (forA, forB, forStranger) = port.requests.map { SocketCredentialProvider.inDirectory(directoryOf(it)) }

        assertArrayEquals(SECRET_OF_A, (forA.resolveToCredential(CredentialsId("a-key")) as SecretText).bytes)
        assertArrayEquals(SECRET_OF_B, (forB.resolveToCredential(CredentialsId("b-key")) as SecretText).bytes)
        for ((run, provider, id) in listOf(
            Triple("the run of A", forA, "b-key"),
            Triple("the run of B", forB, "a-key"),
            Triple("the run of the stranger", forStranger, "a-key"),
            Triple("the run of the stranger", forStranger, "b-key"),
        )) {
            val refused = assertThrows<CredentialBrokerRefusedException>("$run asking for $id") {
                provider.resolveToCredential(CredentialsId(id))
            }
            assertEquals(CredentialBrokerRefusal.CREDENTIAL_NOT_GRANTED, refused.refusal, "$run asking for $id")
        }
        assertEquals(listOf("a-key", "b-key"), store.asked, "the store was asked only for what was granted")
        assertEquals(
            3,
            port.requests.map { directoryOf(it) }.toSet().size,
            "three runs, three brokers",
        )
    }

    @Test
    fun `a broker that could not be opened leaves the key free for the same request`() {
        val port = FakePort { request, sink ->
            sink.append(receiptOf(request))
            awaitCancellation()
        }
        // The root of the brokers is a FILE: no directory can be made below it.
        Files.writeString(brokersRoot(), "in the way")
        val supervisor = supervisor(port, factory(emptyMap(), null))

        val refused = assertInstanceOf(
            ServiceStartOutcome.Refused::class.java,
            supervisor.start(request(key = "order-42")),
        )

        val refusal = assertInstanceOf(ServiceRefusal.RunStartRefused::class.java, refused.refusal)
        assertEquals("the-credential-broker-could-not-start", refusal.reason)
        assertEquals("the directory of a run broker could not be created", refusal.detail)
        assertTrue(port.requests.isEmpty(), "no child was launched without its broker")
        assertEquals(0, supervisor.supervisedRunsInFlight())

        Files.delete(brokersRoot())
        val accepted = supervisor.start(request(key = "order-42"))
        assertInstanceOf(ServiceStartOutcome.Accepted::class.java, accepted)
        assertEquals(1, port.requests.size, "the same key, once the service can open a broker, starts the run")
    }

    private fun brokersRoot(): Path = root.resolve("brokers")

    private fun factory(grants: Map<String, Set<CredentialGrant>>, store: CredentialProvider?): RunBrokerFactory =
        RunBrokerFactory(brokersRoot(), store) { digest -> grants[digest].orEmpty() }

    private fun grant(id: String) = CredentialGrant(id, BrokeredCredentialKind.STRING)

    /**
     * What a child finds the moment it starts: the directory its argv names and what is in it.
     */
    private fun foundBy(request: TaskExecutionRequest): Found {
        val directory = directoryOf(request)
        val names = Files.newDirectoryStream(directory).use { entries -> entries.map { it.fileName.toString() }.toSet() }
        val mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(directory))
        val token = Files.readString(directory.resolve(RunBrokerLease.TOKEN_FILE_NAME))
        return Found(directory, names, mode, token)
    }

    private fun directoryOf(request: TaskExecutionRequest): Path {
        val argv = argvOf(request)
        val at = argv.indexOf(CREDENTIAL_BROKER_OPTION)
        assertNotEquals(-1, at, "the argv names a broker")
        return Path.of(argv[at + 1])
    }

    private fun awaitNoRunInFlight(supervisor: ServiceRunSupervisor) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (supervisor.supervisedRunsInFlight() != 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(0, supervisor.supervisedRunsInFlight(), "the supervisor gave every slot back")
    }

    /**
     * The supervisor of the runs, over a port that is [port] and brokers that are [brokers].
     */
    private fun supervisor(
        port: DurableTaskRuntime,
        brokers: RunBrokerFactory,
        startDeadlineMillis: Long = 5_000L,
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
                startDeadlineMillis = startDeadlineMillis,
            ),
            supervisionRoot = root.resolve("supervisions"),
            collaborators = ServiceCollaborators(brokers = brokers),
        ).also { supervisors += it }
    }

    private fun request(
        key: String? = null,
        clientDigest: String = "digest-of-the-client",
    ): ServiceStartRequest = ServiceStartRequest(
        script = "build.kts",
        workspace = null,
        idempotencyKey = key,
        credentialDigest = clientDigest,
    )

    private fun argvOf(request: TaskExecutionRequest): List<String> =
        (request.task as TaskSpec.ExecTask).argv

    /** The receipt the child of [request] printed, with the correlation the child was given. */
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

    /** What a child found in the directory of its broker at the moment it started. */
    private class Found(
        val directory: Path,
        val names: Set<String>,
        val mode: String,
        val token: String,
    )

    /** A port that launches a child that behaves the way the test says. */
    private class FakePort(
        private val behaviour: suspend (TaskExecutionRequest, ExecutionOutputSink) -> Unit,
    ) : DurableTaskRuntime {

        /** The requests the supervisor asked for. */
        val requests: MutableList<TaskExecutionRequest> = CopyOnWriteArrayList()

        override suspend fun execute(
            request: TaskExecutionRequest,
            outputSink: ExecutionOutputSink,
        ): TaskExecutionResult {
            requests += request
            behaviour(request, outputSink)
            return TaskExecutionResult(exitCode = 0, startedAtEpochMs = 0L, endedAtEpochMs = 0L)
        }
    }

    /** A store that holds bytes by name and records what it was asked. */
    private class StoreDouble(private val secrets: Map<String, ByteArray>) : CredentialProvider {
        val asked: MutableList<String> = CopyOnWriteArrayList()

        override val providerId: String = "store-double"

        override fun resolve(id: CredentialsId): SecretHandle = error("the broker resolves credentials, not handles")

        override fun resolveToCredential(id: CredentialsId): Credential {
            asked += id.value
            val bytes = secrets[id.value] ?: error("not held")
            return SecretText(id, bytes = bytes.copyOf())
        }

        override fun close() = Unit
    }

    private companion object {
        const val CREDENTIAL_BROKER_OPTION = "--credential-broker"
        val SECRET_OF_A: ByteArray = "s3cr3t-of-client-a".toByteArray()
        val SECRET_OF_B: ByteArray = "s3cr3t-of-client-b".toByteArray()
    }
}
