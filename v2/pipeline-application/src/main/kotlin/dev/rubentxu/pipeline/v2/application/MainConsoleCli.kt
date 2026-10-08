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

    fun main(args: Array<String>): Int {
        var controlDir: String? = null
        var runId: String? = null
        var opId: String? = null
        var maxBytes = ConsoleReadService.DEFAULT_PAGE_BYTES
        var maxBytesArg: String? = null
        var afterCursor: String? = null
        var range: String? = null

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--control-dir" -> controlDir = args.getOrNull(++i)
                "--max-bytes" -> maxBytesArg = args.getOrNull(++i)
                "--after-cursor" -> afterCursor = args.getOrNull(++i)
                "--range" -> range = args.getOrNull(++i)
                else -> when {
                    !args[i].startsWith("--") && runId == null -> runId = args[i]
                    !args[i].startsWith("--") && opId == null -> opId = args[i]
                }
            }
            i++
        }

        if (controlDir == null || runId == null || opId == null) {
            System.err.println(
                "Usage: pipeline console --control-dir <path> <runId> <opId> " +
                    "[--max-bytes N] [--after-cursor TOKEN] | --range FROM:TO",
            )
            return 2
        }

        // A flag that does not parse must not become the default. `--max-bytes abc` used to read as
        // the default page size, which is the same shape as a command that silently did something
        // other than what was asked, and it was incoherent with the rest of this file. AUD-04.
        if (maxBytesArg != null) {
            val parsed = maxBytesArg.toIntOrNull()
            if (parsed == null || parsed <= 0) {
                System.err.println("Error: --max-bytes must be a positive integer, got: $maxBytesArg")
                return 2
            }
            maxBytes = parsed
        }

        val root = Path.of(controlDir)
        if (!java.nio.file.Files.isDirectory(root)) {
            System.err.println("Error: control dir not found: $controlDir")
            return 2
        }

        // An event cursor pasted here is a decode failure, not a silent offset into the wrong
        // bytes. The distinct token prefixes are the whole reason.
        val after = afterCursor?.let { token ->
            OutputCursor.decode(token) ?: run {
                System.err.println("Error: not an output cursor token: $token")
                return 2
            }
        }

        val result = if (range != null) {
            val bounds = range.split(':')
            val from = bounds.getOrNull(0)?.toLongOrNull()
            val to = bounds.getOrNull(1)?.toLongOrNull()
            if (bounds.size != 2 || from == null || to == null) {
                System.err.println("Error: --range must be FROM:TO with integers, got $range")
                return 2
            }
            ConsoleReadService.readRange(root, runId, opId, from, to)
        } else {
            ConsoleReadService.read(root, runId, opId, after, maxBytes)
        }

        return emit(result, System.out, System.err)
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
