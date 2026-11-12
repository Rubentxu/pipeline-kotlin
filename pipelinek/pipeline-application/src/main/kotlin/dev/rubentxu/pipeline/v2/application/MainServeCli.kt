package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.EventStoreRefusal
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.InputQuestionLayout
import dev.rubentxu.pipeline.v2.runtime.NoopPipelineTelemetry
import dev.rubentxu.pipeline.v2.runtime.PipelineTelemetry
import dev.rubentxu.pipeline.v2.runtime.PipelineTelemetryFactory
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The usage the `serve` verb prints when its request is refused.
 */
internal const val SERVE_USAGE: String =
    "Usage: pipelinek serve --db <path> --control-root <path> --socket <path> " +
        "[--trust-profile <trusted-local|private-auth>] [--authorization-file <path>] " +
        "[--scripts <dir>] [--workspaces <dir>] " +
        "[--run-deadline-ms <n>] [--start-deadline-ms <n>] " +
        "[--run-allow-network] [--max-supervised-runs <n>] [--credential-sessions]"

/**
 * The requests `serve` accepts.
 *
 * All three options carry a value and all three are REQUIRED: the service
 * reads the substrate the operator names and it listens where the operator
 * says, and a default for any of them would be a service serving something
 * the operator did not choose.
 *
 * `--db` and `--control-root` are tokens the shared parser also knows, so the
 * shared parser's arity for them is the one this table has to agree with.
 * `--socket` belongs to this verb alone: no other form of this CLI names a
 * socket.
 *
 * `--trust-profile` and `--authorization-file` also belong to this verb alone,
 * and their names say what they are: the profile names WHERE the
 * authorizations are read from, and the file names the file they are read
 * from when the profile reads a file. They are OPTIONAL, and the defaults are
 * the ones the profile says: `trusted-local`, and the file the service owns
 * next to its socket. The profile is a closed vocabulary of TWO words —
 * `trusted-local` and `private-auth` — and the usage line names them, so an
 * operator who asks for a third one is told which ones this build serves
 * instead of guessing which one he misspelled.
 *
 * The last five are the DECLARATION of what the service may start: the
 * directory of the scripts it may run, the directory of the workspaces it
 * may name, the deadline of a supervised run, whether a supervised run may
 * reach the network, and how many of them the service watches at once.
 * Without `--scripts` the service starts and cannot start runs: a service
 * that could run whatever it is asked to run would not be a service with a
 * boundary, it would be a command line with a socket in front of it.
 *
 * `--start-deadline-ms` is the SIXTH of that declaration, and it is the
 * deadline of the START and not of the run: it says how long the service
 * waits for a child to take its lease. It is separate because the two
 * deadlines govern two different things and only one of them is about the
 * life of the run.
 *
 * `--credential-sessions` is the opt-in of the CREDENTIAL sessions, and it
 * is the only mode in which a run may ask a connected controller for a
 * credential. Without it the service answers `credential.provider.*` with a
 * refusal and refuses any start that names a credential provider: a
 * capability nobody asked for is not opened by accident.
 *
 * ## Why the opt-in CLOSES the local store
 *
 * Because the two sources of credentials are mutually exclusive by
 * construction, and the one that loses is the one the operator did not name.
 * With the opt-in the service does NOT open the local store at all: the
 * broker of every run is opened with no provider behind it, so a run whose
 * controller denies, vanishes or is late is denied, and the credential is
 * never looked up in a store the operator may not have meant to open. The
 * alternative considered and rejected: opening both and preferring the
 * controller — it would make the failure of a controller SILENTLY fall back
 * to a secret on disk, which is the exact thing this capability promises
 * not to do.
 */
internal val SERVE_REQUEST: CliRequest = CliRequest(
    options = listOf(
        CliOption("--db", takesValue = true),
        CliOption("--control-root", takesValue = true),
        CliOption("--socket", takesValue = true),
        CliOption("--trust-profile", takesValue = true),
        CliOption("--authorization-file", takesValue = true),
        CliOption("--scripts", takesValue = true),
        CliOption("--workspaces", takesValue = true),
        CliOption("--run-deadline-ms", takesValue = true),
        CliOption("--start-deadline-ms", takesValue = true),
        CliOption("--run-allow-network", takesValue = false),
        CliOption("--max-supervised-runs", takesValue = true),
        CliOption("--credential-sessions", takesValue = false),
    ),
)

/**
 * How long a supervised run may live, when the operator does not say.
 *
 * One hour: long enough for a build, short enough that a run that hangs
 * does not hold a slot of the budget for ever.
 */
internal const val DEFAULT_RUN_DEADLINE_MILLIS: Long = 3_600_000L

/**
 * How many runs the service watches at once, when the operator does not say.
 */
internal const val DEFAULT_MAX_SUPERVISED_RUNS: Int = 4

/**
 * How long the service waits for a child to take its lease, when the
 * operator does not say.
 *
 * Thirty seconds: long enough for a JVM to start and a script to compile,
 * short enough that a child that never owns itself does not hold a slot of
 * the budget for long. It is a CONSTANT of the product and not of the
 * operator's machine — a machine that is slower than this needs
 * `--start-deadline-ms`, because a child that is killed for being slow is a
 * child the service refused to start.
 */
internal const val DEFAULT_START_DEADLINE_MILLIS: Long = 30_000L

/**
 * The directory, next to the socket, where the supervised runs are watched.
 *
 * It is the operator's: the port writes each child's result there, and the
 * child's error output goes next to it. The service does not read those
 * files for its answers — it reads the run's own facts from the store.
 */
internal const val SUPERVISION_DIRECTORY: String = "supervisions"

/**
 * The directory, next to the socket of the service, below which every supervised run gets the
 * directory of its credential broker.
 */
internal const val BROKERS_DIRECTORY: String = "brokers"

/**
 * The class the child process is launched with.
 *
 * The same class the operator launches: a run started over the socket is a
 * run of the same product, run the same way, and not a second entry point
 * that could drift from it.
 */
internal const val SERVICE_MAIN_CLASS: String = "dev.rubentxu.pipeline.v2.application.MainKt"

/**
 * `serve` — the local service over a Unix domain socket.
 *
 * ## What this verb does
 *
 * It composes the substrate the operator named, binds the socket it named,
 * and answers requests until the operator stops the process. It is the ONE
 * verb of this CLI that occupies the process: it does not answer and end, it
 * answers and keep listening.
 *
 * ## Why the substrate is REQUIRED and never created
 *
 * `--db` and `--control-root` have no default, the same way `inspect --state`
 * has none: the service reads EXACTLY the substrate the operator names, so
 * what the socket says about a run is comparable with what the command line
 * says about the same run over the same inputs. And the verb does not create
 * the control root: a read-only service that creates the tree it is about to
 * read is a service that writes, and the tree it writes is not the tree it
 * was asked to read.
 *
 * ## Why the process ends when the operator stops it
 *
 * There is no daemon here: `serve` is a process the operator starts on
 * purpose, and it ends when the operator ends it. The socket it created is
 * deleted on the way out, and a socket that survives a crash is found by the
 * next start and judged for what it is.
 */
internal fun runServeVerb(args: Array<String>, out: PrintStream, err: PrintStream): CliExit {
    val admission = CliOptionAdmission.admit(args, SERVE_REQUEST)
    val accepted = when (admission) {
        is CliAdmission.Rejected -> {
            err.println(admission.error.refusalLines("serve").joinToString("\n"))
            err.println(SERVE_USAGE)
            return admission.error.toCliExit()
        }
        is CliAdmission.Admitted -> admission
    }

    val dbPath = accepted.values["--db"]
        ?: return refuseMissing(err, "--db <path>", "to read the durable event store")
    val controlRoot = accepted.values["--control-root"]
        ?: return refuseMissing(err, "--control-root <path>", "to read the Output Plane")
    val socketPath = accepted.values["--socket"]
        ?: return refuseMissing(err, "--socket <path>", "to name the socket the service listens on")

    val socketAbsolute = Path.of(socketPath).toAbsolutePath().normalize()
    val dbAbsolute = Path.of(dbPath).toAbsolutePath().normalize()
    val controlAbsolute = Path.of(controlRoot).toAbsolutePath().normalize()

    val boundary = when (
        val reading = readBoundary(accepted, socketAbsolute, dbAbsolute, controlAbsolute, err)
    ) {
        is BoundaryReading.Read -> reading.boundary
        is BoundaryReading.Refused -> return reading.exit
    }
    val profile = boundary.profile
    val authorizationPath = boundary.authorizationPath
    val authorizations = boundary.authorizations

    // The DECLARATION of what this service may start, and the record of the
    // starts that backs the promise a key makes. Both are read before the
    // substrate, because a policy that is not a policy has to be refused
    // before anything is opened.
    val policy = when (val reading = readRunPolicy(accepted, socketAbsolute, err)) {
        is RunPolicyReading.Read -> reading.declaration
        is RunPolicyReading.Refused -> return reading.exit
    }

    val substrate = ServeSubstrate(
        dbPath = dbPath,
        dbAbsolute = dbAbsolute,
        controlRoot = controlRoot,
        controlAbsolute = controlAbsolute,
        controlRootPath = Paths.get(controlRoot),
        socketAbsolute = socketAbsolute,
        socketPath = socketPath,
    )
    // The opt-in of the credential sessions. It is read HERE, with the rest
    // of the operator's declaration, and not inside the composition: the
    // composition is told what the service may do, and the verb is the one
    // that reads what the operator asked for.
    val credentialSessions = accepted.flags.contains("--credential-sessions")
    val composed = composeServiceToServe(
        substrate,
        policy,
        authorizations,
        SystemClock(),
        err,
        openings = ServeOpenings(credentialSessions = credentialSessions),
    )
    val service = when (composed) {
        is ServeComposition.Composed -> composed
        is ServeComposition.Refused -> return composed.exit
    }
    return serveUntilStopped(service, substrate, profile, authorizationPath, policy, out, credentialSessions)
}

/**
 * The substrate the service was told to use.
 *
 * The paths come in PAIRS because the service uses two of each: the name the
 * operator gave, which is what it prints and what it hands to the child, and
 * the absolute one, which is what it composes the stores with.
 */
internal data class ServeSubstrate(
    val dbPath: String,
    val dbAbsolute: Path,
    val controlRoot: String,
    val controlAbsolute: Path,
    val controlRootPath: Path,
    val socketAbsolute: Path,
    val socketPath: String,
) {

    /**
     * The directory the supervisions live in, next to the socket.
     *
     * It is derived HERE, once, because the supervisor writes the facts of a
     * run into it and the door that answers where the configuration of a run
     * came from has to look in the very same directory: two derivations would
     * be two names for one place.
     */
    val supervisionRoot: Path
        get() = requireNotNull(socketAbsolute.parent).resolve(SUPERVISION_DIRECTORY)
}

/**
 * What the composition of the service produced before it could serve.
 */
internal sealed interface ServeComposition {

    /**
     * The service is composed and it can serve.
     *
     * It owns everything it opened: [close] gives it back, in the order that lets the last
     * reader finish before what it reads from is closed.
     */
    data class Composed(
        val server: ServiceServer,
        val supervisor: ServiceRunSupervisor,
        val store: dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore,
        val storePurpose: EventStorePurpose,
        val authorizationCount: Int,
        val credentials: CredentialProvider?,
        val telemetry: PipelineTelemetry = NoopPipelineTelemetry,
    ) : ServeComposition {

        private val telemetryClosed = AtomicBoolean(false)

        /**
         * Stops the server, then the children it supervises, then the store, then the credential
         * provider, and the telemetry LAST: the requests that were answered while the service was
         * stopping are the last thing it has to export. Idempotent in every piece it closes.
         *
         * The telemetry is closed ONCE even when the shutdown hook and the end of the verb both
         * close the service: closing an exporter twice reports a loss that did not happen.
         */
        fun close() {
            server.close()
            supervisor.close()
            store.close()
            closeServiceCredentials(credentials)
            if (telemetryClosed.compareAndSet(false, true)) runCatching { telemetry.close() }
        }
    }

    /** The service cannot be composed, and this says why. */
    data class Refused(val exit: CliExit) : ServeComposition
}

/**
 * How the service opens the world around it.
 *
 * ## Why these travel together
 *
 * Because they are all the answer to ONE question —"what does this service open, and with what
 * may it be replaced in a test?"— and a composition that took them one by one grew a parameter
 * list nobody could read. One value says it once.
 *
 * @property environment the environment the store and the telemetry are opened from.
 * @property brokerOpening how the local credential store is opened.
 * @property telemetryOpening how the telemetry is opened.
 * @property credentialSessions whether the service serves the sessions of the credential
 *   providers, and therefore whether it opens the local store at all.
 */
internal data class ServeOpenings(
    val environment: Map<String, String> = System.getenv(),
    val brokerOpening: (Map<String, String>) -> ServiceCredentialAccess = ::openServiceCredentialProvider,
    val telemetryOpening: (Map<String, String>) -> PipelineTelemetry = { PipelineTelemetryFactory.create(it) },
    val credentialSessions: Boolean = false,
)

/**
 * Composes the substrate, the doors and the socket of the service.
 *
 * ## Why the substrate is composed BEFORE the socket exists
 *
 * Because a service that cannot read its substrate would only know how to
 * say that it is alive, and a client would read that as an answer about a
 * run.
 *
 * ## Why the LANE of the store is the lane the service needs
 *
 * Because a service that may cancel runs WRITES to it: the terminal of a
 * cancelled run is a fact and the service is the one that writes it. A
 * service that may not cancel anything opens the same file read-only, and
 * says so when it starts.
 *
 * ## Why every refusal CLOSES the store
 *
 * Because a refusal that leaves the store open leaves a writer thread
 * holding the operator's file, and the process that goes on serving would
 * hold a lock nobody asked it to hold.
 *
 * ## Why the telemetry is opened last, and from the operator's environment only
 *
 * Because it is the one piece nothing else depends on: a refusal before it
 * has nothing to flush, and a service that was not told to export opens
 * nothing. The opt-in is the one the engine already reads, and the service
 * adds no switch of its own.
 */
internal fun composeServiceToServe(
    substrate: ServeSubstrate,
    policy: ServiceRunDeclaration,
    authorizations: ServiceAuthorizations,
    clock: SystemClock,
    err: PrintStream,
    openings: ServeOpenings = ServeOpenings(),
): ServeComposition {
    val storePurpose = if (authorizations.mayCancelAnyRun) {
        EventStorePurpose.Write
    } else {
        EventStorePurpose.Read
    }
    // The store the operator named has to BE there, also in the lane that
    // writes: that lane CREATES the file it does not find, and a service that
    // creates the substrate it is about to read answers "the run is unknown"
    // about EVERY run, in a store that holds no run at all. A mistyped
    // `--db` has to say that it is not there, and not leave an empty file
    // where the operator kept their facts.
    if (storePurpose == EventStorePurpose.Write && !Files.exists(substrate.dbAbsolute)) {
        val error = CliError.SubstrateUnavailable(
            describeRefusal(EventStoreRefusal.Absent(substrate.dbPath)),
        )
        err.println(error.refusalLines("serve").joinToString("\n"))
        err.println(SERVE_USAGE)
        return ServeComposition.Refused(error.toCliExit())
    }
    val storeAdmission = admitEventStore(substrate.dbPath, storePurpose)
    val store = when (storeAdmission) {
        is EventStoreAdmission.Admitted -> storeAdmission.store
        is EventStoreAdmission.Refused ->
            return refuseServe(CliError.SubstrateUnavailable(describeRefusal(storeAdmission.refusal)), err)
    }

    // The brokers the runs ask their credentials from. They exist ONLY when some
    // authorization grants a credential, because opening the local store needs the
    // passphrase of the operator and a service that grants nothing has no use for it.
    // A service that cannot open the store it was told to hand credentials from does
    // not start: it would refuse every credential of every run instead.
    //
    // With the opt-in of the credential sessions the store is NOT opened: the
    // brokers are composed with no provider behind them, and the only source of
    // a credential is the controller that registered the session of the run.
    val brokers = when (
        val composed = composeServiceBrokers(
            authorizations = authorizations,
            brokersRoot = requireNotNull(substrate.socketAbsolute.parent).resolve(BROKERS_DIRECTORY),
            environment = openings.environment,
            opening = openings.brokerOpening,
            connectedOnly = openings.credentialSessions,
        )
    ) {
        is ServiceBrokers.Composed -> composed
        is ServiceBrokers.Refused -> {
            store.close()
            return refuseServe(CliError.SubstrateUnavailable(composed.detail), err)
        }
    }

    // The sessions of the configuration providers. They are in memory and they
    // belong to this process: the supervisor asks them for the values of a
    // start, and the operations are the door a controller registers and
    // answers through. Only a credential that may start runs can open a
    // session, so a service that grants no such right exposes none.
    val providers = ConfigurationProviderSessions()

    // The sessions of the CREDENTIAL providers, and only when the operator
    // asked for them: without the opt-in the registry does not exist, and every
    // operation about a credential session is refused by name instead of
    // answering from a registry nobody opened.
    val credentialRegistry = if (openings.credentialSessions) CredentialProviderSessions() else null

    // The supervisor of the runs this service starts. It is composed with
    // the SAME store and the SAME control root the questions are answered
    // from, so a run started by a client is a run the service can answer
    // about.
    val supervisor = composeSupervisor(
        policy = policy,
        dbAbsolute = substrate.dbAbsolute,
        controlAbsolute = substrate.controlAbsolute,
        supervisionRoot = substrate.supervisionRoot,
        clock = clock,
        brokers = brokers.factory,
        configurations = providers,
        credentialSessions = credentialRegistry,
    )

    // The cancellation of a run, and the control port that writes its
    // terminal. They are composed ONLY when some authorization carries the
    // right to cancel: the port writes to the substrate, and a service that
    // may not cancel anything does not open that door.
    val cancellations = if (authorizations.mayCancelAnyRun) {
        ServiceRunCancellation(
            ender = supervisor,
            control = composeRuntimeControlPort(
                eventStore = store,
                controlRoot = substrate.controlAbsolute,
                clock = clock,
                ownerLabel = "service-cancel",
            ),
        )
    } else {
        null
    }

    // The operations and the streets are composed over the SAME substrate and
    // the SAME authorities the questions are answered from, so a run cannot
    // be "unknown" to one door of the service and "known" to the other.
    val composition = when (
        val composed = composeService(
            store = store,
            substrate = substrate,
            clock = clock,
            supervisor = supervisor,
            cancellations = cancellations,
            providers = providers,
            inputs = if (authorizations.mayAnswerAnyInput) {
                ServiceInputsBinding(
                    root = substrate.controlAbsolute.resolve(InputQuestionLayout.INPUTS_DIRECTORY),
                    responderOf = authorizations::labelOfDigest,
                )
            } else {
                null
            },
            credentialSessions = credentialRegistry,
            parameterDescriptions = ApprovedManifestDescriber(ServiceStartAdmission(policy.scriptsRoot, null)),
        )
    ) {
        is ServiceComposition.Composed -> composed
        is ServiceComposition.Refused -> {
            store.close()
            closeServiceCredentials(brokers.provider)
            return refuseServe(CliError.SubstrateUnavailable(composed.detail), err)
        }
    }

    val transport = try {
        UnixSocketTransport.bind(Path.of(substrate.socketPath))
    } catch (e: ServiceStartupException) {
        store.close()
        closeServiceCredentials(brokers.provider)
        return refuseServe(CliError.ServiceUnavailable(e.refusal.reason, e.detail), err)
    }
    // The leftovers of an earlier process are swept ONLY once this one holds the socket: a second
    // service on the same directory fails to bind above, and it must not remove the brokers of the
    // runs of the service that is alive.
    brokers.factory?.sweep()
    // The telemetry is opened last, once nothing can refuse the composition any more: a refusal
    // before this line has nothing to flush. It is the operator's opt-in (`PIPELINEK_OTEL_ENABLED`)
    // and without it the observer is the one that observes nothing.
    val telemetry = openings.telemetryOpening(openings.environment)
    val observer = if (telemetry.enabled) TelemetryServiceObserver(telemetry) else NoServiceObserver
    val server = ServiceServer(
        transport,
        composition.operations,
        composition.streets,
        authorizations,
        observer = observer,
    )
    return ServeComposition.Composed(
        server,
        supervisor,
        store,
        storePurpose,
        authorizations.size,
        brokers.provider,
        telemetry,
    )
}

/** Prints why the service does not start and gives the exit that says so. */
private fun refuseServe(error: CliError, err: PrintStream): ServeComposition.Refused {
    err.println(error.refusalLines("serve").joinToString("\n"))
    err.println(SERVE_USAGE)
    return ServeComposition.Refused(error.toCliExit())
}

/**
 * Serves until the operator stops the process.
 *
 * ## Why the hook closes EVERYTHING the service opened
 *
 * The server, the children it supervises and the store it reads. A signal
 * does not run the `finally` below —the JVM leaves through the hook and the
 * process ends— so a hook that only closed the server would leave the
 * children of the supervised runs behind, and a run that outlives the
 * service that started it is a run nobody is watching.
 */
private fun serveUntilStopped(
    service: ServeComposition.Composed,
    substrate: ServeSubstrate,
    profile: ServiceTrustProfile,
    authorizationPath: Path,
    policy: ServiceRunDeclaration,
    out: PrintStream,
    credentialSessions: Boolean,
): CliExit {
    val server = service.server
    val shutdown = Thread({ service.close() }, "pipelinek-service-shutdown")
    Runtime.getRuntime().addShutdownHook(shutdown)
    try {
        out.println("pipelinek service listening on ${substrate.socketPath}")
        describeBoundary(out, profile, authorizationPath, service.authorizationCount, credentialSessions)
        describeRuns(out, policy)
        describeSubstrate(out, service.storePurpose)
        server.serve()
    } finally {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdown)
        } catch (_: IllegalStateException) {
            // The JVM is already shutting down, and the hook is running or
            // has run. Removing it is a courtesy, not a requirement.
        }
        service.close()
    }
    return CliExit.Success("the service stopped")
}

/**
 * What the boundary of the service is: the profile, the file of the
 * authorizations and the authorizations themselves.
 */
internal data class ServiceBoundary(
    val profile: ServiceTrustProfile,
    val authorizationPath: Path,
    val authorizations: ServiceAuthorizations,
)

/**
 * What the reading of the boundary produced.
 */
internal sealed interface BoundaryReading {

    /** The boundary was read. */
    data class Read(val boundary: ServiceBoundary) : BoundaryReading

    /** The boundary could not be read, and the service does not start. */
    data class Refused(val exit: CliExit) : BoundaryReading
}

/**
 * The boundary of the service, read from the request.
 *
 * ## What it decides
 *
 * The profile the operator asked for, the file the authorizations come from,
 * and the authorizations themselves. Every refusal it can produce is about
 * the BOUNDARY and not about the substrate, and it is produced before a
 * single file of the substrate is opened.
 *
 * ## Why the socket may not BE a file the service reads
 *
 * Because a socket that IS the store would make the store not a store, and a
 * socket that IS the file of the authorizations would put the secret and the
 * door in the same inode. What is NOT refused is the socket living NEXT TO
 * the material: the natural layout of an operator —the store, the control
 * root and the socket in one directory— is the one this rule lets through.
 */
private fun readBoundary(
    accepted: CliAdmission.Admitted,
    socketAbsolute: Path,
    dbAbsolute: Path,
    controlAbsolute: Path,
    err: PrintStream,
): BoundaryReading {
    // The profile is a closed vocabulary: the word says what the operator
    // ASKS for, and an unknown word is refused instead of being taken for
    // the default. A profile the operator misspelled is a boundary nobody
    // chose, and a boundary nobody chose is not a boundary.
    val profile = accepted.values["--trust-profile"]?.let { word ->
        ServiceTrustProfile.parse(word)
            ?: return BoundaryReading.Refused(
                refuseStartup(
                    err,
                    ServiceStartupRefusal.TrustProfileUnknown,
                    "the trust profile is not one this build serves: '$word'",
                ),
            )
    } ?: ServiceTrustProfile.TrustedLocal

    if (socketAbsolute == dbAbsolute || socketAbsolute == controlAbsolute) {
        return BoundaryReading.Refused(
            refuseStartup(
                err,
                ServiceStartupRefusal.SocketPathIsReserved,
                "the socket would be a file the service reads: $socketAbsolute",
            ),
        )
    }

    // The authorizations come from the place the profile names: the file the
    // operator named, the file the service owns next to its socket, or the
    // standard input. The two profiles do not mix: a profile that keeps the
    // authorization off the disk cannot read it from the disk.
    val namedFile = accepted.values["--authorization-file"]
    if (namedFile != null && profile == ServiceTrustProfile.PrivateAuth) {
        return BoundaryReading.Refused(
            refuseStartup(
                err,
                ServiceStartupRefusal.AuthorizationFileRefusedByTheProfile,
                "the private-auth profile reads the authorizations from the standard input, " +
                    "and --authorization-file names a file",
            ),
        )
    }
    val authorizationPath = namedFile?.let { Path.of(it) }
        ?: socketAbsolute.parent?.resolve(ServiceAuthorizationStore.OWNED_FILE_NAME)
        ?: return BoundaryReading.Refused(
            refuseStartup(
                err,
                ServiceStartupRefusal.SocketPathUnavailable,
                "the socket has no directory: $socketAbsolute",
            ),
        )

    // The socket may not BE the file of the authorizations: the service
    // writes that file and then binds the socket, and two things on one
    // inode is one of them broken.
    if (socketAbsolute == authorizationPath.toAbsolutePath().normalize()) {
        return BoundaryReading.Refused(
            refuseStartup(
                err,
                ServiceStartupRefusal.SocketPathIsReserved,
                "the socket would be the file of the authorizations: $socketAbsolute",
            ),
        )
    }

    val reading = when (profile) {
        ServiceTrustProfile.TrustedLocal ->
            ServiceAuthorizationStore.ofFile(authorizationPath, owned = namedFile == null)

        ServiceTrustProfile.PrivateAuth ->
            ServiceAuthorizationStore.readStandardInput(System.`in`)
    }
    return when (reading) {
        is ServiceAuthorizationsReading.Read -> {
            if (reading.authorizations.isEmpty() && profile == ServiceTrustProfile.PrivateAuth) {
                BoundaryReading.Refused(
                    refuseStartup(
                        err,
                        ServiceStartupRefusal.NoAuthorizationOnStandardInput,
                        "the private-auth profile needs at least one authorization on the standard input",
                    ),
                )
            } else {
                BoundaryReading.Read(
                    ServiceBoundary(
                        profile = profile,
                        authorizationPath = authorizationPath,
                        authorizations = ServiceAuthorizations.of(reading.authorizations),
                    ),
                )
            }
        }

        is ServiceAuthorizationsReading.Refused -> BoundaryReading.Refused(
            refuseStartup(err, ServiceStartupRefusal.AuthorizationsRefused, reading.detail),
        )
    }
}

/**
 * What this service may start, and the record that backs the promise a key
 * makes.
 */
internal data class ServiceRunDeclaration(
    val scriptsRoot: Path?,
    val workspacesRoot: Path?,
    val deadlineMillis: Long,
    val startDeadlineMillis: Long,
    val maxSupervisedRuns: Int,
    val allowNetwork: Boolean,
    val registry: ServiceStartRegistry,
)

/**
 * What the reading of the declaration produced.
 */
internal sealed interface RunPolicyReading {

    /** The declaration was read. */
    data class Read(val declaration: ServiceRunDeclaration) : RunPolicyReading

    /** The declaration is not one the service can honour, and the service does not start. */
    data class Refused(val exit: CliExit) : RunPolicyReading
}

/**
 * The DECLARATION of what this service may start, and the record of the
 * starts.
 *
 * ## Why it is read before the substrate
 *
 * Because a policy that is not a policy has to be refused before anything is
 * opened, and because a record of the starts that cannot be read is a
 * promise that cannot be kept: a service that forgot its promises would start
 * a second run for a key it already answered.
 */
private fun readRunPolicy(
    accepted: CliAdmission.Admitted,
    socketAbsolute: Path,
    err: PrintStream,
): RunPolicyReading {
    val scriptsRoot = accepted.values["--scripts"]?.let { Path.of(it).toAbsolutePath().normalize() }
    val workspacesRoot = accepted.values["--workspaces"]?.let { Path.of(it).toAbsolutePath().normalize() }
    val deadlineMillis = accepted.values["--run-deadline-ms"]?.let { text ->
        text.toLongOrNull()?.takeIf { it > 0L } ?: return RunPolicyReading.Refused(
            refuseStartup(
                err,
                ServiceStartupRefusal.RunPolicyRefused,
                "the deadline of a supervised run is a positive number of milliseconds: '$text'",
            ),
        )
    } ?: DEFAULT_RUN_DEADLINE_MILLIS
    val startDeadlineMillis = accepted.values["--start-deadline-ms"]?.let { text ->
        text.toLongOrNull()?.takeIf { it > 0L } ?: return RunPolicyReading.Refused(
            refuseStartup(
                err,
                ServiceStartupRefusal.RunPolicyRefused,
                "the deadline of a start is a positive number of milliseconds: '$text'",
            ),
        )
    } ?: DEFAULT_START_DEADLINE_MILLIS
    val maxSupervisedRuns = accepted.values["--max-supervised-runs"]?.let { text ->
        text.toIntOrNull()?.takeIf { it > 0 } ?: return RunPolicyReading.Refused(
            refuseStartup(
                err,
                ServiceStartupRefusal.RunPolicyRefused,
                "the budget of supervised runs is a positive number: '$text'",
            ),
        )
    } ?: DEFAULT_MAX_SUPERVISED_RUNS
    val allowNetwork = accepted.flags.contains("--run-allow-network")

    val directory = socketAbsolute.parent
        ?: return RunPolicyReading.Refused(
            refuseStartup(
                err,
                ServiceStartupRefusal.SocketPathUnavailable,
                "the socket has no directory: $socketAbsolute",
            ),
        )
    val registry = when (
        val read = ServiceStartRegistry.read(
            directory.resolve(ServiceStartRegistry.FILE_NAME),
            // The degradation of the promise a key makes is said to the
            // OPERATOR, in the channel the operator already reads: the log
            // of the service. A record that degraded in silence would leave a
            // broken promise with nobody looking after it.
            ServiceStartDiagnostics { degradation -> err.println(degradation.describe()) },
        )
    ) {
        is ServiceStartRegistryReading.Read -> read.registry
        is ServiceStartRegistryReading.Refused -> return RunPolicyReading.Refused(
            refuseStartup(err, ServiceStartupRefusal.StartsRecordRefused, read.detail),
        )
    }

    return RunPolicyReading.Read(
        ServiceRunDeclaration(
            scriptsRoot = scriptsRoot,
            workspacesRoot = workspacesRoot,
            deadlineMillis = deadlineMillis,
            startDeadlineMillis = startDeadlineMillis,
            maxSupervisedRuns = maxSupervisedRuns,
            allowNetwork = allowNetwork,
            registry = registry,
        ),
    )
}

/**
 * The supervisor of the runs this service starts.
 *
 * ## Why the paths it hands to the child are ABSOLUTE
 *
 * Because the child works in the directory of its script: a relative path
 * would mean something else there, and the run would write in a place the
 * operator never named.
 *
 * ## Why the record of the starts is NOT a parameter of its own
 *
 * Because the DECLARATION already carries it: a supervisor that received
 * both could be handed one record of the starts and write the promises of
 * another, and the promise of a key is the one thing the record exists to
 * keep.
 */
private fun composeSupervisor(
    policy: ServiceRunDeclaration,
    dbAbsolute: Path,
    controlAbsolute: Path,
    supervisionRoot: Path,
    clock: dev.rubentxu.pipeline.v2.domain.durable.Clock,
    brokers: RunBrokerFactory?,
    configurations: ConfigurationResolver?,
    credentialSessions: CredentialProviderSessions?,
): ServiceRunSupervisor {
    return ServiceRunSupervisor(
        admission = ServiceStartAdmission(policy.scriptsRoot, policy.workspacesRoot),
        runPolicy = ServiceRunPolicy(
            eventStore = dbAbsolute.toString(),
            controlRoot = controlAbsolute.toString(),
            deadlineMillis = policy.deadlineMillis,
            allowNetwork = policy.allowNetwork,
        ),
        command = ServiceRunCommand(
            javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            classpath = System.getProperty("java.class.path"),
            mainClass = SERVICE_MAIN_CLASS,
        ),
        port = ProcessDurableTaskRuntime(supervisionRoot, clock),
        registry = policy.registry,
        limits = ServiceStartLimits(
            maxSupervisedRuns = policy.maxSupervisedRuns,
            startDeadlineMillis = policy.startDeadlineMillis,
        ),
        supervisionRoot = supervisionRoot,
        collaborators = ServiceCollaborators(
            brokers = brokers,
            configurations = configurations,
            credentialSessions = credentialSessions,
        ),
        // The door that closes a run whose child was killed: it stamps the
        // terminal with the SAME clock the service stamps everything else, so
        // a run that the service closed says when the service closed it.
        closer = EventStoreSupervisedRunCloser(
            dbAbsolute.toString(),
            clock,
            OutputPlaneSupervisedRunOutputSealer(controlAbsolute),
        ),
    )
}

/**
 * What the composition of the service produced.
 */
private sealed interface ServiceComposition {

    /** The service is composed and it can answer. */
    data class Composed(
        val operations: ServiceOperations,
        val streets: ServiceStreets,
    ) : ServiceComposition

    /** The service cannot be composed, and this says why. */
    data class Refused(val detail: String) : ServiceComposition
}

/**
 * Where the questions of the runs are answered from, and who answers them.
 *
 * The two travel together because an answer is attributed with the label the
 * responder's digest belongs to, and a root without that attribution would
 * record answers nobody can account for. It exists only when some
 * authorization holds the right to answer.
 */
private class ServiceInputsBinding(
    val root: Path,
    val responderOf: (String?) -> String?,
)

/**
 * Composes the operations and the streets of the service over [store].
 *
 * ## Why the two are composed together
 *
 * Because they answer about the SAME substrate: a run that the streets
 * publish and the operations do not know would be a run the service
 * contradicts itself about.
 *
 * ## Why a failure is a value and not an exception
 *
 * Because the verb has to REFUSE it by name and close the store it opened,
 * and a failure that flies out of the composition would skip both.
 */
private fun composeService(
    store: dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore,
    substrate: ServeSubstrate,
    clock: SystemClock,
    supervisor: ServiceRunSupervisor,
    cancellations: ServiceRunCancellation?,
    providers: ConfigurationProviderSessions,
    inputs: ServiceInputsBinding?,
    credentialSessions: CredentialProviderSessions?,
    parameterDescriptions: ServiceParameterDescriber,
): ServiceComposition = try {
    val dbPath = substrate.dbPath
    val controlRoot = substrate.controlRoot
    val controlRootPath = substrate.controlRootPath
    val introspection = composeRuntimeIntrospectionPort(
        eventStore = store,
        controlRoot = controlRootPath,
        clock = clock,
    )
    val operations = ServiceOperations(
        introspection = introspection,
        substrate = {
            ServiceSubstrate(
                eventStore = dbPath,
                controlRoot = controlRoot,
                eventStorePresent = Files.exists(Path.of(dbPath)),
                controlRootPresent = Files.exists(controlRootPath),
            )
        },
        starts = supervisor,
        cancellations = cancellations,
        providers = providers,
        configurations = RunConfigurationLocator(
            supervisionRoot = substrate.supervisionRoot,
            correlations = EventStoreRunCorrelationReader(dbPath),
        ),
        inputs = inputs?.let {
            ServiceInputAnswers(
                inputsRoot = it.root,
                runState = RunStateQuery(introspection)::stateOf,
                responderOf = it.responderOf,
            )
        },
        credentialSessions = credentialSessions,
        parameterDescriptions = parameterDescriptions,
    )
    val stores = DurableStores.open(store, controlRootPath, clock)
    val streets = ServiceSubscription(
        lanes = ComposeLanes.forService(store, controlRootPath),
        events = composeEventRecordReadPort(store, stores.lease),
    )
    ServiceComposition.Composed(operations, streets)
} catch (e: Exception) {
    ServiceComposition.Refused(e.message ?: "the control root cannot be read")
}

/**
 * The refusal of a request that did not name what it needs.
 */
private fun refuseMissing(err: PrintStream, argument: String, why: String): CliExit {
    val error = CliError.MissingRequiredArgument(argument, why)
    err.println(error.refusalLines("serve").joinToString("\n"))
    err.println(SERVE_USAGE)
    return error.toCliExit()
}

/**
 * The refusal of a service that could not start.
 *
 * The REASON is a word and the DETAIL is the sentence, because every way
 * this fails has a different remedy and the operator is the one who has to
 * apply it.
 */
private fun refuseStartup(err: PrintStream, refusal: ServiceStartupRefusal, detail: String): CliExit {
    val error = CliError.ServiceUnavailable(refusal.reason, detail)
    err.println(error.refusalLines("serve").joinToString("\n"))
    err.println(SERVE_USAGE)
    return error.toCliExit()
}

/**
 * The boundary the service declares, printed when it starts.
 *
 * ## Why this is printed at all
 *
 * Because a boundary nobody reads is a boundary nobody knows, and the
 * operator is the one who has to know: a service that answers questions over
 * a socket has to say what it takes to be one of its clients. The words are
 * the SAME promise the profile makes in the code, written out where the
 * operator sees them.
 *
 * ## What the `trusted-local` promise says
 *
 * That the boundary is the FILE SYSTEM: the socket is `rw-------` in a
 * directory that grants nothing to the group or to others, so any process of
 * the same user may open it, and the token that guards the questions is in a
 * file that any of those processes may read. The token separates IDENTITIES
 * THE OPERATOR NAMES —a client that presents it from a client that does not—
 * and it does not separate two processes of the same user. The JVM does not
 * expose the credentials of the peer of a Unix socket, so nothing in this
 * build can.
 *
 * ## What the `private-auth` promise says
 *
 * That the authorization is NOT on the disk: it was read from the standard
 * input and the service wrote it nowhere, so there is no file for a process
 * of the same user to read. It does not isolate processes either: a process
 * of the same user that reads the service's standard input while it starts
 * takes the authorization. The token separates the same identities the
 * operator names, and it separates no two processes.
 */
internal fun describeBoundary(
    out: PrintStream,
    profile: ServiceTrustProfile,
    authorizationPath: Path,
    authorizationCount: Int,
    credentialSessions: Boolean,
) {
    out.println("trust-profile ${profile.word}")
    out.println(
        when (profile) {
            ServiceTrustProfile.TrustedLocal ->
                "boundary file-system: any process of the same user may open this socket, and " +
                    "the authorization is in a file that any of them may read; the token " +
                    "separates the identities the operator names, not two processes of the " +
                    "same user"

            ServiceTrustProfile.PrivateAuth ->
                "boundary not-on-disk: the authorization was read from the standard input and " +
                    "was written nowhere, and a process of the same user that reads the " +
                    "standard input while the service starts takes it; the token separates " +
                    "the identities the operator names, not two processes of the same user"
        },
    )
    if (profile == ServiceTrustProfile.TrustedLocal) {
        out.println(
            "authorizations $authorizationCount in $authorizationPath; " +
                "a client presents the token of one of them",
        )
    }
    out.println(
        if (credentialSessions) {
            "credential-sessions served: the local credential store was NOT opened; every " +
                "credential of every run comes from the controller that registered its session"
        } else {
            "credential-sessions closed: a run may not name a credential provider, and the " +
                "local credential store is the only source of a credential"
        },
    )
}

/**
 * What this service may start, printed when it starts.
 *
 * ## Why this is printed at all
 *
 * Because a service that may start runs has to say WHICH runs it may start,
 * and the operator is the one who has to know it: the directories, the
 * deadline, the budget and the network are the whole promise, and a promise
 * nobody reads is a promise nobody knows.
 *
 * ## Why a service without `--scripts` says so
 *
 * Because a service that could start nothing has to be told apart from a
 * service that could start everything: the first one answers questions and
 * refuses starts, and the operator has to know which of the two is running.
 */
private fun describeRuns(
    out: PrintStream,
    policy: ServiceRunDeclaration,
) {
    val scriptsRoot = policy.scriptsRoot
    if (scriptsRoot == null) {
        out.println("runs none: this service answers questions and starts no run")
        return
    }
    out.println("scripts $scriptsRoot")
    out.println("workspaces ${policy.workspacesRoot ?: "the product's own scratch"}")
    out.println("run-deadline-ms ${policy.deadlineMillis}")
    out.println("start-deadline-ms ${policy.startDeadlineMillis}")
    out.println("max-supervised-runs ${policy.maxSupervisedRuns}")
    out.println("run-network ${if (policy.allowNetwork) "allowed" else "denied"}")
}

/**
 * The lane the substrate was opened in, printed when the service starts.
 *
 * ## Why this is printed at all
 *
 * Because a service that WRITES to the substrate is a different thing from
 * one that only reads it, and the operator is the one who has to know: the
 * file is the operator's, and a service that writes into it does so
 * because somebody granted the right to cancel runs.
 *
 * The declaration is about the SERVICE, not about the file's permissions:
 * a file that cannot be written is refused at startup, and a service that
 * says it writes is a service that did open the write door.
 */
private fun describeSubstrate(out: PrintStream, purpose: EventStorePurpose) {
    when (purpose) {
        EventStorePurpose.Write -> out.println(
            "substrate read-write: this service writes the terminal of a cancelled run",
        )

        EventStorePurpose.Read -> out.println(
            "substrate read-only: this service reads the store it was given",
        )

        EventStorePurpose.Produce -> out.println(
            "substrate read-write: this service writes the events of the runs it starts",
        )
    }
}

/**
 * The words a refusal of the substrate carries.
 */
private fun describeRefusal(refusal: EventStoreRefusal): String = when (refusal) {
    is EventStoreRefusal.Absent -> "the store is not there: ${refusal.path}"
    is EventStoreRefusal.NoDirectory -> "the directory of the store is not there: ${refusal.path}"
    is EventStoreRefusal.NotReadable -> "the store cannot be read: ${refusal.detail}"
    is EventStoreRefusal.NoEventHistory ->
        "the store holds no event history: the table events is not there: ${refusal.path}"
}
