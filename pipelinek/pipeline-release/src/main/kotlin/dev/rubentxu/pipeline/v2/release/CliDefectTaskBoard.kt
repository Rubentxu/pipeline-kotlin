package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

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
     */
    private fun run(command: List<String>): String {
        val process = try {
            ProcessBuilder(listOf(EXECUTABLE) + command)
                .directory(boardDirectory.toFile())
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            throw DefectBoardFailure(command, "the board CLI could not be started: " +
                (e.message ?: e::class.java.simpleName))
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw DefectBoardFailure(command, "the board CLI did not answer in " +
                "$TIMEOUT_SECONDS seconds.")
        }
        if (process.exitValue() != 0) {
            throw DefectBoardFailure(command, "the board CLI failed with exit " +
                "${process.exitValue()}: $output")
        }
        return output
    }

    companion object {
        /** The name of the CLI that owns the board. */
        private const val EXECUTABLE = "backlog"

        /** How long the board is given to answer. */
        private const val TIMEOUT_SECONDS = 60L

        /** How the board prints the identifier of the task it opened. */
        private val TASK_ID = Regex("Task (TASK-\\d+)")
    }
}
