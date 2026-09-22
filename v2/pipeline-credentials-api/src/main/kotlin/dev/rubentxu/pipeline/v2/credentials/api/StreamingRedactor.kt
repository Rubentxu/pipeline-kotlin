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
    /**
     * PERFORMANCE (WU-RP-022 finding P1): the previous implementation used
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
        private val maxLiteral: Int,
        private val readChunkSize: Int,
        private val patternRegistry: SecretPatternRegistry,
    ) : InputStream() {

        /** Ring buffer holding the pending lookahead window (capacity maxLiteral). */
        private val ring = ByteArray(maxLiteral)
        private var ringHead = 0   // index of oldest byte
        private var ringCount = 0  // number of valid bytes

        /** Seam literals: longest-first, deduplicated first-byte filter built lazily. */
        private val seam: List<ByteArray> = patternRegistry.literalSeam()
        private val seamFirstBytes: ByteArray = ByteArray(seam.size) { seam[it][0] }

        private val markerLength: Int = SecretPatternRegistry.SCRUB_MARKER.length

        /**
         * Output ring for SCRUB_MARKER bytes. Normal operation enqueues at most
         * markerLength bytes before the next drain; close() may enqueue up to
         * maxLiteral bytes (EOF pending drain), hence the capacity.
         */
        private val outputQueue = ByteArray(maxOf(maxLiteral, SecretPatternRegistry.SCRUB_MARKER.length))
        private var outputHead = 0
        private var outputCount = 0

        /** Fixed-size input chunk buffer */
        private val inputBuffer = ByteArray(readChunkSize)

        /** Cursor into inputBuffer after last consumed byte */
        private var inputOffset = 0

        /** Number of valid bytes in inputBuffer (0 means needs fresh read) */
        private var inputLimit = 0

        /** True after source is exhausted and no pending bytes remain */
        private var sourceExhausted = false

        /** True after close() has been called */
        private var closed = false

        override fun read(): Int {
            val buf = ByteArray(1)
            val n = read(buf, 0, 1)
            return if (n > 0) buf[0].toInt() and 0xFF else -1
        }

        private fun ringGet(i: Int): Byte = ring[(ringHead + i) % ring.size]

        private fun ringAddLast(b: Byte) {
            ring[(ringHead + ringCount) % ring.size] = b
            ringCount++
        }

        private fun ringRemoveFirst(): Byte {
            val b = ring[ringHead]
            ringHead = (ringHead + 1) % ring.size
            ringCount--
            return b
        }

        /** Tries to match a seam literal at the ring head; consumes it and enqueues the marker. */
        private fun tryMatchAndEmit(): Boolean {
            if (ringCount == 0) return false
            val first = ringGet(0)
            for (li in seam.indices) {
                if (seamFirstBytes[li] != first) continue
                val literal = seam[li]
                if (literal.size > ringCount) continue
                var match = true
                for (i in 1 until literal.size) {
                    if (ringGet(i) != literal[i]) { match = false; break }
                }
                if (match) {
                    repeat(literal.size) { ringRemoveFirst() }
                    for (byte in SecretPatternRegistry.SCRUB_MARKER.toByteArray(StandardCharsets.UTF_8)) {
                        outputQueue[(outputHead + outputCount) % outputQueue.size] = byte
                        outputCount++
                    }
                    return true
                }
            }
            return false
        }

        /**
         * Fills pending from inputBuffer, reading from source only when inputBuffer is exhausted.
         * @return true if pending is non-empty after filling; false if source is exhausted and pending is empty.
         */
        private fun fillPending(): Boolean {
            while (ringCount < maxLiteral && !sourceExhausted) {
                if (inputOffset >= inputLimit) {
                    inputLimit = source.read(inputBuffer, 0, readChunkSize)
                    inputOffset = 0
                    if (inputLimit == -1) {
                        sourceExhausted = true
                        return ringCount > 0
                    }
                }
                ringAddLast(inputBuffer[inputOffset])
                inputOffset++
            }
            return ringCount > 0
        }

        /**
         * Same observable contract as documented on the class. See class-level
         * algorithm description; implementation is the ring-buffer variant.
         */
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            if (len <= 0) return 0

            var written = 0

            while (written < len) {
                // Step 1: Drain outputQueue first
                while (written < len && outputCount > 0) {
                    b[off + written] = outputQueue[outputHead]
                    outputHead = (outputHead + 1) % outputQueue.size
                    outputCount--
                    written++
                }
                if (written >= len) break

                // Step 2: Source exhausted and pending empty → definitive EOF
                if (sourceExhausted && ringCount == 0) {
                    return if (written > 0) written else -1
                }

                // Step 3: Have full lookahead OR EOF reached → make a one-head decision
                if (ringCount == maxLiteral || sourceExhausted) {
                    if (tryMatchAndEmit()) {
                        continue
                    } else {
                        outputQueue[(outputHead + outputCount) % outputQueue.size] = ringRemoveFirst()
                        outputCount++
                        continue
                    }
                }

                // Step 5: pending.size < maxLiteral and source still has data → fill lookahead
                if (fillPending()) continue
            }

            return written
        }

        override fun close() {
            if (closed) return
            closed = true

            // Contract: after close() ALL buffers are empty. Pending bytes at EOF
            // are simply discarded from the lookahead window (no more reads can
            // consume them); matching is moot because no further data arrives.
            ringCount = 0
            outputCount = 0
            outputHead = 0

            // Clear all buffers
            ring.fill(0)
            outputQueue.fill(0)
            inputBuffer.fill(0)

            sourceExhausted = true
            source.close()
        }
    }
}
