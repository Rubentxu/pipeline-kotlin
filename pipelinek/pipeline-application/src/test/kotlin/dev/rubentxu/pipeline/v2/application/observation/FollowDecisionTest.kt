package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.output.OutputTailState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a `--follow` consumer is promised: it stops exactly when the output lane is
 * final, and not one round earlier.
 *
 * The guarantee is single-valued and lives entirely in [followDecision], which is
 * pure — three inputs, one sealed answer, no clock, no filesystem, no run state.
 * That makes the cheapest instrument that can fail a direct call, and there is no
 * reason to reach for a CLI, a subprocess or an installed distribution to check it.
 *
 * The row that matters is [a sealed stream is not enough while the run is still
 * live]. Sealing every known stream answers "the output plane has nothing
 * pending", not "the run is over": between two steps there is a round in which
 * the previous stream is sealed and the next one has not been declared. Returning
 * `Finished` there ends a follow that still had bytes to deliver — the consumer
 * reports a clean exit over an unfinished run. The other two rows exist because
 * the same answer must also be reachable: without them, the fix for that row
 * could be "always read again", which never terminates anything.
 */
class FollowDecisionTest {
    @Test
    fun `a sealed stream is not enough while the run is still live`() {
        val decision =
            followDecision(
                moreFrames = false,
                tailStates = listOf(OutputTailState.Sealed(finalEnd = 42L)),
                runPlane = RunPlane.Live,
            )

        assertEquals(
            FollowDecision.ReadAgain,
            decision,
            "every known stream is sealed, but the run is live: the next step can still " +
                "declare a stream, so ending here would truncate the follow",
        )
    }

    @Test
    fun `a finished run ends the follow even when the step wrote nothing`() {
        val decision =
            followDecision(
                moreFrames = false,
                tailStates = emptyList(),
                runPlane = RunPlane.Completed,
            )

        assertEquals(
            FollowDecision.Finished,
            decision,
            "a silent step owns no stream, so an empty tail list is the whole evidence; " +
                "the run plane is what confirms the end",
        )
    }

    @Test
    fun `an unanswerable run plane does not strand a console-only follow`() {
        val decision =
            followDecision(
                moreFrames = false,
                tailStates = listOf(OutputTailState.Sealed(finalEnd = 7L)),
                runPlane = RunPlane.Unavailable,
            )

        assertEquals(
            FollowDecision.Finished,
            decision,
            "a lane with no event store cannot be asked whether the run ended; treating " +
                "that as 'keep reading' would hang the consumer forever",
        )
    }

    @Test
    fun `an unsealed or unknown stream still owes bytes whatever the run plane says`() {
        val open = followDecision(false, listOf(OutputTailState.Open(committedEnd = 3L)), RunPlane.Completed)
        val unknown = followDecision(false, listOf(null), RunPlane.Completed)
        val sealedThenOpen =
            followDecision(
                false,
                listOf(OutputTailState.Sealed(finalEnd = 1L), OutputTailState.Open(committedEnd = 9L)),
                RunPlane.Completed,
            )

        assertEquals(FollowDecision.ReadAgain, open, "an open stream still owes bytes")
        assertEquals(FollowDecision.ReadAgain, unknown, "an unknown stream state is not a sealed one")
        assertEquals(FollowDecision.ReadAgain, sealedThenOpen, "one open stream among sealed ones is enough")
    }
}
