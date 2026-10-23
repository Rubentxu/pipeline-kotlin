package dev.rubentxu.pipeline.v2.events.follow

import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The follow refusal vocabulary, observed where a refusal is translated.
 *
 * The follow contract is published (`events.follow.v1`, PUBLISHED), so its refusal
 * vocabulary is a contract an external consumer reads. The read port underneath it already
 * distinguishes three facts — the run does not exist, the cursor is past the tail, the store
 * failed — and the translation between them used to fold all three into "the run does not
 * exist". A consumer that cannot tell a failed store from a missing run will answer the wrong
 * question: it will look for a run that was never there, or retry a read that cannot succeed.
 *
 * These are values, so the test is values: no store, no disk, no clock.
 */
class EventFollowRefusalTranslationTest {

    @Test
    @DisplayName("a run that does not exist stays a run that does not exist")
    fun aRunThatDoesNotExistStaysARunThatDoesNotExist() {
        val translated = EventFollowRefusal.of(EventRecordReadRefusal.UnknownRun("run-1"))

        assertEquals(EventFollowRefusal.UnknownRun("run-1"), translated)
    }

    @Test
    @DisplayName("a cursor past the tail is not a run that does not exist")
    fun aCursorPastTheTailIsNotARunThatDoesNotExist() {
        val translated = EventFollowRefusal.of(
            EventRecordReadRefusal.CursorBeyondTail(
                runId = "run-1",
                requestedSequence = 99L,
                tailSequence = 12L,
            ),
        )

        assertEquals(
            EventFollowRefusal.CursorBeyondTail(
                runId = "run-1",
                requestedSequence = 99L,
                tailSequence = 12L,
            ),
            translated,
        )
    }

    @Test
    @DisplayName("a storage failure is not a run that does not exist")
    fun aStorageFailureIsNotARunThatDoesNotExist() {
        val translated = EventFollowRefusal.of(EventRecordReadRefusal.StorageError("disk went away"))

        assertEquals(EventFollowRefusal.StorageError("disk went away"), translated)
    }
}
