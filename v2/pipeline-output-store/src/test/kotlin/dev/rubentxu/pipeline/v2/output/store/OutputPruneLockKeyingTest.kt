package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputPruneIntent
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * B1c / AUD-08 — the writer and [SegmentOutputStore.prune] must key the SAME stream to ONE lock.
 *
 * ## The defect this pins (measured in B1a, `B1aOutputPlaneProviderLifecycleCharacterizationTest`)
 *
 * `prune` locks the stream by its **directory name** (`safe()` folds `/` onto `_`:
 * `run-lock/op-0/transcript` becomes `run-lock_op-0_transcript`), while the writer locks the
 * **raw** [OutputStreamId] (`run-lock/op-0/transcript`). The two keys never match, so releasing a
 * run ADDS a second, permanently retained `ReentrantLock` for a stream whose writer lock is already
 * there. The B1a soak measured `2N` locks after releasing `N` runs, and the fix is expected to make
 * that row read `N`.
 *
 * ## Fidelity + hermeticity
 *
 * HF1 in-process through the production store: [SegmentOutputStore.open] `appendFrom` is the writer
 * path, `prune` is the production retention path, and the count reads the store's own declared
 * private field `perStream` rather than re-deriving a number. Every path is under `@TempDir`; no
 * network, no wall clock, no duration assertion, no `/tmp`.
 *
 * ## The mutation that must kill this
 *
 * Reverting `prune` to key on `OutputStreamId(dir.fileName.toString())`, or reverting the writer to
 * key on the raw id, makes the post-prune count 2 and fails the second assertion. That mutation was
 * run and its RED captured in the B1c receipt.
 */
@Timeout(30)
class OutputPruneLockKeyingTest {

    private fun recoveredStore(root: Path): SegmentOutputStore =
        SegmentOutputStore(root).also { it.recover() }

    /** The store's own per-stream lock count, read from production state, not re-derived. */
    private fun perStreamLockCount(store: SegmentOutputStore): Int {
        val field = try {
            SegmentOutputStore::class.java.getDeclaredField("perStream")
        } catch (missing: NoSuchFieldException) {
            throw AssertionError(
                "the lock-keying probe lost its target: SegmentOutputStore.perStream no longer " +
                    "exists. Re-point the probe; a stored zero would be a fabricated reading.",
                missing,
            )
        }
        field.isAccessible = true
        return (field.get(store) as Map<*, *>).size
    }

    @Test
    fun `the writer and prune share one canonical lock per stream`(@TempDir root: Path) {
        val store = recoveredStore(root)
        val run = "run-lock"
        val stream = OutputStreamId("$run/op-0/transcript")

        store.open(stream).appendFrom(
            ByteArrayInputStream("payload\n".toByteArray(StandardCharsets.UTF_8)),
            windowBytes = 64,
        )

        val locksAfterWrite = perStreamLockCount(store)
        assertEquals(
            1,
            locksAfterWrite,
            "the writer must register exactly one lock for the stream it wrote",
        )

        val report = store.prune(OutputPruneIntent.RunReachedTerminalState(run))
        assertEquals(1, report.streamsRemoved, "the run's stream must actually be pruned")
        assertEquals(
            0,
            report.streamsRetained,
            "the deletion must complete; a resisted deletion would make the lock count below moot",
        )

        val locksAfterPrune = perStreamLockCount(store)
        assertEquals(
            1,
            locksAfterPrune,
            "prune must REUSE the writer's lock, not add a second one for the same stream: " +
                "the writer keys on the raw id and prune used to key on the folded directory name, " +
                "so releasing a run left 2 permanent locks per stream (measured 2N for N runs in " +
                "B1a). observed afterWrite=$locksAfterWrite afterPrune=$locksAfterPrune; a value of " +
                "2 here is the AUD-08 lock-keying defect, not a flaky count.",
        )
    }
}
