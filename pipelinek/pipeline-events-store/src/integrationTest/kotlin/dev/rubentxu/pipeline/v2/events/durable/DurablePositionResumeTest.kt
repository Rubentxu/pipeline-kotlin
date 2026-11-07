package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.durable.BranchSpec
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.JoinPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame
import dev.rubentxu.pipeline.v2.runtime.durable.BranchExecutionResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The durable POSITION of a run: the place a run resumes from.
 *
 * ## The guarantee
 *
 * The position a run reached is the position the NEXT reader of the file finds. Three facts make
 * that true, and they are three different failures:
 *
 * - the position OUTLIVES the instance that wrote it. A run that is resumed is resumed by
 *   something else: another process, or the same one after it restarted. The position is on the
 *   file, and the file is what the next reader reads.
 * - the position survives in the LOG. A writer that died leaves its write-ahead log behind, and
 *   the committed frame of the position lives in that log until it is folded into the file. A
 *   reader that ignores the log finds nothing, and a run that had already done work starts it
 *   again.
 * - the position does not go BACKWARDS. A write that arrives late, carrying a stage the run had
 *   already passed, must not move the position there: the run would resume at a stage it had
 *   already done, and it would do that work twice.
 *
 * ## Why these facts are the resume
 *
 * "Resuming from the same place" is not a property of the run; it is a property of the position
 * the run left behind. A position that is lost takes the resume with it, a position that is
 * stranded in a log nobody reads takes the resume with it, and a position that goes backwards
 * makes the resume land in the wrong place. None of the three is visible while everything is
 * running in one process, which is why all three are stated here over a file that outlives the
 * writer.
 *
 * ## Why the LOG is a fact of its own
 *
 * The position is not in the file and in the log: it is in ONE of them, and which one depends on
 * when the writer went away. A writer that closed cleanly folded its log into the file; a writer
 * that died did not, and the frame the position is made of is still in the log. The two states
 * are different files, and a reader has to find the position in both. That is why one row copies
 * the file as a clean shutdown leaves it and another copies it as a crash leaves it, and why the
 * second row reads through a door the engine refuses to write through: that door cannot recover
 * the log by writing to it, so the position it finds came from the log or from nowhere.
 *
 * ## Why this is an INTEGRATION test
 *
 * The subject is what the FILE holds and what the DATABASE ENGINE will read back. A double would
 * answer whatever it was told, which is exactly the fact under test. The engine is also the one
 * that decides whether a write survives its writer: a write-ahead log that a dead writer left
 * behind is read by the engine or by nobody.
 *
 * ## What the fakes buy
 *
 * Only the clock. The store, the connections, the doors, the schema, the file and the engine are
 * the real ones.
 */
class DurablePositionResumeTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * A clock that says the same instant, so the positions this test writes are comparable.
     */
    private class FixedClock : Clock {
        override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
    }

    private fun dbPath(): String = tempDir.resolve("pipeline.db").toString()

    /**
     * The position store over the given store's connections.
     */
    private fun positionsOf(store: SqliteEventStore): SqliteReplayCursorStoreImpl =
        SqliteReplayCursorStoreImpl(store.durableConnections, FixedClock())

    @Test
    fun `the position a run reached is the position the next instance finds`() {
        val file = dbPath()

        // The instance that does the work, and then goes away. The restart is not a detail of the
        // scenario: it is the shape the guarantee is about, because the position has to outlive
        // the connections that wrote it.
        val first = SqliteEventStore(file)
        positionsOf(first).advance("run-1", "op-7", 3)
        first.close()

        // The instance that comes after: its own connections, over the same file.
        val second = SqliteEventStore(file)
        val position = positionsOf(second).load("run-1")

        assertNotNull(
            position,
            "the position the first instance reached must outlive it: a run that resumes finds " +
                "where it stopped, and the position that says so is on the file, not in a " +
                "connection that is gone",
        )
        assertEquals(
            "op-7",
            position?.lastOpId,
            "the operation the position names is the one that was journaled",
        )
        assertEquals(
            3,
            position?.stageIndex,
            "the stage the position names is the stage the run reached",
        )
        second.close()
    }

    @Test
    fun `the position survives in the log the writer left behind`() {
        val file = dbPath()

        // A writer that DIED: the database file and the log that goes with it are copied to a
        // directory of their own while the writer is still alive, so what the copy holds is what
        // a killed process leaves on disk. The committed frame of the position is in that log and
        // is not yet folded into the file.
        val writer = SqliteEventStore(file)
        positionsOf(writer).advance("run-1", "op-7", 3)
        val leftBehind = copyTheFileAndItsLog(file)
        writer.close()

        // The reader that comes after the crash. It reads through the door the engine refuses to
        // write through, which is the door that cannot recover the log by writing to it: what
        // this reader finds, it found in the log.
        val reader = SqliteEventStore(leftBehind, mode = EventStoreMode.ReadOnly)
        val position = positionsOf(reader).load("run-1")

        assertNotNull(
            position,
            "a committed position must survive the writer that wrote it: the log holds it until " +
                "it is folded into the file, and a reader that finds no position resumes a run " +
                "that had already done work",
        )
        assertEquals(
            3,
            position?.stageIndex,
            "the stage the position names is the stage the run reached",
        )
        reader.close()
    }

    @Test
    fun `a late advance does not move the position back`() {
        val file = dbPath()
        val store = SqliteEventStore(file)
        val positions = positionsOf(store)

        positions.advance("run-1", "op-9", 5)

        // The write that arrives late, carrying a stage the run had already passed.
        positions.advance("run-1", "op-4", 2)

        val position = positions.load("run-1")
        assertNotNull(
            position,
            "the position the advances wrote must be there to be compared: a position that is " +
                "not there cannot have been moved back",
        )
        assertEquals(
            5,
            position?.stageIndex,
            "a write carrying an older stage must not move the position: the run would resume at " +
                "a stage it had already done, and do that work twice",
        )
        assertEquals(
            "op-9",
            position?.lastOpId,
            "and the operation the position names is still the one of the stage it is at",
        )
        store.close()
    }

    @Test
    fun `the barrier of a parallel frame is the stage of its furthest branch, and a late frame does not move it back`() {
        val file = dbPath()
        val store = SqliteEventStore(file)
        val positions = positionsOf(store)
        val frame = ParallelFrame(
            branches = listOf(BranchSpec(name = "branch-a", steps = emptyList())),
            joinPolicy = JoinPolicy.ALL_COMPLETE,
        )

        positions.advancePastParallelFrame(
            runId = "run-1",
            frame = frame,
            branchResults = listOf(
                BranchExecutionResult(branchIndex = 0, stageIndex = 3),
                BranchExecutionResult(branchIndex = 1, stageIndex = 7),
            ),
            explicitMaxStageIndex = null,
        )

        // The barrier is a position of its own, under a key of its own: the frame is a join, and
        // the position that says the join happened is not the position of the run.
        val barrier = positions.load("run-1:parallel")
        assertNotNull(
            barrier,
            "advancing past a parallel frame must leave the barrier of that frame on the file: " +
                "the run resumes from the join, and the position that says the join happened is " +
                "where the run looks for it",
        )
        assertEquals(
            7,
            barrier?.stageIndex,
            "the barrier sits at the stage of the FURTHEST branch: the frame is done when its " +
                "slowest branch is done, and the slowest branch is the one that decides",
        )
        assertEquals(
            "run-1:parallel-completed",
            barrier?.lastOpId,
            "the operation the barrier names is the completion of the frame it is the barrier of",
        )

        // The frame that arrives late, carrying stages the run had already passed.
        positions.advancePastParallelFrame(
            runId = "run-1",
            frame = frame,
            branchResults = listOf(BranchExecutionResult(branchIndex = 0, stageIndex = 2)),
            explicitMaxStageIndex = null,
        )

        val barrierAfterTheLateFrame = positions.load("run-1:parallel")
        assertNotNull(
            barrierAfterTheLateFrame,
            "the barrier must be there to be compared: a barrier that is not there cannot have " +
                "been moved back",
        )
        assertEquals(
            7,
            barrierAfterTheLateFrame?.stageIndex,
            "a late frame must not move the barrier back: the run would resume at a join it had " +
                "already crossed, and the branches of that join would run again",
        )
        store.close()
    }

    /**
     * Copies the database file and the files that go with it, the way a file left on disk by a
     * process that died is left.
     *
     * The copy is taken while the writer is alive, so the log holds the committed frames the
     * writer wrote and the database file does not hold them yet. That is the state a reader finds
     * after a crash, and it is not a state a clean shutdown leaves behind: closing folds the log
     * into the file and takes the log away.
     */
    private fun copyTheFileAndItsLog(file: String): String {
        val source = Path.of(file)
        val target = tempDir.resolve("left-behind").resolve(source.fileName)
        Files.createDirectories(target.parent)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        listOf("-wal", "-shm").forEach { suffix ->
            val companion = Path.of(file + suffix)
            if (Files.exists(companion)) {
                Files.copy(
                    companion,
                    Path.of(target.toString() + suffix),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
        return target.toString()
    }
}
