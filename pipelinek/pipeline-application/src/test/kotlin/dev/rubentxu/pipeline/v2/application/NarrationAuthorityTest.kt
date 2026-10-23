package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.CompiledObservationQuery
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.observation.ObservationQuery
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import dev.rubentxu.pipeline.v2.application.observation.SelectorCompileResult
import dev.rubentxu.pipeline.v2.application.observation.compileQuery
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What a run is allowed to say about itself on the console.
 *
 * ## The guarantee
 * The console shows the same bytes the store keeps. Not a summary of it, not a
 * richer version of it: the same text. A run that is watched and a run that is
 * kept are the same run, and an operator reading the terminal is reading the
 * record.
 *
 * ## The failure this covers
 * The narration used to sit ABOVE the redaction, so it rendered the event before
 * the redaction had scrubbed it. The visible consequence was a run that refused
 * to keep a registered secret and printed it anyway: the store was clean, the
 * terminal was not, and the terminal is where a secret ends up — in a CI log, in
 * a scrollback, in a screenshot. The redaction has to happen before the
 * narration, and this is what keeps that order from being rearranged by an
 * edit that looks harmless.
 *
 * ## Why the instrument is this cheap
 * The composition is a pure function of the store it wraps, the format, the view
 * and the registry. Nothing here needs a process, a clock or a file, so the
 * cheapest thing that can fail is a direct call.
 */
class NarrationAuthorityTest {

    @Test
    @DisplayName("the console presents exactly what the store keeps")
    fun `the console presents what the store keeps`() {
        val store = InMemoryEventStore()
        val registry = SecretPatternRegistry()
        registry.addSecretValue(SECRET)
        val presented = mutableListOf<String>()
        val sink = composeNarratingEventStore(
            raw = store,
            policy = NarrationPolicy(ObservationFormat.TEXT, ObservationView.NORMAL, identityQuery()),
            secretPatternRegistry = registry,
            emit = { line -> presented += line },
        )

        sink.append(echo("the value is $SECRET"))

        val kept = store.eventsFor(RUN_ID).single() as EchoOutputCaptured
        assertEquals(kept.content, presented.single())
        assertFalse(presented.single().contains(SECRET))
        assertTrue(presented.single().contains(SCRUB_MARKER))
    }

    @Test
    @DisplayName("a machine format leaves the console to the document")
    fun `a machine format narrates nothing`() {
        val store = InMemoryEventStore()
        val presented = mutableListOf<String>()
        val sink = composeNarratingEventStore(
            raw = store,
            policy = NarrationPolicy(ObservationFormat.JSON, ObservationView.NORMAL, identityQuery()),
            secretPatternRegistry = SecretPatternRegistry(),
            emit = { line -> presented += line },
        )

        sink.append(echo("a plain message"))

        // A JSON consumer parses stdout as a document, so a human line there would
        // corrupt it. The document owns that channel and nothing else speaks on it.
        assertTrue(presented.isEmpty())
        assertEquals(1, store.eventsFor(RUN_ID).count())
    }

    private fun identityQuery(): CompiledObservationQuery =
        (compileQuery(ObservationQuery()) as SelectorCompileResult.Ok).value

    private fun echo(content: String): EchoOutputCaptured =
        EchoOutputCaptured(
            eventId = "e-1",
            runId = RUN_ID,
            sequence = 0L,
            occurredAt = Instant.EPOCH,
            stepIndex = 0,
            content = content,
        )

    private companion object {
        const val RUN_ID = "run-narration-authority"
        const val SECRET = "e2e-fixture-secret-value"
        const val SCRUB_MARKER = "****"
    }
}
