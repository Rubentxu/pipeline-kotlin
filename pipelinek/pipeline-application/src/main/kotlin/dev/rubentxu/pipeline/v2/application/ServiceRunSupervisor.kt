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
 * The door the service opens to END a run.
 *
 * ## Why this is a separate door
 *
 * Because a cancellation has TWO owners and they are not the same one: the
 * supervisor owns the PROCESS —it launched the child and only it can end
 * it— and the control port owns the durable FACT. The one that ends a
 * process is not the one that writes a terminal, so it is its own port, and
 * the cancellation asks both.
 *
 * ## What the answer means
 *
 * Whether there WAS a child to end. A run this service does not supervise
 * has no process here, and the closing of such a run is the port's business
 * alone.
 */
internal interface ServiceRunEnder {

    /**
     * Ends the child that runs [runId], and says whether there was one.
     */
    fun end(runId: String): Boolean
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
     *
     * ## Why the child is told to start a FRESH run
     *
     * Because a start over the socket is a request for a NEW run, and the
     * command line's default is the opposite: `run` reuses the last run of
     * the same definition, so that a second `run` continues the first one.
     * Two clients that ask the service for the same script are asking for
     * two runs, and the run that owns itself is the one that holds the
     * lease: a child that reused the identity of a live run found the run
     * already owned and died before it owned itself.
     *
     * The promise that a key means ONE run is the service's own, and it is
     * kept here in the file of the starts, not in the reuse rule of the
     * command line.
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
            "--rerun",
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
) : ServiceRunStarter, ServiceRunEnder, AutoCloseable {

    /** The children this service is watching right now. */
    private val liveRuns = AtomicInteger(0)

    /** The jobs of the children, so that a stop can end them. */
    private val liveJobs = ConcurrentHashMap.newKeySet<Job>()

    /** The threads of the children, so that a stop can WAIT for them. */
    private val liveThreads = ConcurrentHashMap.newKeySet<Thread>()

    /**
     * The children this service watches, by the RUN's own id.
     *
     * The index is by the id the CHILD minted, and not by the id of the
     * supervision, because the id the client knows is the run's: a client
     * that asks to cancel a run names the run, and the service has to be
     * able to find the child that runs it.
     *
     * The index is filled when the receipt arrives —that is the moment the
     * service learns the run's id— and it is emptied when the child ends.
     */
    private val supervised = ConcurrentHashMap<String, Supervision>()

    /**
     * How many supervised runs are alive right now.
     *
     * It is the state of the budget, and it is readable so that a test can
     * tell a budget that gives its slot back from one that leaks it: a
     * counter that is only observable through its effect is a counter whose
     * leak no test can see.
     */
    internal fun supervisedRunsInFlight(): Int = liveRuns.get()

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
        // The correlation is chosen BEFORE the claim, because the claim is
        // written to the file: a claim that does not say which run it may
        // have started cannot be reconciled by anybody.
        val correlationId = UuidCorrelationIdGenerator().next().value

        if (key != null) {
            when (val claim = registry.claim(key, digest, correlationId)) {
                is ServiceStartClaim.Known -> return acceptedOf(claim.record)

                is ServiceStartClaim.Conflict -> return ServiceStartOutcome.Refused(
                    ServiceRefusal.IdempotencyConflict(request.idempotencyKey),
                )

                is ServiceStartClaim.EffectUnknown -> return ServiceStartOutcome.Refused(
                    ServiceRefusal.RunStartRefused(
                        "the-effect-of-this-key-is-unknown",
                        "a start of this key was claimed and the service cannot tell whether " +
                            "it started a run; that run may still be alive",
                    ),
                )

                is ServiceStartClaim.Awaiting -> return awaitOf(claim.future)

                is ServiceStartClaim.Owner -> Unit
            }
        }

        val admitted = admission.admit(request.script, request.workspace)
        if (admitted is ServiceStartAdmissionOutcome.Refused) {
            if (key != null) registry.fail(key, admitted.refusal)
            return ServiceStartOutcome.Refused(admitted.refusal)
        }
        val paths = admitted as ServiceStartAdmissionOutcome.Admitted

        if (!takeSlot()) {
            if (key != null) registry.fail(key, ServiceRefusal.TooManySupervisedRuns)
            return ServiceStartOutcome.Refused(ServiceRefusal.TooManySupervisedRuns)
        }

        return launchRun(paths, key, digest, correlationId)
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
     * @param correlationId the token the child will carry and answer with.
     */
    private fun launchRun(
        paths: ServiceStartAdmissionOutcome.Admitted,
        key: String?,
        digest: String,
        correlationId: String,
    ): ServiceStartOutcome {
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
        val supervision = Supervision(job)
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
                    // The child is over, so it is no longer a run this
                    // service may end: the index has to say so before the
                    // watcher says that the child is over, or a cancellation
                    // could find a child that is already dead.
                    supervision.over(supervised)
                    watcher.signalFinished()
                }
            },
            "pipelinek-supervisor-$supervisionId",
        )
        supervision.thread = thread
        thread.isDaemon = true
        liveJobs.add(job)
        liveThreads.add(thread)
        try {
            thread.start()
        } catch (failure: Exception) {
            liveRuns.decrementAndGet()
            liveJobs.remove(job)
            liveThreads.remove(thread)
            val refusal = ServiceRefusal.RunStartRefused(
                "the-child-could-not-be-launched",
                failure.message ?: "the run's process could not be started",
            )
            if (key != null) registry.fail(key, refusal)
            return ServiceStartOutcome.Refused(refusal)
        }

        val waited = try {
            watcher.await(limits.startDeadlineMillis)
        } catch (interrupted: InterruptedException) {
            // The thread that waits was interrupted, which happens when the
            // service is stopping. An interruption is NOT an answer, and an
            // answer that never arrives reads as a closed connection: the
            // request is refused with a name, the key is released, and the
            // child is stopped through the port.
            Thread.currentThread().interrupt()
            job.cancel()
            val refusal = ServiceRefusal.RunStartRefused(
                "the-start-was-interrupted",
                "the service stopped while the run was starting",
            )
            if (key != null) registry.fail(key, refusal)
            return ServiceStartOutcome.Refused(refusal)
        }

        return when (waited) {
            is LaunchWatcher.Wait.Receipt -> {
                val receipt = waited.receipt
                if (receipt is ServiceLaunchReceipt.Refused) {
                    // The child is stopped through the PORT, exactly as in
                    // the deadline below: a run that will not own itself
                    // must not keep a slot of the budget nor run on with
                    // nobody waiting for it.
                    job.cancel()
                    val refusal = ServiceRefusal.RunStartRefused(
                        "the-run-refused",
                        "the run refused to start; the operator's log says why",
                    )
                    if (key != null) registry.fail(key, refusal)
                    return ServiceStartOutcome.Refused(refusal)
                }
                val launched = receipt as ServiceLaunchReceipt.Launched
                if (launched.correlationId != correlationId) {
                    job.cancel()
                    val refusal = ServiceRefusal.RunStartRefused(
                        "the-receipt-is-not-ours",
                        "the receipt of the launch belongs to another request; " +
                            "the operator's log says why",
                    )
                    if (key != null) registry.fail(key, refusal)
                    return ServiceStartOutcome.Refused(refusal)
                }
                val record = ServiceStartRecord(
                    digest = digest,
                    runId = launched.runId,
                    correlationId = launched.correlationId,
                    ownerId = launched.ownerId,
                    startedAtMs = launched.startedAtMs,
                )
                // The run is now the one a client may ask to cancel, and
                // this is the only moment the service learns the id it has
                // to answer that ask with: the receipt carries the id the
                // CHILD minted.
                supervision.launched(supervised, launched.runId)
                if (key != null) registry.complete(key, record)
                acceptedOf(record)
            }

            LaunchWatcher.Wait.Finished -> {
                val refusal = ServiceRefusal.RunStartRefused(
                    "the-child-died",
                    "the run ended before it owned itself; the operator's log says why",
                )
                if (key != null) registry.fail(key, refusal)
                ServiceStartOutcome.Refused(refusal)
            }

            LaunchWatcher.Wait.TimedOut -> {
                // The child is stopped through the PORT, which kills the tree
                // it can see: a run that nobody is waiting for any more must
                // not keep a slot of the budget for ever.
                job.cancel()
                val refusal = ServiceRefusal.RunStartRefused(
                    "no-ownership-in-time",
                    "the run did not own itself in time; the operator's log says why",
                )
                if (key != null) registry.fail(key, refusal)
                ServiceStartOutcome.Refused(refusal)
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
     *
     * The failure that travels back is the refusal the owner read, with
     * its NAME: a client that decides whether to try again by the name of
     * the refusal is not told one thing by the owner and another by the
     * waiter, because the two are the same fact told twice.
     */
    private fun awaitOf(future: CompletableFuture<ServiceStartRecord>): ServiceStartOutcome {
        return try {
            acceptedOf(future.get(limits.startDeadlineMillis, TimeUnit.MILLISECONDS))
        } catch (failure: Exception) {
            val cause = (failure as? ExecutionException)?.cause
            if (cause is ServiceStartFailed) {
                return ServiceStartOutcome.Refused(cause.refusal)
            }
            ServiceStartOutcome.Refused(
                ServiceRefusal.RunStartRefused(
                    "the-other-start-did-not-finish",
                    cause?.message ?: "the start of this key did not finish in time",
                ),
            )
        }
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
     * Ends the child that runs [runId], and says whether there was one.
     *
     * ## Why the wait is here
     *
     * Because the durable terminal is written AFTER the child is gone, and
     * the port writes it only when the run's lease is free: the lease is
     * held by the child while the child lives, so a cancellation that did
     * not wait would be refused with "the lease is held by another" —the
     * truth, and a truth that would make the cancellation useless.
     *
     * The wait is BOUNDED: a child that does not die in time does not hold
     * the caller for ever, and the port says what it finds.
     *
     * ## Why the answer is a boolean and not a refusal
     *
     * Because there is nothing to refuse: a run this service does not
     * supervise has no child here, and the closing of such a run is the
     * port's business alone. The two halves of a cancellation are separate
     * facts, and this half says whether there was anything to end.
     */
    override fun end(runId: String): Boolean {
        val supervision = supervised[runId] ?: return false
        // The child is ended through the PORT, which kills the tree it can
        // see: a run nobody waits for any more must not keep running.
        supervision.job.cancel()
        val thread = supervision.thread
        if (thread != null) {
            try {
                thread.join(CANCEL_WAIT_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return true
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
        supervised.clear()
    }

    /**
     * The derivations the supervisor owns.
     *
     * They are visible to the module because a test has to be able to
     * reproduce the record of the starts that a service that DIED leaves
     * behind: the key a client chose, derived the way the service derives
     * it, and the fingerprint of the request it made.
     */
    internal companion object {
        /** How long a stop waits for each child to be ended. */
        const val SHUTDOWN_WAIT_MILLIS: Long = 2_000L

        /**
         * How long a cancellation waits for the child to be gone.
         *
         * It is the wait of the ORDER of §4.1: the terminal durable is
         * written after the child dies, and the port writes it only when the
         * lease is free. Ten seconds is long enough for a process that is
         * being destroyed for it to die, and short enough that a caller is
         * not left waiting for a child that does not want to die.
         */
        const val CANCEL_WAIT_MILLIS: Long = 10_000L

        /** The name of the file the child's error output goes to. */
        const val CHILD_LOG_NAME: String = "child.err.log"

        /**
         * The fingerprint of the request that is made.
         *
         * The two names the client controls, and nothing else: the policy,
         * the deadline and the argv are the service's, and a request that
         * repeats with the same names IS the same request.
         *
         * The length of EACH name travels WITH it, because two different
         * pairs of names can produce the same text when they are simply
         * stuck together: `ab` with `c` is not `a` with `bc`. A name that
         * is absent is not the same as a name that is empty, and the
         * fingerprint says so with a length of `-1` for the absent one.
         */
        fun digestOf(script: String, workspace: String?): String {
            val workspaceText = workspace ?: return ServiceAuthorizations.digestOf(
                script.length.toString() + ":" + script + "|-1:",
            )
            return ServiceAuthorizations.digestOf(
                script.length.toString() + ":" + script + "|" +
                    workspaceText.length.toString() + ":" + workspaceText,
            )
        }

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
 * One child the service watches.
 *
 * ## What it holds
 *
 * The [job] that ends the child —through the port, which kills the tree it
 * can see— and the [thread] that waits for it, so that a cancellation can
 * WAIT for the child to be really gone.
 *
 * ## Why the run id is filled LATE
 *
 * Because the id a client names is the one the CHILD minted, and the child
 * mints it when it takes its lease: before the receipt arrives there is no
 * id to index by. The [launched] call is the moment the service learns it.
 *
 * ## Why the ending is a FLAG and not only a removal
 *
 * Because the two events race: a child can die before its receipt is read,
 * and a receipt that arrives after the child died would put a dead child
 * back into the index. The flag makes the removal and the registration
 * agree, whichever comes first.
 */
private class Supervision(
    /** The job that ends the child. */
    val job: Job,
) {

    /** The thread that waits for the child. */
    @Volatile
    var thread: Thread? = null

    /** The lock that guards [ended] and [runId]. */
    private val lock = Any()

    /** Whether the child is already over. */
    private var ended = false

    /** The id the child minted, when it has minted one. */
    private var runId: String? = null

    /**
     * Records the id the child minted, and indexes the supervision by it.
     *
     * A child that is already over is NOT indexed: the receipt can arrive
     * after the death of the child, and a dead child in the index would be a
     * run the service would wait for.
     */
    fun launched(index: ConcurrentHashMap<String, Supervision>, childRunId: String) {
        synchronized(lock) {
            if (ended) return
            runId = childRunId
            index[childRunId] = this
        }
    }

    /**
     * Says that the child is over, and takes the supervision out of the index.
     */
    fun over(index: ConcurrentHashMap<String, Supervision>) {
        synchronized(lock) {
            ended = true
            runId?.let { index.remove(it, this) }
        }
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
