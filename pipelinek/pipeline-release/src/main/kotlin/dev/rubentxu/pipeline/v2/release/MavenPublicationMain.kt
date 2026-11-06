package dev.rubentxu.pipeline.v2.release

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Command-line entry point for the publication gate of the Maven channel.
 *
 * The task that runs this main resolves the paths and hands the arguments
 * over; it re-derives no rule, so the door and the tested model cannot drift
 * apart. Everything this main decides, it decides by calling
 * [MavenPublicationAdmission]: it reads the documents, asks the model what
 * to do with them, prints the diagnostic, and maps the outcome to an exit
 * code.
 *
 * The verdict is also WRITTEN to the last argument when one is given. The
 * publisher keeps that file and quotes it in its own report, and a report
 * that has to carry the build machinery's noise around it is a report nobody
 * reads to the end.
 *
 * ```text
 * usage: maven-publication <request.json> <manifest.json> <certification.json>
 *                         <bundleDir> <sdkZip> <publishedDir> [verdictFile]
 * exit  0 = the request may publish the bundle, 1 = refused, 2 = usage error
 * ```
 */
fun main(args: Array<String>) {
    if (args.size < 6) {
        System.err.println(
            "usage: maven-publication <request.json> <manifest.json> <certification.json> " +
                "<bundleDir> <sdkZip> <publishedDir> [verdictFile]",
        )
        exitProcess(2)
    }

    val verdictFile = if (args.size > 6 && args[6].isNotBlank()) Paths.get(args[6]) else null

    val outcome = MavenPublicationAdmission.admit(
        requestPath = Paths.get(args[0]),
        manifestPath = Paths.get(args[1]),
        certificationPath = Paths.get(args[2]),
        bundleRoot = Paths.get(args[3]),
        sdkZip = Paths.get(args[4]),
        publishedRoot = Paths.get(args[5]),
    )

    when (outcome) {
        is MavenPublicationOutcome.Refused -> {
            val verdict = buildString {
                appendLine("[release] maven publication REFUSED")
                appendLine(outcome.reason)
                append(
                    "Nothing is published. The reason is above.",
                )
            }
            System.err.print(verdict)
            writeVerdict(verdictFile, verdict)
            exitProcess(1)
        }

        is MavenPublicationOutcome.Admitted -> {
            val verdict = buildString {
                appendLine("[release] maven publication PASSED")
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
