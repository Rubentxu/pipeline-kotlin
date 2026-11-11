package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.RunCorrelated
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.EventStoreRefusal
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * The name of the directory that holds the supervision of a run.
 *
 * ## Why the name carries the CORRELATION and not a name of its own
 *
 * Because the facts of a supervision have to survive the restart of the
 * service, and a name the service invented at random is only known to the
 * service that invented it: after a restart the directory of a run would be
 * unreachable, and the facts inside it —the snapshot of the configuration
 * among them— would be there with nobody able to name them.
 *
 * The CORRELATION is the one name of a run that is ALREADY durable: the
 * service chose it before the child was launched, handed it to the child in
 * its argv, and the child published it in the `RunCorrelated` event that
 * opens the run's own history. A directory named after it is therefore
 * findable from the run's own facts, with no index of its own and no second
 * store.
 *
 * ## Why the prefix
 *
 * Because the directory is the PORT's directory as well: the port writes the
 * child's result under the name of the task's op id, and the op id of a
 * supervision IS the name of its directory. A name that says what it holds
 * is easier to read in a listing of the service's own directory.
 */
internal fun supervisionDirectoryName(correlationId: String): String = "sup-" + correlationId

/**
 * What the store says about the correlation a run was started with.
 */
internal sealed interface RunCorrelation {

    /** The run was started with [correlationId]. */
    data class Found(val correlationId: String) : RunCorrelation

    /** The store holds no run with that identity. */
    data object Absent : RunCorrelation

    /** The store could not be read, and [detail] says why. */
    data class Unreadable(val detail: String) : RunCorrelation
}

/**
 * The door that says which correlation a run was started with.
 *
 * ## Why the question is asked of the STORE and not of the service
 *
 * Because the answer has to survive the restart of the service: the service
 * that started the run is not there any more, and everything it knew in
 * memory died with it. The store of the substrate does not die, and the run's
 * own history opens with the event that carries the correlation.
 */
internal interface RunCorrelationReader {

    /** The correlation of [runId]. Never throws. */
    fun correlationOf(runId: String): RunCorrelation
}

/**
 * The reader that looks for the correlation in the run's own events.
 *
 * ## Why the READING lane
 *
 * Because the store of the substrate is shared: the children of the runs that
 * start next write into it, and a reader that held it while it answered a
 * question would make the next child wait for a lock it does not know why it
 * is waiting for.
 *
 * ## Why the store is opened for EVERY question
 *
 * Because the reader does not OWN the store: the service opened it, the
 * service closes it, and a reader that kept a handle of its own would be a
 * second owner of a file that a writer is using. The question is asked once
 * per query of a client about a run, and a query of that kind is rare.
 *
 * ## What a store that is NOT there means
 *
 * That the store holds no run at all, and therefore no run with that
 * identity: the answer is [RunCorrelation.Absent], the same answer a store
 * with runs in it gives for an identity it has never seen.
 */
internal class EventStoreRunCorrelationReader(private val storePath: String) : RunCorrelationReader {

    override fun correlationOf(runId: String): RunCorrelation {
        val admission = admitEventStore(storePath, EventStorePurpose.Read)
        val store = when (admission) {
            is EventStoreAdmission.Admitted -> admission.store
            is EventStoreAdmission.Refused -> return when (admission.refusal) {
                is EventStoreRefusal.Absent -> RunCorrelation.Absent
                else -> RunCorrelation.Unreadable(admission.refusal.toString())
            }
        }
        return try {
            val correlated = store.eventsFor(runId).filterIsInstance<RunCorrelated>().firstOrNull()
            if (correlated == null) RunCorrelation.Absent else RunCorrelation.Found(correlated.correlationId.value)
        } catch (failure: Exception) {
            RunCorrelation.Unreadable(failure.message ?: failure.javaClass.simpleName)
        } finally {
            store.close()
        }
    }
}

/**
 * The door that finds the configuration snapshot of a run.
 *
 * ## Where the location comes from
 *
 * From the run's OWN facts: the `RunCorrelated` event the child published
 * carries the correlation the service chose, and the correlation names the
 * directory the supervision wrote the snapshot into. The chain is short, it
 * is made of durable facts, and it holds after the service restarts: the
 * snapshot is on the disk and the way to name it is in the store.
 *
 * ## Why there is no INDEX of the snapshots
 *
 * Because an index in memory is a second authority about where a fact lives,
 * and the one that was here died with the service: a run whose entry the
 * index forgot was answered as a run the service did not know, while its
 * snapshot was on the disk all the same. A location that is DERIVED cannot
 * be forgotten, because nothing has to remember it.
 *
 * ## What it answers for a run it does not know
 *
 * [RunConfigurationLookup.Unknown], the answer that says the service did not
 * start a run with that identity. It is not an error and it is not an empty
 * description: an empty description says that the run was given nothing, and
 * that is a different fact from "this service did not start it".
 */
internal class RunConfigurationLocator(
    private val supervisionRoot: Path,
    private val correlations: RunCorrelationReader,
) : ServiceRunConfigurations {

    /**
     * Where the configuration of [runId] came from, read from the snapshot the run itself reads.
     *
     * Never throws: a question about a run is asked over a socket, and a door
     * that flies out of the answer takes the request down with it.
     */
    override fun describe(runId: String): RunConfigurationLookup {
        val correlationId = when (val correlation = correlations.correlationOf(runId)) {
            is RunCorrelation.Absent -> return RunConfigurationLookup.Unknown
            is RunCorrelation.Unreadable -> return RunConfigurationLookup.Unreadable(
                "the snapshot of the configuration of the run cannot be read",
            )

            is RunCorrelation.Found -> correlation.correlationId
        }
        val directory = supervisionRoot.resolve(supervisionDirectoryName(correlationId))
        // The directory is the service's own record that THIS service started
        // the run: a run of another service that shares the substrate has its
        // directory in the other service's own directory, and a run whose
        // directory is not here is a run this service did not start.
        if (!Files.isDirectory(directory)) return RunConfigurationLookup.Unknown
        val snapshot = directory.resolve(ServiceRunSupervisor.RUN_CONFIG_NAME)
        return try {
            RunConfigurationLookup.Found(RunConfigurationReader.describe(snapshot))
        } catch (_: Exception) {
            // The snapshot is there and cannot be read: it was written by a
            // build that speaks another schema, or it was changed under the
            // service. The message says so with a fixed text, because the
            // message of a failure over the file system names paths, and a
            // path does not travel.
            RunConfigurationLookup.Unreadable("the snapshot of the configuration of the run cannot be read")
        }
    }
}
