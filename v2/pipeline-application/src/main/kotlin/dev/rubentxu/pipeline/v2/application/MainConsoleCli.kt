package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputPage
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * M1-P3 — the consumable read side: console output, addressed by [OutputCursor].
 *
 * ## What this is for
 *
 * `OutputReadPort` existed since M1-P1, and nothing called it. A contract nobody can call is a
 * design, not a product. This is the surface a consumer actually reaches for: a Jenkins
 * `LogStorage`, a Fabric ACL adapter, or [MainConsoleCli].
 *
 * ## Why it is not built on `EventHistoryReader`
 *
 * The event plane continues over a store-assigned **sequence**; the output plane continues over a
 * **committed byte offset**. Building a console reader on the event sequence is precisely the
 * conflation `ADR-M1 D3` forbids: a gap in the sequence is indistinguishable from output that was
 * never emitted, and unrelated event traffic perturbs the read. So this reads the Output Plane and
 * only the Output Plane, and its continuation token is `out-cursor-v1:`, never `evt-cursor-v1:`.
 *
 * ## Resolution, not guessing
 *
 * A consumer names a **run and an operation**, not a stream id. The mapping is
 * [OutputPlaneProvider.streamId] and it is one-way on purpose: this service never *derives* a
 * cursor, it only resolves what a consumer already asked for. A reader that invented a stream
 * identity would be one refactor away from reading the wrong run.
 *
 * ## Refusals are reported, not swallowed
 *
 * A refusal is a fact about the question, not an error: an unknown stream and a cursor from another
 * stream are both ways a consumer asked something that cannot be answered. They are rendered to the
 * caller, never turned into an empty page — an empty page is a valid answer, and conflating it with
 * "wrong stream" is how a reader ends up believing it read nothing when it read nothing at all.
 */
object ConsoleReadService {

    /** Bytes emitted per continuation by default. A page is bounded; this is the bound. */
    const val DEFAULT_PAGE_BYTES: Int = 64 * 1024

    /** What a consumer gets back: a bounded page, or the reason there isn't one. */
    sealed interface Result {
        data class Page(val page: OutputPage) : Result
        data class Refused(val reason: OutputRefusal) : Result
    }

    /**
     * Read one bounded page of [opId]'s transcript in [runId], continuing after [after].
     *
     * @param after `null` reads from the start. A token for a different stream is **refused**,
     *   not clamped — see the class KDoc.
     */
    fun read(
        controlDirRoot: Path,
        runId: String,
        opId: String,
        after: OutputCursor?,
        maxBytes: Int = DEFAULT_PAGE_BYTES,
    ): Result {
        val store = OutputPlaneProvider.storeFor(controlDirRoot)
        val stream = OutputPlaneProvider.streamId(runId, opId)
        // The foreign-stream check is NOT repeated here. SegmentOutputStore.read already refuses a
        // cursor naming another stream, with the identical OutputRefusal, and a mutation harness
        // proved the duplicate was unreachable: disabling the service's copy left the suite green
        // because the store caught it anyway. One place owns the rule.
        val cursor = after ?: OutputCursor.start(stream)
        return when (val result = store.read(stream, cursor, maxBytes)) {
            is OutputReadResult.Page -> Result.Page(result.page)
            is OutputReadResult.Refused -> Result.Refused(result.reason)
        }
    }

    /** Read an arbitrary committed byte range. The same bytes a paged read would deliver. */
    fun readRange(
        controlDirRoot: Path,
        runId: String,
        opId: String,
        from: Long,
        to: Long,
    ): Result {
        val store = OutputPlaneProvider.storeFor(controlDirRoot)
        val stream = OutputPlaneProvider.streamId(runId, opId)
        return when (val result = store.readRange(stream, from, to)) {
            is OutputReadResult.Page -> Result.Page(result.page)
            is OutputReadResult.Refused -> Result.Refused(result.reason)
        }
    }

    /** Render a refusal as one stable line, so a script can branch on the reason. */
    fun renderRefusal(reason: OutputRefusal): String = when (reason) {
        is OutputRefusal.ForeignStream ->
            "console-refused: foreign-stream expected=${reason.expected.value} actual=${reason.actual.value}"
        is OutputRefusal.UnknownStream ->
            "console-refused: unknown-stream ${reason.stream.value}"
        is OutputRefusal.OffsetBeyondCommitted ->
            "console-refused: offset-beyond-committed requested=${reason.requested} committed=${reason.committed}"
        is OutputRefusal.InvalidRange ->
            "console-refused: invalid-range [${reason.from}, ${reason.to})"
        is OutputRefusal.DanglingCommit ->
            "console-refused: dangling-commit end=${reason.requestedEnd} readable=${reason.readableBytes}"
        OutputRefusal.RecoveryNotCompleted ->
            "console-refused: recovery-not-completed"
    }
}

/**
 * M1-P3 — the console reader CLI.
 *
 * ```text
 * pipeline console --control-dir <path> <runId> <opId> [--max-bytes N] [--after-cursor TOKEN]
 * pipeline console --control-dir <path> <runId> <opId> --range FROM:TO
 * ```
 *
 * The console transcript goes to **stdout as bytes**, not as a rendered line, because a consumer
 * that has to strip a CLI's framing out of a transcript will eventually strip the wrong thing. The
 * continuation token goes to **stderr**, which is the convention [MainEventsCli] already uses and
 * keeps the two planes from colliding.
 */
object MainConsoleCli {

    /**
     * The pure decision: what the caller asked for, or the exact line to print and why not to run.
     *
     * Separating this from [main] is not cosmetic. `main` was doing argument selection, validation,
     * filesystem access and effect execution in one body, and adding the three strictness refusals
     * pushed its cyclomatic complexity to 29 against a limit of 25. The fix is the one the project
     * asks for everywhere else: decide purely, interpret at the effect boundary.
     */
    private sealed interface Invocation {

        /** A complete, valid request. `controlDir` is still a string: resolving it is [main]'s job. */
        data class Ok(
            val controlDir: String,
            val runId: String,
            val opId: String,
            val maxBytes: Int,
            val after: OutputCursor?,
            val range: Pair<Long, Long>?,
        ) : Invocation

        /** The command must NOT run. `message` is printed verbatim by [usageError]. */
        data class Usage(val message: String) : Invocation
    }

    /**
     * What the argument scan produced: the raw strings, or the line to print and why not to run.
     *
     * Raw, not interpreted: this phase knows argument SHAPE (which option got which token) and
     * nothing else. Interpreting the values is [parseInvocation]'s job, and keeping the two apart is
     * what lets each stay under the complexity the project allows.
     */
    private sealed interface Scan {

        data class Args(
            val controlDir: String?,
            val runId: String?,
            val opId: String?,
            val maxBytesArg: String?,
            val afterCursor: String?,
            val range: String?,
        ) : Scan

        data class Usage(val message: String) : Scan
    }

    /**
     * Arguments in, typed decision out. Pure: no I/O, no console, no environment.
     *
     * Every refusal carries a message that NAMES the offending token, because a usage error whose
     * text does not say which argument was wrong is a usage error the caller has to guess at.
     */
    private fun scanArgs(args: Array<String>): Scan {
        var controlDir: String? = null
        var runId: String? = null
        var opId: String? = null
        var maxBytesArg: String? = null
        var afterCursor: String? = null
        var range: String? = null

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when (arg) {
                // An option whose value is missing is a usage error, not a reason to fall back to a
                // default: the caller gets output shaped by a value they never supplied.
                "--control-dir" -> {
                    i++
                    controlDir = args.getOrNull(i) ?: return Scan.Usage("Error: --control-dir requires a value")
                }
                "--max-bytes" -> {
                    i++
                    maxBytesArg = args.getOrNull(i) ?: return Scan.Usage("Error: --max-bytes requires a value")
                }
                "--after-cursor" -> {
                    i++
                    afterCursor = args.getOrNull(i) ?: return Scan.Usage("Error: --after-cursor requires a value")
                }
                "--range" -> {
                    i++
                    range = args.getOrNull(i) ?: return Scan.Usage("Error: --range requires a value")
                }
                else -> when {
                    // A leading `--` that is not a known option is a typo or a flag from another
                    // build. Ignoring it would run a different command than the one typed and still
                    // exit 0, which is what this rejects.
                    arg.startsWith("--") -> return Scan.Usage("Error: unknown option: $arg")
                    runId == null -> runId = arg
                    opId == null -> opId = arg
                    // The command reads ONE run and ONE op. A third positional was dropped on the
                    // floor while the first two were kept, so the caller's intent was silently
                    // narrowed.
                    else -> return Scan.Usage("Error: unexpected extra argument: $arg")
                }
            }
            i++
        }

        return Scan.Args(
            controlDir = controlDir,
            runId = runId,
            opId = opId,
            maxBytesArg = maxBytesArg,
            afterCursor = afterCursor,
            range = range,
        )
    }

    /**
     * Interprets a completed scan into a decision: presence of the required arguments, the page
     * size, the cursor token and the range bounds. Pure, and separate from [scanArgs] so each stays
     * under the complexity the project allows while the shape/semantics split stays honest.
     */
    private fun parseInvocation(args: Array<String>): Invocation {
        val scan = when (val scanned = scanArgs(args)) {
            is Scan.Usage -> return Invocation.Usage(scanned.message)
            is Scan.Args -> scanned
        }

        val controlDir = scan.controlDir
        val runId = scan.runId
        val opId = scan.opId
        if (controlDir == null || runId == null || opId == null) {
            return Invocation.Usage(
                "Usage: pipeline console --control-dir <path> <runId> <opId> " +
                    "[--max-bytes N] [--after-cursor TOKEN] | --range FROM:TO",
            )
        }

        // A flag that does not parse must not become the default. `--max-bytes abc` used to read as
        // the default page size, which is the same shape as a command that silently did something
        // other than what was asked, and it was incoherent with the rest of this file. AUD-04.
        val maxBytes = if (scan.maxBytesArg == null) {
            ConsoleReadService.DEFAULT_PAGE_BYTES
        } else {
            val parsed = scan.maxBytesArg.toIntOrNull()
            if (parsed == null || parsed <= 0) {
                return Invocation.Usage(
                    "Error: --max-bytes must be a positive integer, got: ${scan.maxBytesArg}",
                )
            }
            parsed
        }

        // An event cursor pasted here is a decode failure, not a silent offset into the wrong
        // bytes. The distinct token prefixes are the whole reason.
        val after = scan.afterCursor?.let { token ->
            OutputCursor.decode(token)
                ?: return Invocation.Usage("Error: not an output cursor token: $token")
        }

        val bounds = scan.range?.let { value ->
            val parts = value.split(':')
            val from = parts.getOrNull(0)?.toLongOrNull()
            val to = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size != 2 || from == null || to == null) {
                return Invocation.Usage("Error: --range must be FROM:TO with integers, got $value")
            }
            Pair(from, to)
        }

        return Invocation.Ok(
            controlDir = controlDir,
            runId = runId,
            opId = opId,
            maxBytes = maxBytes,
            after = after,
            range = bounds,
        )
    }

    fun main(args: Array<String>): Int {
        val invocation = when (val decision = parseInvocation(args)) {
            is Invocation.Usage -> return usageError(decision.message)
            is Invocation.Ok -> decision
        }

        val root = Path.of(invocation.controlDir)
        if (!java.nio.file.Files.isDirectory(root)) {
            return usageError("Error: control dir not found: ${invocation.controlDir}")
        }

        val result = invocation.range?.let { (from, to) ->
            ConsoleReadService.readRange(root, invocation.runId, invocation.opId, from, to)
        } ?: ConsoleReadService.read(
            root,
            invocation.runId,
            invocation.opId,
            invocation.after,
            invocation.maxBytes,
        )

        return emit(result, System.out, System.err)
    }

    /**
     * One refusal shape for every usage error, so the exit code and the stream cannot drift apart
     * between branches. The message is printed VERBATIM, which is what lets the missing-argument
     * case keep its `Usage: ...` line while the others start with `Error:`. Section 12 of
     * `CLI_OBSERVABILITY_SPEC.md` is the contract this implements.
     */
    private fun usageError(message: String): Int {
        System.err.println(message)
        return 2
    }

    /** Emit the result. Split out so a test drives it without capturing the real process streams. */
    fun emit(result: ConsoleReadService.Result, out: PrintStream, err: PrintStream): Int =
        when (result) {
            is ConsoleReadService.Result.Refused -> {
                err.println(ConsoleReadService.renderRefusal(result.reason))
                1
            }
            is ConsoleReadService.Result.Page -> {
                // Raw bytes. The transcript is not text-shaped in general and re-encoding it here
                // would corrupt anything that is not valid UTF-8.
                out.write(result.page.bytes)
                out.flush()
                result.page.next?.let { err.println(it.encode()) }
                0
            }
        }

    /** Convenience for a consumer that wants the whole stream; bounded by the store, not by memory. */
    fun readWholeStream(
        controlDirRoot: Path,
        runId: String,
        opId: String,
        maxBytes: Int = ConsoleReadService.DEFAULT_PAGE_BYTES,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = null
        var pages = 0
        while (true) {
            // Capped: a store that never ends the stream would otherwise loop here forever, in
            // production code, for any consumer that asks for the whole transcript.
            check(++pages < 1_000_000) { "readWholeStream did not terminate after $pages pages" }
            val result = ConsoleReadService.read(controlDirRoot, runId, opId, cursor, maxBytes)
            when (result) {
                is ConsoleReadService.Result.Refused -> throw IllegalStateException(
                    ConsoleReadService.renderRefusal(result.reason),
                )
                is ConsoleReadService.Result.Page -> {
                    out.write(result.page.bytes)
                    cursor = result.page.next ?: break
                }
            }
        }
        return out.toByteArray()
    }

    internal fun decodeForTest(token: String): OutputCursor? = OutputCursor.decode(token)

    internal fun streamIdFor(runId: String, opId: String): OutputStreamId =
        OutputPlaneProvider.streamId(runId, opId)

    internal fun utf8(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)
}
