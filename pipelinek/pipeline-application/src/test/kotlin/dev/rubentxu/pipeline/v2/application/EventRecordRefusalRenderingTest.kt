package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What the operator reads when the event lane refuses.
 *
 * The three refusals have three different remedies — stop looking for the run, reset the cursor,
 * or deal with the store — so the text has to carry the difference. A rendering that folded them
 * together would throw away the only thing the refusal knew, and the operator would be left
 * guessing which of the three things went wrong.
 */
class EventRecordRefusalRenderingTest {

    @Test
    @DisplayName("a run that does not exist says so, and names the run")
    fun aRunThatDoesNotExistSaysSo() {
        val text = EventRecordReadRefusal.UnknownRun("run-1").render()

        assertTrue(text.contains("unknown run"), "the text must name the refusal: $text")
        assertTrue(text.contains("run-1"), "the text must name the run: $text")
    }

    @Test
    @DisplayName("a cursor past the tail says so, with both positions")
    fun aCursorPastTheTailSaysSo() {
        val text = EventRecordReadRefusal.CursorBeyondTail(
            runId = "run-1",
            requestedSequence = 99L,
            tailSequence = 12L,
        ).render()

        assertTrue(text.contains("past the tail"), "the text must name the refusal: $text")
        assertTrue(text.contains("99"), "the text must name the requested position: $text")
        assertTrue(text.contains("12"), "the text must name the tail: $text")
    }

    @Test
    @DisplayName("a storage failure says so, and carries the cause")
    fun aStorageFailureSaysSo() {
        val text = EventRecordReadRefusal.StorageError("the disk went away").render()

        assertTrue(text.contains("event store failed"), "the text must name the refusal: $text")
        assertTrue(text.contains("the disk went away"), "the text must carry the cause: $text")
    }

    @Test
    @DisplayName("the three refusals do not read alike")
    fun theThreeRefusalsDoNotReadAlike() {
        val texts = listOf(
            EventRecordReadRefusal.UnknownRun("run-1").render(),
            EventRecordReadRefusal.CursorBeyondTail("run-1", 99L, 12L).render(),
            EventRecordReadRefusal.StorageError("boom").render(),
        )

        assertEquals(texts.size, texts.toSet().size, "each refusal must read differently: $texts")
    }
}
