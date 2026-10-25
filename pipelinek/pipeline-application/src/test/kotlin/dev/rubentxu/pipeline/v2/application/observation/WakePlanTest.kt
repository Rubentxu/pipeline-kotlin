package dev.rubentxu.pipeline.v2.application.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The wake plan is one policy with two directions of failure: parking while
 * data flows turns the poll interval into added latency on every chunk, and
 * reading without parking while the plane is quiet turns a follower into a
 * busy loop. Each row pins one direction.
 */
class WakePlanTest {

    @Test
    fun `a read that produced records is followed immediately by the next read`() {
        assertEquals(WakeDecision.ReadFromCursor, wakePlan(recordsInLastRead = 1, pollDeadlineMs = 50))
    }

    @Test
    fun `a quiet read parks with the deadline always armed`() {
        assertEquals(
            WakeDecision.AwaitHintOrDeadline(deadlineMs = 50),
            wakePlan(recordsInLastRead = 0, pollDeadlineMs = 50),
        )
    }
}
