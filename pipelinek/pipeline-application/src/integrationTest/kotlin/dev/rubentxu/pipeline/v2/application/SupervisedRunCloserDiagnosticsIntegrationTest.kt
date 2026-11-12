package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The terminal the closer writes says why the run failed, when the run's own compilation said it.
 *
 * ## The guarantee under trial
 *
 * A child whose script does not compile ends with the exit code 1 and writes no terminal of its
 * own; the store keeps what the compiler said in the run's `CompilationFinished`. The closer
 * writes the terminal of that run, and a terminal that only said "its child ended with the exit
 * code 1" sent the client of the service to a machine it cannot read. The terminal carries the
 * closer's own diagnostic first and then the ERRORS of the run's last compilation, as the store
 * holds them: message, line, column and file.
 *
 * ## Why each row is here
 *
 * - The failed compilation is the case: its errors and fatal reports follow the closer's
 *   diagnostic in the order the compiler raised them, and its warnings do not, because a warning
 *   is not why a run failed.
 * - The second compilation is the one the child that ended ran: a run compiled twice is closed
 *   with the errors of the last compilation, not with the first one's.
 * - The clean compilation and the run with no compilation keep the closer's diagnostic alone:
 *   nothing the compiler said explains their end.
 *
 * ## The instrument
 *
 * A real store in the row's own directory, written through the door the product writes with, and
 * a sealer that seals: the order of the seal is the business of another lane.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SupervisedRunCloserDiagnosticsIntegrationTest {

    @TempDir
    lateinit var root: Path

    private lateinit var db: Path

    private val clock: Clock = object : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private val sealer = object : SupervisedRunOutputSealer {
        override fun sealOutputOf(runId: String): SupervisedRunSeal = SupervisedRunSeal.Sealed(0)
    }

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        (admission as EventStoreAdmission.Admitted).store.close()
    }

    @Test
    fun `the terminal of a run whose compilation failed carries the errors of that compilation`() {
        compiled(RUN, listOf(UNRESOLVED, DEPRECATED, NO_RETURN, CRASHED))

        val terminal = close(RUN, SupervisedRunEnd.Ended(1))

        assertEquals(
            listOf(SupervisedRunEnd.Ended(1).diagnostic(), UNRESOLVED, NO_RETURN, CRASHED),
            terminal.diagnostics,
            "the terminal says how the child ended and then what the compiler refused, without its warnings",
        )
    }

    @Test
    fun `a run compiled twice is closed with the errors of its last compilation`() {
        compiled(RUN, listOf(UNRESOLVED))
        compiled(RUN, listOf(NO_RETURN))

        val terminal = close(RUN, SupervisedRunEnd.Ended(1))

        assertEquals(listOf(SupervisedRunEnd.Ended(1).diagnostic(), NO_RETURN), terminal.diagnostics)
    }

    @Test
    fun `a clean compilation, or none, leaves the closer's diagnostic alone`() {
        compiled(RUN, listOf(DEPRECATED))

        assertEquals(
            listOf(SupervisedRunEnd.TimedOut.diagnostic()),
            close(RUN, SupervisedRunEnd.TimedOut).diagnostics,
        )
        assertEquals(
            listOf(SupervisedRunEnd.Ended(1).diagnostic()),
            close(OTHER_RUN, SupervisedRunEnd.Ended(1)).diagnostics,
        )
    }

    /** Writes a `CompilationFinished` of [runId] with [diagnostics] through the product's writing door. */
    private fun compiled(runId: String, diagnostics: List<ScriptingDiagnostic>) {
        val admission = admitEventStore(db.toString(), EventStorePurpose.Write)
        val store = (admission as EventStoreAdmission.Admitted).store
        try {
            val eventId = "e-compiled-${System.nanoTime()}"
            store.append(
                CompilationFinished(
                    eventId = eventId,
                    runId = runId,
                    sequence = 0L,
                    occurredAt = Instant.EPOCH,
                    cacheKey = CacheKey("0".repeat(64), CacheKey.V3),
                    diagnostics = diagnostics,
                ),
            )
            store.makeDurable(eventId)
        } finally {
            store.close()
        }
    }

    /** Closes [runId] with [end] and reads back the terminal the closer wrote. */
    private fun close(runId: String, end: SupervisedRunEnd): RunFinished {
        val closure = EventStoreSupervisedRunCloser(db.toString(), clock, sealer).closeWithoutTerminal(runId, end)
        assertInstanceOf(SupervisedRunClosure.Closed::class.java, closure, "the closer did not close $runId")
        val admission = admitEventStore(db.toString(), EventStorePurpose.Read)
        val store = (admission as EventStoreAdmission.Admitted).store
        return try {
            store.eventsFor(runId).filterIsInstance<RunFinished>().single()
        } finally {
            store.close()
        }
    }

    private companion object {
        const val RUN: String = "run-1"
        const val OTHER_RUN: String = "run-2"
        const val SCRIPT: String = "/work/config.pipeline.kts"

        val UNRESOLVED = ScriptingDiagnostic(
            ScriptDiagnosticSeverity.ERROR, "Unresolved reference 'undefinedThing'.", 3, 13, SCRIPT,
        )
        val DEPRECATED = ScriptingDiagnostic(
            ScriptDiagnosticSeverity.WARNING, "Use 'toInt' to give the type explicitly.", 1, 11, SCRIPT,
        )
        val NO_RETURN = ScriptingDiagnostic(
            ScriptDiagnosticSeverity.ERROR, "Return type mismatch: expected 'Int', actual 'String'.", 7, 5, SCRIPT,
        )
        val CRASHED = ScriptingDiagnostic(
            ScriptDiagnosticSeverity.FATAL, "The compiler could not analyse the script.", 0, 0, SCRIPT,
        )
    }
}
