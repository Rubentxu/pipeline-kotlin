package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.dsl.RunInputs
import dev.rubentxu.pipeline.v2.dsl.RunInputsOutcome
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import dev.rubentxu.pipeline.v2.runtime.RunConfiguration
import dev.rubentxu.pipeline.v2.runtime.RunConfigurationOutcome
import dev.rubentxu.pipeline.v2.service.ServiceProviderReference
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
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
 * @property configuration the name/value pairs the steps of the run will see, as the client
 *   sent them. They are RAW here: the rules are the supervisor's to apply, and the supervisor
 *   applies them before it claims the idempotency key.
 * @property parameters the values the client delivered for the parameters the pipeline
 *   declares, as the client sent them. They are RAW here too, and they are checked against
 *   the [parameterContract] before the idempotency key is claimed.
 * @property parameterContract the declaration the client knows the pipeline has. It travels
 *   because the service does not compile the script: describing it is compiling it, and
 *   compiling it is running the author's code inside the service.
 * @property configurationProvider the provider session the keys the request did not set itself
 *   are resolved through, or null when the request asks none. The values are never in the
 *   request: the service asks the provider for them at the start.
 * @property credentialProvider the name of the credential provider session of the client whose
 *   controller holds the credentials of the run, or null when the request names none. The
 *   credentials are never in the request: the run asks for each one when a step needs it.
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
    val configuration: Map<String, String> = emptyMap(),
    val parameters: Map<String, String> = emptyMap(),
    val parameterContract: List<dev.rubentxu.pipeline.v2.domain.ParameterSpec> = emptyList(),
    val configurationProvider: ServiceProviderReference? = null,
    val credentialProvider: String? = null,
) {

    /**
     * The request WITHOUT the values of its configuration.
     *
     * The generated one would render them, and a line of log that carried
     * them would publish them to whoever reads the log. The rules are the
     * supervisor's to apply, and the values are the client's own text.
     *
     * The same is true of the parameters' values, and for the same reason.
     */
    override fun toString(): String =
        "ServiceStartRequest(script=$script, workspace=$workspace, idempotencyKey=$idempotencyKey, " +
            "credentialDigest=$credentialDigest, configuration=${configuration.size} entries, " +
            "parameters=${parameters.size} values, provider=${configurationProvider?.provider}, " +
            "credentialProvider=$credentialProvider)"
}

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
        /**
         * The configuration the run WILL HAVE.
         *
         * It is the configuration of the REQUEST that is being answered,
         * and not a copy of something the service remembered: the
         * fingerprint of a request covers the configuration, so a request
         * that repeats a key with another configuration is refused before
         * this point, and the configuration of the answer is therefore
         * always the configuration of the run that is answered.
         */
        val configuration: RunConfiguration = RunConfiguration.EMPTY,
        /**
         * The parameter values the run WILL HAVE.
         *
         * They are the DELIVERED ones —the values the client sent— and not the
         * effective ones, because the effective ones are the delivered plus the
         * defaults of the SCRIPT's own declaration, and the script's
         * declaration is not the service's to know: describing it is compiling
         * it, and compiling it is running the author's code inside the service.
         *
         * They are the configuration's twin: the answer echoes what the client
         * delivered, so a service that does not know the field ignores it in
         * silence and starts the run without the values, and the client that
         * sent them has to be able to tell the two apart.
         */
        val parameters: Map<String, String> = emptyMap(),
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
     *
     * ## Why the configuration travels in a FILE and not in the argv
     *
     * Because the argv of a process is readable by any user of the machine
     * through `/proc/<pid>/cmdline`, and the configuration is a PUBLIC
     * channel: its values are in the environment of the steps of the run.
     * Putting them in the argv as well would publish them to a wider
     * audience than the one that asked for them. The argv carries the PATH
     * of the snapshot, which is a path inside the service's own directory.
     *
     * ## Why the option is ABSENT when there is no configuration
     *
     * Because the argv of a run that was given nothing is the argv it
     * always was: no option, no snapshot, and the behaviour of the run is
     * the behaviour it always had.
     *
     * ## Why the credential broker travels as a DIRECTORY
     *
     * Because the directory holds the socket of the broker and the file with
     * the token of the session, and the argv is readable through
     * `/proc/<pid>/cmdline`: the argv names the place, the token stays in a
     * file the child reads once and deletes. The option is absent when the
     * service has no broker for the run, as for the snapshots.
     */
    fun argvFor(
        script: Path,
        workspace: Path?,
        correlationId: String,
        policy: ServiceRunPolicy,
        runConfig: Path? = null,
        runInputs: Path? = null,
        credentialBroker: Path? = null,
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
        runConfig?.let { argv += listOf("--run-config", it.toString()) }
        runInputs?.let { argv += listOf("--run-inputs", it.toString()) }
        credentialBroker?.let { argv += listOf("--credential-broker", it.toString()) }
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
 * The collaborators the service MAY have.
 *
 * ## Why they travel together
 *
 * Because they are the doors the service opens only when the operator's
 * declaration asks for them: a service that grants no credential opens no
 * broker, a service with no configuration provider asks no controller, and a
 * service that was not started with credential sessions exposes none. Three
 * parameters that are absent together are one thing that is present or
 * absent.
 *
 * @property brokers the factory that opens the credential broker of a run.
 * @property configurations the resolver that asks a controller for the values of a
 *   configuration the request did not carry.
 * @property credentialSessions the sessions a controller registers its credentials in.
 */
internal data class ServiceCollaborators(
    val brokers: RunBrokerFactory? = null,
    val configurations: ConfigurationResolver? = null,
    val credentialSessions: CredentialProviderSessions? = null,
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
 *
 * ## The broker of a run
 *
 * When the service holds credential [brokers], every run it starts gets its
 * own broker BEFORE the child is launched, with the grants of the client that
 * asked for the run, and loses it when the child is over, whatever way the run
 * ended. A run that never started leaves no broker behind.
 */
internal class ServiceRunSupervisor(
    private val admission: ServiceStartAdmission,
    private val runPolicy: ServiceRunPolicy,
    private val command: ServiceRunCommand,
    private val port: DurableTaskRuntime,
    private val registry: ServiceStartRegistry,
    private val limits: ServiceStartLimits,
    private val supervisionRoot: Path,
    private val collaborators: ServiceCollaborators = ServiceCollaborators(),
    /**
     * The door that closes a run whose child ended without a terminal.
     *
     * It is a VALUE and not a call into the store, because the store the run
     * writes is the store of the SUBSTRATE, and a supervisor that opened it
     * for itself would hold a writer against every run that starts next. The
     * door opens the writing lane only when a child has just died, and closes
     * it right after the fact is durable.
     */
    private val closer: SupervisedRunCloser =
        EventStoreSupervisedRunCloser(
            runPolicy.eventStore,
            SystemClock(),
            OutputPlaneSupervisedRunOutputSealer(Path.of(runPolicy.controlRoot)),
        ),
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
     * The CONFIGURATION is checked first, because its rules are pure and
     * have no effect: a request that is not a request does not occupy the
     * client's idempotency key, and the client can fix it and repeat with
     * the SAME key. Then the key is claimed, because a client that retries
     * with a key the service already answered must get the run that
     * started — even if the script has been removed from the directory
     * since. Then the names are resolved, because a request for a script
     * that does not exist is not a request to run. And only then is a slot
     * of the budget taken, because a request that is already answered
     * needs no slot.
     *
     * ## What the answer proves
     *
     * That a run EXISTS and OWNS ITSELF: the child took its lease and
     * printed the receipt that says so, and the receipt carries the
     * correlation this request gave it. A receipt that belongs to another
     * request is not evidence of anything, and it is refused.
     */
    override fun start(request: ServiceStartRequest): ServiceStartOutcome {
        // The rules of the configuration are checked BEFORE anything else,
        // and the reason is the ORDER: they have no effect, so a request
        // that breaks them never becomes a claim, and the client keeps a
        // key it can retry with.
        val configuration = when (val outcome = RunConfiguration.of(request.configuration)) {
            is RunConfigurationOutcome.Accepted -> outcome.configuration
            is RunConfigurationOutcome.Refused -> return ServiceStartOutcome.Refused(
                ServiceRefusal.RunConfigurationRefused(
                    outcome.refusal.reason,
                    outcome.refusal.detail,
                ),
            )
        }
        // The PARAMETERS are checked next, and for the same reason as the
        // configuration: the rules are pure and they have no effect, so a
        // request that breaks them never becomes a claim.
        //
        // What they are checked against is the CONTRACT the client carries,
        // and not the declaration of the script: the service does not compile
        // the scripts it is asked about, and describing one is compiling it.
        //
        // A request that delivers values WITHOUT the contract is refused
        // before it is checked: the service cannot check what it cannot
        // know, and taking the values on faith is not something it does.
        if (request.parameters.isNotEmpty() && request.parameterContract.isEmpty()) {
            return ServiceStartOutcome.Refused(
                ServiceRefusal.ParametersRefused(
                    "the-service-has-no-contract-for-these-parameters",
                    "the service does not compile the scripts it is asked about, so it cannot " +
                        "know the parameters a script declares; the request has to carry the " +
                        "declaration the client knows",
                ),
            )
        }
        when (val outcome = checkParameters(request.parameters, request.parameterContract)) {
            is RunInputsOutcome.Accepted -> Unit
            is RunInputsOutcome.Refused -> return ServiceStartOutcome.Refused(
                ServiceRefusal.ParametersRefused(
                    outcome.refusal.reason,
                    outcome.refusal.detail,
                ),
            )
        }
        // The provider reference is checked next, and for the same reason: the check is pure.
        // What it decides is which keys the PROVIDER is asked for — the ones the request did not
        // set itself — and whether the merged configuration can possibly be legal.
        val plan = when (val outcome = planProvider(request.configurationProvider, configuration)) {
            is ProviderPlan.Refused -> return ServiceStartOutcome.Refused(outcome.refusal)
            else -> outcome
        }
        // The credential provider is checked here too, for the same reason: the checks are pure,
        // and a start that names a controller which is not there never becomes a claim.
        checkCredentialProvider(request)?.let { return ServiceStartOutcome.Refused(it) }
        val digest = digestOf(
            request.script,
            request.workspace,
            configuration,
            request.parameterContract,
            request.parameters,
            request.configurationProvider,
            request.credentialProvider,
        )
        val key = request.idempotencyKey?.let { registryKeyOf(request.credentialDigest, it) }
        // The correlation is chosen BEFORE the claim, because the claim is
        // written to the file: a claim that does not say which run it may
        // have started cannot be reconciled by anybody.
        val correlationId = UuidCorrelationIdGenerator().next().value

        if (key != null) {
            when (val claim = registry.claim(key, digest, correlationId)) {
                is ServiceStartClaim.Known -> return acceptedOf(
                    claim.record,
                    configuration,
                    request.parameters,
                )

                is ServiceStartClaim.Conflict -> return ServiceStartOutcome.Refused(
                    ServiceRefusal.IdempotencyConflict(request.idempotencyKey),
                )

                // The refusal says what the operator has to do with the run
                // this key may have started: the correlation that finds it
                // and the operations that reconcile it. A client that is
                // told that the effect is unknown and nothing else has to
                // ask the operator what to do, and the operator has to look
                // for an answer the service already knows.
                is ServiceStartClaim.EffectUnknown -> return ServiceStartOutcome.Refused(
                    ServiceRefusal.RunStartRefused(
                        "the-effect-of-this-key-is-unknown",
                        "a start of this key was claimed and the service cannot tell whether " +
                            "it started a run; that run may still be alive; " +
                            claim.reconciliation,
                    ),
                )

                is ServiceStartClaim.Awaiting -> return awaitOf(
                    claim.future,
                    configuration,
                    request.parameters,
                )

                is ServiceStartClaim.Refused -> return ServiceStartOutcome.Refused(
                    ServiceRefusal.RunStartRefused(
                        "the-start-cannot-be-recorded",
                        "the claim of this key could not be written, and a start that cannot be " +
                            "remembered is a start that cannot be promised: ${claim.cause}",
                    ),
                )

                is ServiceStartClaim.Owner -> Unit
            }
        }

        val admitted = admission.admit(request.script, request.workspace)
        if (admitted is ServiceStartAdmissionOutcome.Refused) {
            if (key != null) registry.fail(key, admitted.refusal)
            return ServiceStartOutcome.Refused(admitted.refusal)
        }
        val paths = admitted as ServiceStartAdmissionOutcome.Admitted

        // The provider is asked AFTER the claim and the admission and BEFORE a slot is taken:
        // a start that is already answered, or that names a script which is not there, must not
        // wake a controller, and a start that waits for a controller must not hold a slot of the
        // budget while it waits. A resolution that fails releases the key, so the client can
        // repeat the request with the same key once the provider is back.
        val effective = when (val outcome = resolveEffective(plan, request, configuration)) {
            is EffectiveOutcome.Resolved -> outcome.effective
            is EffectiveOutcome.Refused -> {
                if (key != null) registry.fail(key, outcome.refusal)
                return ServiceStartOutcome.Refused(outcome.refusal)
            }
        }

        if (!takeSlot()) {
            if (key != null) registry.fail(key, ServiceRefusal.TooManySupervisedRuns)
            return ServiceStartOutcome.Refused(ServiceRefusal.TooManySupervisedRuns)
        }

        return launchRun(
            paths,
            key,
            digest,
            correlationId,
            configuration,
            effective,
            request.parameters,
            BrokerBinding(request.credentialDigest, request.credentialProvider),
        )
    }

    /**
     * Why the credential provider the request names cannot serve it, or null when it can.
     *
     * The order is the order of the remedies: a name that is not a name is the client's text; a
     * service that was not started to serve credential sessions, or that grants no credential to
     * any client, is the operator's; and a controller that is not registered is the client's
     * again. Nothing here is asked of the controller, and nothing falls back to another provider.
     */
    private fun checkCredentialProvider(request: ServiceStartRequest): ServiceRefusal? {
        val name = request.credentialProvider ?: return null
        val sessions = collaborators.credentialSessions
        val refusal = when {
            !isLegalProviderName(name) -> "credential-provider-name-illegal" to
                "the name of a credential provider matches $PROVIDER_NAME_SHAPE"

            sessions == null -> CredentialProviderOperations.REASON_NOT_COMPOSED to
                "this service was not started to serve credential sessions"

            collaborators.brokers == null -> "credential-brokers-not-composed" to
                "no authorization of this service grants a credential, so its runs have no broker"

            request.credentialDigest.isBlank() || !sessions.hasSession(request.credentialDigest, name) ->
                "provider-not-registered" to
                    "no live session of the credential provider '$name' exists for this credential"

            else -> return null
        }
        return ServiceRefusal.CredentialProviderRefused(refusal.first, refusal.second)
    }

    /**
     * Which keys the provider is asked for, decided without any effect.
     *
     * A key the request set itself is never asked: the explicit value has precedence and the
     * provider is not told it exists. The merged shape —the explicit entries plus the keys to
     * ask, with an empty value standing for the one the provider will give— goes through the
     * rules of the configuration, so a name that could never be legal, or a merge that would
     * exceed the limit of entries, is refused before the idempotency key is claimed.
     */
    private fun planProvider(reference: ServiceProviderReference?, explicit: RunConfiguration): ProviderPlan {
        if (reference == null) return ProviderPlan.Nothing
        if (reference.keys.isEmpty()) {
            return ProviderPlan.Refused(
                ServiceRefusal.RunConfigurationRefused(
                    "provider-has-no-keys",
                    "a provider reference must name at least one key to resolve",
                ),
            )
        }
        val toAsk = reference.keys.distinct().filter { it !in explicit.values }
        when (val outcome = RunConfiguration.of(explicit.values + toAsk.associateWith { "" })) {
            is RunConfigurationOutcome.Accepted -> Unit
            is RunConfigurationOutcome.Refused -> return ProviderPlan.Refused(
                ServiceRefusal.RunConfigurationRefused(outcome.refusal.reason, outcome.refusal.detail),
            )
        }
        return if (toAsk.isEmpty()) ProviderPlan.Nothing else ProviderPlan.Ask(toAsk)
    }

    /**
     * The configuration the run will be given: the explicit one, completed by the provider.
     *
     * ## What the provider can and cannot do
     *
     * It is asked for exactly the keys of the plan, through the session the SAME credential
     * registered under the name the request gave: there is no lookup by name across
     * credentials and no fallback to another provider, and a resolution that fails stops the
     * start. What it answers is merged under the explicit entries and goes through the rules of
     * the configuration again, so the snapshot the run keeps is the one that was validated.
     */
    private fun resolveEffective(
        plan: ProviderPlan,
        request: ServiceStartRequest,
        explicit: RunConfiguration,
    ): EffectiveOutcome {
        if (plan !is ProviderPlan.Ask) return EffectiveOutcome.Resolved(EffectiveConfiguration(explicit, null))
        val provider = checkNotNull(request.configurationProvider).provider
        val resolver = collaborators.configurations
        if (resolver == null || request.credentialDigest.isBlank()) {
            return EffectiveOutcome.Refused(
                ServiceRefusal.RunConfigurationRefused(
                    "provider-not-registered",
                    "no live session of the provider '$provider' exists for this credential",
                ),
            )
        }
        val resolution = try {
            resolver.resolve(request.credentialDigest, provider, plan.keys)
        } catch (_: Exception) {
            return EffectiveOutcome.Refused(
                ServiceRefusal.RunConfigurationRefused(
                    "provider-resolution-failed",
                    "the provider '$provider' could not be asked for its values",
                ),
            )
        }
        return when (resolution) {
            is ProviderResolution.Refused -> EffectiveOutcome.Refused(
                ServiceRefusal.RunConfigurationRefused(resolution.reason, resolution.detail),
            )

            is ProviderResolution.Resolved -> mergeResolved(explicit, plan, resolution)
        }
    }

    private fun mergeResolved(
        explicit: RunConfiguration,
        plan: ProviderPlan.Ask,
        resolution: ProviderResolution.Resolved,
    ): EffectiveOutcome {
        if (resolution.values.keys != plan.keys.toSet()) {
            // The resolver promises exactly the keys it was asked: a value for a key nobody
            // asked is not configuration the request delivered, and it is not applied.
            return EffectiveOutcome.Refused(
                ServiceRefusal.RunConfigurationRefused(
                    "provider-answer-unexpected",
                    "the provider answered keys other than the ones it was asked",
                ),
            )
        }
        val merged = when (val outcome = RunConfiguration.of(explicit.values + resolution.values)) {
            is RunConfigurationOutcome.Accepted -> outcome.configuration
            is RunConfigurationOutcome.Refused -> return EffectiveOutcome.Refused(
                ServiceRefusal.RunConfigurationRefused(outcome.refusal.reason, outcome.refusal.detail),
            )
        }
        val origins = LinkedHashMap<String, RunConfigurationOrigin>()
        explicit.values.keys.sorted().forEach { name ->
            origins[name] = RunConfigurationOrigin(RunConfigurationReader.ORIGIN_REQUEST, RunConfigurationReader.TYPE_TEXT)
        }
        plan.keys.sorted().forEach { name ->
            origins[name] = RunConfigurationOrigin(
                RunConfigurationReader.ORIGIN_PROVIDER,
                resolution.provenance.types[name] ?: RunConfigurationReader.TYPE_TEXT,
            )
        }
        val provenance = RunConfigurationProvenance(
            provider = resolution.provenance.provider,
            revision = resolution.provenance.revision,
            sourceDigest = resolution.provenance.digest,
            origins = origins,
        )
        return EffectiveOutcome.Resolved(EffectiveConfiguration(merged, provenance))
    }

    /**
     * The check of the [values] of a request against the [contract] it carries.
     *
     * ## Why the check applies the contract's DEFAULTS
     *
     * Because the contract is the declaration the client knows, and the values of a request
     * are the values the client delivered: the effective values of the REQUEST are the
     * delivered ones plus the defaults of that declaration.
     *
     * The script's own declaration is verified by the child, and the effective values of the RUN
     * are the ones of the script. A client whose contract differs from the script gets a run
     * that refuses to start, and the operator's log says why.
     *
     * ## When the child's check happens
     *
     * AFTER the script's own top-level body ran, and BEFORE any step of the run ran. The
     * declaration of a pipeline is built while the author's body runs —that is when the
     * `pipeline { }` block is constructed— so the child cannot know it any earlier, and the
     * check that is refused here is refused before the first step, not before the author's
     * code.
     */
    private fun checkParameters(
        values: Map<String, String>,
        contract: List<dev.rubentxu.pipeline.v2.domain.ParameterSpec>,
    ): RunInputsOutcome {
        if (values.isEmpty() && contract.isEmpty()) return RunInputsOutcome.Accepted(RunInputs.EMPTY)
        return RunInputs.of(contract, values)
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
     * @param configuration the configuration of the REQUEST, the one the answer echoes.
     * @param effective the configuration the child is given: the request's, completed by the
     *   provider when the request named one.
     * @param binding who asked: the digest of the token of the client, or null when the request
     *   came from nobody the service knows, decides what the broker of the run grants, and the
     *   name of its credential provider decides where the bytes of a grant come from.
     */
    private fun launchRun(
        paths: ServiceStartAdmissionOutcome.Admitted,
        key: String?,
        digest: String,
        correlationId: String,
        configuration: RunConfiguration,
        effective: EffectiveConfiguration,
        delivered: Map<String, String>,
        binding: BrokerBinding,
    ): ServiceStartOutcome {
        // The supervision's own identity is the CORRELATION the request
        // carries: the child publishes it in the run's own history, and a
        // directory named after it can be found again after the service
        // restarts. A name the service invented here would know only this
        // process, and the facts inside the directory would be there with
        // nobody able to name them.
        val supervisionId = supervisionDirectoryName(correlationId)
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

        // The snapshots the child reads, written BEFORE the child is launched.
        val snapshots = when (val outcome = snapshotsOf(supervisionDir, key, effective, delivered)) {
            is SnapshotsOutcome.Written -> outcome
            is SnapshotsOutcome.Refused -> return ServiceStartOutcome.Refused(outcome.refusal)
        }

        // The broker of the run is opened AFTER the snapshots and BEFORE the
        // argv, because the argv names its directory. A broker that cannot be
        // opened stops the run: a run that was promised credentials it will
        // never be given would only fail later, inside a step, with less to
        // say about why.
        val lease = when (val opening = openBroker(key, binding, correlationId)) {
            is BrokerOpening.Opened -> opening.lease
            is BrokerOpening.Refused -> return ServiceStartOutcome.Refused(opening.refusal)
        }

        val argv = command.argvFor(
            paths.script,
            paths.workspace,
            correlationId,
            runPolicy,
            // The snapshot is written for EVERY run, and the child is given it
            // only when it carries values: the argv of a plain run is the argv
            // it always was.
            snapshots.configuration.takeIf { !effective.configuration.isEmpty },
            snapshots.parameters,
            lease?.directory,
        )
        val watcher = LaunchWatcher(supervisionDir.resolve(CHILD_LOG_NAME))
        val job = Job()
        val supervision = Supervision(job)
        val taskRequest = taskRequestOf(supervisionId, argv, paths.script)
        val thread = Thread(
            { superviseChild(job, watcher, supervision, taskRequest, lease) },
            "pipelinek-supervisor-$supervisionId",
        )
        supervision.thread = thread
        thread.isDaemon = true
        liveJobs.add(job)
        liveThreads.add(thread)
        try {
            thread.start()
        } catch (failure: Exception) {
            // The thread never ran, so nobody else will close the broker.
            closeLease(lease)
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
                acceptedOf(record, configuration, delivered)
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
     * The thread that waits for the child and keeps the service's own facts
     * true while the run is alive.
     *
     * The child's failure is the RUN's failure and not the service's: the
     * service keeps serving, and the run's own facts say what happened to
     * it. What the thread does NOT do is swallow the run's end: when the
     * child is over, the run is no longer one this service may end, and the
     * slot the budget gave to it goes back.
     *
     * The order inside the `finally` is the contract, because the two facts
     * are read by different hands: the budget answers "may the service start
     * another run?", and the index answers "is there a child to end?". A
     * budget that said "no run in flight" while the index still held the
     * child would let a second run start while the first one is still in the
     * index, and a cancellation arriving in that window would find a child
     * that is already dead.
     *
     * The broker of the run goes FIRST: a token that outlives the child opens
     * nothing, and a broker that cannot be closed must not keep the slot.
     */
    private fun superviseChild(
        job: Job,
        watcher: LaunchWatcher,
        supervision: Supervision,
        taskRequest: TaskExecutionRequest,
        lease: RunBrokerLease?,
    ) {
        var end: SupervisedRunEnd? = null
        try {
            val result = runBlocking(job) { port.execute(taskRequest, watcher) }
            end = endOf(result)
        } catch (_: CancellationException) {
            // The child was stopped from the outside: the job that watches it
            // was cancelled. The run's own terminal is written by whoever
            // asked for the stop, and NOT here.
            end = SupervisedRunEnd.Cancelled
        } catch (_: Exception) {
            // The child's failure is the RUN's failure and not the service's.
            end = SupervisedRunEnd.Failed
        } finally {
            closeLease(lease)
            closeRunWithoutTerminal(supervision, end)
            supervision.over(supervised)
            liveJobs.remove(job)
            liveThreads.remove(Thread.currentThread())
            // The slot the budget gave to this run is given back HERE and
            // nowhere else: one run, one slot, one decrement. A counter that
            // is incremented twice and decremented once is a budget that
            // leaks, and a budget that leaks refuses every run after the
            // fourth.
            liveRuns.decrementAndGet()
            watcher.signalFinished()
        }
    }

    /**
     * How the child ended, as the port says it.
     *
     * The port answers with a result and not with an exception when the child
     * ended on its own, and the result says WHICH of the three it was: the
     * watchdog killed it, somebody cancelled it, or it exited.
     */
    private fun endOf(result: TaskExecutionResult): SupervisedRunEnd = when {
        result.timedOut -> SupervisedRunEnd.TimedOut
        result.cancelled -> SupervisedRunEnd.Cancelled
        else -> SupervisedRunEnd.Ended(result.exitCode)
    }

    /**
     * Closes the run whose child ended WITHOUT a terminal.
     *
     * ## Why the run is closed HERE and not by the child
     *
     * Because the child that could not write its terminal is DEAD: the
     * watchdog kills it with a signal that gives the JVM no chance to write
     * anything, and a run whose writer is gone can never write its own
     * bookend. Such a run stays open for ever, and a client that waits for
     * its terminal waits for ever.
     *
     * ## Why the run that somebody asked to close is NOT closed here
     *
     * Because the cancel lane writes the terminal of the run it cancels,
     * through the control port and under the lease of the run. A supervisor
     * that wrote a second terminal for the same run would give the run two
     * truths about its own end, and the reader would take the last one it
     * sees. The flag is raised BEFORE the child is stopped, so the thread
     * that watches the child always sees it before it can close the run.
     *
     * ## Why a failure to close is not the service's failure
     *
     * Because the run is the one that is broken, not the service: a store
     * that refuses the write leaves the run open, and the operator sees that
     * in the store itself. The service keeps serving the other runs.
     */
    private fun closeRunWithoutTerminal(supervision: Supervision, end: SupervisedRunEnd?) {
        val runId = supervision.runId() ?: return
        if (end == null || supervision.isClosedByRequest()) return
        try {
            closer.closeWithoutTerminal(runId, end)
        } catch (_: Exception) {
            // The run stays open, and the store says so. The service keeps
            // serving: a run that cannot be closed is not a reason to stop.
        }
    }

    /**
     * The broker of a run that is about to be launched.
     *
     * A service without brokers opens none and the run goes on without one. A broker that
     * cannot be opened stops the run BEFORE its child exists: the slot the budget gave to the
     * run goes back and the key of the claim is released, so the client may ask again once the
     * operator has fixed the cause. The words of the refusal are the fixed ones of the failure.
     */
    private fun openBroker(key: String?, binding: BrokerBinding, correlationId: String): BrokerOpening =
        try {
            BrokerOpening.Opened(openLease(binding, correlationId))
        } catch (failure: RunBrokerLeaseException) {
            liveRuns.decrementAndGet()
            val refusal = ServiceRefusal.RunStartRefused(
                "the-credential-broker-could-not-start",
                failure.message ?: "the credential broker of the run could not be started",
            )
            if (key != null) registry.fail(key, refusal)
            BrokerOpening.Refused(refusal)
        }

    /**
     * The lease of a run: with the service's own provider, with the connected provider the
     * request named, or none.
     *
     * A request that names a controller gets a provider of its own, bound to the identity that
     * started the run and to the run itself, which the lease closes when the run is over. The
     * checks before the claim made sure the pieces exist; a missing piece here is a broker that
     * cannot be opened, and never a run that goes on with another source.
     */
    private fun openLease(binding: BrokerBinding, correlationId: String): RunBrokerLease? {
        val factory = collaborators.brokers
        val name = binding.credentialProvider ?: return factory?.open(binding.clientDigest)
        val owner = binding.clientDigest?.takeIf { it.isNotBlank() }
        val sessions = collaborators.credentialSessions
        if (owner == null || sessions == null || factory == null) {
            throw RunBrokerLeaseException("the connected credential provider of the run is not available")
        }
        return factory.openConnected(owner) { grants ->
            ConnectedCredentialProvider(sessions, owner, name, correlationId, grants)
        }
    }

    /** Who asked for the run, as far as its broker is concerned. */
    private class BrokerBinding(val clientDigest: String?, val credentialProvider: String?)

    /** What opening the broker of a run came to. */
    private sealed interface BrokerOpening {

        /** The broker is open, or the service has none ([lease] is null). */
        class Opened(val lease: RunBrokerLease?) : BrokerOpening

        /** The broker could not be opened and the run does not start. */
        class Refused(val refusal: ServiceRefusal.RunStartRefused) : BrokerOpening
    }

    /** The request to the port for the child of [script], supervised under [supervisionId]. */
    private fun taskRequestOf(supervisionId: String, argv: List<String>, script: Path): TaskExecutionRequest =
        TaskExecutionRequest(
            task = TaskSpec.ExecTask(argv),
            runId = RunId(supervisionId),
            opId = supervisionId,
            attempt = 0,
            timeoutMs = runPolicy.deadlineMillis,
            env = emptyMap(),
            workspaceRoot = script.parent?.toString(),
        )

    /**
     * Closes the broker of a run, if it had one.
     *
     * A broker that cannot be closed is not the run's failure: what it left on
     * disk is swept when the service starts again, and the token it served is
     * useless once the child is over.
     */
    private fun closeLease(lease: RunBrokerLease?) {
        try {
            lease?.close()
        } catch (_: Exception) {
            // Swept at the next start; failing here would skip the facts that must stay true.
        }
    }

    /**
     * The snapshots the CHILD reads, written before the child is launched.
     *
     * They are two files and not one, because they are two different things: the
     * configuration is what the steps of the run will SEE, and the parameters are what the
     * pipeline DECLARES it accepts. The child checks the parameters against the declaration
     * of the script, and the declaration is a product of the child's own compilation.
     *
     * The configuration is recorded for EVERY run and the parameters only for a run that
     * was given some: the configuration is what the door answers about, and a record that
     * exists only when there is something to record cannot say that there was nothing.
     * The argv of a plain run is the argv it always was either way: the option that names
     * a snapshot is only there when the snapshot carries values.
     *
     * A snapshot that cannot be written stops the run, because a run whose values do not
     * exist is a run that cannot be promised: the child that does not receive them cannot
     * check them against the declaration. The slot the budget gave to it goes back and the
     * key is released, so the client is free to repeat the request with the same key.
     *
     * ## Which refusal the client sees
     *
     * The one that names what the client ASKED for: the configuration it gave comes first,
     * the parameters it gave come second, and the record of a run that was given no
     * configuration is written last. A client that sent only parameters is told that its
     * parameters could not be written, not that a configuration it never sent could not.
     */
    private fun snapshotsOf(
        supervisionDir: Path,
        key: String?,
        effective: EffectiveConfiguration,
        delivered: Map<String, String>,
    ): SnapshotsOutcome {
        val given = if (effective.configuration.isEmpty) null else writeSnapshot(supervisionDir, key, effective)
        if (given is ConfigurationSnapshotOutcome.Refused) return SnapshotsOutcome.Refused(given.refusal)
        val parametersSnapshot = when (val outcome = writeInputsSnapshot(supervisionDir, key, delivered)) {
            is SnapshotOutcome.None -> null
            is SnapshotOutcome.Written -> outcome.path
            is SnapshotOutcome.Refused -> return SnapshotsOutcome.Refused(outcome.refusal)
        }
        val configurationSnapshot = when (val outcome = given ?: writeSnapshot(supervisionDir, key, effective)) {
            is ConfigurationSnapshotOutcome.Written -> outcome.path
            is ConfigurationSnapshotOutcome.Refused -> return SnapshotsOutcome.Refused(outcome.refusal)
        }
        return SnapshotsOutcome.Written(configurationSnapshot, parametersSnapshot)
    }

    /**
     * The snapshot of the configuration, written BEFORE the child is launched.
     *
     * ## Why EVERY run gets a snapshot
     *
     * Because the snapshot is the RECORD of what the run was given, and a
     * record that exists only when there is something to record cannot say
     * that there was nothing: the door that answers where the configuration
     * of a run came from would have to tell "the run was given nothing" from
     * "the record is gone", and the first is a fact about the run while the
     * second is an accident. A snapshot that says "no values" is the record
     * of a run that was given nothing.
     *
     * ## Why the child is not given the snapshot of a plain run
     *
     * Because the argv of a plain run is the argv it always was, and the
     * behaviour of a run that was given nothing is the behaviour it always
     * had. The snapshot is written either way; the option that names it is
     * only there when the snapshot carries values.
     *
     * ## Why a snapshot that cannot be written stops the run
     *
     * Because a run whose configuration does not exist is a run that cannot
     * be promised. The slot the budget gave to it goes back and the key is
     * released, so the client is free to repeat the request with the same
     * key.
     */
    private fun writeSnapshot(
        supervisionDir: Path,
        key: String?,
        effective: EffectiveConfiguration,
    ): ConfigurationSnapshotOutcome {
        val path = supervisionDir.resolve(RUN_CONFIG_NAME)
        return try {
            writeRunConfiguration(path, effective)
            ConfigurationSnapshotOutcome.Written(path)
        } catch (failure: Exception) {
            // The slot the budget gave to this run is given back: the run is
            // NOT going to happen, and a budget that keeps the slot of a run
            // that never started is a budget that leaks.
            liveRuns.decrementAndGet()
            val refusal = ServiceRefusal.RunConfigurationRefused(
                "the-configuration-cannot-be-written",
                "the snapshot of the configuration could not be written, and a run whose " +
                    "configuration does not exist is a run that cannot be promised: " +
                    (failure.message ?: failure.javaClass.simpleName),
            )
            if (key != null) registry.fail(key, refusal)
            ConfigurationSnapshotOutcome.Refused(refusal)
        }
    }

    /**
     * Writes the snapshot of the PARAMETERS at [path].
     *
     * ## Why it is a FILE and not the argv
     *
     * Because the argv of a process is readable by any user of the machine through
     * `/proc/<pid>/cmdline`, and the argv carries the PATH of the snapshot, which is a path
     * inside the service's own directory. The values themselves stay in the file.
     *
     * ## Why it is written the way the registry writes its own
     *
     * Because a file that is created and only THEN chmod'ed has a window in which it exists
     * with the mode of the umask and with the content inside. The recipe is the one the
     * product already uses: a temporary file created with the mode it will keep, the content
     * written into it, and an ATOMIC move into place.
     *
     * ## Why a snapshot that cannot be written stops the run
     *
     * Because the child checks the values against the declaration of the SCRIPT, and a
     * child that does not receive them cannot check them: a run that would run with
     * parameters nobody delivered is not a run that can be promised.
     */
    private fun writeInputsSnapshot(
        supervisionDir: Path,
        key: String?,
        delivered: Map<String, String>,
    ): SnapshotOutcome {
        if (delivered.isEmpty()) return SnapshotOutcome.None
        val path = supervisionDir.resolve(RUN_INPUTS_NAME)
        return try {
            writeRunInputs(path, delivered)
            SnapshotOutcome.Written(path)
        } catch (failure: Exception) {
            liveRuns.decrementAndGet()
            val refusal = ServiceRefusal.ParametersRefused(
                "the-parameters-cannot-be-written",
                "the snapshot of the parameters could not be written, and a run that does not " +
                    "receive the parameters it was asked for is not a run that can be " +
                    "promised: " + (failure.message ?: failure.javaClass.simpleName),
            )
            if (key != null) registry.fail(key, refusal)
            SnapshotOutcome.Refused(refusal)
        }
    }

    /**
     * Writes the snapshot of [parameters] at [path].
     *
     * ## What the file holds
     *
     * The schema of the document and the values that were DELIVERED. The defaults are not
     * written: the child knows the declaration of the script, and it applies the defaults
     * of THAT declaration when it checks the values.
     */
    private fun writeRunInputs(path: Path, delivered: Map<String, String>) {
        val directory = checkNotNull(path.parent) { "the snapshot has no directory" }
        val document = runInputsDocument(delivered)
        val temporary = Files.createTempFile(directory, RUN_INPUTS_NAME, ".tmp")
        try {
            Files.setPosixFilePermissions(
                temporary,
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ),
            )
            Files.writeString(temporary, document.toString())
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * Writes the snapshot of the [effective] configuration at [path].
     *
     * ## Why the file is written the way the registry writes its own
     *
     * Because a file that is created and only THEN chmod'ed has a window in
     * which it exists with the mode of the umask and with the content
     * inside, and another user of the machine can read it in that window.
     * The recipe is the one the product already uses: a temporary file
     * created with the mode it will keep, the content written into it, and
     * an ATOMIC move into place. The file is therefore never visible under
     * any other mode.
     *
     * ## What the file holds
     *
     * The schema of the document and the values, and —only when a provider took part— the
     * provenance of every key. It is a document the CHILD reads, and the child re-validates
     * it: the disk is not an authority, and a file that the service wrote is still a file on
     * the disk.
     */
    private fun writeRunConfiguration(path: Path, effective: EffectiveConfiguration) {
        val directory = checkNotNull(path.parent) { "the snapshot has no directory" }
        val document = runConfigurationDocument(effective.configuration.values, effective.provenance)
        val temporary = Files.createTempFile(directory, RUN_CONFIG_NAME, ".tmp")
        try {
            Files.setPosixFilePermissions(
                temporary,
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ),
            )
            Files.writeString(temporary, document.toString())
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * The answer of a request whose run is already known.
     *
     * The [configuration] is the one of the REQUEST that is being answered
     * and not one the service remembered: the fingerprint of the request
     * covers the configuration, so a request that reaches this point with
     * another configuration was refused as a conflict before it got here.
     * That is what lets the answer echo the configuration without the
     * service keeping the client's values in a record of its own.
     */
    private fun acceptedOf(
        record: ServiceStartRecord,
        configuration: RunConfiguration,
        parameters: Map<String, String>,
    ): ServiceStartOutcome.Accepted =
        ServiceStartOutcome.Accepted(
            record.runId,
            record.correlationId,
            record.ownerId,
            record.startedAtMs,
            configuration,
            parameters,
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
    private fun awaitOf(
        future: CompletableFuture<ServiceStartRecord>,
        configuration: RunConfiguration,
        parameters: Map<String, String>,
    ): ServiceStartOutcome {
        return try {
            acceptedOf(
                future.get(limits.startDeadlineMillis, TimeUnit.MILLISECONDS),
                configuration,
                parameters,
            )
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
        // The run is being closed by the caller of this door, and that caller
        // writes the terminal of the run: the flag is raised BEFORE the child
        // is stopped, so the thread that watches the child does not write a
        // second terminal for a run that is being closed elsewhere.
        supervision.markClosedByRequest()
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
         * The terminal durable is written after the child dies, and the port
         * writes it only when the lease is free. Ten seconds is long enough
         * for a process that is being destroyed for it to die, and short
         * enough that a caller is not left waiting for a child that does not
         * want to die.
         */
        const val CANCEL_WAIT_MILLIS: Long = 10_000L

        /** The name of the file the child's error output goes to. */
        const val CHILD_LOG_NAME: String = "child.err.log"

        /**
         * The name of the snapshot of the configuration, inside the
         * supervision's own directory.
         *
         * It is a NAME and not a path, and the directory is the service's
         * own: the client never names a file, it names a script and a
         * workspace by their names inside the directories the operator
         * admitted, and the configuration does not open a third door.
         */
        const val RUN_CONFIG_NAME: String = "run-config.json"

        /**
         * The name of the snapshot of the parameters.
         *
         * It is a name of its own and not a second field of the configuration's snapshot: the
         * configuration is the environment of the steps and the parameters are the inputs the
         * pipeline declares, and two documents that carry one thing each are two documents
         * that can be read apart.
         */
        const val RUN_INPUTS_NAME: String = "run-inputs.json"

        /**
         * The fingerprint of the request that is made.
         *
         * The two names the client controls and the configuration it
         * delivered, and nothing else: the policy, the deadline and the
         * argv are the service's, and a request that repeats with the same
         * names AND the same configuration IS the same request.
         *
         * The length of EACH name travels WITH it, because two different
         * pairs of names can produce the same text when they are simply
         * stuck together: `ab` with `c` is not `a` with `bc`. A name that
         * is absent is not the same as a name that is empty, and the
         * fingerprint says so with a length of `-1` for the absent one.
         *
         * The configuration travels in its CANONICAL form, which is
         * length-prefixed as well: a plain join would make
         * `{"A":"1","B":"2"}` and `{"A":"1\nB=2"}` the same request, and
         * the second request would then be answered with the run of the
         * first, running with the first's configuration.
         *
         * A request WITHOUT a configuration keeps the fingerprint it had
         * before the configuration existed: the suffix is only appended
         * when there is something to append, so the records of the starts
         * that were written before are still comparable with the ones that
         * are written now.
         */
        fun digestOf(
            script: String,
            workspace: String?,
            configuration: RunConfiguration = RunConfiguration.EMPTY,
            parameterContract: List<dev.rubentxu.pipeline.v2.domain.ParameterSpec> = emptyList(),
            parameters: Map<String, String> = emptyMap(),
            provider: ServiceProviderReference? = null,
            credentialProvider: String? = null,
        ): String {
            val base = workspace?.let { workspaceText ->
                script.length.toString() + ":" + script + "|" +
                    workspaceText.length.toString() + ":" + workspaceText
            } ?: (script.length.toString() + ":" + script + "|-1:")
            val canonical = RunConfiguration.canonicalText(configuration.values)
            val withConfiguration = if (canonical.isEmpty()) {
                base
            } else {
                base + "#" + canonical.length.toString() + ":" + canonical
            }
            // The parameters travel in a carrier of their OWN, and the marker is
            // one the configuration's canonical text can never begin with: a
            // request that carries a configuration and a request that carries
            // parameters with the same texts are DIFFERENT requests, and a
            // fingerprint that could not tell them apart would answer the
            // second one with the run of the first.
            val parametersText = RunInputs.canonicalText(parameterContract, parameters)
            val withParameters = if (parametersText.isEmpty()) {
                withConfiguration
            } else {
                withConfiguration + "@p:" + parametersText.length.toString() + ":" + parametersText
            }
            // The provider reference travels in a carrier of ITS OWN, appended only when the
            // request names one, so that the fingerprint of every request that names none is
            // the fingerprint it had. The keys are sorted and distinct: the order a request
            // lists them in does not make it another request.
            val providerText = provider?.let(::providerCanonicalText).orEmpty()
            val withProvider = if (providerText.isEmpty()) {
                withParameters
            } else {
                withParameters + "@c:" + providerText.length.toString() + ":" + providerText
            }
            // The credential provider travels in a carrier of its own as well, appended only when
            // the request names one: a start that asks the same run with another controller is
            // another request, and the fingerprint of every start that names none is unchanged.
            val withCredentials = credentialProvider?.let {
                withProvider + "@k:" + it.length.toString() + ":" + it
            } ?: withProvider
            return ServiceAuthorizations.digestOf(withCredentials)
        }

        /** The canonical text of [provider]: the name and the keys, length-prefixed. */
        private fun providerCanonicalText(provider: ServiceProviderReference): String =
            (listOf(provider.provider) + provider.keys.distinct().sorted())
                .joinToString("|") { it.length.toString() + ":" + it }

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
/**
 * What the snapshot of the configuration turned out to be.
 *
 * A run with no configuration has no snapshot, a run with one has the file
 * the child reads, and a run whose snapshot could not be written has a
 * refusal and no run at all.
 */
/**
 * The two files the child reads before it runs.
 *
 * They are written together because they are written at the same moment and for the same
 * reason: the child cannot check the values of the parameters, nor compose the environment,
 * without a file to read.
 */
private sealed interface SnapshotsOutcome {

    /**
     * The files are written.
     *
     * Each path is null when the run was given nothing of that kind: a run without a
     * configuration has no configuration file, and a run without parameters has none either.
     */
    data class Written(
        /** The file with the configuration, or null when the run was given none. */
        val configuration: Path?,
        /** The file with the parameters, or null when the run was given none. */
        val parameters: Path?,
    ) : SnapshotsOutcome

    /** One of the files could not be written, and the refusal says why. */
    data class Refused(val refusal: ServiceRefusal) : SnapshotsOutcome
}

/** What a start asks of the provider, decided before anything is claimed. */
private sealed interface ProviderPlan {

    /** The provider is not asked anything: the request named none, or set every key itself. */
    data object Nothing : ProviderPlan

    /** The provider is asked for [keys], the ones the request did not set itself. */
    data class Ask(val keys: List<String>) : ProviderPlan

    /** The reference breaks a rule; the start is refused before the key is claimed. */
    data class Refused(val refusal: ServiceRefusal) : ProviderPlan
}

/** The configuration a run is given and, when a provider took part, where it came from. */
private class EffectiveConfiguration(val configuration: RunConfiguration, val provenance: RunConfigurationProvenance?)

private sealed interface EffectiveOutcome {
    data class Resolved(val effective: EffectiveConfiguration) : EffectiveOutcome
    data class Refused(val refusal: ServiceRefusal) : EffectiveOutcome
}

private sealed interface SnapshotOutcome {

    /** The document was not written, because the run was given nothing to write. */
    data object None : SnapshotOutcome

    /** The snapshot is at [path]. */
    data class Written(val path: Path) : SnapshotOutcome

    /** The snapshot could not be written, and the refusal says why. */
    data class Refused(val refusal: ServiceRefusal) : SnapshotOutcome
}

/**
 * The outcome of writing the snapshot of the CONFIGURATION of a run.
 *
 * It has no "nothing to write" case: the configuration of a run is recorded
 * for every run, and a run that was given nothing is recorded as a run that
 * was given nothing.
 */
private sealed interface ConfigurationSnapshotOutcome {

    /** The snapshot is at [path]. */
    data class Written(val path: Path) : ConfigurationSnapshotOutcome

    /** The snapshot could not be written, and the refusal says why. */
    data class Refused(val refusal: ServiceRefusal) : ConfigurationSnapshotOutcome
}

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

    /** Whether somebody asked for this run to be closed. */
    private var closedByRequest = false

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

    /**
     * The id the child minted, when it has minted one.
     *
     * It is the id the CLIENT knows, and not the id of the supervision: the
     * run's own facts are under the id the child minted.
     */
    fun runId(): String? = synchronized(lock) { runId }

    /** Whether somebody asked for this run to be closed. */
    fun isClosedByRequest(): Boolean = synchronized(lock) { closedByRequest }

    /**
     * Says that somebody asked for this run to be closed.
     *
     * The flag is raised BEFORE the child is stopped, because the thread that
     * watches the child reads it AFTER the child is over: a flag raised
     * afterwards would arrive too late to keep the supervisor from writing a
     * second terminal for a run the cancel lane is closing.
     */
    fun markClosedByRequest() {
        synchronized(lock) { closedByRequest = true }
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
