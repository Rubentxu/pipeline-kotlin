package dev.rubentxu.pipeline.v2.release

/**
 * The board that holds the tasks of this repository.
 *
 * The board is the STATE of the received defects, and it is the only state
 * there is: a defect that the board holds is a defect that was received, and
 * a defect the board does not hold is one that has to be opened. There is no
 * second index of received defects, because two indexes are two answers to
 * the same question and they drift.
 *
 * The port is narrow on purpose. The reception asks two things of the board:
 * whether a task already carries a label, and how to open a task. Everything
 * else about a task is decided by the product, not by the board.
 */
interface DefectTaskBoard {

    /**
     * The identifier of the task that already carries [label], or null when no
     * task of the board carries it.
     */
    fun taskHolding(label: String): String?

    /**
     * Open [spec] in the board and return the identifier of the task opened.
     */
    fun openTask(spec: DefectTaskSpec): String
}

/**
 * The board could not be read or written.
 *
 * The reason travels with it because the operator has to know whether the
 * defect was recorded or not: a reception that failed leaves no task, and the
 * next reception of the same defect will try again.
 */
class DefectBoardFailure(
    /** The command that was run, without the path of the executable. */
    val command: List<String>,
    /** What the board said. */
    val detail: String,
) : RuntimeException("the board failed on '$command': $detail")
