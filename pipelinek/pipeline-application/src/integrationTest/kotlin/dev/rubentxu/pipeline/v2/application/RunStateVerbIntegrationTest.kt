package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.durable.DurableStores
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.runtime.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The run's state, answered twice over the same store.
 *
 * ## The guarantee
 *
 * The application operation that answers about a run and the verb that prints that
 * answer say the SAME thing about the SAME run. They read one store, and the line the
 * operator reads is the line the operation's answer carries. An operation that
 * answered a different fact than the verb prints is a second truth about a run, and
 * the caller that trusted the wrong one would wait for a run that is not coming.
 *
 * ## Why this instrument is an INTEGRATION test
 *
 * The unit lane already knows what the answer IS: `RunStateQueryTest` walks the
 * port's whole refusal vocabulary, and `RunStatePresentationTest` pins every line.
 * What the unit lane cannot know is whether the two halves FIT: the verb composes the
 * port over a real store and reads it, and the operation reads the same port. Only a
 * real store can say whether the operation and the verb still answer the same run.
 *
 * The only thing faked here is the world OUTSIDE the product: the directory the
 * material lives in, which is a temporary directory this test owns.
 *
 * ## What is NOT here, and why
 *
 * The published distribution is not started: that belongs to the harness that
 * certifies the released artifact, and this repository has no layer that boots a
 * product. What this lane asserts is the seam the harness will exercise — the
 * operation and the verb over one store.
 */
class RunStateVerbIntegrationTest {

    @TempDir
    lateinit var root: Path

    /** The durable store the product writes to, and the run's state is read from. */
    private lateinit var db: Path

    private val runId = "run-state-1"

    private val clock: Clock = FixedClock

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        val workspace = Files.createDirectories(root.resolve("workspace"))
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val store = (admission as EventStoreAdmission.Admitted).store
        store.append(RunStarted(
            eventId = "event-1",
            runId = runId,
            sequence = 0L,
            occurredAt = Instant.EPOCH,
            scriptPath = workspace.resolve("pipeline.kts").toString(),
        ))
        store.append(RunFinished(
            eventId = "event-2",
            runId = runId,
            sequence = 0L,
            occurredAt = Instant.EPOCH,
            outcome = "success",
            diagnostics = emptyList(),
        ))
        // The writer is asynchronous: a reader that opened the store now would
        // find a run whose last event had not landed yet.
        store.flush()

        // The run's lease is DECLARED and then GIVEN BACK.
        //
        // The lease record is the authority that says this run exists: a reader
        // that finds no record of it answers "this instance does not know run",
        // and the run's own events are not enough to declare it. The record
        // survives the release, which is what keeps the run known while leaving
        // no live owner behind it.
        val stores = DurableStores.open(store, root, clock)
        val owner = requireNotNull(RunOwnerId.of("owner-of-the-fixture")) {
            "the fixture's owner id is always well formed"
        }
        val identity = requireNotNull(RunId.of(runId)) { "the fixture's run id is always well formed" }
        stores.lease.acquire(LeaseRequest(runId = identity, ownerId = owner))
        stores.lease.release(owner)
        store.close()
    }

    /**
     * The operation and the verb answer the same run with the same fact.
     */
    @Test
    fun `the operation and the verb say the same thing about the same run`() {
        val answer = answerAbout(runId)
        val observed = assertInstanceOf(RunStateAnswer.Observed::class.java, answer).observation
        assertEquals(
            RuntimeObservation.Terminal(
                attempt = dev.rubentxu.pipeline.v2.domain.AttemptId(1),
                terminal = TerminalObservation.Succeeded(0L),
                terminalAtMs = 0L,
            ),
            observed,
            "the run wrote its own bookend, so the operation answers that it finished",
        )

        val asked = ask("inspect", runId, "--db", db.toString(), "--control-root", root.toString(), "--state")

        assertEquals(0, asked.exit.code, "the verb failed: ${asked.stderr}")
        assertEquals(
            InspectionPresentation.renderRunState(observed),
            asked.stdout.trim(),
            "the line the operator reads is the line the operation's answer carries",
        )
        assertEquals(
            "run-state terminal outcome=succeeded at=0",
            asked.stdout.trim(),
            "the run finished in success, and the line says so",
        )
    }

    /**
     * A run the store does not know is refused by both, and neither invents a state.
     */
    @Test
    fun `a run nobody knows is refused by the operation and by the verb`() {
        val answer = answerAbout("run-that-was-never-written")

        assertEquals(
            RunStateAnswer.Refused(RunStateRefusal.UnknownRun("run-that-was-never-written")),
            answer,
            "the run is not known, and the answer says that instead of guessing a state",
        )

        val asked = ask(
            "inspect",
            "run-that-was-never-written",
            "--db",
            db.toString(),
            "--control-root",
            root.toString(),
            "--state",
        )

        assertEquals(
            2,
            asked.exit.code,
            "a refusal travels with the invalid-argument code; the verb printed: " +
                "${asked.stdout} / ${asked.stderr}",
        )
        assertEquals(
            "inspect: this instance does not know run 'run-that-was-never-written'.",
            asked.stderr.trim(),
            "the refusal says that the run is unknown, and it says it on stderr",
        )
        assertEquals(
            "",
            asked.stdout.trim(),
            "a refusal prints no state: a line of state would be a state nobody observed",
        )
    }

    /**
     * What the operation answers about [runId], read over the store this test wrote.
     */
    private fun answerAbout(runId: String): RunStateAnswer {
        val admission = admitEventStore(db.toString(), EventStorePurpose.Read)
        val store = (admission as EventStoreAdmission.Admitted).store
        return store.use { eventStore ->
            RunStateQuery(
                composeRuntimeIntrospectionPort(
                    eventStore = eventStore,
                    controlRoot = root,
                    clock = clock,
                ),
            ).stateOf(runId)
        }
    }

    /**
     * The answer of a verb, and what it printed.
     */
    private data class Answer(val exit: CliExit, val stdout: String, val stderr: String)

    /**
     * Runs the dispatcher over [words] and reads its answer.
     *
     * The dispatcher is the product's own door, so the answer is the answer the
     * operator would get, and the process is not ended by a test.
     */
    private fun ask(vararg words: String): Answer {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8.name())
        val err = PrintStream(errBytes, true, Charsets.UTF_8.name())

        val outcome = handleInventoryVerb(words.asList().toTypedArray(), out, err)
        out.flush()
        err.flush()

        val answered = outcome as? InventoryVerbOutcome.Answered
            ?: error("the verb did not answer at all: $outcome")
        return Answer(answered.exit, outBytes.toString(Charsets.UTF_8), errBytes.toString(Charsets.UTF_8))
    }

    /** The clock this lane stamps its material with: it does not move. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }
}
