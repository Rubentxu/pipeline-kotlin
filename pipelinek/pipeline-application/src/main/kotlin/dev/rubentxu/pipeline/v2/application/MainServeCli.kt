package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.EventStoreRefusal
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The usage the `serve` verb prints when its request is refused.
 */
internal const val SERVE_USAGE: String =
    "Usage: pipelinek serve --db <path> --control-root <path> --socket <path> " +
        "[--trust-profile <name>] [--authorization-file <path>]"

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
 * and their names say what they are: the profile names the boundary the
 * service declares, and the file names where the authorizations are read
 * from. They are OPTIONAL, and the defaults are the ones the profile says:
 * `trusted-local`, and the file the service owns next to its socket.
 */
internal val SERVE_REQUEST: CliRequest = CliRequest(
    options = listOf(
        CliOption("--db", takesValue = true),
        CliOption("--control-root", takesValue = true),
        CliOption("--socket", takesValue = true),
        CliOption("--trust-profile", takesValue = true),
        CliOption("--authorization-file", takesValue = true),
    ),
)

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

    // The profile is a closed vocabulary: the word says what the operator
    // ASKS for, and an unknown word is refused instead of being taken for
    // the default. A profile the operator misspelled is a boundary nobody
    // chose, and a boundary nobody chose is not a boundary.
    val profile = accepted.values["--trust-profile"]?.let { word ->
        ServiceTrustProfile.parse(word)
            ?: return refuseStartup(
                err,
                ServiceStartupRefusal.TrustProfileUnknown,
                "the trust profile is not one this build serves: '$word'",
            )
    } ?: ServiceTrustProfile.TrustedLocal

    // The socket may not BE a file the service reads or writes. A socket that
    // IS the store would make the store not a store, and a socket that IS
    // the file of the authorizations would put the secret and the door in
    // the same inode.
    //
    // What is NOT refused is the socket living NEXT TO the material: the
    // natural layout of an operator —the store, the control root and the
    // socket in one directory— is the one this rule has to let through.
    val socketAbsolute = Path.of(socketPath).toAbsolutePath().normalize()
    val dbAbsolute = Path.of(dbPath).toAbsolutePath().normalize()
    val controlAbsolute = Path.of(controlRoot).toAbsolutePath().normalize()
    if (socketAbsolute == dbAbsolute || socketAbsolute == controlAbsolute) {
        return refuseStartup(
            err,
            ServiceStartupRefusal.SocketPathIsReserved,
            "the socket would be a file the service reads: $socketAbsolute",
        )
    }

    // The authorizations come from the place the profile names: the file the
    // operator named, the file the service owns next to its socket, or the
    // standard input. The two profiles do not mix: a profile that keeps the
    // authorization off the disk cannot read it from the disk.
    val namedFile = accepted.values["--authorization-file"]
    if (namedFile != null && profile == ServiceTrustProfile.Isolated) {
        return refuseStartup(
            err,
            ServiceStartupRefusal.AuthorizationFileRefusedByTheProfile,
            "the isolated profile reads the authorizations from the standard input, " +
                "and --authorization-file names a file",
        )
    }
    val authorizationPath = namedFile?.let { Path.of(it) }
        ?: socketAbsolute.parent?.resolve(ServiceAuthorizationStore.OWNED_FILE_NAME)
        ?: return refuseStartup(
            err,
            ServiceStartupRefusal.SocketPathUnavailable,
            "the socket has no directory: $socketPath",
        )

    // The socket may not BE the file of the authorizations: the service
    // writes that file and then binds the socket, and two things on one
    // inode is one of them broken.
    if (socketAbsolute == authorizationPath.toAbsolutePath().normalize()) {
        return refuseStartup(
            err,
            ServiceStartupRefusal.SocketPathIsReserved,
            "the socket would be the file of the authorizations: $socketAbsolute",
        )
    }

    val reading = when (profile) {
        ServiceTrustProfile.TrustedLocal ->
            ServiceAuthorizationStore.ofFile(authorizationPath, owned = namedFile == null)

        ServiceTrustProfile.Isolated ->
            ServiceAuthorizationStore.readStandardInput(System.`in`)
    }
    val authorizations = when (reading) {
        is ServiceAuthorizationsReading.Read -> {
            if (reading.authorizations.isEmpty() && profile == ServiceTrustProfile.Isolated) {
                return refuseStartup(
                    err,
                    ServiceStartupRefusal.IsolationUnavailable,
                    "the isolated profile needs at least one authorization on the standard input",
                )
            }
            ServiceAuthorizations.of(reading.authorizations)
        }

        is ServiceAuthorizationsReading.Refused ->
            return refuseStartup(err, ServiceStartupRefusal.AuthorizationsRefused, reading.detail)
    }

    // The substrate is composed ONCE, before the socket exists: a service
    // that cannot read its substrate would only know how to say that it is
    // alive, and a client would read that as an answer about a run.
    val storeAdmission = admitEventStore(dbPath, EventStorePurpose.Read)
    val store = when (storeAdmission) {
        is EventStoreAdmission.Admitted -> storeAdmission.store
        is EventStoreAdmission.Refused -> {
            val error = CliError.SubstrateUnavailable(describeRefusal(storeAdmission.refusal))
            err.println(error.refusalLines("serve").joinToString("\n"))
            err.println(SERVE_USAGE)
            return error.toCliExit()
        }
    }

    val controlRootPath = Paths.get(controlRoot)
    val clock = SystemClock()
    val operations = try {
        ServiceOperations(
            introspection = composeRuntimeIntrospectionPort(
                eventStore = store,
                controlRoot = controlRootPath,
                clock = clock,
            ),
            substrate = {
                ServiceSubstrate(
                    eventStore = dbPath,
                    controlRoot = controlRoot,
                    eventStorePresent = Files.exists(Path.of(dbPath)),
                    controlRootPresent = Files.exists(controlRootPath),
                )
            },
        )
    } catch (e: Exception) {
        store.close()
        val error = CliError.SubstrateUnavailable(
            e.message ?: "the control root cannot be read",
        )
        err.println(error.refusalLines("serve").joinToString("\n"))
        err.println(SERVE_USAGE)
        return error.toCliExit()
    }

    // The streets are composed over the SAME substrate and the SAME authorities the
    // questions are answered from, so a run cannot be "unknown" to one door of the
    // service and "known" to the other.
    val streets = try {
        val stores = DurableStores.open(store, controlRootPath, clock)
        ServiceSubscription(
            lanes = ComposeLanes.forService(store, controlRootPath),
            events = composeEventRecordReadPort(store, stores.lease),
        )
    } catch (e: Exception) {
        store.close()
        val error = CliError.SubstrateUnavailable(
            e.message ?: "the control root cannot be read",
        )
        err.println(error.refusalLines("serve").joinToString("\n"))
        err.println(SERVE_USAGE)
        return error.toCliExit()
    }

    val transport = try {
        UnixSocketTransport.bind(Path.of(socketPath))
    } catch (e: ServiceStartupException) {
        store.close()
        val error = CliError.ServiceUnavailable(e.refusal.reason, e.detail)
        err.println(error.refusalLines("serve").joinToString("\n"))
        err.println(SERVE_USAGE)
        return error.toCliExit()
    }

    val server = ServiceServer(transport, operations, streets, authorizations)
    val shutdown = Thread({ server.close() }, "pipelinek-service-shutdown")
    Runtime.getRuntime().addShutdownHook(shutdown)
    try {
        out.println("pipelinek service listening on $socketPath")
        describeBoundary(out, profile, authorizationPath, authorizations.size)
        server.serve()
    } finally {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdown)
        } catch (_: IllegalStateException) {
            // The JVM is already shutting down, and the hook is running or
            // has run. Removing it is a courtesy, not a requirement.
        }
        server.close()
        store.close()
    }
    return CliExit.Success("the service stopped")
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
 * ## What the `isolated` promise says
 *
 * That the authorization is NOT on the disk: it was read from the standard
 * input and the service wrote it nowhere, so there is no file for a process
 * of the same user to read. It does not isolate processes either: a process
 * of the same user that reads the service's standard input while it starts
 * takes the authorization.
 */
private fun describeBoundary(
    out: PrintStream,
    profile: ServiceTrustProfile,
    authorizationPath: Path,
    authorizationCount: Int,
) {
    out.println("trust-profile ${profile.word}")
    out.println(
        when (profile) {
            ServiceTrustProfile.TrustedLocal ->
                "boundary file-system: any process of the same user may open this socket; " +
                    "the token separates the identities the operator names, not two processes " +
                    "of the same user"

            ServiceTrustProfile.Isolated ->
                "boundary not-on-disk: the authorization was read from the standard input and " +
                    "was written nowhere"
        },
    )
    if (profile == ServiceTrustProfile.TrustedLocal) {
        out.println(
            "authorizations $authorizationCount in $authorizationPath; " +
                "a client presents the token of one of them",
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
    is EventStoreRefusal.NoEventHistory -> "the store holds no event history: ${refusal.path}"
}
