package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The guarantee: the streams a reader lists for a run are the streams the run has declared NOW,
 * including the ones another process declared after the reader first asked.
 *
 * The capacity this serves: a service that follows the output of a run whose child writes from a
 * process of its own. The service asks which streams the run has before the child has declared
 * any, and a list that stays as it was on that first question tells the follower that a run with
 * open output has none.
 *
 * ## Why two indexes over one root
 * Two indexes that share a directory and nothing else are what two processes are: neither sees the
 * other's memory, and the `.streams` file is the only thing they have in common.
 */
class DeclaredStreamsOfAnotherProcessTest {

    @TempDir
    lateinit var root: Path

    private val runId = "run-1"

    /** A store over [dir] that no pin holds back: the rows are about the index, not about retention. */
    private fun storeOver(dir: Path, recoveryPermitted: Boolean = true): SegmentOutputStore =
        SegmentOutputStore(
            root = dir,
            recoveryPermitted = recoveryPermitted,
            pinConsult = object : DirectoryPinConsult {
                override fun pinsOfDirectory(directoryName: String): DirectoryPinAnswer =
                    DirectoryPinAnswer.Answered(emptyList())
            },
        )

    private val stdout = OutputStreamAddress.of(runId, "run-1-s0-0", OutputChannel.STDOUT).stream

    private val stderr = OutputStreamAddress.of(runId, "run-1-s0-0", OutputChannel.STDERR).stream

    @Test
    fun `a stream another process declares after the first question is listed by the next one`() {
        val reader = storeOver(root, recoveryPermitted = false).frameIndex()
        val writer = storeOver(root).frameIndex()
        assertEquals(
            emptyList<Any>(),
            reader.streamsOfRun(runId),
            "nothing has been declared yet, so the first question has an empty answer",
        )

        writer.declareStream(stdout, OutputChannel.STDOUT)

        assertEquals(
            listOf(stdout),
            reader.streamsOfRun(runId),
            "the run declared a stream after the reader first asked, and the reader's next answer " +
                "is about the run as it is now",
        )
    }

    @Test
    fun `a second stream another process declares is listed beside the first`() {
        val reader = storeOver(root, recoveryPermitted = false).frameIndex()
        val writer = storeOver(root).frameIndex()
        writer.declareStream(stdout, OutputChannel.STDOUT)
        assertEquals(listOf(stdout), reader.streamsOfRun(runId))

        writer.declareStream(stderr, OutputChannel.STDERR)

        assertEquals(
            listOf(stderr, stdout).sortedBy { it.value },
            reader.streamsOfRun(runId),
            "a reader that has already listed a run's streams still lists the one that came later",
        )
    }

    @Test
    fun `a stream this index declares itself is listed once`() {
        val index = storeOver(root).frameIndex()

        index.declareStream(stdout, OutputChannel.STDOUT)
        index.declareStream(stdout, OutputChannel.STDOUT)

        assertEquals(
            listOf(stdout),
            index.streamsOfRun(runId),
            "declaring a stream twice leaves one stream, however the answer is kept",
        )
    }
}
