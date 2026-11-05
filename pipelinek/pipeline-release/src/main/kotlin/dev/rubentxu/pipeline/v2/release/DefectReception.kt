package dev.rubentxu.pipeline.v2.release

/**
 * The task a reception opens in the task board of this repository.
 *
 * The task is the RECORD of a received defect, and it has to carry enough for
 * whoever picks it up to act without guessing: which candidate it was found
 * against, which scenario produced it, what was observed, and when. The
 * identity of the defect travels with it because the identity is what makes
 * the reception idempotent.
 *
 * The type and the priority are the board's own vocabulary, and they travel as
 * text for that reason: this product decides WHAT the task is and how urgent
 * it is, and the board decides what it calls those things.
 */
data class DefectTaskSpec(
    /** The title the board shows. */
    val title: String,
    /** The body of the task. */
    val description: String,
    /** The labels the task carries in the board. */
    val labels: List<String>,
    /** The type of the task, in the vocabulary of the board. */
    val type: String,
    /** The priority of the task, in the vocabulary of the board. */
    val priority: String,
    /** The milestone the task belongs to, or null when it belongs to none. */
    val milestone: String?,
    /** The criteria that say the defect is dealt with. */
    val acceptanceCriteria: List<String>,
    /** The conditions that say the task is finished. */
    val definitionOfDone: List<String>,
    /** The documents that back the task. */
    val references: List<String>,
)

/**
 * What the reception of a defect report may mean for the task board.
 */
sealed interface DefectReception {

    /** The defect is new, and it belongs to the candidate in course. */
    data class Opened(val identity: DefectIdentity, val spec: DefectTaskSpec) : DefectReception

    /**
     * The defect is new, and it belongs to ANOTHER candidate.
     *
     * It is kept, and it is kept APART: a defect of another cycle is not part
     * of the work of the cycle in course, and correcting it as if it were
     * would break the other one.
     */
    data class KeptApart(val identity: DefectIdentity, val spec: DefectTaskSpec) : DefectReception

    /** The defect is already in the task board, so no second task is opened. */
    data class AlreadyReceived(val identity: DefectIdentity, val taskId: String) : DefectReception
}

/**
 * The task of the board that already holds a defect.
 */
data class DefectTaskHeld(val taskId: String)

/**
 * Decide what the reception of [event] means for the task board.
 *
 * The rule has three cases and no fourth:
 *
 *  - the identity is already held by a task: the defect is already in the
 *    task board and NO second task is opened. This is what makes the reception
 *    idempotent, and it is decided by the IDENTITY of the defect and not by
 *    the date, because the same observation reported twice is the same defect.
 *  - the defect is new and it was observed against the candidate in course:
 *    ONE task is opened for it.
 *  - the defect is new and it was observed against ANOTHER candidate: it is
 *    kept, and kept apart from the work of the cycle in course.
 *
 * Pure: it reads the values it is given and returns a decision. It touches no
 * filesystem, no clock and no board.
 */
fun defectReception(
    event: DefectReportedEvent,
    identity: DefectIdentity,
    currentCandidate: CandidateId,
    alreadyHeld: DefectTaskHeld?,
    milestone: String?,
    documentReference: String?,
): DefectReception {
    if (alreadyHeld != null) {
        return DefectReception.AlreadyReceived(identity, alreadyHeld.taskId)
    }
    val belongsToCurrentCycle = event.candidateId == currentCandidate
    val spec = defectTaskSpec(
        event = event,
        identity = identity,
        currentCandidate = currentCandidate,
        milestone = milestone,
        documentReference = documentReference,
        belongsToCurrentCycle = belongsToCurrentCycle,
    )
    return if (belongsToCurrentCycle) {
        DefectReception.Opened(identity, spec)
    } else {
        DefectReception.KeptApart(identity, spec)
    }
}

/**
 * The task that records a received defect.
 *
 * The labels are what the reception reads the task board by: the identity of the
 * defect says WHICH defect the task is about, and the candidate says WHICH
 * cycle it belongs to. A task that carried only the identity would mix the
 * defects of every cycle into one list, and a task that carried only the
 * candidate would let the same defect open two tasks.
 */
private fun defectTaskSpec(
    event: DefectReportedEvent,
    identity: DefectIdentity,
    currentCandidate: CandidateId,
    milestone: String?,
    documentReference: String?,
    belongsToCurrentCycle: Boolean,
): DefectTaskSpec {
    val candidate = event.candidateId
    val title = "Defecto en ${event.defect.scenario} contra ${candidate.digest.take(12)}" +
        if (belongsToCurrentCycle) "" else " (otro ciclo)"
    return DefectTaskSpec(
        title = title,
        description = describeDefect(
            event = event,
            identity = identity,
            currentCandidate = currentCandidate,
            belongsToCurrentCycle = belongsToCurrentCycle,
        ),
        labels = listOf(identity.label, candidate.taskLabel(), CYCLE_LABEL),
        type = "bug",
        priority = if (event.evidence.blocksRelease) "high" else "medium",
        milestone = milestone,
        acceptanceCriteria = listOf(
            "La causa del fallo del escenario ${event.defect.scenario} está identificada en el " +
                "producto y el arreglo está en el código.",
            "Una RC que incluye el arreglo pasa por la misma admisión que certificó a " +
                "${candidate.digest.take(12)}.",
        ),
        definitionOfDone = listOf(
            "El arreglo va en una RC publicada, y la admisión y la certificación de esa RC " +
                "constan en el ciclo.",
        ),
        references = listOfNotNull(documentReference),
    )
}

/**
 * The body of the task: what was seen, against what, and when.
 *
 * Nothing here is optional prose. The candidate says which bytes failed, the
 * scenario says what ran, the observation says what happened, and the instant
 * says when: those four are what separate "the harness failed for a reason"
 * from "the product failed for a reason".
 */
private fun describeDefect(
    event: DefectReportedEvent,
    identity: DefectIdentity,
    currentCandidate: CandidateId,
    belongsToCurrentCycle: Boolean,
): String = buildString {
    appendLine("Defecto reportado por el arnés contra el candidato ${event.candidateId.canonical}.")
    appendLine()
    appendLine("- Identidad del defecto: ${identity.label}")
    appendLine("- Escenario: ${event.defect.scenario}")
    appendLine("- Contrato: ${event.defect.contract}")
    appendLine("- Tipo de fallo: ${event.defect.failureType}")
    appendLine("- Firma: ${event.defect.signature}")
    appendLine("- Observado: ${event.evidence.observed}")
    appendLine("- Esperado: ${event.evidence.expected}")
    appendLine("- Gravedad: ${event.evidence.severity}")
    appendLine("- Bloquea la release: ${if (event.evidence.blocksRelease) "sí" else "no"}")
    appendLine("- Versión del producto: ${event.evidence.productVersion}")
    appendLine("- Commit del producto: ${event.evidence.productCommit}")
    appendLine("- Revisión del arnés: ${event.evidence.harnessRevision}")
    appendLine("- Observado el: ${event.observedAt.canonical}")
    if (!belongsToCurrentCycle) {
        appendLine()
        appendLine(
            "Este defecto se observó contra un candidato distinto del que está en curso " +
                "(${currentCandidate.canonical}): se conserva aparte, para no mezclarlo con " +
                "el trabajo del ciclo en curso.",
        )
    }
    appendLines("Comandos", event.evidence.commands)
    appendLines("Logs", event.evidence.logs)
}

/**
 * Append a titled list, and nothing at all when the list is empty.
 */
private fun StringBuilder.appendLines(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    appendLine()
    appendLine("- $title:")
    lines.forEach { appendLine("  - $it") }
}

/**
 * The label the tasks of the certification cycle carry.
 *
 * The defects of the cycle are looked up by this label, and it is the label
 * the milestones of the cycle already use.
 */
private const val CYCLE_LABEL = "rc-certification"
