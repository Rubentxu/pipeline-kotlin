package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.console.OperationSelection
import dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLines
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.application.observation.ObservationRecord
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputPage
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import java.io.PrintStream
import java.nio.charset.StandardCharsets

object ConsolePresentation {

    /**
     * Emit the filtered per-frame result.
     *
     * The filter is applied HERE, at emit time, against the [ConsoleFrameFilters.Active] ADT
     * the parser admitted. The predicate runs on each frame's stream id
     * (`<runId>/<opId>/<channel>`): the id is read from its two ends and the operation it
     * names is asked of the selection. The closed-format dispatch mirrors
     * [ConsolePresentation.emitFrames]: `TEXT` writes the merged bytes of the surviving frames, `JSONL` writes
     * one record per surviving frame, and the continuation token rides on the last surviving
     * record's ordinal so a `--follow` consumer resumes from the right place even when the
     * filter dropped frames in between.
*/
    fun emitFramesFiltered(
        result: ObservationOutputRead,
        request: ConsoleReadRequest.FramesFiltered,
        out: PrintStream,
        err: PrintStream,
        selection: OperationSelection = OperationSelection.AllOperations,
    ): Int = when (result) {
        is ObservationOutputRead.Refused -> {
            err.println(ConsoleReadService.renderRefusal(result.reason))
            1
        }

        is ObservationOutputRead.Page -> {
            // The SAME [OperationSelection] the follow loop consults, so the single read and
            // the follow loop cannot drift apart. One selection now answers "which frames?"
            // for every frame path.
            val filtered = result.page.records.filter { record -> ConsoleFramePlane.selectionSelects(selection, record.frame.stream.value) }
            val lastOrdinal = filtered.lastOrNull()?.frame?.ordinal ?: result.page.lastOrdinal
            // `moreFrames` is the index's "did the unfiltered walk cut a window short?" claim.
            // The walk did (or did not) regardless of which records we drop, so we keep the
            // unfiltered walk's signal so a follow consumer can resume.
            val moreFrames = result.page.moreFrames
            when (request.format) {
                MainConsoleCli.ConsoleFormat.TEXT -> {
                    for (record in filtered) out.write(record.bytes)
                    out.flush()
                    if (moreFrames) {
                        err.println(ConsoleFramePlane.encodeFrameOrdinal(lastOrdinal))
                    }
                    0
                }
                MainConsoleCli.ConsoleFormat.JSONL -> {
                    java.io.OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                        ObservationJsonLines.writeTo(
                            filtered,
                            writer,
                        )
                    }
                    if (moreFrames) {
                        err.println(ConsoleFramePlane.encodeFrameOrdinal(lastOrdinal))
                    }
                    0
                }
            }
        }
    }

    /**
     * Emit the per-frame result.
     *
     * The dispatch is on the closed [MainConsoleCli.ConsoleFormat] ADT:
     * - `TEXT` writes the merged bytes of the page's frames to stdout (in observation order,
     * one frame after another), with a continuation token on stderr carrying the ordinal of
     * the last frame written, so a consumer can resume from it.
     * - `JSONL` writes one [ObservationJsonLines] record per real frame, in observation
     * order, each carrying the frame's true [OutputFrame.ordinal] / [OutputFrame.from] /
     * [OutputFrame.to] / [OutputFrame.channel] plus the operation id parsed from the
     * stream. This is the per-frame wire shape [ObservationJsonLines] was already wired
     * to emit (the path was a synthetic-merged-page workaround).
*/
    fun emitFrames(
        result: ObservationOutputRead,
        request: ConsoleReadRequest.Frames,
        out: PrintStream,
        err: PrintStream,
    ): Int = when (result) {
        is ObservationOutputRead.Refused -> {
            err.println(ConsoleReadService.renderRefusal(result.reason))
            1
        }

        is ObservationOutputRead.Page -> {
            val page = result.page
            when (request.format) {
                MainConsoleCli.ConsoleFormat.TEXT -> {
                    // Concatenate the per-frame bytes. Frames in the page are already in
                    // observation order (the index owns that ordering). A consumer that
                    // wanted a per-frame line keeps the per-frame record on the wire by
                    // switching to --format jsonl; this path is the bytes-only view.
                    for (record in page.records) out.write(record.bytes)
                    out.flush()
                    if (page.moreFrames) {
                        // The continuation token is a decimal frame ordinal, not a byte
                        // cursor: the per-frame path is bounded by frame count and resumes
                        // by frame ordinal, and that is the only honest way to express it.
                        // It is the ordinal of the LAST frame this page delivered, because
                        // that is the position the next read continues from. The number of
                        // frames in the page is not that position: an operation whose frames
                        // begin after another operation's does not begin at the ordinal the
                        // read started from, and a token built from the count would send the
                        // consumer back into the page it already has.
                        err.println(ConsoleFramePlane.encodeFrameOrdinal(page.lastOrdinal))
                    }
                    0
                }
                MainConsoleCli.ConsoleFormat.JSONL -> {
                    // Per-frame records: one jsonl line per real frame, in observation order.
                    // The encoder [ObservationJsonLines.writeTo] flushes after each record
                    // so a `--follow` consumer does not wait for the page to fill. The
                    // writer wraps `out` in an OutputStreamWriter so a PrintStream reaches
                    // the Writer overload without losing the auto-flush behaviour the
                    // emitter relies on.
                    java.io.OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                        ObservationJsonLines.writeTo(
                            page.records,
                            writer,
                        )
                    }
                    if (page.moreFrames) {
                        // The same token the text form prints: the ordinal of the last frame
                        // delivered, which is where the next read continues from.
                        err.println(ConsoleFramePlane.encodeFrameOrdinal(page.lastOrdinal))
                    }
                    0
                }
            }
        }
    }

    /**
     * Emit the result. Split out so a test drives it without capturing the real process streams.
     *
     * The format dispatch is a `when` over the closed [MainConsoleCli.ConsoleFormat] ADT (two
     * cases: `TEXT` raw bytes, `JSONL` envelope). The `JSONL` path builds a synthetic
     * [ObservationRecord.Output] from the page so the existing [ObservationJsonLines] writer
     * stays the only encoder; an ordinal of `0L` and a synthetic
     * [OutputFrame] are honest about the fact that the merged page is one reader-side
     * projection, not a kernel-recorded frame.
*/
    fun emit(
        result: ConsoleReadService.Result,
        request: ConsoleReadRequest,
        out: PrintStream,
        err: PrintStream,
    ): Int = when (result) {
        is ConsoleReadService.Result.Refused -> {
            err.println(ConsoleReadService.renderRefusal(result.reason))
            1
        }
        is ConsoleReadService.Result.Page -> {
            when (request.format) {
                MainConsoleCli.ConsoleFormat.TEXT -> {
                    // Raw bytes. The transcript is not text-shaped in general and re-encoding it
                    // Here would corrupt anything that is not valid
                    out.write(result.page.bytes)
                    out.flush()
                    result.page.next?.let { err.println(it.encode()) }
                    0
                }
                MainConsoleCli.ConsoleFormat.JSONL -> emitJsonlPage(result.page, out, err)
            }
        }
    }

    /**
     * Emit one [ObservationJsonLines] record for a merged [OutputPage].
     *
     * The page names one stream (`page.stream`) and a byte range (`page.from..page.committedEnd`).
     * We construct an [ObservationRecord.Output] carrying the page's bytes so the existing
     * encoder handles the text/base64 split (`ObservationJsonLines.encodeOutputLine`). The
     * ordinal is `0L` (synthetic) and the [OutputFrame] is named from the page's reported
     * stream and range; the page's "next" cursor still goes to stderr so a `--follow` consumer
     * can resume without parsing the jsonl stream for control bytes.
*/
    private fun emitJsonlPage(
        page: OutputPage,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        // The merged page's stream id names the channel that contributed the FIRST byte
        // (see [ConsoleReadService.read]). `OutputStreamAddress.parse` reads it back; an
        // unknown shape falls back to STDOUT because a `null` channel would be a fact a
        // consumer would have to invent a default for, and `STDOUT` is the honest answer
        // when the read could not name anything else.
        val address = OutputStreamAddress.parse(page.stream)
        val channel = address?.channel ?: OutputChannel.STDOUT
        val syntheticFrame = OutputFrame(
            ordinal = 0L,
            stream = page.stream,
            channel = channel,
            from = page.from,
            to = page.committedEnd,
        )
        val record = ObservationRecord.Output(
            frame = syntheticFrame,
            bytes = page.bytes,
            text = String(page.bytes, StandardCharsets.UTF_8),
        )
        val writer = java.io.PrintWriter(java.io.OutputStreamWriter(out, StandardCharsets.UTF_8), true)
        ObservationJsonLines.writeTo(listOf(record), writer)
        writer.flush()
        page.next?.let { err.println(it.encode()) }
        return 0
    }

    /**
     * Emit the records of one poll to stdout, in the requested format.
     *
     * The follow path does not emit a continuation token to stderr: the per-poll ordinal is a
     * local cursor that does not survive the process, as the spec for `--follow` requires. A consumer
     * that needs to recover from a clip would re-invoke `pipelinek console --follow --after-ordinal
     * <N>` directly, the same way it started.
*/
    internal fun emitFollowPage(
        records: List<ObservationRecord.Output>,
        format: MainConsoleCli.ConsoleFormat,
        out: PrintStream,
        @Suppress("UNUSED_PARAMETER") err: PrintStream,
    ) {
        when (format) {
            MainConsoleCli.ConsoleFormat.TEXT -> {
                for (record in records) out.write(record.bytes)
                out.flush()
            }
            MainConsoleCli.ConsoleFormat.JSONL -> {
                // DO NOT wrap `out` in a writer and call `.use {}` on it: OutputStreamWriter's
                // close closes the underlying stream by default, and a `.use` block calls
                // close on exit. After the first follow page that pattern closes System.out,
                // and the next page's `out.write(...)` throws IOException — silently losing the
                // rest of the follow output. Use a writer that does NOT propagate close, and
                // flush it explicitly without closing.
                val noCloseOut = object : java.io.FilterOutputStream(out) {
                    override fun close() {
                        // No-op: the writer's own close would propagate to the underlying
                        // stream and close System.out between follow pages. We own the
                        // stream's lifetime; the JVM owns System.out.
                    }
                }
                val writer = java.io.OutputStreamWriter(noCloseOut, StandardCharsets.UTF_8)
                ObservationJsonLines.writeTo(
                    records,
                    writer,
                )
                writer.flush()
            }
        }
    }
}
