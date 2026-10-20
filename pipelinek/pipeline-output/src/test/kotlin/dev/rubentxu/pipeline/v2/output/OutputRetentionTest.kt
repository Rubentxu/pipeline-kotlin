package dev.rubentxu.pipeline.v2.output

import dev.rubentxu.pipeline.v2.output.follow.FollowUntil
import dev.rubentxu.pipeline.v2.output.follow.OutputFollowOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `RetainUntil.authorize` is the only thing standing between "a run finished" and "its output may
 * be deleted". Every other combination has to answer `null`, so the matrix IS the contract: it is
 * written out in full rather than sampled, because a policy that quietly starts authorising a case
 * it should refuse is indistinguishable from data loss until someone reads the diff.
 *
 * There are three policies and two lifecycles; six cells, all pinned, so a future `when` that drops
 * a branch fails here instead of in a prune of live output.
 */
class OutputRetentionTest {

    private val policies = listOf(
        RetainUntil.RunTerminalPlus,
        RetainUntil.ExplicitReleaseOnly,
        RetainUntil.Forever,
    )
    private val lifecycles = listOf(RunLifecycle.Terminal, RunLifecycle.StillRunning)

    // --- the authorisation matrix --------------------------------------------

    @Test
    fun `only RunTerminalPlus on a terminal run authorises a prune`() {
        for (policy in policies) {
            for (lifecycle in lifecycles) {
                val authorises = policy == RetainUntil.RunTerminalPlus && lifecycle == RunLifecycle.Terminal

                assertEquals(
                    if (authorises) OutputPruneIntent.RunReachedTerminalState("run-7") else null,
                    policy.authorize("run-7", lifecycle),
                    "${policy::class.simpleName} against ${lifecycle::class.simpleName}",
                )
            }
        }
    }

    @Test
    fun `a still-running run is never authorised to prune, under any policy`() {
        // The cell that matters most: a run still producing output cannot have its output deleted,
        // however permissive the policy. The type has no case for it, and this is the test that
        // says so -- if a case were ever added, the compiler would stop this test compiling.
        for (policy in policies) {
            assertNull(policy.authorize("run-7", RunLifecycle.StillRunning),
                "${policy::class.simpleName} must not authorise a running run")
        }
    }

    @Test
    fun `Forever authorises nothing even for a finished run`() {
        assertNull(RetainUntil.Forever.authorize("run-7", RunLifecycle.Terminal))
    }

    @Test
    fun `no policy ever claims an operator release`() {
        // `OperatorReleased` carries a `requestedBy`, i.e. it asserts a person approved a
        // deletion. A policy that returned one would be forging that approval. Nothing in this
        // module constructs it either -- it exists for the operator-facing port -- so the matrix
        // above already shows it cannot happen; this names the reason so the gap reads as
        // deliberate rather than as an oversight waiting to be filled in.
        for (policy in policies) {
            for (lifecycle in lifecycles) {
                assertFalse(policy.authorize("run-7", lifecycle) is OutputPruneIntent.OperatorReleased,
                    "${policy::class.simpleName} must not claim an operator release")
            }
        }
    }

    @Test
    fun `authorize passes the run id through unchanged`() {
        // No normalisation and no validation: authorize is a decision function, not a constructor.
        // Pinned so that a future guard added here is a deliberate change, not a silent one.
        assertEquals(
            OutputPruneIntent.RunReachedTerminalState(""),
            RetainUntil.RunTerminalPlus.authorize("", RunLifecycle.Terminal),
            "authorize invents no validity rule for the run id it was handed",
        )
    }

    // --- the two intents it can name -----------------------------------------

    @Test
    fun `the two intents are different claims even about the same run`() {
        val byState = OutputPruneIntent.RunReachedTerminalState("run-7")
        val byOperator = OutputPruneIntent.OperatorReleased("run-7", "someone")

        assertEquals("run-7", byState.runId)
        assertEquals("run-7", byOperator.runId)
        assertEquals("someone", byOperator.requestedBy, "the author travels with the claim")
        assertFalse(byState == byOperator as OutputPruneIntent,
            "a run that ended is not the same claim as a run an operator released")
    }

    @Test
    fun `a prune report accepts zero and rejects nothing, including negative counts`() {
        // Pinned AS-IS: `OutputPruneReport` carries no validation, so a caller can construct a
        // report claiming bytes were released without any having been. That is a carrier, not a
        // decision -- the decision is `OutputPruneIntent` -- so if validation is ever added this
        // test fails and says exactly which invariant moved.
        val empty = OutputPruneReport(streamsRemoved = 0, bytesReleased = 0L, streamsRetained = 0)
        val negative = OutputPruneReport(streamsRemoved = -1, bytesReleased = -1L, streamsRetained = -1)

        assertEquals(0, empty.streamsRemoved, "a no-op prune is a legitimate answer, not an error")
        assertEquals(-1L, negative.bytesReleased, "the report does not police its own numbers today")
    }

    // --- the follow knobs that gate on a loop ---------------------------------

    @Test
    fun `a follow that reads no bytes is a legal goal, a negative one is not`() {
        assertEquals(0L, FollowUntil.UntilBytesRead(0L).limit)
        assertThrows(IllegalArgumentException::class.java) { FollowUntil.UntilBytesRead(-1L) }
    }

    @Test
    fun `the unbounded and sealed goals are different claims`() {
        // `Unbounded` never finishes on its own; `UntilAllSealed` finishes when the run closes its
        // streams. Equality that ignored the type would let a consumer ask for one and receive the
        // other's behaviour.
        assertFalse(FollowUntil.Unbounded == FollowUntil.UntilAllSealed("run-7") as FollowUntil)
        assertEquals(FollowUntil.Unbounded, OutputFollowOptions().until, "unbounded is the default")
        assertEquals(
            FollowUntil.UntilAllSealed("run-7"),
            OutputFollowOptions(until = FollowUntil.UntilAllSealed("run-7")).until,
        )
    }

    @Test
    fun `a follow poll interval of zero or negative is refused`() {
        // The guard's own message gives the reason: a follow that skips the wait re-polls a live
        // store connection in a tight loop, and that loop runs inside a native call where an
        // interrupt is never observed. A caller passing 0 is asking for that spin.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            OutputFollowOptions(pollIntervalMs = 0L)
        }
        assertTrue(ex.message?.contains("pollIntervalMs") == true, "message names the field: ${ex.message}")

        assertThrows(IllegalArgumentException::class.java) { OutputFollowOptions(pollIntervalMs = -1L) }
    }

    @Test
    fun `the remaining follow knobs refuse zero and a negative resume ordinal`() {
        // pageMaxBytes=0 and maxRecords=0 both produce a follow that reads nothing and returns
        // nothing, which is indistinguishable from a hang; afterOrdinal is an ordinal, so a
        // negative one has no byte it could mean.
        assertThrows(IllegalArgumentException::class.java) { OutputFollowOptions(pageMaxBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { OutputFollowOptions(maxRecords = 0) }
        assertThrows(IllegalArgumentException::class.java) { OutputFollowOptions(afterOrdinal = -1L) }

        assertEquals(0L, OutputFollowOptions(afterOrdinal = 0L).afterOrdinal, "zero is a legal ordinal")
        assertNull(OutputFollowOptions().afterOrdinal, "null resumes from the beginning")
    }

    @Test
    fun `the documented follow defaults are the ones the options type carries`() {
        // These four numbers are the ones `pipeline observe --follow` shipped with. They are
        // duplicated in three places in this module's docs; pinning them here means a default that
        // drifts shows as one failing assertion naming the new value, not as a behaviour change
        // noticed in production.
        val defaults = OutputFollowOptions()

        assertEquals(64 * 1024, defaults.pageMaxBytes)
        assertEquals(25L, defaults.pollIntervalMs)
        assertEquals(256, defaults.maxRecords)
        assertTrue(defaults.includeFrames, "frames are on unless a consumer opts out")
        assertTrue(defaults.streams.isEmpty(), "an empty selection means every declared stream")
    }
}
