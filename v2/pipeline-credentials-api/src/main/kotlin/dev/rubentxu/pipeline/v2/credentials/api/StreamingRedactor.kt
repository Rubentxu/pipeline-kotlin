package dev.rubentxu.pipeline.v2.credentials.api

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

/**
 * The mutable state of a single [StreamingRedactor] pass.
 *
 * The redactor's state used to be hidden inside
 * [StreamingRedactor.RedactingInputStream], which made "no more data" and
 * "end of stream" the same event — `close()` did both, so a caller that
 * wanted to drain the lookahead at EOF also had to close the source. The
 * ingest agent needs to call them separately: the FIFO's read returns EOF
 * (kernel signal) and the agent then flushes the lookahead WITHOUT closing
 * the source. The state machine makes those two facts independent.
 *
 * One [RedactorState] is independent of every other: two passes with the
 * same [StreamingRedactor] (the same secret registry) do not share state.
 * The agent creates one per FIFO channel and feeds it as bytes arrive.
 */
class RedactorState internal constructor(
    /** The maximum encoded literal byte length, copied at construction. */
    val maxLiteral: Int,
    /** Scratch buffer length (caller's preferred chunk size). */
    val chunkSize: Int,
) {
    /** Ring buffer holding the pending lookahead window (capacity [maxLiteral]). */
    internal val ring = ByteArray(maxLiteral)
    internal var ringHead = 0
    internal var ringCount = 0

    /** Seam literals: longest-first, deduplicated first-byte filter. */
    internal lateinit var seam: List<ByteArray>
    internal lateinit var seamFirstBytes: ByteArray

    /**
     * Output ring for SCRUB_MARKER bytes. Capacity MUST accommodate the worst
     * case of a single [feed] call: a [feed] with `len` input bytes can emit
     * up to `len` bytes (one per input byte, when the lookahead ring is full),
     * plus the SCRUB_MARKER expansion. The agent's read buffer is `chunkSize`,
     * so a single feed can emit up to `chunkSize` bytes plus one SCRUB_MARKER.
     * Without this, a large `feed` call wraps the output ring and overwrites
     * bytes that the next [take] has not yet read — R2.3(b) / TODO 4 caught
     * Without this, a large `feed` call wraps the output ring and overwrites
     * bytes that the next [take] has not yet read — caught as "stdout garbled"
     * (outputQueue=56, feed emits 8136 bytes, first 25 are clobbered before
     * the take loop drains them).
     */
    internal val outputQueue = ByteArray(maxOf(maxLiteral, chunkSize) + SecretPatternRegistry.SCRUB_MARKER.length)
    internal var outputHead = 0
    internal var outputCount = 0

    /** True after [StreamingRedactor.finish] is called; no more bytes will be fed. */
    internal var sourceExhausted = false
}


/**
 * Stateful stream redaction decorator that scrubs secrets split across arbitrary
 * read boundaries using a bounded prefix-scanner algorithm.
 *
 * ## Bounded Prefix-Scanner Algorithm
 *
 * Unlike the failed carry-prefix approach that used scrub-and-slice on a shrinking
 * String, this implementation:
 *
 * 1. **Pending buffer**: Holds at most `maxLiteralByteLength` raw bytes from the
 *    source. This is the maximum lookahead needed to rule out any secret match
 *    at the pending head.
 *
 * 2. **Output queue**: Holds bytes waiting to be returned to the caller. When a
 *    secret literal is detected at the pending head, the raw literal bytes are
 *    consumed from pending and replaced with `SCRUB_MARKER` bytes in the output
 *    queue. The output queue allows the marker to be drained in smaller reads
 *    than the marker length.
 *
 * 3. **Input chunk buffer**: Fixed-size buffer for reading from the source.
 *
 * ## Memory Bound
 *
 * Per wrapped stream: `O(maxLiteralByteLength + chunkSize + markerLength)`
 * - pending: at most maxLiteralByteLength bytes
 * - outputQueue: at most markerLength bytes (SCRUB_MARKER = "****" = 4 bytes)
 * - inputBuffer: chunkSize bytes
 *
 * Total: O(maxLiteral + chunk + marker)
 *
 * ## Correctness Invariants
 *
 * - `InputStream.read(byte[], off, len)` with `len > 0` returns a positive count
 *   or -1; it NEVER returns 0 as an internal signal.
 * - A byte is NOT emitted from pending until enough lookahead exists to rule out
 *   every secret literal beginning at that byte.
 * - At EOF, the remaining pending buffer is drained by applying the same rule
 *   (but no match is possible since no more data will arrive).
 *
 * ## Re-entrancy
 *
 * Each `wrap()` returns a new `RedactingInputStream` instance with independent state.
 * Multiple concurrent `wrap()` calls on the same `StreamingRedactor` are fully
 * independent. `close()` clears all buffers and closes the source.
 *
 * @param registry The [SecretPatternRegistry] providing the active patterns.
 *                  Its [SecretPatternRegistry.maxLiteralByteLength] determines the
 *                  pending buffer size.
 * @param chunkSize The number of bytes to read from the underlying source
 *                  per `read()` call. Default 8192.
 */
class StreamingRedactor(
    private val registry: SecretPatternRegistry,
    private val chunkSize: Int = 8192,
) {

    /**
     * The maximum encoded literal byte length.
     *
     * This is the bound for the pending buffer: we need to hold at most this many
     * bytes to have sufficient lookahead to rule out any secret match at the
     * pending head.
     */
    val maxLiteralByteLength: Int = registry.maxLiteralByteLength()

    /**
     * The seam literals (longest-first) for this redactor, deduplicated by
     * first byte. Exposed for the state-machine API: [initialState] copies
     * the array references into the [RedactorState] so the per-pass state
     * does not need to re-scan the registry on every call.
     */
    private val seamLiterals: List<ByteArray> = registry.literalSeam()
    private val seamFirstBytes: ByteArray = ByteArray(seamLiterals.size) { seamLiterals[it][0] }

    /**
     * A fresh, independent [RedactorState] for one redaction pass.
     *
     * Each call returns a state with its own buffers, lookahead, and output
     * queue. The seam literal table is shared (read-only) with the redactor.
     */
    fun initialState(chunkSize: Int = this.chunkSize): RedactorState {
        val state = RedactorState(maxLiteralByteLength, chunkSize)
        state.seam = seamLiterals
        state.seamFirstBytes = seamFirstBytes
        return state
    }

    /**
     * Feeds [len] bytes from [bytes] (offset [off]) into the lookahead of
     * [state], emitting whatever is now decidable. The call is non-blocking
     * by design: it never reads from an [InputStream], only from the byte
     * array the caller hands it. EOF on the source is signalled separately
     * by [finish]; conflating the two is the bug the state machine removes.
     *
     * @return the updated [RedactorState]. The state is mutated in place
     *   and also returned, mirroring the typical Kotlin mutable-bag idiom.
     */
    fun feed(state: RedactorState, bytes: ByteArray, off: Int, len: Int): RedactorState {
        if (state.sourceExhausted) return state
        require(off >= 0 && len >= 0 && off + len <= bytes.size) {
            "feed($off, $len) is outside a ${bytes.size}-byte array"
        }
        var i = 0
        while (i < len) {
            // 1. Drain whatever was already decided before adding more.
            drainOneHead(state)
            // 2. Add a byte to the lookahead (if there's room).
            if (state.ringCount < state.maxLiteral) {
                state.ring[(state.ringHead + state.ringCount) % state.ring.size] = bytes[off + i]
                state.ringCount++
                i++
                continue
            }
            // 3. Ring is full but no decision was made. The seam table does
            //    not cover this prefix, so the head byte is NOT the start of
            //    a secret. Emit it as plain text and drop it, then re-try
            //    the new head on the next iteration.
            emitHeadAsPlain(state)
        }
        // One more pass to drain any decision that became possible after the
        // last byte was added.
        drainOneHead(state)
        return state
    }

    /**
     * Drains the decided bytes from [state] into [bytes] starting at [off],
     * up to [maxLen] bytes. Returns the number of bytes copied. The agent
     * is expected to call this after every [feed] (and once more after
     * [finish]) and to flush the result through the Output Plane sink.
     */
    fun take(state: RedactorState, bytes: ByteArray, off: Int, maxLen: Int): Int {
        var written = 0
        while (written < maxLen && state.outputCount > 0) {
            bytes[off + written] = state.outputQueue[state.outputHead]
            state.outputHead = (state.outputHead + 1) % state.outputQueue.size
            state.outputCount--
            written++
        }
        return written
    }

    /**
     * How many decided bytes are currently sitting in [state]'s output
     * queue. Useful for callers that want to skip the [take] call when the
     * queue is empty; the agent uses the return value of [take] directly
     * and does not need this, but it is part of the public surface.
     */
    fun available(state: RedactorState): Int = state.outputCount

    /**
     * Marks [state] as exhausted: no more bytes will be fed. Bytes still in
     * the lookahead are now decidable (no future data can complete a
     * partial literal), so [finish] drains them through the same matcher
     * used by [feed]. The returned byte array is the final flush; the
     * caller MUST persist it before discarding the state.
     *
     * [finish] is idempotent: a second call returns an empty array.
     */
    fun finish(state: RedactorState): ByteArray {
        if (state.sourceExhausted) return ByteArray(0)
        state.sourceExhausted = true
        // The lookahead is now decidable. Run the same one-head decision
        // loop as [feed] until the ring is empty.
        while (state.ringCount > 0) {
            // `atEof = true`: no more bytes can arrive, so a literal longer than what is buffered
            // must be matched by the prefix that IS present rather than deferred forever.
            if (!drainOneHead(state, atEof = true)) {
                // No seam literal starts with this byte; emit it as-is.
                state.outputQueue[(state.outputHead + state.outputCount) % state.outputQueue.size] =
                    state.ring[state.ringHead]
                state.outputHead = state.outputHead // unchanged
                state.outputCount++
                state.ringHead = (state.ringHead + 1) % state.ring.size
                state.ringCount--
            }
        }
        val out = ByteArray(state.outputCount)
        for (i in 0 until state.outputCount) {
            out[i] = state.outputQueue[(state.outputHead + i) % state.outputQueue.size]
        }
        // Zero the queue so the state is safe to discard; the secret-bearing
        // bytes are no longer reachable from it.
        state.outputHead = 0
        state.outputCount = 0
        java.util.Arrays.fill(state.outputQueue, 0.toByte())
        return out
    }

    /**
     * The only way out of a full ring with nothing decidable.
     *
     * Emit the ring head as plain text. It is emitted rather than dropped because a byte inside
     * the lookahead has to reach the Output Plane eventually, and it is emitted NOW because the
     * alternative is a stall: see [RedactingInputStream.read].
     *
     * Called from two places, and that is the whole point. [feed] reached it while it still had
     * input to consume; the InputStream wrapper needs it too, because the wrapper's feed is
     * bounded by the ring's FREE SPACE — so when the ring is already full the wrapper can never
     * call `feed` at all, and without this method it has no branch that makes progress.
     */
    private fun relieveFullRing(state: RedactorState) {
        if (state.ringCount < state.maxLiteral) return
        if (drainOneHead(state)) return
        emitHeadAsPlain(state)
    }

    /** Move the ring head to the output queue verbatim, and make room in the ring. */
    private fun emitHeadAsPlain(state: RedactorState) {
        state.outputQueue[(state.outputHead + state.outputCount) % state.outputQueue.size] =
            state.ring[state.ringHead]
        state.outputCount++
        state.ringHead = (state.ringHead + 1) % state.ring.size
        state.ringCount--
    }

    /**
     * Try to match a seam literal at the ring head. If matched, the literal
     * bytes are consumed from the ring and the SCRUB_MARKER is enqueued.
     * @return true if a literal was matched (one head byte was decided).
     */
    private fun drainOneHead(state: RedactorState, atEof: Boolean = false): Boolean {
        if (state.ringCount == 0) return false
        val first = state.ring[state.ringHead]
        for (li in state.seam.indices) {
            if (state.seamFirstBytes[li] != first) continue
            val literal = state.seam[li]
            if (literal.size > state.ringCount) {
                // A literal that does not FIT yet means the head is UNDECIDED, and
                // undecided must not fall through to a shorter literal.
                //
                // This used to `continue`, so the scan went on to the next literal. When two
                // secrets share a prefix (`SECRET-AB` and `SECRET-ABCDEFGH`) and the ring held
                // fewer bytes than the longer one, the longer was skipped and the SHORTER matched
                // — so the real secret was replaced by `****` and its own tail, `CDEFGH`, was
                // written to the Output Plane in the clear:
                //
                //   input   SECRET-ABCDEFGH;SECRET-AB;SECRET-ABCDEFGH
                //   output  ****CDEFGH;****;****          <- the tail of a real secret, in clear
                //
                // The ring is sized to the LONGEST literal, so a full ring is always decidable and
                // this arm is only reachable with a partly filled ring — which is the normal state
                // while the source is still producing. At EOF no more bytes can arrive, so the
                // scan proceeds and takes the longest literal that fits; `finish` passes atEof.
                if (!atEof) return false
                continue
            }
            var match = true
            for (i in 1 until literal.size) {
                if (state.ring[(state.ringHead + i) % state.ring.size] != literal[i]) {
                    match = false
                    break
                }
            }
            if (match) {
                repeat(literal.size) {
                    state.ringHead = (state.ringHead + 1) % state.ring.size
                    state.ringCount--
                }
                for (byte in SecretPatternRegistry.SCRUB_MARKER.toByteArray(StandardCharsets.UTF_8)) {
                    state.outputQueue[(state.outputHead + state.outputCount) % state.outputQueue.size] = byte
                    state.outputCount++
                }
                return true
            }
        }
        return false
    }

    /**
     * Decorates an [InputStream] with secret redaction across chunk boundaries.
     *
     * The returned stream must be closed by the caller to trigger final flush
     * and zero the internal buffers.
     *
     * @param input The underlying stream to decorate.
     * @return A redaction [InputStream] wrapping [input].
     */
    fun wrap(input: InputStream): InputStream =
        RedactingInputStream(input, initialState())

    /**
     * Inner [InputStream] decorator backed by a [RedactorState]. The state
     * is the single source of truth for the redaction decision; the wrapper
     * just shovels bytes between the [InputStream] and the public state
     * machine, so the [wrap] and the agent's direct state-machine use cannot
     * drift.
     *
     * Each wrapped stream has its own state, so multiple concurrent wraps
     * are safe.
     */
    /**
     * PERFORMANCE: the previous implementation used
     * `ArrayDeque<Byte>` (one boxed Byte per byte) plus a full pendingArray copy
     * and a linear scan over every seam literal PER BYTE. Throughput measured at
     * ~100 KB/s, which stalls any pipeline whose child writes >~100 MiB to the
     * console transcript (observed: 200 MiB run busy-spinning at 100% CPU for
     * 36+ minutes without completing).
     *
     * This rewrite keeps the EXACT same observable contract (see class docs and
     * StreamingRedactorTest, 23 tests):
     * - never returns 0 from read(b, off, len>0);
     * - a byte is only emitted once maxLiteral lookahead rules out every seam literal;
     * - at EOF remaining pending bytes are emitted as-is;
     * - close() drains pending, clears buffers, closes the source.
     *
     * Implementation: ring buffer of primitive bytes with incremental
     * longest-first literal matching. Per emitted byte the cost is
     * O(seam_literals x literal_length) only for bytes that share a prefix with
     * a literal; the common path is a single first-byte comparison per literal.
     */
    internal inner class RedactingInputStream(
        private val source: InputStream,
        private val state: RedactorState,
    ) : InputStream() {

        /** Fixed-size input chunk buffer used by the wrapper's read loop. */
        private val inputBuffer = ByteArray(state.chunkSize)

        /** Cursor into inputBuffer after last consumed byte */
        private var inputOffset = 0

        /** Number of valid bytes in inputBuffer (-1 means EOF reached). */
        private var inputLimit = 0

        /** True after close() has been called */
        private var closed = false

        /**
         * The part of the EOF flush this call could not hand back.
         *
         * `finish` empties the state's output queue and zeroes it, so a flush that does not fit
         * the caller's buffer is otherwise **gone**: the bytes left the pipe, the state was
         * cleared, and the next `read` would have said "no more data" about a transcript that had
         * more in it. Held here so the next call serves the remainder, which is the only
         * difference between "a small read" and "truncated output".
         */
        private var finalFlush: ByteArray = ByteArray(0)
        private var finalFlushOffset = 0

        override fun read(): Int {
            val buf = ByteArray(1)
            val n = read(buf, 0, 1)
            return if (n > 0) buf[0].toInt() and 0xFF else -1
        }

        /**
         * Same observable contract as documented on the class. Backed by the
         * shared [RedactorState] so the agent's direct use of the state
         * machine and the InputStream wrapper cannot diverge.
         */
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            if (len <= 0) return 0

            var written = 0

            while (written < len) {
                // Step 0 — serve the retained EOF flush BEFORE anything else.
                //
                // It is first because it is the only step that can still have bytes: `finish`
                // empties and zeroes the state, so once it has run the queue is gone for good.
                // The old code copied `min(final.size, len - written)` and returned, throwing
                // the remainder away — a one-byte read at EOF truncated the transcript by
                // everything it could not hold.
                if (finalFlushOffset < finalFlush.size) {
                    val n = minOf(finalFlush.size - finalFlushOffset, len - written)
                    System.arraycopy(finalFlush, finalFlushOffset, b, off + written, n)
                    finalFlushOffset += n
                    written += n
                    continue
                }

                // Step 1: Drain outputQueue first via the public state machine.
                written += this@StreamingRedactor.take(state, b, off + written, len - written)
                if (written >= len) break

                // Step 2: Source exhausted (state.ringCount == 0 because finish
                // was called via close()) and pending empty → EOF.
                if (state.sourceExhausted && state.ringCount == 0) {
                    return if (written > 0) written else -1
                }

                // Step 2.5 — hand back what has been DECIDED, before touching the source again.
                //
                // This is the same guard that used to sit after Step 4, where it could not fire in
                // the case that mattered. Step 3 refills `inputBuffer` by BLOCKING on
                // `source.read`, and it runs first, so by the time the old guard was evaluated the
                // buffer was full again and its `inputOffset >= inputLimit` condition no longer
                // held. The guard could therefore only ever fire after a blocking read — which is
                // the opposite of what it was written to prevent.
                //
                // With the guard unreachable the wrapper instead filled the
                // entire buffer or blocked, so:
                //
                //   15-byte payload     -> nothing committed mid-step (waits for EOF)
                //   128 KiB payload     -> exactly one 64 KiB window committed, then a block
                //
                // Placing the guard BEFORE the refill is the
                // minimal change: it can only fire when `written > 0` and the input buffer is
                // drained, so it never returns before any decision has been made.
                //
                // Short reads are legal for `InputStream.read(b, off, len)` and every consumer here
                // already handles them: `DurableShellExecutor`'s pump loops on the returned count,
                // and `ObsPc2IngestAgent` writes whatever `n` it gets. Nothing depends on a full fill.
                if (written > 0 && !state.sourceExhausted && inputOffset >= inputLimit) {
                    return written
                }

                // Step 3: Fill the lookahead from the source if we have room.
                if (inputOffset >= inputLimit && !state.sourceExhausted) {
                    inputLimit = source.read(inputBuffer, 0, state.chunkSize)
                    inputOffset = 0
                    if (inputLimit == -1) {
                        // No more data; flush the lookahead as finalised bytes. The WHOLE flush is
                        // retained, and Step 0 hands back whatever this call cannot take.
                        finalFlush = this@StreamingRedactor.finish(state)
                        finalFlushOffset = 0
                        continue
                    }
                }
                if (inputOffset < inputLimit) {
                    val space = state.maxLiteral - state.ringCount
                    if (space > 0) {
                        val chunk = minOf(inputLimit - inputOffset, len - written, space)
                        this@StreamingRedactor.feed(state, inputBuffer, inputOffset, chunk)
                        inputOffset += chunk
                        continue
                    }
                    // The ring is FULL and nothing is decidable at its head.
                    //
                    // This is the branch that used to not exist. The feed above is bounded by the
                    // ring's FREE SPACE, so a full ring means the wrapper can never call `feed`,
                    // and the early-return below requires room to return. Every path through the
                    // loop then left `written` unchanged: a busy loop at 100% CPU that consumed
                    // nothing while the child kept writing. An empty registry made it near-certain,
                    // because with no secrets almost no head is ever decidable and the ring fills
                    // after a handful of bytes.
                    //
                    // Emitting the head is the only way out, and it is SAFE: the head is being
                    // emitted precisely because no literal starts with it, so no secret can span
                    // it. It is also not optional for correctness — a redactor that cannot emit a
                    // byte it has already decided is not withholding anything a secret needs.
                    this@StreamingRedactor.relieveFullRing(state)
                    continue
                }

                // Step 4: pending.size < maxLiteral and source still has data → fill
                // lookahead. Return what we have rather than blocking on the source.
                //
                // `InputStream.read` is allowed to return fewer than `len` bytes, and forcing a full
                // fill is what stranded the transcript. `available()` says how much can be emitted
                // without touching the source; a caller that trusts it asks for exactly that. This
                // method then consumed the buffer, found the lookahead ring short and the input
                // buffer drained, and called `source.read()` — which blocks for as long as the
                // producer chooses to say nothing. The bytes it had already decided stayed inside
                // this object and never reached the caller.
                if (written > 0 && !state.sourceExhausted && state.ringCount < state.maxLiteral && inputOffset >= inputLimit) {
                    return written
                }
            }

            return written
        }

        /**
         * How many sanitized bytes can be emitted RIGHT NOW without reading more from the source.
         *
         * Backed by the shared state machine; the InputStream wrapper contributes
         * no decision of its own.
         */
        override fun available(): Int {
            if (closed) return 0
            val bufferedFromSource = maxOf(0, inputLimit - inputOffset)
            val pending = state.ringCount + bufferedFromSource
            val decidable = if (state.sourceExhausted) pending else maxOf(0, pending - state.maxLiteral)
            return state.outputCount + decidable
        }

        override fun close() {
            if (closed) return
            closed = true
            // Drain the lookahead through the public state machine so the
            // wrapper and the agent's direct state-machine use produce the
            // same final bytes.
            if (!state.sourceExhausted) {
                val final = this@StreamingRedactor.finish(state)
                // Best-effort write to a discarded buffer; the caller has
                // already given up on the source.
                @Suppress("UNUSED_VARIABLE") val unused = final
            }
            // Zero the state so it is safe to discard. The retained EOF flush is zeroed for the
            // same reason: it is decided output, and decided output can still be secret-shaped.
            //
            // `inputBuffer` is zeroed too, and that is not incidental. It holds RAW bytes straight
            // off the pipe — including any that were never fed, because the ring filled first —
            // so leaving them in a discarded object is exactly the leak the whole class exists to
            // prevent.
            java.util.Arrays.fill(inputBuffer, 0.toByte())
            inputOffset = 0
            inputLimit = 0
            if (finalFlush.isNotEmpty()) java.util.Arrays.fill(finalFlush, 0.toByte())
            finalFlush = ByteArray(0)
            finalFlushOffset = 0
            java.util.Arrays.fill(state.ring, 0.toByte())
            state.ringHead = 0
            state.ringCount = 0
            source.close()
        }
    }
}
