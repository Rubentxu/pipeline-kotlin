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
    "Usage: pipelinek serve --db <path> --control-root <path> --socket <path>"

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
 */
internal val SERVE_REQUEST: CliRequest = CliRequest(
    options = listOf(
        CliOption("--db", takesValue = true),
        CliOption("--control-root", takesValue = true),
        CliOption("--socket", takesValue = true),
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

    val server = ServiceServer(transport, operations, streets)
    val shutdown = Thread({ server.close() }, "pipelinek-service-shutdown")
    Runtime.getRuntime().addShutdownHook(shutdown)
    try {
        out.println("pipelinek service listening on $socketPath")
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
 * The words a refusal of the substrate carries.
 */
private fun describeRefusal(refusal: EventStoreRefusal): String = when (refusal) {
    is EventStoreRefusal.Absent -> "the store is not there: ${refusal.path}"
    is EventStoreRefusal.NoDirectory -> "the directory of the store is not there: ${refusal.path}"
    is EventStoreRefusal.NotReadable -> "the store cannot be read: ${refusal.detail}"
    is EventStoreRefusal.NoEventHistory -> "the store holds no event history: ${refusal.path}"
}
