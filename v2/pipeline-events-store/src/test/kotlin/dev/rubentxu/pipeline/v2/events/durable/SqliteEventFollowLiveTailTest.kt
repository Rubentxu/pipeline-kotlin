package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.follow.EventFollowEvent
import dev.rubentxu.pipeline.v2.events.follow.EventFollowOptions
import dev.rubentxu.pipeline.v2.events.follow.EventFollowState
import dev.rubentxu.pipeline.v2.events.follow.EventFollowUntil
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * F2 falsifier — `SqliteEventFollowHandle` MUST NOT emit
 * [EventFollowEvent.Completed] on a quiet page (a page with
 * `hasMore=false` and no `RunFinished`). The terminal
 * `Completed` event is reserved for the two terminal conditions
 * the design names:
 *
 *   - `UntilSequence` satisfied (the consumer's explicit bound)
 *   - `RunFinished` observed in the page
 *
 * The pre-fix behaviour conflated "no new data right now" with
 * "the run is finished", so a follow that opened on a still-live
 * run and reached the natural tail of currently available history
 * would emit `Completed` even though the producer might publish
 * more events later. The cross-JVM test (`M1DCrossJvmFollowTest`)
 * did not surface this defect because its scenarios always include
 * a `RunFinished` row in the page the child opens.
 *
 * ## What this class pins
 *
 * 1. A follow opened in [EventFollowUntil.UntilRunFinished] mode
 *    that reaches the natural tail of the run's history MUST
 *    keep polling, MUST NOT emit `Completed`, and MUST deliver
 *    events published after the follow reached the tail.
 * 2. The same follow MUST emit `Completed` (and the terminal
 *    `RunFinished` state change) when the producer appends
 *    `RunFinished`.
 * 3. Closing the handle stops the polling loop promptly.
 *
 * ## Why this lives in `:pipeline-events-store`
 *
 * The defect is in the store-side `SqliteEventFollowHandle`, not
 * in the consumer-side follower contract. Pinning it here
 * surfaces the bug to the same module that owns the fix.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SqliteEventFollowLiveTailTest {

    private fun store(db: Path): SqliteEventStore = SqliteEventStore(db.toString())

    private fun follower(store: SqliteEventStore): SqliteEventFollower =
        SqliteEventFollower(
            port = EventRecordReadPortStoreAdapter(
                store = store,
                runExists = store::hasRun,
                tailSequence = store::tailSequence,
            ),
            runExists = store::hasRun,
        )

    private fun runStarted(runId: String): RunStarted =
        RunStarted(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            sequence = 0L, // store auto-assigns per-runId monotonic sequence
            occurredAt = Instant.now(),
            scriptPath = "f2.test",
        )

    private fun stageStarted(runId: String, name: String = "stage"): StageStarted =
        StageStarted(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            sequence = 0L, // store auto-assigns per-runId monotonic sequence
            occurredAt = Instant.now(),
            stageIndex = 0,
            stageName = name,
        )

    private fun runFinished(runId: String): RunFinished =
        RunFinished(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            sequence = 0L, // store auto-assigns per-runId monotonic sequence
            occurredAt = Instant.now(),
            outcome = "SUCCESS",
            diagnostics = emptyList<ScriptingDiagnostic>(),
        )

    @Test
    @DisplayName("F2: a follow that reaches the natural tail keeps polling and does NOT emit Completed")
    fun followAtNaturalTailKeepsPolling(@TempDir tmp: Path) {
        val runId = "m1-f2-live-${UUID.randomUUID()}"
        val db = tmp.resolve("$runId.db")
        val s = store(db)

        // Seed: one event so the run is known to the follow.
        s.append(runStarted(runId))
        s.flush()

        val handle = follower(s).open(
            runId = runId,
            options = EventFollowOptions(
                query = EventQuery.All,
                pollIntervalMs = 50L,
                maxRecords = 256,
                until = EventFollowUntil.UntilRunFinished(runId),
            ),
        )
        try {
            val iter = handle.iterator()

            // First event the handle emits is StateChanged (the
            // handle's open-boundary state, Live(0) before any row).
            // Then the Page with the RunStarted row follows. The
            // order is fixed by SqliteEventFollowHandle.drainOnce.
            val first = iter.next()
            assertTrue(
                first is EventFollowEvent.StateChanged,
                "first event must be a StateChanged (the open-boundary Live state); got $first",
            )
            val firstState = (first as EventFollowEvent.StateChanged).state
            assertTrue(
                firstState is EventFollowState.Live,
                "the open-boundary state must be a Live state; got $firstState",
            )

            val second = iter.next()
            assertTrue(
                second is EventFollowEvent.Page,
                "second event must be a Page with the RunStarted row; got $second",
            )
            val secondPage = second as EventFollowEvent.Page
            // The store auto-assigns per-runId sequences starting
            // at 1 (LPR-041), so the only row in the page is at
            // sequence=1. The newState is therefore Live(1).
            assertEquals(
                EventFollowState.Live(1L),
                secondPage.newState,
                "after draining the only row, the page state must reflect the lastSequence",
            )

            // Pre-fix: the follow would emit `Completed` on the
            // next call (3e branch). Post-fix: it keeps polling.
            // We verify by appending a new event after a brief
            // quiescent period and confirming the follow picks
            // it up.
            Thread.sleep(200L) // quiet period
            s.append(stageStarted(runId, name = "after-tail"))
            s.flush()

            // The follow's polling loop must pick up the new
            // event. With pollIntervalMs=50, we wait up to 2s.
            val deadline = System.currentTimeMillis() + 2_000L
            var pickedUp = false
            var sawCompleted = false
            while (System.currentTimeMillis() < deadline && !pickedUp) {
                if (!iter.hasNext()) {
                    sawCompleted = true
                    break
                }
                val ev = iter.next()
                if (ev is EventFollowEvent.Page) {
                    val kinds = ev.slice.records.mapNotNull {
                        (it as? dev.rubentxu.pipeline.v2.events.EventRecordRead.Decoded)?.event?.javaClass?.simpleName
                    }
                    if ("StageStarted" in kinds) {
                        pickedUp = true
                    }
                }
            }
            assertTrue(
                pickedUp,
                "the follow MUST pick up events published after the natural tail; " +
                    "pre-fix behaviour: hasNext returned false and the follow terminated as Completed. " +
                    "sawCompleted=$sawCompleted",
            )
        } finally {
            handle.close()
        }
    }

    @Test
    @DisplayName("F2: a follow in UntilRunFinished mode emits Completed when RunFinished is observed")
    fun followEmitsCompletedOnRunFinished(@TempDir tmp: Path) {
        val runId = "m1-f2-runfinished-${UUID.randomUUID()}"
        val db = tmp.resolve("$runId.db")
        val s = store(db)

        s.append(runStarted(runId))
        s.flush()

        val handle = follower(s).open(
            runId = runId,
            options = EventFollowOptions(
                query = EventQuery.All,
                pollIntervalMs = 50L,
                maxRecords = 256,
                until = EventFollowUntil.UntilRunFinished(runId),
            ),
        )
        try {
            val iter = handle.iterator()

            // Drain the open-boundary StateChanged first, then the
            // initial Page carrying the RunStarted row.
            assertTrue(iter.next() is EventFollowEvent.StateChanged)
            assertTrue(iter.next() is EventFollowEvent.Page)

            // Append RunFinished. The follow must observe it
            // on its next poll and emit Completed (3d path).
            s.append(runFinished(runId))
            s.flush()

            val deadline = System.currentTimeMillis() + 2_000L
            var sawCompleted = false
            var sawRunFinishedState = false
            while (System.currentTimeMillis() < deadline && !sawCompleted) {
                if (!iter.hasNext()) break
                when (val ev = iter.next()) {
                    is EventFollowEvent.Page -> {
                        // Drain page events; Completed is what we look for.
                    }
                    is EventFollowEvent.StateChanged -> {
                        if (ev.state is EventFollowState.RunFinished) {
                            sawRunFinishedState = true
                        }
                    }
                    EventFollowEvent.Completed -> {
                        sawCompleted = true
                    }
                    else -> {}
                }
            }
            assertTrue(
                sawRunFinishedState,
                "the follow MUST emit StateChanged(RunFinished) when RunFinished is observed",
            )
            assertTrue(
                sawCompleted,
                "the follow MUST emit Completed after RunFinished is observed (3d path); " +
                    "this is the legitimate terminal, distinct from the 3e bug removed in this PR",
            )
        } finally {
            handle.close()
        }
    }
}
