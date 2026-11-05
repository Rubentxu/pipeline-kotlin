package dev.rubentxu.pipeline.v2.release

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Command-line entry point for the certification gate of a promotion.
 *
 * The task that runs this main resolves the paths and hands the arguments
 * over; it re-derives no rule, so the door and the tested model cannot drift
 * apart. Everything this main decides, it decides by calling
 * [PromotionCertificationAdmission]: it reads the document, asks the model
 * what to do with it, prints the diagnostic, and maps the outcome to an exit
 * code.
 *
 * The candidate is an argument and not something this main discovers: the
 * promotion knows which bytes it is about to promote, and the certification
 * is judged against THAT candidate, not against whatever the document claims
 * about itself.
 *
 * The verdict is also WRITTEN to the third argument when one is given. The
 * promotion keeps that file and quotes it in its own report, and a report
 * that has to carry the build machinery's noise around it is a report nobody
 * reads to the end.
 *
 * ```text
 * usage: promotion-certification <certification.json> <candidateId> [verdictFile]
 * exit  0 = the certification admits the candidate, 1 = refused, 2 = usage error
 * ```
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println(
            "usage: promotion-certification <certification.json> <candidateId> [verdictFile]",
        )
        exitProcess(2)
    }

    val candidate = try {
        CandidateId.fromDigest(args[1])
    } catch (e: IllegalArgumentException) {
        System.err.println("[release] promotion certification NOT checked — ${e.message}")
        exitProcess(2)
    }

    val verdictFile = if (args.size > 2 && args[2].isNotBlank()) Paths.get(args[2]) else null

    val outcome = PromotionCertificationAdmission.admit(Paths.get(args[0]), candidate)

    when (outcome) {
        is PromotionCertificationOutcome.Refused -> {
            val verdict = buildString {
                appendLine("[release] promotion certification REFUSED")
                appendLine(outcome.reason)
                append(
                    "The candidate is not certified for a stable release, so nothing is " +
                        "promoted. The reason is above.",
                )
            }
            System.err.print(verdict)
            writeVerdict(verdictFile, verdict)
            exitProcess(1)
        }

        is PromotionCertificationOutcome.Admitted -> {
            val verdict = buildString {
                appendLine("[release] promotion certification PASSED")
                appendLine("[release]   candidate_id: ${outcome.candidateId}")
                appendLine("[release]   verdict: ${outcome.summary.trim()}")
            }
            print(verdict)
            writeVerdict(verdictFile, verdict)
            exitProcess(0)
        }
    }
}

/**
 * Write the verdict to [path], when a path was given.
 *
 * A verdict that cannot be written is not a reason to change the decision:
 * the decision is already made, and the door reads the exit code. The
 * failure is reported, because a report that says nothing is a report that
 * will be read as a pass.
 */
private fun writeVerdict(path: Path?, verdict: String) {
    if (path == null) return
    try {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, verdict)
    } catch (e: IOException) {
        System.err.println(
            "[release] the verdict could not be written to $path: " +
                (e.message ?: e::class.java.simpleName),
        )
    }
}
