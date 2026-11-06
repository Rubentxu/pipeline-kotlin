package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * How the board CLI is started.
 *
 * The port exists so that the reception can be exercised WITHOUT the board
 * CLI installed: what this board has to get right is the DEADLINE and the
 * BOUND of what it reads, and neither of them is the CLI's business. A test
 * that had to launch the real CLI would not be a test of this board but of
 * the CLI, and the CLI is not what is being fixed here.
 *
 * The port is narrow on purpose: it says how a process is started and where,
 * and nothing else. The deadline, the bound and the reading belong to the
 * board, because they are the board's policy.
 */
interface BoardProcessLauncher {

    /** Start [command] in [directory] and return the process it became. */
    fun start(command: List<String>, directory: Path): Process
}

/**
 * The launcher that starts the real board CLI.
 *
 * The error stream is folded into the output: a refusal of the board is part
 * of what the board said, and a reader that only looked at the output stream
 * would report an empty failure.
 */
internal class ProcessBuilderLauncher : BoardProcessLauncher {

    override fun start(command: List<String>, directory: Path): Process =
        ProcessBuilder(listOf(EXECUTABLE) + command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()

    private companion object {
        /** The name of the CLI that owns the board. */
        const val EXECUTABLE = "backlog"
    }
}

/**
 * The board of this repository, reached through the CLI that owns it.
 *
 * The tasks are written BY THE CLI and never by editing their Markdown: the
 * file of a task is governed by the tool that reads it, and an editor that
 * rewrites its frontmatter breaks what the tool reads. The CLI is also what
 * answers the question the reception asks of the board, so the state of the
 * received defects and the tasks that record them cannot drift apart.
 *
 * The board is a DIRECTORY, and it is a parameter: the board of this
 * repository is one directory, and a reception that had to guess where it is
 * would read the board of whichever directory it happened to run in.
 *
 * This is the only class of this reception that starts a process. Everything it
 * decides, it decides from the values it is given; it opens no scenario and
 * boots no product.
 */
class CliDefectTaskBoard(
    /** The directory that holds the board. */
    private val boardDirectory: Path,
    /** How the board CLI is started. */
    private val launcher: BoardProcessLauncher = ProcessBuilderLauncher(),
    /** How long the board is given to answer, in milliseconds. */
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** How much of what the board says is kept, in bytes. */
    private val outputLimitBytes: Long = DEFAULT_OUTPUT_LIMIT_BYTES,
) : DefectTaskBoard {

    override fun taskHolding(label: String): String? {
        val output = run(listOf("task", "list", "--labels", label, "--json"))
        val tasks = try {
            Json.parseToJsonElement(output).jsonObject["tasks"]?.jsonArray.orEmpty()
        } catch (e: IllegalArgumentException) {
            throw DefectBoardFailure(listOf("task", "list", "--labels", label, "--json"), output)
        }
        val id = tasks.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
        return id
    }

    override fun openTask(spec: DefectTaskSpec): String {
        val command = buildList {
            add("task")
            add("create")
            add(spec.title)
            add("--plain")
            add("--type")
            add(spec.type)
            add("--priority")
            add(spec.priority)
            add("--labels")
            add(spec.labels.joinToString(","))
            spec.milestone?.let {
                add("--milestone")
                add(it)
            }
            spec.acceptanceCriteria.forEach {
                add("--ac")
                add(it)
            }
            spec.definitionOfDone.forEach {
                add("--dod")
                add(it)
            }
            add("--description")
            add(spec.description)
            spec.references.forEach {
                add("--ref")
                add(it)
            }
        }
        val output = run(command)
        return TASK_ID.find(output)?.groupValues?.get(1)
            ?: throw DefectBoardFailure(command, "the task was opened but its identifier could " +
                "not be read from what the board said: $output")
    }

    /**
     * Run the CLI in the board's directory and return what it said.
     *
     * The error stream is folded into the output: a refusal of the board is
     * part of what the board said, and a reader that only looked at the
     * output stream would report an empty failure.
     *
     * ## The order is the contract
     *
     * The DEADLINE is armed the moment the process is started, and the output
     * is read WHILE the deadline runs. The two cannot be swapped: a board that
     * keeps its pipe open and never ends would leave a reader waiting for
     * ever, and a deadline that is armed after that wait is a deadline that
     * never arrives. The reception would hang, and with it the build that
     * asked for it.
     *
     * The output is read on its own thread for the same reason: a child that
     * writes more than the pipe holds blocks until somebody reads, so a
     * reader that only runs after the child ended would turn a board with a
     * long answer into a board that timed out.
     *
     * What is KEPT is bounded, and what is kept over the bound is refused
     * rather than trimmed: a trimmed answer is an answer the board never
     * gave, and the board's identifier would be read out of half of it.
     */
    private fun run(command: List<String>): String {
        val process = try {
            launcher.start(command, boardDirectory)
        } catch (e: IOException) {
            throw DefectBoardFailure(command, "the board CLI could not be started: " +
                (e.message ?: e::class.java.simpleName))
        }
        val answer = BoardAnswer(outputLimitBytes)
        val reader = thread(isDaemon = true, name = READER_THREAD) {
            // The stream is closed here and not left to the collector: a
            // descriptor that only ends when the garbage collector runs is a
            // descriptor the process keeps.
            process.inputStream.use { answer.read(it) }
        }
        val finished = try {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            killTree(process)
            throw DefectBoardFailure(command, "the wait for the board CLI was interrupted.")
        }
        if (!finished) {
            killTree(process)
            reader.join(READER_JOIN_MILLIS)
            throw DefectBoardFailure(command, "the board CLI did not answer in " +
                "$timeoutMillis milliseconds.")
        }
        reader.join(READER_JOIN_MILLIS)
        if (reader.isAlive) {
            killTree(process)
            throw DefectBoardFailure(command, "the board CLI's answer did not end: something " +
                "kept its output open.")
        }
        if (answer.failedToRead) {
            throw DefectBoardFailure(command, "the board CLI's answer could not be read: " +
                "${answer.readFailure}")
        }
        if (answer.overTheLimit) {
            throw DefectBoardFailure(command, "the board CLI said ${answer.saidBytes} bytes, " +
                "over the limit of $outputLimitBytes bytes: ${answer.excerpt(EXCERPT_BYTES)}")
        }
        val output = answer.text()
        val code = process.exitValue()
        if (code != 0) {
            throw DefectBoardFailure(command, "the board CLI failed with exit $code: " +
                answer.excerpt(EXCERPT_BYTES))
        }
        return output
    }

    /**
     * Kill the process and everything it started.
     *
     * The tree is killed and not only the child: a child that survives its
     * parent can hold the pipe open, and the reader waiting on that pipe
     * would never end.
     */
    private fun killTree(process: Process) {
        process.descendants().use { descendants ->
            descendants.forEach { it.destroyForcibly() }
        }
        process.destroyForcibly()
    }

    companion object {
        /** How long the board is given to answer, in milliseconds. */
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L

        /**
         * How much of what the board says is kept, in bytes.
         *
         * It is a listing of tasks, and a listing of tasks is small: the
         * limit is here to refuse a board that talks without end, not to
         * accommodate one that talks a lot.
         */
        const val DEFAULT_OUTPUT_LIMIT_BYTES = 8L * 1024 * 1024

        /** How much of the board's answer a refusal quotes, in bytes. */
        private const val EXCERPT_BYTES = 4096L

        /** How long the reader is given to end once the board ended. */
        private const val READER_JOIN_MILLIS = 1_000L

        /** The name of the thread that reads what the board said. */
        private const val READER_THREAD = "board-cli-reader"

        /** How the board prints the identifier of the task it opened. */
        private val TASK_ID = Regex("Task (TASK-\\d+)")
    }
}

/**
 * What the board CLI said, and how much of it was kept.
 *
 * The board is asked for two things, and they are not the same: what it said,
 * and whether it said too much. A board that talks without end is refused,
 * and the refusal says HOW MUCH there was: an operator that is told "the
 * board said too much" cannot tell a board that answered 9 MiB from one that
 * answered 9 GiB.
 *
 * What does not fit in the limit is NOT kept, and what the board said after
 * the limit is DRAINED rather than left in the pipe: a child that keeps
 * writing into a pipe nobody reads blocks, and the deadline would end up
 * blaming the board for what the reader did.
 */
private class BoardAnswer(private val limitBytes: Long) {

    /** The bytes that were kept, up to the limit. */
    private val kept = ByteArrayOutputStream()

    /** How many bytes the board said, kept or not. */
    @Volatile
    var saidBytes: Long = 0L
        private set

    /** Whether the board said more than the limit. */
    @Volatile
    var overTheLimit: Boolean = false
        private set

    /** Whether the answer could not be read to the end. */
    @Volatile
    var failedToRead: Boolean = false
        private set

    /** Why the answer could not be read. */
    @Volatile
    var readFailure: String = ""
        private set

    /**
     * Read what the board said, keeping up to the limit and draining the rest.
     */
    fun read(stream: InputStream) {
        val chunk = ByteArray(CHUNK_BYTES)
        try {
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) return
                saidBytes += read
                if (kept.size().toLong() + read <= limitBytes) {
                    kept.write(chunk, 0, read)
                } else {
                    overTheLimit = true
                    // What FITS is kept, and only what fits: the excerpt a
                    // refusal quotes has to be the PREFIX of what the board
                    // said, or the refusal quotes something the board never
                    // said.
                    val room = (limitBytes - kept.size()).coerceAtLeast(0L).toInt()
                    if (room > 0) {
                        kept.write(chunk, 0, room)
                    }
                }
            }
        } catch (e: IOException) {
            failedToRead = true
            readFailure = e.message ?: e::class.java.simpleName
        }
    }

    /** What the board said, as far as it was kept. */
    fun text(): String = String(kept.toByteArray(), Charset.defaultCharset())

    /**
     * The first bytes of what the board said, for a refusal that quotes it.
     *
     * The cut is DECLARED: a refusal that quotes four kilobytes of a
     * megabyte and does not say so is a refusal that lies about what it read.
     */
    fun excerpt(limitBytes: Long): String {
        val bytes = kept.toByteArray()
        val shown = bytes.size.toLong().coerceAtMost(limitBytes).toInt()
        val quoted = String(bytes, 0, shown, Charset.defaultCharset())
        return if (saidBytes > shown) "$quoted (the first $shown of $saidBytes bytes)"
        else quoted
    }

    private companion object {
        /** How many bytes are read at a time. */
        const val CHUNK_BYTES = 8192
    }
}
