package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The supervisor of the runs the service starts.
 *
 * ## The guarantee under trial
 *
 * That the service answers "started" only when the run OWNS ITSELF, and
 * that it answers with the run's OWN identity and not with the identity of
 * the supervision. The evidence of ownership is the receipt the child prints
 * right after it takes its lease, so a run that was refused by the product,
 * a run that died before it owned itself and a run that did not own itself in
 * time are three refusals with three names, and none of them is a success.
 *
 * ## The capacity this serves
 *
 * A client that starts a run over the socket needs to know whether the run
 * EXISTS before it watches it: a client told "started" for a run that never
 * took its lease would watch a run that will never answer, and a client told
 * "started" with the supervision's id would watch a run that does not exist.
 *
 * ## Why the port is faked here
 *
 * The supervisor's own behaviour is about the CONVERSATION with the port:
 * what it asks for, when it believes the answer, and what it does when the
 * answer never comes. Whether a real process starts and prints a receipt is
 * a fact about the machine, and the integration lane is where it is observed.
 *
 * ## Why the fake child ECHOES the correlation
 *
 * Because that is what a real child does: it prints the correlation it was
 * given. A fake that printed a constant would make the receipt of every
 * launch look like the receipt of another request, and the check that the
 * receipt IS this request's could not be told apart from the check that it
 * is not.
 *
 * ## What this does not freeze
 *
 * The argv of the child, the identity the child mints for its run, and the
 * words of the sentences the refusals carry: only the NAMES are the
 * contract.
 */
class ServiceRunSupervisorTest {

    @Test
    fun `a run that owns itself is accepted with the identity the child printed`(@TempDir root: Path) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port)

        val outcome = supervisor.start(request("build.kts"))

        val accepted = assertInstanceOf(ServiceStartOutcome.Accepted::class.java, outcome)
        assertEquals(RUN_ID, accepted.runId, "the answer carries the run's own identity")
        assertEquals(correlationOf(port.requests.single()), accepted.correlationId)
        assertEquals(OWNER_ID, accepted.ownerId)
        assertEquals(1700L, accepted.startedAtMs)
    }

    @Test
    fun `a name that does not resolve is refused and no process is launched`(@TempDir root: Path) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port)

        val outcome = supervisor.start(request("nothing.kts"))

        val refused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, outcome)
        assertEquals(
            ServiceRefusal.ScriptNotAdmitted("nothing.kts"),
            refused.refusal,
            "a name that names nothing is refused by name",
        )
        assertEquals(0, port.requests.size, "no child was launched for a name that does not resolve")
    }

    @Test
    fun `a child that dies before it owns itself is refused`(@TempDir root: Path) {
        val port = FakePort { _, _ -> Unit }
        val supervisor = supervisor(root, port)

        val outcome = supervisor.start(request("build.kts"))

        val refused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, outcome)
        assertEquals(
            ServiceRefusal.RunStartRefused(
                "the-child-died",
                "the run ended before it owned itself; the operator's log says why",
            ),
            refused.refusal,
            "a child that said nothing is not a run that started",
        )
    }

    @Test
    fun `a run that refuses to launch is refused`(@TempDir root: Path) {
        val port = FakePort { _, sink -> sink.append(refusedReceipt("the-script-does-not-compile")) }
        val supervisor = supervisor(root, port)

        val outcome = supervisor.start(request("build.kts"))

        val refused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, outcome)
        assertEquals(
            ServiceRefusal.RunStartRefused(
                "the-run-refused",
                "the run refused to start; the operator's log says why",
            ),
            refused.refusal,
            "the product's own refusal is not a success",
        )
    }

    @Test
    fun `a child that does not own itself in time is refused`(@TempDir root: Path) {
        val port = FakePort { _, _ -> delay(30_000L) }
        val supervisor = supervisor(root, port, startDeadlineMillis = 150L)

        val outcome = supervisor.start(request("build.kts"))

        val refused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, outcome)
        assertEquals(
            ServiceRefusal.RunStartRefused(
                "no-ownership-in-time",
                "the run did not own itself in time; the operator's log says why",
            ),
            refused.refusal,
            "a run that says nothing in time is not a run that started",
        )
    }

    @Test
    fun `a receipt that belongs to another request is refused`(@TempDir root: Path) {
        val port = FakePort { _, sink -> sink.append(receipt(RUN_ID, "corr-of-another-request", OWNER_ID)) }
        val supervisor = supervisor(root, port)

        val outcome = supervisor.start(request("build.kts"))

        val refused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, outcome)
        assertEquals(
            ServiceRefusal.RunStartRefused(
                "the-receipt-is-not-ours",
                "the receipt of the launch belongs to another request; " +
                    "the operator's log says why",
            ),
            refused.refusal,
            "a receipt that does not carry this request's correlation proves nothing",
        )
    }

    @Test
    fun `the budget of supervised runs is a budget`(@TempDir root: Path) {
        val port = FakePort { request, sink ->
            sink.append(receiptOf(request))
            delay(30_000L)
        }
        val supervisor = supervisor(root, port, maxSupervisedRuns = 1)

        val first = supervisor.start(request("build.kts"))
        val second = supervisor.start(request("other.kts"))

        assertInstanceOf(
            ServiceStartOutcome.Accepted::class.java,
            first,
            "the first run took the only slot of the budget",
        )
        val refused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, second)
        assertEquals(
            ServiceRefusal.TooManySupervisedRuns,
            refused.refusal,
            "a service that watches one run at a time refuses the second one",
        )
    }

    @Test
    fun `a run that ended gives its slot of the budget back`(@TempDir root: Path) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port, maxSupervisedRuns = 1)

        val first = supervisor.start(request("build.kts"))
        assertInstanceOf(
            ServiceStartOutcome.Accepted::class.java,
            first,
            "the first run took the only slot of the budget",
        )

        awaitNoRunInFlight(supervisor)

        val second = supervisor.start(request("other.kts"))
        assertInstanceOf(
            ServiceStartOutcome.Accepted::class.java,
            second,
            "a run that ended gives its slot back: a budget that leaks refuses every " +
                "run after the fourth, for ever",
        )
    }

    @Test
    fun `a caller that waits for the same key reads the refusal the owner read`(@TempDir root: Path) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val port = FakePort { _, _ ->
            started.countDown()
            release.await()
        }
        val supervisor = supervisor(root, port, startDeadlineMillis = 30_000L)
        val owner = AtomicReference<ServiceStartOutcome>()

        val ownerThread = thread { owner.set(supervisor.start(request("build.kts", key = "key-1"))) }
        assertTrue(started.await(5, TimeUnit.SECONDS), "the owner launched the child")
        val releaser = thread {
            Thread.sleep(200L)
            release.countDown()
        }

        val waiter = supervisor.start(request("build.kts", key = "key-1"))

        releaser.join()
        ownerThread.join(5_000L)
        val ownerRefused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, owner.get())
        val waiterRefused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, waiter)
        assertEquals(
            ownerRefused.refusal,
            waiterRefused.refusal,
            "the two callers read the SAME fact: one start that failed, told the same way",
        )
        assertEquals(
            ServiceRefusal.RunStartRefused(
                "the-child-died",
                "the run ended before it owned itself; the operator's log says why",
            ),
            waiterRefused.refusal,
            "the refusal is the one the owner read, with its NAME",
        )
    }

    @Test
    fun `a start that is interrupted is refused with a name and releases the key`(@TempDir root: Path) {
        val started = CountDownLatch(1)
        val port = FakePort { _, _ ->
            started.countDown()
            delay(30_000L)
        }
        val supervisor = supervisor(root, port, startDeadlineMillis = 30_000L)
        val registry = registryAt(root)
        val outcome = AtomicReference<ServiceStartOutcome>()

        val worker = thread { outcome.set(supervisor.start(request("build.kts", key = "key-1"))) }
        assertTrue(started.await(5, TimeUnit.SECONDS), "the child was launched")
        worker.interrupt()
        worker.join(5_000L)

        val refused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, outcome.get())
        assertEquals(
            ServiceRefusal.RunStartRefused(
                "the-start-was-interrupted",
                "the service stopped while the run was starting",
            ),
            refused.refusal,
            "an interruption is a refusal with a name, and not a connection closed without a line",
        )
        assertInstanceOf(
            ServiceStartClaim.Owner::class.java,
            registry.claim("key-1", DIGEST),
            "a key whose start was interrupted is free again: nothing was started",
        )
    }

    @Test
    fun `the same key with the same request answers with the same run`(@TempDir root: Path) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port)

        val first = supervisor.start(request("build.kts", key = "key-1"))
        val second = supervisor.start(request("build.kts", key = "key-1"))

        val one = assertInstanceOf(ServiceStartOutcome.Accepted::class.java, first)
        val two = assertInstanceOf(ServiceStartOutcome.Accepted::class.java, second)
        assertEquals(one, two, "a key means one run, and the retry gets the same one")
        assertEquals(1, port.requests.size, "the retry did not start a second child")
    }

    @Test
    fun `the same key with another request is a conflict`(@TempDir root: Path) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port)
        supervisor.start(request("build.kts", key = "key-1"))

        val outcome = supervisor.start(request("other.kts", key = "key-1"))

        val refused = assertInstanceOf(ServiceStartOutcome.Refused::class.java, outcome)
        assertEquals(
            ServiceRefusal.IdempotencyConflict("key-1"),
            refused.refusal,
            "a key names a request, so the same key with another request is a conflict",
        )
    }

    @Test
    fun `the same key of another client is another run`(@TempDir root: Path) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port)

        val mine = supervisor.start(request("build.kts", key = "key-1", digest = DIGEST))
        val theirs = supervisor.start(request("build.kts", key = "key-1", digest = OTHER_DIGEST))

        val one = assertInstanceOf(ServiceStartOutcome.Accepted::class.java, mine)
        val two = assertInstanceOf(ServiceStartOutcome.Accepted::class.java, theirs)
        assertNotEquals(
            one.correlationId,
            two.correlationId,
            "a key is a key OF A CLIENT: two clients that choose the same word are two runs",
        )
        assertEquals(2, port.requests.size, "both clients started a run of their own")
    }

    @Test
    fun `the supervision carries its own identity and not the run's`(@TempDir root: Path) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port)

        supervisor.start(request("build.kts"))

        val request = port.requests.single()
        assertTrue(
            request.opId.startsWith("sup-"),
            "the supervision has an identity of its own: ${request.opId}",
        )
        assertEquals(
            request.opId,
            request.runId.value,
            "the port's request names the SUPERVISION, and the run's own id is the child's",
        )
    }

    @Test
    fun `the child is asked to run in the directory of its script`(@TempDir root: Path) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port)

        supervisor.start(request("build.kts"))

        val request = port.requests.single()
        assertEquals(
            Files.createDirectories(root.resolve("scripts")).toRealPath().toString(),
            request.workspaceRoot,
            "the child works where its script is, so a script that reads a file next to itself finds it",
        )
    }

    @Test
    fun `the argv of the child carries the store, the control root and the script`(@TempDir root: Path) {
        val port = FakePort { request, sink -> sink.append(receiptOf(request)) }
        val supervisor = supervisor(root, port)

        supervisor.start(request("build.kts"))

        val argv = (port.requests.single().task as TaskSpec.ExecTask).argv
        assertEquals("/usr/bin/java", argv.first())
        assertTrue(argv.contains("--db"), "the child writes in the store the service reads")
        assertTrue(argv.contains("/tmp/store.db"), "the store is ABSOLUTE, because the child is elsewhere")
        assertTrue(argv.contains("--control-root"))
        assertTrue(argv.contains("/tmp/control"), "the control root is ABSOLUTE too")
        assertTrue(argv.contains("--isolated"), "a run with no workspace is isolated")
        assertTrue(argv.last().endsWith("build.kts"), "the script is the last word of the argv")
    }

    /**
     * The supervisor of [root], with the [port] that launches the children.
     */
    private fun supervisor(
        root: Path,
        port: DurableTaskRuntime,
        maxSupervisedRuns: Int = 4,
        startDeadlineMillis: Long = 5_000L,
        registry: ServiceStartRegistry = registryAt(root),
    ): ServiceRunSupervisor {
        val scripts = Files.createDirectories(root.resolve("scripts"))
        Files.writeString(scripts.resolve("build.kts"), "print(1)")
        Files.writeString(scripts.resolve("other.kts"), "print(2)")
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
                maxSupervisedRuns = maxSupervisedRuns,
                startDeadlineMillis = startDeadlineMillis,
            ),
            supervisionRoot = root.resolve("supervisions"),
        )
    }

    /**
     * The record of the starts, in [root].
     */
    private fun registryAt(root: Path): ServiceStartRegistry {
        val reading = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))
        return assertInstanceOf(ServiceStartRegistryReading.Read::class.java, reading).registry
    }

    /**
     * Waits until no supervised run is alive, and says so if one is not.
     *
     * The budget gives its slot back in the thread of the child, and that
     * thread finishes on its own time: a test that wants to see the slot
     * come back has to WAIT for it, and cannot assume it is already there.
     */
    private fun awaitNoRunInFlight(supervisor: ServiceRunSupervisor) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (supervisor.supervisedRunsInFlight() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10L)
        }
        assertEquals(
            0,
            supervisor.supervisedRunsInFlight(),
            "the child ended and gave its slot of the budget back",
        )
    }

    /**
     * The request to start [script], of the client whose credential has the
     * digest [digest].
     */
    private fun request(
        script: String,
        key: String? = null,
        digest: String = DIGEST,
    ): ServiceStartRequest = ServiceStartRequest(
        script = script,
        workspace = null,
        idempotencyKey = key,
        credentialDigest = digest,
    )

    /**
     * The receipt the child of [request] printed: it carries the
     * correlation the child was given, as a real child does.
     */
    private fun receiptOf(request: TaskExecutionRequest): OutputChunk =
        receipt(RUN_ID, correlationOf(request), OWNER_ID)

    /**
     * The correlation the supervisor gave to the child of [request].
     */
    private fun correlationOf(request: TaskExecutionRequest): String {
        val argv = (request.task as TaskSpec.ExecTask).argv
        val index = argv.indexOf("--correlation-id")
        return argv[index + 1]
    }

    /**
     * The receipt of a launch that owns itself.
     */
    private fun receipt(runId: String, correlationId: String, ownerId: String): OutputChunk =
        OutputChunk.stdout(
            (
                """{"schema":"pipelinek.launch/1","runId":"$runId","correlationId":"$correlationId",""" +
                    """"ownerId":"$ownerId","startedAtMs":1700,"status":"Launched"}""" + "\n"
                ).toByteArray(),
        )

    /**
     * The receipt of a launch that was refused.
     */
    private fun refusedReceipt(reason: String): OutputChunk =
        OutputChunk.stdout(
            (
                """{"schema":"pipelinek.launch/1","runId":"","correlationId":"corr-1",""" +
                    """"ownerId":"","startedAtMs":1700,"status":"Refused:$reason"}""" + "\n"
                ).toByteArray(),
        )

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
        /** The run the child minted for itself. */
        private const val RUN_ID: String = "run-minted-by-the-child"

        /** The authority that holds the run's lease. */
        private const val OWNER_ID: String = "owner-1"

        /** The digest of the credential of the client that asks. */
        private const val DIGEST: String = "digest-of-the-client"

        /** The digest of the credential of another client. */
        private const val OTHER_DIGEST: String = "digest-of-another-client"
    }
}
