package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The captured stdout channel's VALUE, read under one rule.
 *
 * ## Why this exists
 * The captured stdout — the typed VALUE channel of a `sh` step with `returnStdout` — is read by
 * two components, in two different processes and in two different modules:
 *
 *  - the executor, when the process ends and the value is published;
 *  - the recovery observer, when a later process reads the control directory of an operation
 *    whose worker died.
 *
 * Both read the same file, whose name belongs to [ShellProtocolLayout]. They used to read it
 * under two DIFFERENT rules: the executor trimmed the text and the observer did not. A program
 * that printed `hello\n` therefore published `"hello"` on a fresh run and `"hello\n"` on a
 * resumed one — the same step, the same program, the same file, and two different public values.
 * The difference was invisible until a consumer compared them, and it is exactly the difference
 * a complete `sh` must not have: a typed capture and a resumption that do NOT preserve the same
 * public semantics.
 *
 * ## The rule
 * ```text
 * the file is ABSENT      →  null      nobody recorded anything — an ABSENCE
 * the file holds "abc\n"  →  "abc"     the program printed this
 * the file holds ""       →  ""        the program printed NOTHING — a fact
 * ```
 * The absence and the emptiness are different facts and are never collapsed into one: a
 * `returnStdout` step whose file was never written has no value to report, and a step whose
 * program printed nothing has an empty one.
 *
 * The text is trimmed, and that is the rule the FRESH path has always published. It is kept
 * because it is the semantics every consumer of a fresh `sh` already depends on: changing it
 * would change the public value of every execution that runs today, and the resumed path is the
 * one that has to catch up, not the other way round.
 *
 * ## What this does NOT own
 *  - The channel's NAME, which belongs to [ShellProtocolLayout].
 *  - The channel's RETENTION. The executor disposes of the file after reading it, under
 *    [CaptureRetainPolicy]; the observer does not, because the file is the evidence the
 *    reconciler reads. Disposing is a side effect each caller owns, not part of the value.
 *
 * ## Why it is public
 * The recovery observer lives in the engine module, which cannot see this module's `internal`
 * declarations, so the value's authority has to be reachable from outside the runtime. Its
 * surface is visible, but this module is not a published contract — it carries no
 * `maven-publish` and no ABI dump — so nothing an external consumer compiles against changes.
 */
object ShellCapturedStdout {

    /**
     * The most bytes a captured stdout may hold.
     *
     * The captured value is MATERIALISED: it is read into a [String] and published as a typed
     * value, so its cost is O(value) in memory, and twice the bytes, because a character is two
     * bytes. A shell step that prints without bound could therefore demand more memory than the
     * host has, and the product would have had no word to say about it.
     *
     * 64 MiB is the product's limit: far more than any value a legitimate step carries, and small
     * enough that materialising one cannot take the runtime down. The limit is a REFUSAL and
     * never a truncation — a value over the limit is not published, not shortened, and not
     * reported as an empty value, because an empty value is the answer to "the program printed
     * nothing", and this program printed too much.
     *
     * The number lives HERE, beside the rule that reads the channel, because the two are one
     * decision: this reader is the only place that knows how big the value is.
     */
    const val MAX_CAPTURED_STDOUT_BYTES: Long = 64L * 1024 * 1024

    /**
     * Reads the captured stdout of [controlDir] under the rule above.
     *
     * @param controlDir The control directory of one durable shell operation.
     * @param limitBytes The most bytes the value may hold. The default is the product's limit; it
     *   is a parameter so that the refusal can be exercised without writing the limit's worth of
     *   bytes, which is the whole reason the parameter exists.
     * @return `null` when the captured channel is absent; the captured text when it is present.
     * @throws java.io.IOException when the channel is present but cannot be read, or when it holds
     *   more than [limitBytes]. Each caller decides what an unreadable channel means for it: the
     *   executor publishes a REFUSAL and never an empty value, and the observer lets the failure
     *   surface rather than claim that the program printed nothing.
     */
    fun read(controlDir: Path, limitBytes: Long = MAX_CAPTURED_STDOUT_BYTES): String? {
        val outputFile = ShellProtocolLayout(controlDir).outputFile
        if (!Files.exists(outputFile)) {
            return null
        }
        // The size is read BEFORE the text, so a value over the limit is refused without ever
        // being materialised. The channel is written by the child and read after the child
        // ended, so the size it has here is the size it will keep.
        val size = Files.size(outputFile)
        if (size > limitBytes) {
            throw IOException(
                "the captured stdout is $size bytes, over the limit of $limitBytes bytes",
            )
        }
        return Files.readString(outputFile).trim()
    }

    /**
     * Opens the captured stdout channel of [controlDir] for writing.
     *
     * The channel has ONE name and ONE place, and this is the write side of it: whoever writes
     * the channel writes the file [read] reads, so the two cannot disagree about what the value
     * is or where it lives.
     *
     * The durable substrate writes the channel with the child's own redirection; this is the side
     * for a substrate that holds the bytes in the JVM — the generic process runtime, whose child
     * is pumped instead of redirected. The bytes are the same bytes: the value is the stdout, EXACT
     * and not sanitized, which is what makes it the value and not a transcript.
     *
     * @param controlDir The control directory of one shell operation.
     * @return The stream that writes the channel. The caller owns it and closes it.
     */
    fun writer(controlDir: Path): OutputStream {
        Files.createDirectories(controlDir)
        return Files.newOutputStream(ShellProtocolLayout(controlDir).outputFile)
    }
}
