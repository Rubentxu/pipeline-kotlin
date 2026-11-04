package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptOrigin
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the console says when the cache served the run.
 *
 * ## The guarantee
 *
 * The line the console prints about a compilation states WHERE the executable
 * form came from: compiled here, or reused from a store an earlier run
 * wrote.
 *
 * ## The failure this covers
 *
 * Measured with the default cache: a cold run of a script costs 5,1 s and
 * compiles; the next one costs 1,2 s, does NOT rewrite the compiled jar (same
 * name, same size, same moment), and still printed "Compiling pipeline". The
 * operator had no way to tell a working cache from a cache that does not
 * exist: every run announced a compilation, so the cache's effect on the
 * run was invisible.
 *
 * The event was published at the TOP of the compilation, before the cache was
 * consulted, so the line was truthful about the PHASE and lying about the
 * FACT. The fix moves the announcement to the point where the answer exists.
 *
 * ## Why the closing line carries the outcome too
 *
 * The line that opens the phase states the DECISION. The line that closes it
 * states the OUTCOME, and the two differ in exactly one case: a stored
 * artifact that loads but cannot be evaluated, which makes the run compile
 * after all. A console that only printed the decision would claim a reuse the
 * run did not perform; a console that prints both shows the divergence
 * instead of hiding it.
 *
 * ## Why the instrument is this cheap
 *
 * The renderer is a pure projection over a list of events. No process, no
 * clock, no file: the cheapest thing that can fail is a call with a list.
 */
class CompilationOriginConsoleTest {

    @Test
    fun `a compilation that reused a stored artifact does not announce a compilation`() {
        val console = HumanConsoleRenderer.render(
            listOf(compilationStarted(ScriptOrigin.REUSED)),
            ObservationView.EVENTS,
        )

        assertTrue(
            console.contains("Reusing compiled pipeline"),
            "the console does not say the cache served the run: $console",
        )
        assertFalse(
            console.contains("Compiling pipeline"),
            "the console announces a compilation the run did not perform: $console",
        )
    }

    @Test
    fun `a compilation that compiled the source announces a compilation`() {
        val console = HumanConsoleRenderer.render(
            listOf(compilationStarted(ScriptOrigin.COMPILED)),
            ObservationView.EVENTS,
        )

        assertTrue(
            console.contains("Compiling pipeline"),
            "the console does not say the compiler ran: $console",
        )
        assertFalse(
            console.contains("Reusing compiled pipeline"),
            "the console announces a reuse the run did not perform: $console",
        )
    }

    /**
     * A record that does not say claims nothing. "Preparing pipeline" is the
     * honest rendering of a durable row written before the origin existed: it
     * never says a compiler ran, because the row cannot prove it.
     */
    @Test
    fun `a compilation whose origin is not recorded claims nothing`() {
        val console = HumanConsoleRenderer.render(
            listOf(compilationStarted(ScriptOrigin.UNKNOWN)),
            ObservationView.EVENTS,
        )

        assertTrue(
            console.contains("Preparing pipeline"),
            "the console does not say what it does not know: $console",
        )
        assertFalse(
            console.contains("Compiling pipeline"),
            "an unrecorded origin was read as a compilation: $console",
        )
        assertFalse(
            console.contains("Reusing compiled pipeline"),
            "an unrecorded origin was read as a reuse: $console",
        )
    }

    /**
     * The closing line states what really happened, so the case where the
     * decision and the outcome differ is visible in the transcript.
     */
    @Test
    fun `the closing line states the outcome of the compilation`() {
        val reused = HumanConsoleRenderer.render(
            listOf(compilationFinished(ScriptOrigin.REUSED)),
            ObservationView.EVENTS,
        )
        val compiled = HumanConsoleRenderer.render(
            listOf(compilationFinished(ScriptOrigin.COMPILED)),
            ObservationView.EVENTS,
        )

        assertTrue(
            reused.contains("reused"),
            "the closing line does not say the artifact was reused: $reused",
        )
        assertTrue(
            compiled.contains("compiled"),
            "the closing line does not say the compiler ran: $compiled",
        )
        assertFalse(
            reused.contains("compiled"),
            "a reused compilation is reported as a compilation: $reused",
        )
    }

    private fun compilationStarted(origin: ScriptOrigin): DomainEvent = CompilationStarted(
        eventId = "e-1",
        runId = "run-1",
        sequence = 0L,
        occurredAt = Instant.EPOCH,
        origin = origin,
    )

    private fun compilationFinished(origin: ScriptOrigin): DomainEvent = CompilationFinished(
        eventId = "e-2",
        runId = "run-1",
        sequence = 1L,
        occurredAt = Instant.EPOCH,
        cacheKey = CacheKey("hash", CacheKey.V3),
        diagnostics = emptyList(),
        origin = origin,
    )
}
