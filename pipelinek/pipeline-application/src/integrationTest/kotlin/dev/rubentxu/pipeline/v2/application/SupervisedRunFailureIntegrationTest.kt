package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Optional
import java.util.stream.Stream
import kotlin.concurrent.thread

/**
 * The runs that the service starts, and the failure that a start under load can cause.
 *
 * ## The guarantee under trial
 *
 * Two facts about the same door:
 *
 * - a run the service ACCEPTED ends with a terminal, even when the service
 *   accepted it together with five others and refused them;
 * - a run that FAILED says why it failed, and the reason is in the terminal
 *   the reader of the run opens.
 *
 * The second fact is the one the load makes expensive. A child that fails in
 * the middle of its life leaves the run without a terminal —the writer of the
 * run is the child, and a child that is gone writes nothing— so the service
 * closes the run instead. A terminal that said only "the child could not be
 * run to its end" would tell the reader THAT the run failed and nothing about
 * WHY, and a reader with no why has nowhere to look.
 *
 * ## Why the sample of the load is DECLARED
 *
 * Because a lane that measured "a lot" and said nothing more is a lane that
 * nobody can read: the reader cannot tell a sample of thirty starts from a
 * sample of three. The sample is five rounds of six simultaneous starts
 * against the top of supervised runs that ships —thirty starts, of which the
 * ones the budget accepts are real children that compile a script and run
 * it— and the lane says what the sample saw.
 *
 * ## Why the failing child is OWNED by the lane
 *
 * Because a real child cannot be made to fail in a chosen way: it either
 * ends with a code or it does not. The failure of the report —a fork that
 * could not happen, a lease that could not be taken— is a failure of the
 * PORT, and the port is what raises it. The lane gives the port a child that
 * owns itself and then fails, so the failure happens after the receipt and
 * the run is ACCEPTED, which is the shape of the report.
 *
 * Everything else is the product's own: the real supervisor, the real port,
 * the real closer writing into a real store.
 *
 * ## What these rows do NOT freeze
 *
 * How many of the six the budget accepts: that is a race between six starts
 * and the top of the service, and the answer belongs to the machine as much
 * as to the code. What the rows freeze is that every start is ANSWERED and
 * that every accepted run ends.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class SupervisedRunFailureIntegrationTest {

    @TempDir
    lateinit var root: Path

    /** The store the service reads and the runs write. */
    private lateinit var db: Path

    /** The control root the runs write. */
    private lateinit var control: Path

    /** The directory of the scripts a client may name. */
    private lateinit var scripts: Path

    /** The directory where the port writes what each child leaves behind. */
    private lateinit var supervisionRoot: Path

    private val clock: Clock = FixedClock

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        control = Files.createDirectories(root.resolve("control"))
        scripts = Files.createDirectories(root.resolve("scripts"))
        supervisionRoot = Files.createDirectories(root.resolve("supervisions"))
        // A script that ends by itself: the lane is about the LOAD, and a
        // child that never ends would hold the slot of the budget for ever.
        Files.writeString(
            scripts.resolve("quick.pipeline.kts"),
            "pipeline {\n    stages {\n        stage(\"quick\") {\n" +
                "            sh(\"echo quick\")\n        }\n    }\n}\n",
        )
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val writable = (admission as EventStoreAdmission.Admitted).store
        DurableStores.open(writable, control, clock)
        writable.close()
    }

    /**
     * Six starts at once, repeated, and what the service did with them.
     *
     * Every start is answered —accepted, or refused with a name— and every
     * accepted run ends with a terminal. The rounds follow each other without
     * waiting, because a round that waited for the runs of the previous one
     * would measure a service that is idle.
     */
    @Test
    fun `six simultaneous starts are all answered and every accepted run ends with its terminal`() {
        val supervisor = supervisor(ProcessDurableTaskRuntime(supervisionRoot, clock))
        val accepted = mutableListOf<String>()
        val refused = mutableListOf<ServiceRefusal>()

        for (round in 1..ROUNDS) {
            for (answer in simultaneousStarts(supervisor, round)) {
                if (answer is ServiceStartOutcome.Accepted) {
                    accepted += answer.runId
                } else if (answer is ServiceStartOutcome.Refused) {
                    refused += answer.refusal
                } else {
                    error("the service did not answer a start of the round $round: $answer")
                }
            }
        }

        assertTrue(
            accepted.isNotEmpty(),
            "the sample accepted no run at all: a lane that measured nothing is not a lane",
        )
        // The receipt of the sample: how many starts the budget took and how
        // many it refused, and with what name. A reader of this lane needs
        // the numbers and not only the verdict.
        println(
            "the sample of the load: $ROUNDS rounds of $STARTS_PER_ROUND simultaneous starts; " +
                "accepted=${accepted.size}, refused=${refused.size}, " +
                "the refusals were ${refused.map { it.kind }.distinct()}",
        )
        assertEquals(
            ROUNDS * STARTS_PER_ROUND,
            accepted.size + refused.size,
            "every start of the sample was answered, and the sample was the one it declares",
        )
        for (runId in accepted) {
            val terminal = requireNotNull(awaitTerminal(runId)) {
                "the run $runId that the service accepted never ended: a run without a " +
                    "terminal is a run that a reader waits for for ever"
            }
            assertTrue(
                closesOf(terminal).all { causeOf(it).isNotEmpty() },
                "every close of a failed run names the cause of the failure: " +
                    "${terminal.diagnostics.map { it.message }}",
            )
        }
    }

    /**
     * A run whose child fails AFTER it owns itself says why it failed.
     *
     * The child owns itself first —the receipt is what the service believes—
     * and the failure comes afterwards, which is the shape of the report: a
     * run that was accepted and then could not go on. The terminal the
     * service writes names the failure in the words of the failure itself.
     */
    @Test
    fun `a run whose child fails after it owns itself says why it failed`() {
        val fails = CountDownLatch(1)
        val supervisor = supervisor(
            ProcessDurableTaskRuntime(supervisionRoot, clock) { builder ->
                FailingChild(builder.command(), fails)
            },
        )

        val outcome = supervisor.start(request("quick.pipeline.kts", key = "key-failing"))
        val accepted = assertInstanceOf(
            ServiceStartOutcome.Accepted::class.java,
            outcome,
            "the run owned itself before it failed",
        )

        fails.countDown()

        val terminal = requireNotNull(awaitTerminal(accepted.runId)) {
            "the run that failed was never closed: the writer of the run was the child, and " +
                "the child is gone"
        }
        assertEquals(
            RunOutcome.FAILURE_TOKEN,
            terminal.outcome,
            "the run that could not go on ended as a failure",
        )
        assertTrue(
            terminal.diagnostics.any { it.message.contains(CAUSE) },
            "the terminal of the run names WHY it failed, and not only THAT it failed: " +
                "${terminal.diagnostics.map { it.message }}",
        )
    }

    /**
     * The supervisor of the runs, with the [port] that launches the children.
     */
    private fun supervisor(port: DurableTaskRuntime): ServiceRunSupervisor {
        val registry = when (val read = ServiceStartRegistry.read(root.resolve(ServiceStartRegistry.FILE_NAME))) {
            is ServiceStartRegistryReading.Read -> read.registry
            is ServiceStartRegistryReading.Refused -> error(read.detail)
        }
        return ServiceRunSupervisor(
            admission = ServiceStartAdmission(scripts, null),
            runPolicy = ServiceRunPolicy(
                eventStore = db.toAbsolutePath().toString(),
                controlRoot = control.toAbsolutePath().toString(),
                deadlineMillis = RUN_BUDGET_MILLIS,
                allowNetwork = false,
            ),
            command = ServiceRunCommand(
                javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                classpath = System.getProperty("java.class.path"),
                mainClass = "dev.rubentxu.pipeline.v2.application.MainKt",
            ),
            port = port,
            registry = registry,
            // The top is the one that ships: four supervised runs at once.
            // The deadline of the ownership is generous, because a start that
            // is refused for patience measures the patience of the lane and
            // not the behaviour of the service.
            limits = ServiceStartLimits(maxSupervisedRuns = MAX_SUPERVISED_RUNS, startDeadlineMillis = START_DEADLINE_MILLIS),
            supervisionRoot = supervisionRoot,
            closer = EventStoreSupervisedRunCloser(
                db.toAbsolutePath().toString(),
                clock,
                OutputPlaneSupervisedRunOutputSealer(control),
            ),
        )
    }

    /**
     * The six starts of the [round], all of them at the same time.
     *
     * The latch is what makes them simultaneous: every thread waits for it,
     * and it is released when all of them are waiting.
     */
    private fun simultaneousStarts(
        supervisor: ServiceRunSupervisor,
        round: Int,
    ): List<ServiceStartOutcome> {
        val go = CountDownLatch(1)
        val answers = ConcurrentLinkedQueue<ServiceStartOutcome>()
        val workers = (1..STARTS_PER_ROUND).map { index ->
            thread {
                go.await()
                answers += supervisor.start(request("quick.pipeline.kts", key = "key-$round-$index"))
            }
        }
        go.countDown()
        workers.forEach { it.join(JOIN_MILLIS) }
        return answers.toList()
    }

    /**
     * The request to start [script], of the client of this lane.
     */
    private fun request(script: String, key: String): ServiceStartRequest = ServiceStartRequest(
        script = script,
        workspace = null,
        idempotencyKey = key,
        credentialDigest = DIGEST,
    )

    /**
     * The terminal of [runId], asked for until it arrives.
     *
     * The wait is the budget of the run plus the room the closing takes: the
     * service promises that a run ends within its budget, and a row that
     * waits less than that measures its own impatience.
     */
    private fun awaitTerminal(runId: String): RunFinished? {
        val deadline = System.currentTimeMillis() + TERMINAL_DEADLINE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val terminal = terminalFactOf(runId)
            if (terminal != null) return terminal
            Thread.sleep(POLL_MILLIS)
        }
        return null
    }

    /**
     * The terminal fact of the run, read from the run's own store.
     */
    private fun terminalFactOf(runId: String): RunFinished? {
        val admission = admitEventStore(db.toString(), EventStorePurpose.Read)
        val store = (admission as EventStoreAdmission.Admitted).store
        return try {
            store.eventsFor(runId).filterIsInstance<RunFinished>().lastOrNull()
        } finally {
            store.close()
        }
    }

    /**
     * The closes of the run: the messages that say the child could not go on.
     */
    private fun closesOf(terminal: RunFinished): List<String> =
        terminal.diagnostics.map { it.message }.filter { it.contains(SUPERVISED_CLOSE) }

    /**
     * The cause of a close, or nothing when the close names none.
     */
    private fun causeOf(close: String): String =
        close.substringAfter(SUPERVISED_CLOSE).trim().removePrefix(":").trim()

    /**
     * A child that owns itself and then fails.
     *
     * It prints the receipt of the launch on its standard output, as a real
     * child does, and it fails when the lane tells it to. The failure is the
     * one the port reports when the process cannot go on: it raises, and the
     * run that it had already claimed is left to the service.
     */
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private class FailingChild(command: List<String>, private val fails: CountDownLatch) : Process() {

        private val receipt: ByteArray = receiptOf(command).toByteArray()

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(receipt)

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            fails.await(FAIL_MILLIS, TimeUnit.MILLISECONDS)
            throw IOException(CAUSE)
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            fails.await(timeout, unit)
            throw IOException(CAUSE)
        }

        override fun exitValue(): Int = throw IllegalThreadStateException("the child fails")

        override fun destroy() = Unit

        override fun destroyForcibly(): Process = this

        override fun isAlive(): Boolean = false

        override fun toHandle(): ProcessHandle = EmptyHandle

        private companion object {
            /** The run the failing child minted for itself. */
            const val RUN_ID: String = "run-of-the-failing-child"

            /** The authority that holds the lease of that run. */
            const val OWNER_ID: String = "owner-of-the-failing-child"

            /**
             * The receipt of the launch, with the correlation the service gave
             * to this child: a receipt that names another correlation is a
             * receipt of another launch.
             */
            fun receiptOf(command: List<String>): String {
                val index = command.indexOf("--correlation-id")
                val correlation = command[index + 1]
                return """{"schema":"pipelinek.launch/1","runId":"$RUN_ID","correlationId":"$correlation",""" +
                    """"ownerId":"$OWNER_ID","startedAtMs":1700,"status":"Launched"}""" + "\n"
            }
        }
    }

    /** The clock this lane stamps its material with: it does not move. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        /** The rounds of the sample: five rounds of six starts. */
        const val ROUNDS: Int = 5

        /** The starts of every round, all of them at the same time. */
        const val STARTS_PER_ROUND: Int = 6

        /** The top of supervised runs that ships. */
        const val MAX_SUPERVISED_RUNS: Int = 4

        /** The digest of the credential of the client that asks. */
        const val DIGEST: String = "digest-of-the-client"

        /**
         * The failure of the child, in the words of a failure of the machine:
         * the kind of the failure and the message that names the resource.
         */
        const val CAUSE: String = "IOException: error=0, Cannot allocate memory"

        /**
         * What the close of a run says when the child could not be run to its
         * end. The cause is what follows it.
         */
        const val SUPERVISED_CLOSE: String = "could not be run to its end"

        /** The budget of the run: the watchdog kills the child when it is over. */
        const val RUN_BUDGET_MILLIS: Long = 120_000L

        /** How long the service waits for the receipt of the child. */
        const val START_DEADLINE_MILLIS: Long = 120_000L

        /** How long the lane waits for the terminal of a run. */
        const val TERMINAL_DEADLINE_MILLIS: Long = RUN_BUDGET_MILLIS + 30_000L

        /** How long the lane waits between two questions about a run. */
        const val POLL_MILLIS: Long = 250L

        /** How long a thread of the sample waits for its answer. */
        const val JOIN_MILLIS: Long = 180_000L

        /** How long the failing child waits before it fails by itself. */
        const val FAIL_MILLIS: Long = 120_000L
    }
}

/**
 * A handle that names no process and has no descendants.
 */
private object EmptyHandle : ProcessHandle {
    override fun pid(): Long = 0L

    /**
     * There is no process to describe, and nothing asks: the port reads the
     * handle for the DESCENDANTS, and this one has none.
     */
    override fun info(): ProcessHandle.Info = throw UnsupportedOperationException("the child names no process")

    override fun children(): Stream<ProcessHandle> = Stream.empty()

    override fun descendants(): Stream<ProcessHandle> = Stream.empty()

    override fun isAlive(): Boolean = false

    override fun onExit(): CompletableFuture<ProcessHandle> = CompletableFuture.completedFuture(this)

    override fun supportsNormalTermination(): Boolean = false

    override fun destroy(): Boolean = false

    override fun destroyForcibly(): Boolean = false

    override fun parent(): Optional<ProcessHandle> = Optional.empty()

    override fun compareTo(other: ProcessHandle): Int = 0
}
