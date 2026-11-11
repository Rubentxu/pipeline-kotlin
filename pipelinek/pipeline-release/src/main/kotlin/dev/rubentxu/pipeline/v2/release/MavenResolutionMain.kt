package dev.rubentxu.pipeline.v2.release

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Command-line entry point for the CONSUMER-side check of the Maven channel.
 *
 * The publication gate answers whether a bundle may be published; this one
 * answers whether the repository a consumer resolves from serves the bytes
 * that publication certified. It is the question nobody else asks, and until
 * it is asked the statement "the SDK is published" rests on a receipt that
 * only says the bundle was handed over.
 *
 * The task that runs this main resolves the paths and hands the arguments
 * over; it re-derives no rule, so the check and the tested model cannot drift
 * apart. Everything this main decides, it decides by calling
 * [MavenResolutionAdmission].
 *
 * The verdict is also WRITTEN to the last argument when one is given. The
 * publisher keeps that file and quotes it in its own report, and a report
 * that has to carry the build machinery's noise around it is a report nobody
 * reads to the end.
 *
 * The credential of a repository that asks for one is read from the
 * environment and goes nowhere else: it is never an argument, never a file
 * and never part of the verdict.
 *
 * ```text
 * usage: maven-resolution <manifest.json> <repositoryUrl> [verdictFile]
 * exit  0 = the repository serves what the manifest declares
 *       1 = the repository does not serve it
 *       2 = the state could not be measured, or the invocation was wrong
 * ```
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: maven-resolution <manifest.json> <repositoryUrl> [verdictFile]")
        exitProcess(2)
    }

    val manifestPath = Paths.get(args[0])
    val repositoryUrl = args[1]
    val verdictFile = if (args.size > 2 && args[2].isNotBlank()) Paths.get(args[2]) else null

    val reader = HttpMavenRepositoryReader(
        baseUrl = repositoryUrl,
        user = credentialFromEnvironment("PIPELINEK_REPOSITORY_USER"),
        token = credentialFromEnvironment("PIPELINEK_REPOSITORY_TOKEN"),
    )

    val outcome = try {
        MavenResolutionAdmission.admit(manifestPath, reader)
    } catch (e: Exception) {
        // The instrument broke, so the state was not measured. That is NOT a
        // channel that does not carry the bytes, and the exit code says so:
        // the two facts have different fixes and a reader must not confuse
        // them.
        val verdict = buildString {
            appendLine("[release] maven resolution NOT RUN")
            appendLine("[release]   repository: $repositoryUrl")
            append("the check could not conclude: ${e.message ?: e::class.java.simpleName}")
        }
        System.err.print(verdict)
        writeVerdict(verdictFile, verdict)
        exitProcess(2)
    }

    when (outcome) {
        is MavenResolutionOutcome.Verified -> {
            val verdict = buildString {
                appendLine("[release] maven resolution PASSED")
                appendLine("[release]   repository: $repositoryUrl")
                appendLine("[release]   verdict: ${outcome.summary.trim()}")
            }
            print(verdict)
            writeVerdict(verdictFile, verdict)
            exitProcess(0)
        }

        is MavenResolutionOutcome.Rejected -> {
            val verdict = buildString {
                appendLine("[release] maven resolution FAILED")
                appendLine("[release]   repository: $repositoryUrl")
                appendLine(outcome.summary.trim())
                append("The channel does not carry the certified bytes. The reason is above.")
            }
            System.err.print(verdict)
            writeVerdict(verdictFile, verdict)
            exitProcess(1)
        }

        is MavenResolutionOutcome.Refused -> {
            val verdict = buildString {
                appendLine("[release] maven resolution NOT RUN")
                appendLine("[release]   repository: $repositoryUrl")
                append(outcome.reason)
            }
            System.err.print(verdict)
            writeVerdict(verdictFile, verdict)
            exitProcess(2)
        }
    }
}

/**
 * The credential the environment holds under [name], or null when it holds
 * none.
 *
 * A blank value is no value: a credential that is empty is a credential the
 * repository will refuse, and the reader that sends it would report an
 * answer that says nothing about the channel.
 */
private fun credentialFromEnvironment(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }

/**
 * Write the verdict to [path], when a path was given.
 *
 * A verdict that cannot be written is not a reason to change the decision:
 * the decision is already made, and the door reads the exit code. The failure
 * is reported, because a report that says nothing is a report that will be
 * read as a pass.
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
