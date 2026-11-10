package dev.rubentxu.pipeline.v2.service

import dev.rubentxu.pipeline.v2.domain.AttemptId
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.runtime.inspect.IntrospectionFailure
import dev.rubentxu.pipeline.v2.runtime.inspect.JournalPosition
import dev.rubentxu.pipeline.v2.runtime.inspect.LeaseHolder
import dev.rubentxu.pipeline.v2.runtime.inspect.OutputTailView
import dev.rubentxu.pipeline.v2.runtime.inspect.ProcessRef
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TailState
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The `run.state` document: the observation of a run, as it travels.
 *
 * ## The guarantee under trial
 *
 * A run's state crosses the wire and comes back as the SAME observation it
 * was. Every fact the observation carries survives the trip, and the facts
 * the two sides disagree about — the lease's fencing token, the journal's
 * position, the tails of the output — travel too, because a document that
 * dropped them would make the remote answer a DIFFERENT answer.
 *
 * ## The capacity this serves
 *
 * The command line renders a run's state from this observation, and so does a
 * client that asked a service for it. A consumer that compares the two must
 * find the same fact, and a consumer that watches a run's lease must find the
 * same holder.
 */
class ServiceRunStateTest {

    @Test
    fun `a run in flight travels whole and comes back whole`() {
        val observation = RuntimeObservation.Running(
            attempt = AttemptId(3),
            leaseHolder = LeaseHolder(ownerId = "owner-1", fencingToken = 7L, alive = true),
            fencingToken = 7L,
            journalPosition = JournalPosition(operations = 2, latestOpId = "op-1", latestTerminalAtMs = 100L),
            outputTails = listOf(
                OutputTailView(
                    stream = OutputStreamId("run-1/op-1/stdout"),
                    state = TailState.Open(committedEnd = 120L),
                    lastOrdinal = 4L,
                ),
            ),
            process = ProcessRef(host = "host-1", pid = 42L),
        )

        val decoded = decode(observation)

        assertEquals(observation, decoded)
    }

    @Test
    fun `a run in flight without a holder travels without one`() {
        val observation = RuntimeObservation.Running(
            attempt = AttemptId(1),
            leaseHolder = null,
            fencingToken = 0L,
            journalPosition = JournalPosition(operations = 0, latestOpId = null, latestTerminalAtMs = null),
            outputTails = emptyList(),
            process = null,
        )

        assertEquals(observation, decode(observation))
    }

    @Test
    fun `every terminal outcome travels and comes back`() {
        val terminals = listOf(
            TerminalObservation.Succeeded(999L),
            TerminalObservation.Failed("boom", 999L),
            TerminalObservation.Unstable(999L),
            TerminalObservation.Aborted(999L),
            TerminalObservation.Cancelled(999L),
            TerminalObservation.Other("a-vocabulary-this-build-does-not-know", 999L),
        )

        for (terminal in terminals) {
            val observation = RuntimeObservation.Terminal(AttemptId(1), terminal, 999L)

            assertEquals(observation, decode(observation), "the outcome '$terminal' did not travel whole")
        }
    }

    @Test
    fun `a run that holds no live evidence travels as itself and not as a refusal`() {
        val observation = RuntimeObservation.LiveButEmpty(AttemptId(2), "no live evidence")

        assertEquals(observation, decode(observation))
    }

    @Test
    fun `every substrate failure travels as itself`() {
        val failures = listOf(
            IntrospectionFailure.NoControlRoot,
            IntrospectionFailure.NoEventStore,
            IntrospectionFailure.NoOutputPlane,
            IntrospectionFailure.NoJournal,
            IntrospectionFailure.StorageError("the disk said no"),
        )

        for (failure in failures) {
            val observation = RuntimeObservation.Unobservable(failure)

            assertEquals(observation, decode(observation), "the failure '$failure' did not travel whole")
        }
    }

    @Test
    fun `a document of a state this build does not know is refused and not guessed`() {
        val document = buildJsonObject { put("state", JsonPrimitive("a-state-from-the-future")) }

        val decoded = ServiceRunState.decode(document)

        val unreadable = assertInstanceOf(ServiceRunStateDecoding.Unreadable::class.java, decoded)
        assertEquals(true, unreadable.reason.contains("a-state-from-the-future"))
    }

    @Test
    fun `a document that is missing a fact this build needs is refused`() {
        val document = buildJsonObject {
            put("state", JsonPrimitive(ServiceRunState.RUNNING))
            put("attempt", JsonPrimitive(1))
            // No fencing token, no journal position, no tails: the observation
            // cannot be built out of this.
        }

        val decoded = ServiceRunState.decode(document)

        assertInstanceOf(ServiceRunStateDecoding.Unreadable::class.java, decoded)
    }

    @Test
    fun `a document with a field this build does not know is read anyway`() {
        val document = buildJsonObject {
            put("state", JsonPrimitive(ServiceRunState.EMPTY))
            put("attempt", JsonPrimitive(5))
            put("reason", JsonPrimitive("no live evidence"))
            put("aFieldFromTheFuture", JsonPrimitive("ignored"))
        }

        val decoded = ServiceRunState.decode(document)

        assertEquals(
            RuntimeObservation.LiveButEmpty(AttemptId(5), "no live evidence"),
            assertInstanceOf(ServiceRunStateDecoding.Observed::class.java, decoded).observation,
        )
    }

    @Test
    fun `a document that is not a document at all is refused`() {
        val decoded = ServiceRunState.decode(JsonObject(emptyMap()))

        assertInstanceOf(ServiceRunStateDecoding.Unreadable::class.java, decoded)
    }

    /**
     * The observation [observation] travels as and comes back as.
     */
    private fun decode(observation: RuntimeObservation): RuntimeObservation? {
        val decoded = ServiceRunState.decode(ServiceRunState.encode(observation))
        val observed = assertInstanceOf(ServiceRunStateDecoding.Observed::class.java, decoded)
        return observed.observation
    }
}
