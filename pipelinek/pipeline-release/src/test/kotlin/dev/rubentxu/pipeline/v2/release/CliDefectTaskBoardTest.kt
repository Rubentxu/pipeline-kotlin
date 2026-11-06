package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The board that is reached through the CLI that owns it.
 *
 * The board is the only part of the reception that starts a process, and it is
 * the only part that had NO row: everything the reception decides was
 * certified against a board held in memory, so the defects of talking to a
 * real process — a deadline that never starts, an answer nobody bounds, a tree
 * that survives its child — were invisible.
 *
 * The instrument is a FAKE PROCESS and not a double of the board: what this
 * board has to get right is the order in which it talks to the process it
 * started, and a double of the board would hide that order. The board CLI is
 * not installed here, and a row that needed it would be a row about the CLI.
 */
class CliDefectTaskBoardTest {

    @Test
    fun `a board CLI that keeps its pipe open ends in a timeout refusal`() {
        val descendant = FakeProcessHandle()
        val process = FakeBoardProcess(keepsThePipeOpen = true, descendant = descendant)
        val board = board(process)

        // The bound of this row is the CALL, and it is short on purpose: a
        // board that waits for its answer BEFORE arming the deadline spends
        // that wait in the reader, and the row has to see it.
        val failure = assertInstanceOf(
            DefectBoardFailure::class.java,
            within(deadline = 500) { board.taskHolding("label") },
            "a board CLI that never answered was taken for an answer",
        )

        assertTrue(
            failure.detail.contains("did not answer"),
            "the refusal does not say that the board did not answer: ${failure.detail}",
        )
        assertTrue(
            process.treeKilled.get(),
            "the board CLI was not killed after the deadline elapsed",
        )
        assertTrue(
            descendant.destroyed.get(),
            "the process the board CLI started survived it",
        )
    }

    @Test
    fun `a board CLI that fails carries its exit code and what it said`() {
        val process = FakeBoardProcess(answer = "the board refused", exitCode = 3)
        val board = board(process)

        val failure = assertInstanceOf(
            DefectBoardFailure::class.java,
            within(deadline = 5_000) { board.taskHolding("label") },
            "a board CLI that failed was taken for an answer",
        )

        assertTrue(
            failure.detail.contains("exit 3"),
            "the refusal does not carry the code the board exited with: ${failure.detail}",
        )
        assertTrue(
            failure.detail.contains("the board refused"),
            "the refusal does not carry what the board said: ${failure.detail}",
        )
    }

    @Test
    fun `a board that opens a task without saying its identifier is a failure`() {
        val process = FakeBoardProcess(answer = "done")
        val board = board(process)

        val failure = assertInstanceOf(
            DefectBoardFailure::class.java,
            within(deadline = 5_000) { board.openTask(spec()) },
            "a task whose identifier was never said was taken for an opened task",
        )

        assertTrue(
            failure.detail.contains("identifier"),
            "the refusal does not say that the identifier was never said: ${failure.detail}",
        )
    }

    @Test
    fun `a board CLI that says more than the limit is refused`() {
        val process = FakeBoardProcess(answer = "x".repeat(200))
        val board = board(process, outputLimitBytes = 64)

        val failure = assertInstanceOf(
            DefectBoardFailure::class.java,
            within(deadline = 5_000) { board.taskHolding("label") },
            "a board CLI that said too much was taken for an answer",
        )

        assertTrue(
            failure.detail.contains("over the limit of 64 bytes"),
            "the refusal does not say that the board said too much: ${failure.detail}",
        )
        assertTrue(
            failure.detail.contains("said 200 bytes"),
            "the refusal does not say how much the board said: ${failure.detail}",
        )
        assertTrue(
            failure.detail.contains("the first 64 of 200 bytes"),
            "the refusal does not say how much of the answer it quoted: ${failure.detail}",
        )
        assertFalse(
            failure.detail.contains("x".repeat(65)),
            "the refusal quotes the whole answer of a board that said too much",
        )
    }

    @Test
    fun `a board whose answer cannot be read is a failure and not an answer`() {
        val process = FakeBoardProcess(failsWhileReading = true)
        val board = board(process)

        val failure = assertInstanceOf(
            DefectBoardFailure::class.java,
            within(deadline = 5_000) { board.taskHolding("label") },
            "a board whose answer could not be read was taken for an answer",
        )

        assertTrue(
            failure.detail.contains("could not be read"),
            "the refusal does not say that the answer could not be read: ${failure.detail}",
        )
    }

    @Test
    fun `the board closes the pipe of the CLI it started`() {
        val pipe = RecordingPipe("""{"tasks":[]}""")
        val board = board(FakeBoardProcess(pipe = pipe))

        board.taskHolding("the-label")

        assertTrue(pipe.closed.get(), "the pipe of the board CLI was left open")
    }

    @Test
    fun `the board answers which task holds the label`() {
        val process = FakeBoardProcess(answer = """{"tasks":[{"id":"TASK-7"}]}""")
        val board = board(process)

        val held = board.taskHolding("the-label")

        assertEquals("TASK-7", held, "the board did not answer which task holds the label")
    }

    @Test
    fun `the board CLI is started in the board's directory`() {
        val directory = boardDirectory()
        val launcher = RecordingLauncher { FakeBoardProcess(answer = """{"tasks":[]}""") }
        val board = CliDefectTaskBoard(directory, launcher)

        board.taskHolding("the-label")

        assertEquals(
            directory,
            launcher.directory,
            "the board CLI was started outside the board's directory",
        )
    }

    /**
     * The board under test, with a deadline short enough for a test to pay.
     */
    private fun board(
        process: FakeBoardProcess,
        outputLimitBytes: Long = CliDefectTaskBoard.DEFAULT_OUTPUT_LIMIT_BYTES,
    ): CliDefectTaskBoard =
        CliDefectTaskBoard(
            boardDirectory = boardDirectory(),
            launcher = RecordingLauncher { process },
            timeoutMillis = 200,
            outputLimitBytes = outputLimitBytes,
        )

    /**
     * The directory the board lives in. The board never reads it: the CLI is
     * started there, and that is all.
     */
    private fun boardDirectory(): Path =
        Files.createDirectories(Files.createTempDirectory("board-cli-test"))

    /**
     * Run [call] and return what it produced.
     *
     * The call is made on its own thread and the thread is given a deadline:
     * a board that never ends is a HANG, and a hang that is only visible as a
     * suite that never finishes is not a failure anybody can read. The thread
     * is a daemon so that a board that never ends cannot keep the JVM alive
     * after the row has failed.
     */
    private fun within(deadline: Long, call: () -> Any?): Any? {
        var outcome: Result<Any?>? = null
        val worker = thread(isDaemon = true, name = "board-cli-under-test") {
            outcome = runCatching(call)
        }
        worker.join(deadline)
        assertFalse(
            worker.isAlive,
            "the board did not end within $deadline milliseconds: the deadline never started",
        )
        val done = outcome ?: error("the board did not run at all")
        return done.getOrNull() ?: done.exceptionOrNull()
    }

    /**
     * The specification of the task the board is asked to open.
     */
    private fun spec(): DefectTaskSpec = DefectTaskSpec(
        title = "a defect",
        description = "what was seen",
        labels = listOf("the-label"),
        type = "bug",
        priority = "high",
        milestone = null,
        acceptanceCriteria = listOf("the cause is identified"),
        definitionOfDone = listOf("the fix is published"),
        references = emptyList(),
    )
}

/**
 * A process that behaves the way the board CLI is asked to behave.
 *
 * It is a `java.lang.Process` and not a double of the board: the defect this
 * card closes is in the ORDER in which the board talks to the process it
 * started, and a double of the board would hide that order.
 *
 * The pipe of a process that keeps it open breaks only when the process dies,
 * which is what a real pipe does: a child that never exits and never closes
 * its output leaves the reader waiting, and the only thing that ends that
 * wait is the death of the child.
 */
private class FakeBoardProcess(
    /** What the process said. */
    private val answer: String = "",
    /** The code the process ended with. */
    private val exitCode: Int = 0,
    /** Whether the process keeps its pipe open and never ends. */
    private val keepsThePipeOpen: Boolean = false,
    /** Whether the answer breaks while it is being read. */
    private val failsWhileReading: Boolean = false,
    /** The process this one started, if it started one. */
    private val descendant: FakeProcessHandle? = null,
    /** The pipe the process writes to, when the test wants to watch it. */
    private val pipe: InputStream? = null,
) : Process() {

    /** The pipe breaks when the process dies, and not before. */
    private val pipeBroken = CountDownLatch(1)

    /** Whether the process was killed. */
    val treeKilled = AtomicBoolean(false)

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = when {
        pipe != null -> pipe
        keepsThePipeOpen -> PipeHeldOpen(pipeBroken)
        failsWhileReading -> PipeThatBreaks()
        else -> ByteArrayInputStream(answer.toByteArray())
    }

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = exitCode

    override fun exitValue(): Int = exitCode

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !keepsThePipeOpen

    override fun isAlive(): Boolean = keepsThePipeOpen

    override fun destroy() {
        pipeBroken.countDown()
    }

    override fun destroyForcibly(): Process {
        treeKilled.set(true)
        pipeBroken.countDown()
        return this
    }

    override fun descendants(): java.util.stream.Stream<ProcessHandle> =
        descendant?.let { java.util.stream.Stream.of(it) } ?: java.util.stream.Stream.empty()
}

/**
 * A process the board CLI started.
 *
 * The tree of a process is what the board has to kill: a child that survives
 * its parent can hold the pipe open, and a reader waiting on that pipe never
 * ends.
 */
private class FakeProcessHandle : ProcessHandle {

    /** Whether the process was killed. */
    val destroyed = AtomicBoolean(false)

    override fun pid(): Long = 4242L

    override fun info(): ProcessHandle.Info = ProcessHandle.current().info()

    override fun onExit(): java.util.concurrent.CompletableFuture<ProcessHandle> =
        java.util.concurrent.CompletableFuture.completedFuture(this)

    override fun isAlive(): Boolean = !destroyed.get()

    override fun destroy(): Boolean {
        destroyed.set(true)
        return true
    }

    override fun destroyForcibly(): Boolean {
        destroyed.set(true)
        return true
    }

    override fun supportsNormalTermination(): Boolean = false

    override fun parent(): java.util.Optional<ProcessHandle> = java.util.Optional.empty()

    override fun children(): java.util.stream.Stream<ProcessHandle> = java.util.stream.Stream.empty()

    override fun descendants(): java.util.stream.Stream<ProcessHandle> = java.util.stream.Stream.empty()

    override fun compareTo(other: ProcessHandle): Int = 0

    override fun toString(): String = "the process the board CLI started"
}

/**
 * A pipe that remembers whether it was closed.
 *
 * A descriptor that only ends when the garbage collector runs is a descriptor
 * the process keeps, and the board is what has to end it.
 */
private class RecordingPipe(private val content: String) : InputStream() {

    /** Whether the pipe was closed. */
    val closed = AtomicBoolean(false)

    private val bytes = content.toByteArray()
    private var position = 0

    override fun read(): Int {
        if (position >= bytes.size) return -1
        return bytes[position++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(len, bytes.size - position)
        System.arraycopy(bytes, position, b, off, count)
        position += count
        return count
    }

    override fun close() {
        closed.set(true)
    }
}

/**
 * A pipe that stays open until the process that holds it dies.
 */
private class PipeHeldOpen(private val broken: CountDownLatch) : InputStream() {

    override fun read(): Int {
        broken.await()
        return -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        broken.await()
        return -1
    }
}

/**
 * A pipe that breaks while it is being read.
 */
private class PipeThatBreaks : InputStream() {

    override fun read(): Int = throw IOException("the pipe broke")

    override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("the pipe broke")
}

/**
 * A launcher that answers with the process it is given, and remembers where
 * the board CLI was started.
 */
private class RecordingLauncher(private val process: (List<String>) -> Process) : BoardProcessLauncher {

    /** The directory the board CLI was started in. */
    var directory: Path? = null

    /** The commands the board asked for. */
    val commands = mutableListOf<List<String>>()

    override fun start(command: List<String>, directory: Path): Process {
        this.directory = directory
        commands += command
        return process(command)
    }
}
