package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * What a client asks the service to start.
 *
 * @property script the name of the script, inside the directory the operator admitted.
 * @property workspace the name of the workspace, or null for the product's own scratch.
 * @property idempotencyKey the label that makes a repeated request the same request.
 */
internal data class ServiceStartRequest(
    val script: String,
    val workspace: String?,
    val idempotencyKey: String?,
    /**
     * The DIGEST of the credential that asked for this run.
     *
     * It is the digest and not the token, because the registry is a file
     * that outlives the request, and the file must not carry a secret. It
     * is also what makes the idempotency key a key OF A CLIENT: two
     * clients that choose the same word mean two different requests, and a
     * client that could read another client's run would be a hole in the
     * very boundary the credential draws.
     */
    val credentialDigest: String,
)

/**
 * What the service decided about a request to start a run.
 *
 * Two shapes, and a caller has to act on both: the run started, and it did
 * not.
 */
internal sealed interface ServiceStartOutcome {

    /**
     * The run started and it owns itself.
     *
     * @property runId the identity of the run, the one `run.state` understands.
     * @property correlationId the token the service chose and passed to the run.
     * @property ownerId the authority that holds the run's lease.
     * @property startedAtMs when the run took its ownership.
     */
    data class Accepted(
        val runId: String,
        val correlationId: String,
        val ownerId: String,
        val startedAtMs: Long,
    ) : ServiceStartOutcome

    /** The run did not start, and the refusal says why. */
    data class Refused(val refusal: ServiceRefusal) : ServiceStartOutcome
}

/**
 * The door the service opens to start a run.
 *
 * It is a seam because the operations that answer a request must not know
 * HOW a run is started: they ask, and they get an answer or a refusal.
 */
internal interface ServiceRunStarter {

    /** Starts the run [request] asks for. */
    fun start(request: ServiceStartRequest): ServiceStartOutcome
}

/**
 * What the operator declared about the runs this service supervises.
 *
 * @property eventStore the path of the store the service reads, and the run writes.
 * @property controlRoot the path of the control root the service reads, and the run writes.
 * @property deadlineMillis how long a supervised run may live.
 * @property allowNetwork whether a supervised run may reach the network.
 */
internal data class ServiceRunPolicy(
    val eventStore: String,
    val controlRoot: String,
    val deadlineMillis: Long,
    val allowNetwork: Boolean,
)

/**
 * How the child process is launched.
 *
 * The three values are the ones the machine has, and they are a VALUE so
 * that a test can point them at something else: the supervisor does not
 * know how a JVM is invoked, it knows that a command is.
 */
internal data class ServiceRunCommand(
    val javaBinary: String,
    val classpath: String,
    val mainClass: String,
) {

    /**
     * The argv of the child that runs [script].
     *
     * ## Why this is a list and not a string
     *
     * Because it is handed to the operating system as a list: there is no
     * shell anywhere on the path, so no name the client controls can become
     * a shell word. Every value here is either a path the admission
     * resolved or a value the operator declared.
     *
     * ## Why the child runs in the script's own directory
     *
     * Because a script that reads a file next to itself has to find it
     * wherever the script is. The directory of the script is the one place
     * that is the same for every operator of the same script.
     */
    fun argvFor(
        script: Path,
        workspace: Path?,
        correlationId: String,
        policy: ServiceRunPolicy,
    ): List<String> {
        val argv = mutableListOf(
            javaBinary,
            "-cp",
            classpath,
            mainClass,
            "run",
            "--db",
            policy.eventStore,
            "--control-root",
            policy.controlRoot,
            "--format",
            "jsonl",
            "--correlation-id",
            correlationId,
        )
        if (policy.allowNetwork) argv += "--allow-network"
        if (workspace != null) {
            argv += listOf("--workspace", workspace.toString())
        } else {
            argv += "--isolated"
        }
        argv += script.toString()
        return argv
    }
}

/**
 * The budgets of the supervisor.
 *
 * @property maxSupervisedRuns how many children the service watches at once.
 * @property startDeadlineMillis how long the service waits for a child to own itself.
 */
internal data class ServiceStartLimits(
    val maxSupervisedRuns: Int = 4,
    val startDeadlineMillis: Long = 30_000L,
)

/**
 * The supervisor of the runs this service starts.
 *
 * ## What it owns
 *
 * One child process per run, launched through the port that already exists
 * for launching processes ([DurableTaskRuntime]), watched until it owns
 * itself, and then left alone: the run does not belong to the request that
 * asked for it.
 *
 * ## Why the child is a PROCESS and not a thread
 *
 * Because the run writes in the same store the service reads, and because a
 * run that lives in the service's JVM dies with the service. A child is the
 * only shape in which a run can outlive the request that asked for it.
 *
 * ## Why the answer waits for the receipt
 *
 * Because the receipt is the evidence of ownership: the child takes its
 * lease BEFORE it prints it, and a client that is told "started" before
 * that has been told about a run that may not exist. The answer therefore
 * carries the run's own identity, and the run keeps going after it.
 *
 * ## What the supervisor does NOT do
 *
 * It does not decide the outcome of a run, and it does not keep the run's
 * facts: those live in the store and in the control root, and the service
 * reads them from there like it reads every other run.
 */
internal class ServiceRunSupervisor(
    private val admission: ServiceStartAdmission,
    private val runPolicy: ServiceRunPolicy,
    private val command: ServiceRunCommand,
    private val port: DurableTaskRuntime,
    private val registry: ServiceStartRegistry,
    private val limits: ServiceStartLimits,
    private val supervisionRoot: Path,
) : ServiceRunStarter, AutoCloseable {

    /** The children this service is watching right now. */
    private val liveRuns = AtomicInteger(0)

    /** The jobs of the children, so that a stop can end them. */
    private val liveJobs = ConcurrentHashMap.newKeySet<Job>()

    /** The threads of the children, so that a stop can WAIT for them. */
    private val liveThreads = ConcurrentHashMap.newKeySet<Thread>()

    /**
     * Starts the run [request] asks for.
     *
     * ## The order of the checks is the contract
     *
     * The key is claimed FIRST, because a client that retries with a key the
     * service already answered must get the run that started — even if the
     * script has been removed from the directory since. Then the names are
     * resolved, because a request for a script that does not exist is not a
     * request to run. And only then is a slot of the budget taken, because
     * a request that is already answered needs no slot.
     *
     * ## What the answer proves
     *
     * That a run EXISTS and OWNS ITSELF: the child took its lease and
     * printed the receipt that says so, and the receipt carries the
     * correlation this request gave it. A receipt that belongs to another
     * request is not evidence of anything, and it is refused.
     */
    override fun start(request: ServiceStartRequest): ServiceStartOutcome {
        val digest = digestOf(request.script, request.workspace)
        val key = request.idempotencyKey?.let { registryKeyOf(request.credentialDigest, it) }

        if (key != null) {
            when (val claim = registry.claim(key, digest)) {
                is ServiceStartClaim.Known -> return acceptedOf(claim.record)

                is ServiceStartClaim.Conflict -> return ServiceStartOutcome.Refused(
                    ServiceRefusal.IdempotencyConflict(request.idempotencyKey),
                )

                is ServiceStartClaim.Awaiting -> return awaitOf(claim.future)

                is ServiceStartClaim.Owner -> Unit
            }
        }

        val admitted = admission.admit(request.script, request.workspace)
        if (admitted is ServiceStartAdmissionOutcome.Refused) {
            if (key != null) registry.fail(key, "the names do not resolve")
            return ServiceStartOutcome.Refused(admitted.refusal)
        }
        val paths = admitted as ServiceStartAdmissionOutcome.Admitted

        if (!takeSlot()) {
            if (key != null) registry.fail(key, "the budget of supervised runs was full")
            return ServiceStartOutcome.Refused(ServiceRefusal.TooManySupervisedRuns)
        }

        return launchRun(paths, key, digest)
    }

    /**
     * Launches the child of [paths] and waits for it to own itself.
     *
     * ## Why the wait is here and not in the caller
     *
     * Because the answer of a start is the receipt of the child, and the
     * receipt arrives after the child took its lease. The caller of this
     * method has already decided that the request is admitted and that
     * there is a slot for it: from here on, the only question is what the
     * child said.
     *
     * @param paths the names the admission resolved.
     * @param key the key of the claim, or null when the client chose none.
     * @param digest the fingerprint of the request that was made.
     */
    private fun launchRun(
        paths: ServiceStartAdmissionOutcome.Admitted,
        key: String?,
        digest: String,
    ): ServiceStartOutcome {
        val correlationId = UuidCorrelationIdGenerator().next().value
        val supervisionId = "sup-" + UUID.randomUUID().toString()
        val argv = command.argvFor(paths.script, paths.workspace, correlationId, runPolicy)
        // The supervision's own directory: the port writes the child's
        // result there, and the child's error output goes next to it. It is
        // under the service's own directory, so it is the operator's.
        val supervisionDir = supervisionRoot.resolve(supervisionId)
        try {
            Files.createDirectories(supervisionDir)
        } catch (_: Exception) {
            // The directory is where the port writes its result; if it
            // cannot be made, the port will fail on its own and the start
            // is refused with what the port said.
        }
        val watcher = LaunchWatcher(supervisionDir.resolve(CHILD_LOG_NAME))
        val job = Job()
        val taskRequest = TaskExecutionRequest(
            task = TaskSpec.ExecTask(argv),
            runId = RunId(supervisionId),
            opId = supervisionId,
            attempt = 0,
            timeoutMs = runPolicy.deadlineMillis,
            env = emptyMap(),
            workspaceRoot = paths.script.parent?.toString(),
        )
        val thread = Thread(
            {
                try {
                    runBlocking(job) { port.execute(taskRequest, watcher) }
                } catch (_: Exception) {
                    // The child's failure is the RUN's failure and not the
                    // service's: the service keeps serving, and the run's
                    // own facts say what happened to it.
                } finally {
                    // The slot the budget gave to this run is given back
                    // HERE and nowhere else: one run, one slot, one
                    // decrement. A counter that is incremented twice and
                    // decremented once is a budget that leaks, and a budget
                    // that leaks refuses every run after the fourth.
                    liveRuns.decrementAndGet()
                    liveJobs.remove(job)
                    liveThreads.remove(Thread.currentThread())
                    watcher.signalFinished()
                }
            },
            "pipelinek-supervisor-$supervisionId",
        )
        thread.isDaemon = true
        liveJobs.add(job)
        liveThreads.add(thread)
        try {
            thread.start()
        } catch (failure: Exception) {
            liveRuns.decrementAndGet()
            liveJobs.remove(job)
            liveThreads.remove(thread)
            if (key != null) registry.fail(key, "the child could not be launched")
            return ServiceStartOutcome.Refused(
                ServiceRefusal.RunStartRefused(
                    "the-child-could-not-be-launched",
                    failure.message ?: "the run's process could not be started",
                ),
            )
        }

        return when (val waited = watcher.await(limits.startDeadlineMillis)) {
            is LaunchWatcher.Wait.Receipt -> {
                val receipt = waited.receipt
                if (receipt is ServiceLaunchReceipt.Refused) {
                    if (key != null) registry.fail(key, "the run refused to launch")
                    return ServiceStartOutcome.Refused(
                        ServiceRefusal.RunStartRefused(
                            "the-run-refused",
                            "the run refused to start; the operator's log says why",
                        ),
                    )
                }
                val launched = receipt as ServiceLaunchReceipt.Launched
                if (launched.correlationId != correlationId) {
                    if (key != null) registry.fail(key, "the receipt is not this request's")
                    return ServiceStartOutcome.Refused(
                        ServiceRefusal.RunStartRefused(
                            "the-receipt-is-not-ours",
                            "the receipt of the launch belongs to another request; " +
                                "the operator's log says why",
                        ),
                    )
                }
                val record = ServiceStartRecord(
                    digest = digest,
                    runId = launched.runId,
                    correlationId = launched.correlationId,
                    ownerId = launched.ownerId,
                    startedAtMs = launched.startedAtMs,
                )
                if (key != null) registry.complete(key, record)
                acceptedOf(record)
            }

            LaunchWatcher.Wait.Finished -> {
                if (key != null) registry.fail(key, "the run ended before it owned itself")
                ServiceStartOutcome.Refused(
                    ServiceRefusal.RunStartRefused(
                        "the-child-died",
                        "the run ended before it owned itself; the operator's log says why",
                    ),
                )
            }

            LaunchWatcher.Wait.TimedOut -> {
                // The child is stopped through the PORT, which kills the tree
                // it can see: a run that nobody is waiting for any more must
                // not keep a slot of the budget for ever.
                job.cancel()
                if (key != null) registry.fail(key, "the run did not own itself in time")
                ServiceStartOutcome.Refused(
                    ServiceRefusal.RunStartRefused(
                        "no-ownership-in-time",
                        "the run did not own itself in time; the operator's log says why",
                    ),
                )
            }
        }
    }

    /**
     * The answer of a run that started.
     */
    private fun acceptedOf(record: ServiceStartRecord): ServiceStartOutcome.Accepted =
        ServiceStartOutcome.Accepted(
            record.runId,
            record.correlationId,
            record.ownerId,
            record.startedAtMs,
        )

    /**
     * The answer of a request that found another start of the SAME key in
     * flight.
     *
     * The wait is the answer of the request that OWNS the start, and it is
     * the failure of that start when it failed: a client that is told "it
     * did not finish in time" about a start that is known to have failed
     * would wait for a run that will never come.
     */
    private fun awaitOf(future: CompletableFuture<ServiceStartRecord>): ServiceStartOutcome = try {
        acceptedOf(future.get(limits.startDeadlineMillis, TimeUnit.MILLISECONDS))
    } catch (failure: Exception) {
        val cause = (failure as? ExecutionException)?.cause
        ServiceStartOutcome.Refused(
            ServiceRefusal.RunStartRefused(
                "the-other-start-did-not-finish",
                cause?.message ?: "the start of this key did not finish in time",
            ),
        )
    }

    /**
     * Takes a slot of the budget, or says there is none.
     *
     * The increment is atomic and the refusal undoes it, so two workers
     * racing for the last slot cannot both take it.
     */
    private fun takeSlot(): Boolean {
        val live = liveRuns.incrementAndGet()
        if (live <= limits.maxSupervisedRuns) return true
        liveRuns.decrementAndGet()
        return false
    }

    /**
     * Ends the runs this service supervises.
     *
     * A supervisor that leaves children behind when it stops is not a
     * supervisor: the cancellation goes through the port, which kills the
     * tree it can see, and the wait gives that kill the time it needs
     * before the process ends.
     */
    override fun close() {
        liveJobs.forEach { it.cancel() }
        liveThreads.forEach { thread ->
            try {
                thread.join(SHUTDOWN_WAIT_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        liveJobs.clear()
        liveThreads.clear()
    }

    private companion object {
        /** How long a stop waits for each child to be ended. */
        const val SHUTDOWN_WAIT_MILLIS: Long = 2_000L

        /** The name of the file the child's error output goes to. */
        const val CHILD_LOG_NAME: String = "child.err.log"

        /**
         * The fingerprint of the request that is made.
         *
         * The two names the client controls, and nothing else: the policy,
         * the deadline and the argv are the service's, and a request that
         * repeats with the same names IS the same request.
         *
         * The length of the script's name travels WITH it, because two
         * different pairs of names can produce the same text when they are
         * simply stuck together: `ab` with `c` is not `a` with `bc`.
         */
        fun digestOf(script: String, workspace: String?): String =
            ServiceAuthorizations.digestOf(script.length.toString() + ":" + script + (workspace ?: ""))

        /**
         * The key a client stores its starts under.
         *
         * The credential's digest and the client's own key, joined. The
         * join is the one that makes the key a key OF A CLIENT: two
         * clients that choose the same word are two different requests,
         * and a client that could read the run of another client would be
         * a hole in the boundary the credential draws.
         */
        fun registryKeyOf(credentialDigest: String, key: String): String =
            credentialDigest + ":" + key
    }
}

/**
 * What the child's receipt of its own launch said.
 */
internal sealed interface ServiceLaunchReceipt {

    /** The run took its ownership. */
    data class Launched(
        val runId: String,
        val correlationId: String,
        val ownerId: String,
        val startedAtMs: Long,
    ) : ServiceLaunchReceipt

    /** The run refused to start, and this is its reason. */
    data class Refused(val reason: String) : ServiceLaunchReceipt
}

/**
 * The watcher of a child's output.
 *
 * ## What it looks for
 *
 * The receipt of the launch, and nothing else: the child prints it on its
 * standard output, in one line, as soon as it owns itself. The rest of the
 * child's output is the run's own, and the run's facts live in the store.
 *
 * ## Why the lines are assembled
 *
 * Because the port delivers the output in CHUNKS, and a line can be split
 * between two of them. A receipt that arrives in halves is a receipt that
 * was never read, and a run that owns itself and is not believed is a run
 * that is refused.
 */
private class LaunchWatcher(
    private val logFile: Path?,
) : ExecutionOutputSink {

    /** What the watcher is waiting for. */
    private val state = AtomicReference<LaunchWatcher.Wait?>(null)

    /** The latch that is released when there is something to say. */
    private val arrived = CountDownLatch(1)

    /** The bytes of the line being assembled. */
    private val buffer = StringBuilder()

    /** The lock that guards the buffer. */
    private val bufferLock = Any()

    override suspend fun append(chunk: OutputChunk) {
        if (chunk.stream == TaskStream.STDERR) {
            writeToLog(chunk.data)
            return
        }
        val lines = synchronized(bufferLock) {
            buffer.append(String(chunk.data, StandardCharsets.UTF_8))
            val found = mutableListOf<String>()
            var newline = buffer.indexOf("\n")
            while (newline >= 0) {
                found += buffer.substring(0, newline)
                buffer.delete(0, newline + 1)
                newline = buffer.indexOf("\n")
            }
            if (buffer.length > MAX_LINE_CHARS) buffer.setLength(0)
            found
        }
        for (line in lines) {
            val receipt = parseLaunchReceipt(line) ?: continue
            if (state.compareAndSet(null, LaunchWatcher.Wait.Receipt(receipt))) arrived.countDown()
            return
        }
    }

    /**
     * Writes the child's error output in the operator's log.
     *
     * The log is a convenience and not a guarantee: a log that cannot be
     * written does not stop the run, because the run is not the service's
     * business and the service is not the run's.
     */
    private fun writeToLog(data: ByteArray) {
        val target = logFile ?: return
        try {
            Files.write(
                target,
                data,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
            )
        } catch (_: Exception) {
            // Nothing to do: the log is best-effort.
        }
    }

    /**
     * Says that the child is over, when it is over before saying anything.
     */
    fun signalFinished() {
        if (state.compareAndSet(null, LaunchWatcher.Wait.Finished)) arrived.countDown()
    }

    /**
     * Waits for the receipt, for the end of the child, or for the deadline.
     */
    fun await(millis: Long): LaunchWatcher.Wait {
        val got = arrived.await(millis, TimeUnit.MILLISECONDS)
        if (!got) return LaunchWatcher.Wait.TimedOut
        return state.get() ?: LaunchWatcher.Wait.Finished
    }

    /**
     * The four things the watcher can be waiting for.
     */
    sealed interface Wait {
        /** The receipt arrived. */
        data class Receipt(val receipt: ServiceLaunchReceipt) : Wait

        /** The child ended without saying anything. */
        data object Finished : Wait

        /** The deadline went by. */
        data object TimedOut : Wait
    }

    private companion object {

        /**
         * The schema of the receipt of a launch.
         *
         * It is the PRODUCER's constant and not a literal written here: the
         * producer's own KDoc says that a consumer must parse by it, and a
         * consumer that carried its own copy would stop recognising every
         * receipt the day the producer bumps it.
         */
        const val LAUNCH_SCHEMA: String = LaunchReceipt.SCHEMA

        /** The biggest line the watcher is willing to assemble. */
        const val MAX_LINE_CHARS: Int = 64 * 1024

        /**
         * The receipt [line] carries, or null when it carries none.
         *
         * A line that is not JSON, or that is JSON of another schema, is
         * not a receipt: the child's output holds more than one document
         * and only one of them is about the launch.
         */
        fun parseLaunchReceipt(line: String): ServiceLaunchReceipt? {
            val parsed = try {
                Json.parseToJsonElement(line)
            } catch (_: Exception) {
                return null
            }
            val document = parsed as? JsonObject ?: return null
            if (document.text("schema") != LAUNCH_SCHEMA) return null
            val status = document.text("status") ?: return null
            if (status.startsWith(REFUSED_PREFIX)) {
                return ServiceLaunchReceipt.Refused(status.removePrefix(REFUSED_PREFIX))
            }
            val runId = document.text("runId") ?: return null
            val correlationId = document.text("correlationId") ?: return null
            val ownerId = document.text("ownerId") ?: return null
            val startedAtMs = document.text("startedAtMs")?.toLongOrNull() ?: return null
            return ServiceLaunchReceipt.Launched(runId, correlationId, ownerId, startedAtMs)
        }

        /** The prefix of the status of a launch that was refused. */
        const val REFUSED_PREFIX: String = "Refused:"

        /** The string [key] holds, or null when it holds none. */
        fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
