package dev.rubentxu.pipeline.v2.credentials.api

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

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
     * Decorates an [InputStream] with secret redaction across chunk boundaries.
     *
     * The returned stream must be closed by the caller to trigger final flush
     * and zero the internal buffers.
     *
     * @param input The underlying stream to decorate.
     * @return A redaction [InputStream] wrapping [input].
     */
    fun wrap(input: InputStream): InputStream =
        RedactingInputStream(input, maxLiteralByteLength, chunkSize, registry)

    /**
     * Inner [InputStream] decorator with its own buffer state.
     *
     * Each wrapped stream has independent pending (maxLiteralByteLength),
     * outputQueue (SCRUB_MARKER length), and inputBuffer (chunkSize) —
     * so multiple concurrent wraps are safe.
     */
    internal inner class RedactingInputStream(
        private val source: InputStream,
        private val maxLiteral: Int,
        private val readChunkSize: Int,
        private val patternRegistry: SecretPatternRegistry,
    ) : InputStream() {

        /** Pending raw bytes: bounded by maxLiteral */
        private val pending = ArrayDeque<Byte>()

        /** Output queue for pending SCRUB_MARKER bytes (bounded by SCRUB_MARKER.length) */
        private val outputQueue = ArrayDeque<Byte>()

        /** Fixed-size input chunk buffer */
        private val inputBuffer = ByteArray(readChunkSize)

        /** Cursor into inputBuffer after last consumed byte */
        private var inputOffset = 0

        /** Number of valid bytes in inputBuffer (0 means needs fresh read) */
        private var inputLimit = 0

        /** Marker bytes as UTF-8 for efficient enqueuing */
        private val markerBytes = SecretPatternRegistry.SCRUB_MARKER.toByteArray(StandardCharsets.UTF_8)

        /** True after source is exhausted and no pending bytes remain */
        private var sourceExhausted = false

        /** True after close() has been called */
        private var closed = false

        override fun read(): Int {
            val buf = ByteArray(1)
            val n = read(buf, 0, 1)
            return if (n > 0) buf[0].toInt() and 0xFF else -1
        }

        /**
         * Fills pending from inputBuffer, reading from source only when inputBuffer is exhausted.
         * Bytes beyond maxLiteral remain in inputBuffer for subsequent calls.
         * @return true if pending is non-empty after filling; false if source is exhausted and pending is empty.
         */
        private fun fillPending(): Boolean {
            while (pending.size < maxLiteral && !sourceExhausted) {
                if (inputOffset >= inputLimit) {
                    inputLimit = source.read(inputBuffer, 0, readChunkSize)
                    inputOffset = 0
                    if (inputLimit == -1) {
                        sourceExhausted = true
                        return pending.isNotEmpty()
                    }
                }
                pending.addLast(inputBuffer[inputOffset])
                inputOffset++
            }
            return pending.isNotEmpty()
        }

        /**
         * Reads bytes from the stream, redacting any secrets found.
         *
         * Never returns 0 as an internal signal. Returns:
         * - positive count: number of bytes written to b
         * - -1: end of stream reached and no more bytes to emit
         *
         * The algorithm:
         * 1. Drain outputQueue into caller buffer (fills len or until empty)
         * 2. If caller buffer not full:
         *    a. If source exhausted and pending empty → return -1 (done)
         *    b. If source exhausted with pending bytes → drain all pending to outputQueue, continue
         *    c. If pending.size == maxLiteral OR source exhausted → make a decision
         *       - If match: enqueue marker, continue
         *       - If no match: enqueue one byte, continue
         *    d. Otherwise: fill pending (may read another source chunk to establish EOF)
         *    e. Repeat until caller buffer is full or definitive EOF
         * 3. Return bytes written to caller buffer
         */
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            if (len <= 0) return 0

            var written = 0

            // Main read loop: fill caller's buffer
            while (written < len) {
                // Step 1: Drain outputQueue first
                while (written < len && outputQueue.isNotEmpty()) {
                    b[off + written] = outputQueue.removeFirst()
                    written++
                }
                if (written >= len) break

                // Step 2: Source exhausted and pending empty → definitive EOF
                if (sourceExhausted && pending.isEmpty()) {
                    return if (written > 0) written else -1
                }

                // Step 3: Have full lookahead OR EOF reached → make a one-head decision
                if (pending.size == maxLiteral || sourceExhausted) {
                    if (tryMatchAndEmit()) {
                        continue
                    } else {
                        val byte = pending.removeFirst()
                        outputQueue.addLast(byte)
                        continue
                    }
                }

                // Step 5: pending.size < maxLiteral and source still has data → fill lookahead
                if (fillPending()) continue
            }

            return written
        }

        /**
         * Attempts to match a secret literal at the pending head.
         *
         * @return true if a match was found (raw literal bytes consumed from pending,
         *         SCRUB_MARKER bytes enqueued to outputQueue); false if no match
         *         (pending unchanged, caller should emit one byte)
         */
        private fun tryMatchAndEmit(): Boolean {
            if (pending.isEmpty()) return false

            // Get pending as ByteArray for efficient matching
            val pendingSize = pending.size
            val pendingArray = ByteArray(pendingSize)
            var idx = 0
            for (byte in pending) {
                pendingArray[idx++] = byte
            }

            // Get the ordered literal seam (longest-first)
            val seam = patternRegistry.literalSeam()

            for (literal in seam) {
                if (literal.size > pendingSize) continue
                if (literal.size == 0) continue

                // Check if pending starts with this literal
                var match = true
                for (i in literal.indices) {
                    if (pendingArray[i] != literal[i]) {
                        match = false
                        break
                    }
                }

                if (match) {
                    // Consume the raw literal bytes from pending
                    for (i in 0 until literal.size) {
                        pending.removeFirst()
                    }
                    // Enqueue SCRUB_MARKER bytes to outputQueue
                    for (byte in markerBytes) {
                        outputQueue.addLast(byte)
                    }
                    return true
                }
            }

            return false
        }

        override fun close() {
            if (closed) return
            closed = true

            // At EOF, drain remaining pending bytes as non-secrets (no more data will arrive)
            while (pending.isNotEmpty()) {
                // No match is possible when source is exhausted, but we still
                // process one byte at a time to maintain the output queue pattern
                outputQueue.addLast(pending.removeFirst())
            }

            // Clear all buffers
            pending.clear()
            outputQueue.clear()
            inputBuffer.fill(0)

            sourceExhausted = true
            source.close()
        }
    }
}
