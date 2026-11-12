package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path

/** How much of the end of each child's stderr a row prints: the cause is at the end. */
private const val CHILD_STDERR_TAIL: Int = 4_000

/**
 * The end of what each child supervised under [supervisionRoot] wrote to its stderr, labelled
 * by the directory of its supervision.
 *
 * A child that ends without a terminal of its own says why in its stderr: an exception the JVM
 * printed, a refusal of the command line. That file lives in the row's own directory, which is
 * gone once the row ends, so a row that fails prints it in its message or nobody reads it.
 */
internal fun childStderrTails(supervisionRoot: Path): String {
    if (!Files.isDirectory(supervisionRoot)) return "no child was supervised"
    val tails = Files.list(supervisionRoot).use { supervisions ->
        supervisions.sorted().toList().mapNotNull { supervision ->
            val log = supervision.resolve(ServiceRunSupervisor.CHILD_LOG_NAME)
            if (!Files.isRegularFile(log)) return@mapNotNull null
            val text = String(Files.readAllBytes(log), Charsets.UTF_8).takeLast(CHILD_STDERR_TAIL)
            "${supervision.fileName}: ${text.ifBlank { "(empty)" }}"
        }
    }
    return if (tails.isEmpty()) "no child wrote a stderr" else tails.joinToString(" || ")
}
