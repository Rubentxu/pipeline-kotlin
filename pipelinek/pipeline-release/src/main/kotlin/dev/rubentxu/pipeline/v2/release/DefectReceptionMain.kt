package dev.rubentxu.pipeline.v2.release

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * The command-line entry point of the reception of a defect report.
 *
 * The task that runs this main resolves the paths and hands the arguments
 * over; it re-derives no rule, so the build and the tested model cannot drift
 * apart. Everything this main decides, it decides by calling
 * [defectReception]: it reads the document, asks the model what to do with
 * it, writes what the model produced, and maps the outcome to an exit code.
 *
 * The candidate in course is an ARGUMENT and not something this main reads
 * from the tree: a reception that guessed which candidate is in course would
 * mix the defects of one cycle with the defects of another, and the mistake
 * would be invisible in everything it writes.
 *
 * ```text
 * usage: defect-reception <eventFile> <currentCandidate> [<boardDir>] [<milestoneId>]
 * exit  0 = the defect was recorded, or it was already recorded;
 *        1 = the report was refused, or the board could not be written;
 *        2 = usage error
 * ```
 */
fun main(args: Array<String>) {
    if (args.size < 2 || args.size > 4) {
        System.err.println(
            "usage: defect-reception <eventFile> <currentCandidate> [<boardDir>] [<milestoneId>]",
        )
        exitProcess(2)
    }

    val eventFile: Path = Paths.get(args[0])
    val currentCandidate = try {
        CandidateId.fromDigest(args[1])
    } catch (e: IllegalArgumentException) {
        System.err.println("[release] defect report NOT received — ${e.message}")
        exitProcess(2)
    }
    val boardDirectory = if (args.size > 2) Paths.get(args[2]) else Paths.get("").toAbsolutePath()
    val milestone = args.getOrNull(3)

    if (!Files.isRegularFile(eventFile)) {
        System.err.println(
            "[release] defect report NOT received — the document at $eventFile does not exist.",
        )
        exitProcess(2)
    }
    val text = try {
        Files.readString(eventFile)
    } catch (e: java.io.IOException) {
        System.err.println(
            "[release] defect report NOT received — the document at $eventFile could not be " +
                "read: ${e.message ?: e::class.java.simpleName}",
        )
        exitProcess(2)
    }

    val event = when (val read = DefectReportedEventReader.decode(text)) {
        is DefectReportedEventDecode.Accepted -> read.event
        is DefectReportedEventDecode.Rejected -> {
            System.err.println("[release] defect report NOT received")
            System.err.println(read.render())
            exitProcess(1)
        }
    }

    val identity = DefectIdentity.of(event.defect)
    val board = CliDefectTaskBoard(boardDirectory)
    val alreadyHeld = try {
        board.taskHolding(identity.label)?.let { DefectTaskHeld(it) }
    } catch (e: DefectBoardFailure) {
        System.err.println("[release] defect report NOT received — ${e.message}")
        exitProcess(1)
    }

    val opening = when (
        val reception = defectReception(
            event = event,
            identity = identity,
            currentCandidate = currentCandidate,
            alreadyHeld = alreadyHeld,
            milestone = milestone,
            documentReference = eventFile.toString(),
        )
    ) {
        is DefectReception.Opened -> open(board, reception.spec, "OPENED")

        is DefectReception.KeptApart -> open(board, reception.spec, "OPENED APART")

        is DefectReception.AlreadyReceived -> {
            println("[release] defect ${identity.canonical} ALREADY received")
            println("[release]   task: ${reception.taskId}")
            println("[release]   no second task was opened")
            exitProcess(0)
        }
    }

    when (opening) {
        is TaskOpening.Opened -> {
            println("[release] defect ${identity.canonical} ${opening.verdict}")
            println("[release]   task: ${opening.taskId}")
            println("[release]   title: ${opening.title}")
            exitProcess(0)
        }

        is TaskOpening.Failed -> {
            System.err.println("[release] defect report NOT recorded — ${opening.detail}")
            exitProcess(1)
        }
    }
}

/**
 * What opening the task of a received defect produced.
 */
private sealed interface TaskOpening {

    /** The task was opened. */
    data class Opened(val taskId: String, val title: String, val verdict: String) : TaskOpening

    /** The board could not be written. */
    data class Failed(val detail: String) : TaskOpening
}

/**
 * Open the task the model produced, and say what came of it.
 *
 * The function does not end the process: it returns the decision, and the
 * entry point is the one that decides what to do with it.
 */
private fun open(board: DefectTaskBoard, spec: DefectTaskSpec, verdict: String): TaskOpening =
    try {
        TaskOpening.Opened(board.openTask(spec), spec.title, verdict)
    } catch (e: DefectBoardFailure) {
        TaskOpening.Failed(e.message ?: "the board could not be written.")
    }
