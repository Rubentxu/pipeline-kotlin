package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.durable.EventStoreRefusal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What the operator reads when the event store cannot be opened.
 *
 * These four refusals have four different remedies — point at the right file, create the
 * directory, hand over a file SQLite can open, or point at a file that holds events at all — so
 * the text has to carry the difference. A rendering that folded them together would throw away
 * the only thing the refusal knew, and the operator would be left guessing which of the four
 * things went wrong.
 *
 * The text is a public contract: operators and scripts grep it, and `events` has printed
 * `db not found` since it first read a run.
 */
class EventStoreRefusalRenderingTest {

    private val path = "/runs/run-42/events.db"

    @Test
    @DisplayName("a file that is not there says so, and names the file")
    fun aFileThatIsNotThereSaysSo() {
        val text = EventStoreRefusal.Absent(path).render()

        assertTrue(text.contains("db not found"), "the text must name the refusal: $text")
        assertTrue(text.contains(path), "the text must name the path: $text")
    }

    @Test
    @DisplayName("a missing directory says so, and names the path")
    fun aMissingDirectorySaysSo() {
        val text = EventStoreRefusal.NoDirectory(path).render()

        assertTrue(text.contains("no directory"), "the text must name the refusal: $text")
        assertTrue(text.contains(path), "the text must name the path: $text")
    }

    @Test
    @DisplayName("a file SQLite cannot open says so, and carries SQLite's own cause")
    fun aFileSqliteCannotOpenSaysSo() {
        val text = EventStoreRefusal.NotReadable(path, "file is not a database").render()

        assertTrue(text.contains("not readable"), "the text must name the refusal: $text")
        assertTrue(text.contains("file is not a database"), "the text must carry the cause: $text")
        assertTrue(text.contains(path), "the text must name the path: $text")
    }

    @Test
    @DisplayName("a database with no events in it says so, and names the path")
    fun aDatabaseWithNoEventsInItSaysSo() {
        // The reader pointed at the wrong file has to be told WHICH file holds no history.
        // Answering "unknown run" would send the operator after the run instead of the file.
        val text = EventStoreRefusal.NoEventHistory(path).render()

        assertTrue(text.contains("no event history"), "the text must name the refusal: $text")
        assertTrue(text.contains(path), "the text must name the path: $text")
    }

    @Test
    @DisplayName("the four refusals do not read alike")
    fun theFourRefusalsDoNotReadAlike() {
        val texts = listOf(
            EventStoreRefusal.Absent(path).render(),
            EventStoreRefusal.NoDirectory(path).render(),
            EventStoreRefusal.NotReadable(path, "boom").render(),
            EventStoreRefusal.NoEventHistory(path).render(),
        )

        assertEquals(texts.size, texts.toSet().size, "each refusal must read differently: $texts")
    }
}
