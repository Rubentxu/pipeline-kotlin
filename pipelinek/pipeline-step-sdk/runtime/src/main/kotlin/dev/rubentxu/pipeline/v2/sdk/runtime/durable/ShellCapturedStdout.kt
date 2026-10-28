package dev.rubentxu.pipeline.v2.sdk.runtime.durable

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
 * The observer lives in the application module, which cannot see this module's `internal`
 * declarations, so the value's authority has to be reachable from outside the runtime. Its
 * surface is visible, but this module is not a published contract — it carries no
 * `maven-publish` and no ABI dump — so nothing an external consumer compiles against changes.
 */
object ShellCapturedStdout {

    /**
     * Reads the captured stdout of [controlDir] under the rule above.
     *
     * @param controlDir The control directory of one durable shell operation.
     * @return `null` when the captured channel is absent; the captured text when it is present.
     * @throws java.io.IOException when the channel is present but cannot be read. Each caller
     *   decides what an unreadable channel means for it: the executor answers with an empty
     *   value and never throws, and the observer lets the failure surface rather than claim
     *   that the program printed nothing.
     */
    fun read(controlDir: Path): String? {
        val outputFile = ShellProtocolLayout(controlDir).outputFile
        if (!Files.exists(outputFile)) {
            return null
        }
        return Files.readString(outputFile).trim()
    }
}
