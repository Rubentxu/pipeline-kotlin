package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.UUID

/**
 * How the child of a supervised run ended, when it ended WITHOUT a terminal.
 *
 * ## Why these four and not one
 *
 * Because they say DIFFERENT things to whoever reads the run afterwards: a
 * child that outlived the budget was stopped by the watchdog, a child that was
 * cancelled was stopped from the outside, a child that ended on its own ended
 * in a way the run cannot describe, and a child that could not be run at all
 * never got far enough to end. A run closed with the wrong one of the four
 * sends the reader to look in the wrong place.
 */
internal sealed interface SupervisedRunEnd {

    /** The child outlived the budget of the run and the watchdog killed it. */
    data object TimedOut : SupervisedRunEnd

    /** The child was stopped before it ended. */
    data object Cancelled : SupervisedRunEnd

    /**
     * The child ended on its own and the run holds no terminal of its own.
     *
     * @property exitCode the code the child left behind.
     */
    data class Ended(val exitCode: Int) : SupervisedRunEnd

    /** The child could not be run to its end: the port failed under it. */
    data object Failed : SupervisedRunEnd

    /** The word the run's terminal carries for this end. */
    fun outcomeToken(): String = when (this) {
        // A run that was stopped before it could finish was ABORTED: that is
        // the word the cancellation writes too, and the two say the same thing
        // — somebody stopped the run from the outside.
        TimedOut, Cancelled -> RunOutcome.ABORTED_TOKEN
        // A child that ended on its own did not finish its work, and a child
        // that could not be run at all did not even start it: both are a
        // failure of the run, not a stop from the outside. The bare word is
        // the one the wire carries: it names the outcome and not the cause,
        // and the cause is in the diagnostic below.
        is Ended, Failed -> RunOutcome.FAILURE_TOKEN
    }

    /** The diagnostic that says WHY the run ended this way. */
    fun diagnostic(): ScriptingDiagnostic {
        val message = when (this) {
            TimedOut ->
                "the run was stopped: its child outlived the budget of the run and was killed"
            Cancelled ->
                "the run was stopped: its child was cancelled before it ended"
            is Ended ->
                "the run ended without a terminal of its own: its child ended with the exit code $exitCode"
            Failed ->
                "the run ended without a terminal of its own: its child could not be run to its end"
        }
        return ScriptingDiagnostic(
            severity = ScriptDiagnosticSeverity.ERROR,
            message = message,
            line = 0,
            column = 0,
            path = SUPERVISED_DIAGNOSTIC_PATH,
        )
    }

    private companion object {
        /** The path the diagnostic carries: the run has no source file to point at. */
        const val SUPERVISED_DIAGNOSTIC_PATH: String = "supervised-run"
    }
}

/**
 * What the closer did with the run it was asked about.
 */
internal sealed interface SupervisedRunClosure {

    /** The run already held its terminal: the closer wrote nothing. */
    data object AlreadyEnded : SupervisedRunClosure

    /** The closer wrote the terminal of the run. */
    data class Closed(val eventId: String) : SupervisedRunClosure

    /** The closer could not write: the store refused, or the write failed. */
    data class Refused(val detail: String) : SupervisedRunClosure
}

/**
 * The door the supervisor opens to CLOSE a run whose child ended without a
 * terminal.
 *
 * ## Why the supervisor needs a door at all
 *
 * Because the child is KILLED, not asked to stop: the watchdog ends it with a
 * signal that gives the JVM no chance to write anything, and a run whose
 * writer was killed can never write its own terminal. Without this door such
 * a run stays open for ever: the store holds no bookend, the follow never
 * stops, and a client that waits for the terminal waits for ever.
 *
 * ## What this door does NOT do
 *
 * It does not decide the outcome of a run that is still alive: it is asked
 * about a run whose writer is GONE, and about nothing else. The terminal it
 * writes is the same one the cancellation writes — the run was ABORTED — and
 * it carries the reason in its diagnostic, because the run itself cannot say
 * why it did not finish.
 */
internal interface SupervisedRunCloser {

    /**
     * Closes [runId] when it does not hold a terminal yet.
     *
     * @param runId the run whose writer is gone.
     * @param end how that writer ended.
     * @return what the closer did.
     */
    fun closeWithoutTerminal(runId: String, end: SupervisedRunEnd): SupervisedRunClosure
}

/**
 * The closer that writes the terminal of the run in the run's own store.
 *
 * ## Why the writing lane is opened HERE and not when the service starts
 *
 * Because the lane that writes CREATES the store it does not find, and a
 * service that creates the substrate it is about to read answers "the run is
 * unknown" about every run in a store that holds no run at all. This closer
 * opens the lane only when a child has just died, and at that moment the store
 * is there: the child wrote it.
 *
 * ## Why the QUESTION is asked through the reading door
 *
 * Because the substrate is shared: the children of the runs that start next
 * write to it, and a writer that holds it while it answers a question would
 * make the next child wait for a lock it does not know why it is waiting for.
 * The question "does this run already hold a terminal" is a question a read
 * can answer, so it is asked through the door that only reads.
 *
 * ## Why the store has to be THERE before the lane is opened
 *
 * Because that lane creates the file it does not find, and a closer that
 * created the substrate of a run would leave an empty store behind and answer
 * about a run that never existed. The refusal of a store that is not there is
 * this closer's own, and it leaves the machine as it found it.
 *
 * ## Why the lane is closed right after the fact is written
 *
 * Because the child is gone, and its connection with it: a writer that stayed
 * open would hold the store against the NEXT run's child, and the run that
 * starts next would meet a database another process holds.
 *
 * ## Why the terminal is made durable before the closer answers
 *
 * Because the lane writes through a writer thread, and a closer that answered
 * "closed" over a fact that was still in the queue would let the next closer
 * find no terminal and write a SECOND one. One run, one terminal.
 *
 * ## Why the output is sealed BEFORE the terminal
 *
 * Because the terminal says the run is over, and a run whose streams are
 * still open is not: the follower of its output waits for the seal that the
 * killed child never wrote. The seal goes first, so that a run is never
 * closed and unsealed at the same time; a seal that cannot be written leaves
 * the run open, and the closer answers with the refusal.
 *
 * ## Why the terminal carries the errors of the run's compilation
 *
 * Because a child whose script does not compile ends with the exit code 1 and
 * writes no terminal of its own, and the exit code alone does not say why: the
 * client of the service reads the terminal, not the machine the child ran on.
 * What the compiler refused is already in the run's store, in its last
 * `CompilationFinished`, so the terminal carries the closer's own diagnostic
 * first and then the errors and fatal reports of that compilation, as the
 * store holds them. Its warnings stay out: a warning is not why a run failed.
 * The child's own stderr stays out too: it is a file of the supervision, not
 * a fact of the run's store, and nothing redacted it on its way there.
 *
 * ## What this closer does NOT do
 *
 * It does not seal a run that already holds a terminal: that run was closed
 * by whoever wrote the terminal, and the seal is theirs.
 *
 * It does not write the JOURNAL of the run either. The journal says what the
 * run DID, and the work a killed child left in it may still be recovered by
 * an operator who asks for it. The terminal this door writes says what the
 * SERVICE could not finish; the journal keeps saying what the run did, and
 * the two are different facts about different things.
 */
internal class EventStoreSupervisedRunCloser(
    private val storePath: String,
    private val clock: Clock,
    private val outputSealer: SupervisedRunOutputSealer,
) : SupervisedRunCloser {

    override fun closeWithoutTerminal(runId: String, end: SupervisedRunEnd): SupervisedRunClosure {
        if (!storeIsThere()) {
            return SupervisedRunClosure.Refused("the store of the run is not there: $storePath")
        }
        // The run's own terminal is asked about FIRST, and it is asked through
        // the READING door: the substrate is shared with the children of the
        // runs that start next, and a door that WRITES would take the write
        // lock of that substrate to answer a question a read can answer.
        if (holdsTerminal(runId)) return SupervisedRunClosure.AlreadyEnded
        val admission = admitEventStore(storePath, EventStorePurpose.Write)
        val store = when (admission) {
            is EventStoreAdmission.Admitted -> admission.store
            is EventStoreAdmission.Refused ->
                return SupervisedRunClosure.Refused(admission.refusal.toString())
        }
        try {
            // The terminal is asked about AGAIN, through the writing door: the
            // question was asked through the reading door, and a run that ended
            // by itself must keep the terminal it wrote. A second terminal
            // would be a second truth about the same run.
            var compilation: CompilationFinished? = null
            for (event in store.eventsFor(runId)) {
                if (event is RunFinished) return SupervisedRunClosure.AlreadyEnded
                if (event is CompilationFinished) compilation = event
            }
            // The output is sealed BEFORE the terminal is written, so that a
            // run is never closed and unsealed at the same time: a reader that
            // sees the terminal sees streams that will not grow. A seal that
            // fails leaves the run OPEN, and the refusal says why.
            when (val sealed = outputSealer.sealOutputOf(runId)) {
                is SupervisedRunSeal.Sealed -> Unit
                is SupervisedRunSeal.Refused ->
                    return SupervisedRunClosure.Refused(
                        "the output of the run could not be sealed: ${sealed.detail}",
                    )
            }
            val eventId = "e-supervised-" + UUID.randomUUID().toString()
            store.append(
                RunFinished(
                    eventId = eventId,
                    runId = runId,
                    // Zero asks the store for the next sequence of the run; it
                    // is a request, not a value.
                    sequence = 0L,
                    occurredAt = clock.now(),
                    outcome = end.outcomeToken(),
                    diagnostics = listOf(end.diagnostic()) + compilation.errors(),
                ),
            )
            store.makeDurable(eventId)
            return SupervisedRunClosure.Closed(eventId)
        } catch (failure: Exception) {
            return SupervisedRunClosure.Refused(failure.message ?: failure.javaClass.simpleName)
        } finally {
            store.close()
        }
    }

    /**
     * What the compiler refused in this compilation: its errors and fatal
     * reports, in the order it raised them, and nothing for a run that holds
     * no compilation.
     */
    private fun CompilationFinished?.errors(): List<ScriptingDiagnostic> =
        this?.diagnostics.orEmpty().filter { it.severity >= ScriptDiagnosticSeverity.ERROR }

    /**
     * Whether the run already holds a terminal, as the READING door says it.
     *
     * ## Why a refusal is NOT a terminal
     *
     * Because a refusal is the door saying "I could not tell", and a door that
     * could not tell must not be read as "there is no terminal": the run
     * would be closed over a question nobody answered. The refusal leaves the
     * question OPEN, and the writing door asks it again — through the door
     * that can see the rows the reading door could not.
     */
    private fun holdsTerminal(runId: String): Boolean {
        val admission = admitEventStore(storePath, EventStorePurpose.Read)
        val store = when (admission) {
            is EventStoreAdmission.Admitted -> admission.store
            is EventStoreAdmission.Refused -> return false
        }
        return try {
            store.eventsFor(runId).any { it is RunFinished }
        } catch (_: Exception) {
            false
        } finally {
            store.close()
        }
    }

    /**
     * Whether the store of the run is there.
     *
     * A path that cannot be parsed is a path that is not there: the lane that
     * writes would answer about a file that does not exist.
     */
    private fun storeIsThere(): Boolean = try {
        Files.exists(Path.of(storePath))
    } catch (_: InvalidPathException) {
        false
    }
}
