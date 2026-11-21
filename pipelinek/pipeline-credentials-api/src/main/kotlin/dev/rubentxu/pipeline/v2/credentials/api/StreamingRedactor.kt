package dev.rubentxu.pipeline.v2.credentials.api

import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * The mutable state of a single [StreamingRedactor] pass.
 * The redactor's state used to be hidden inside
 * [StreamingRedactor.RedactingInputStream], which made "no more data" and
 * "end of stream" the same event — `close()` did both, so a caller that
 * wanted to drain the lookahead at EOF also had to close the source. The
 * ingest agent needs to call them separately: the FIFO's read returns EOF
 * (kernel signal) and the agent then flushes the lookahead WITHOUT closing
 * the source. The state machine makes those two facts independent.
 * One [RedactorState] is independent of every other: two passes with the
 * same [StreamingRedactor] (the same secret registry) do not share state.
 * The agent creates one per FIFO channel and feeds it as bytes arrive.
*/
class RedactorState internal constructor(
    maxLiteral: Int,
    /** Scratch buffer length (caller's preferred chunk size). */
    val chunkSize: Int,
) {
    /**
     * The window of this pass: the maximum encoded literal byte length the pass holds.
     *
     * The window FOLLOWS the registry: a value that is registered while the pass is
     * running makes the window wider, because the window is what lets a whole value be
     * masked as ONE marker instead of one marker per piece of it. The window only grows:
     * a narrower window would drop the bytes that are still pending, and a byte that
     * leaves the transcript is worse than a byte that stays in it.
     */
    var maxLiteral: Int = maxLiteral
        private set

    /** Ring buffer holding the pending lookahead window (capacity [maxLiteral]). */
    internal var ring = ByteArray(maxLiteral)
    internal var ringHead = 0
    internal var ringCount = 0

    /** The fragments this pass matches, captured when the pass starts. */
    internal lateinit var matcher: FragmentMatcher

    /**
     * The version of the registry's seam this pass captured.
     *
     * It is what tells the pass that the seam it holds is no longer the seam of the
     * registry, and it is compared with one read per fragment.
     */
    internal var seamVersion: Int = 0

    /**
     * Widens the window of this pass to [newWindow] bytes, keeping the bytes that are
     * still pending.
     *
     * The pending bytes are copied in order, because they are bytes of the transcript
     * that nobody has decided yet, and a window that dropped them would take them out of
     * the transcript. A window that is already wide enough is left alone.
     */
    internal fun widenWindow(newWindow: Int) {
        if (newWindow <= maxLiteral) return
        val widened = ByteArray(newWindow)
        for (i in 0 until ringCount) {
            widened[i] = ring[(ringHead + i) % ring.size]
        }
        java.util.Arrays.fill(ring, 0.toByte())
        ring = widened
        ringHead = 0
        maxLiteral = newWindow
    }

    /**
     * The DECIDED bytes waiting for the caller to take them.
     *
     * A decided byte is one the redactor has already settled: plain text, or the
     * SCRUB_MARKER standing for a secret. Decided bytes ARE the transcript, so
     * **a decided byte is never written over**. The queue therefore grows to
     * hold what was decided instead of relying on a capacity a later call could
     * exceed: a fixed ring is only safe while every caller feeds no more than
     * the ring was sized for, and that is a promise about callers living
     * somewhere else in the tree. A queue that wraps eats the beginning of a
     * transcript — the bytes the reader had not taken yet — so this one grows.
     *
     * What it holds is the decided bytes the caller has NOT taken. A caller that
     * takes what it decided after every feed keeps it to one feed's worth.
    */
    internal var outputQueue = ByteArray(maxOf(maxLiteral, chunkSize))
    internal var outputHead = 0
    internal var outputCount = 0

    /**
     * Makes room for [needed] more decided bytes, growing the queue if it must.
     *
     * The bytes already decided are copied over in order, so growing changes
     * where they live and never what they are. The old allocation is wiped
     * before it is dropped: it held decided bytes, and a discarded buffer is one
     * the collector keeps around for a while.
    */
    internal fun reserveOutput(needed: Int) {
        if (outputQueue.size - outputCount >= needed) return
        var capacity = outputQueue.size
        while (capacity - outputCount < needed) capacity *= 2
        val grown = ByteArray(capacity)
        for (i in 0 until outputCount) grown[i] = outputQueue[(outputHead + i) % outputQueue.size]
        java.util.Arrays.fill(outputQueue, 0.toByte())
        outputQueue = grown
        outputHead = 0
    }

    /** True after [StreamingRedactor.finish] is called; no more bytes will be fed. */
    internal var sourceExhausted = false
}


/**
 * Stateful stream redaction decorator that scrubs secrets split across arbitrary
 * read boundaries using a bounded prefix-scanner algorithm.
 *
 * ## What it guarantees
 * A registered secret is masked wherever it appears, and so is every piece of it that
 * has at least `fragmentThresholdBytes` bytes - even when the flow delivers the value in
 * pieces separated by bytes that are not part of it. The bytes that are not part of the
 * value come out as they went in.
 *
 * The threshold is the one the constructor names. A redactor built without naming one
 * masks the pieces that are at least [SecretPatternRegistry.MIN_MASKABLE_LENGTH] bytes
 * long.
 *
 * ## What it does not promise, and says so here
 * - **A piece shorter than the threshold is not protected.** The threshold counts BYTES,
 *   because the seam is bytes. A piece of fewer bytes than the threshold is not masked,
 *   and that is the price of a redactor that does not destroy ordinary output.
 * - **A transformation of the value that is not one of the encoded variants is not
 *   detected.** The masked forms are the value itself, its base64 forms, its hexadecimal
 *   forms and its URL-encoded form. A value that reaches the transcript in a shape this
 *   product does not know about is not masked by this class.
 * - **Text that holds a piece of the threshold length is masked, whether it is part of
 *   the secret or not.** That is the known false positive of a fragment-aware redactor,
 *   and it is the reason the threshold exists.
 *
 * ## Bounded Prefix-Scanner Algorithm
 * Unlike the failed carry-prefix approach that used scrub-and-slice on a shrinking
 * String, this implementation:
 * 1. **Pending buffer**: Holds at most `maxLiteralByteLength` raw bytes from the
 *    source. This is the maximum lookahead needed to rule out any secret match
 *    at the pending head.
 * 2. **Output queue**: Holds bytes waiting to be returned to the caller. When a
 *    secret literal is detected at the pending head, the raw literal bytes are
 *    consumed from pending and replaced with `SCRUB_MARKER` bytes in the output
 *    queue. The output queue allows the marker to be drained in smaller reads
 *    than the marker length.
 * 3. **Input chunk buffer**: Fixed-size buffer for reading from the source.
 * ## Memory Bound
 * Per wrapped stream: `O(maxLiteralByteLength + chunkSize + markerLength)`
 * - pending: at most maxLiteralByteLength bytes
 * - outputQueue: the DECIDED bytes the caller has not taken yet. A caller that
 *   takes what it decided after every feed keeps this to one feed's worth.
 * - inputBuffer: chunkSize bytes
 * Total: O(maxLiteral + chunk + marker)
 * ## Correctness Invariants
 * - `InputStream.read(byte[], off, len)` with `len > 0` returns a positive count
 *   or -1; it NEVER returns 0 as an internal signal.
 * - A byte is NOT emitted from pending until enough lookahead exists to rule out
 *   every secret literal beginning at that byte.
 * - A DECIDED byte is never written over. The decided bytes ARE the transcript,
 *   and a transcript that loses bytes to its own buffer is worse than no
 *   transcript at all.
 * - At EOF, the remaining pending buffer is drained by applying the same rule
 *   (but no match is possible since no more data will arrive).
 * ## When the seam is captured, and what a later registration does
 * A pass captures the seam of the registry when it starts, and it does NOT keep that
 * capture for the whole of its life: the registry declares when its seam changed, and
 * the pass reloads it as soon as it sees that. So a value that is registered AFTER the
 * pass started is masked in the bytes that arrive after the registration.
 *
 * The reload keeps the state of the pass, and that is the whole point of it:
 * - The bytes that are still PENDING were never decided, so the new seam decides
 *   them. A value split across two fragments stays masked across a registration that
 *   happens between them.
 * - The bytes that were DECIDED are the transcript, and they are never rewritten. A
 *   registration cannot un-say what the transcript already said.
 * - The window only grows. A registration that makes the window wider widens the
 *   lookahead and keeps the pending bytes in it; a registration that would make it
 *   narrower leaves it alone, because a narrower window would drop bytes that are
 *   still pending.
 *
 * What a registration cannot do is reach the bytes that were decided before it
 * happened: those bytes were the transcript before the value was registered, and the
 * pass does not rewrite them.
 * ## Re-entrancy
 * Each `wrap()` returns a new `RedactingInputStream` instance with independent state.
 * Multiple concurrent `wrap()` calls on the same `StreamingRedactor` are fully
 * independent. `close()` clears all buffers and closes the source.
 * @param registry The [SecretPatternRegistry] providing the active patterns.
 *                  Its [SecretPatternRegistry.maxLiteralByteLength] determines the
 *                  pending buffer size, and the pass follows it when the registry
 *                  changes.
 * @param chunkSize The number of bytes to read from the underlying source
 *                  per `read()` call. Default 8192.
*/
class StreamingRedactor(
    private val registry: SecretPatternRegistry,
    private val chunkSize: Int = 8192,
) {

    /**
     * The number of bytes a piece of a secret must have to be masked.
     *
     * The default is [SecretPatternRegistry.MIN_MASKABLE_LENGTH], which is the
     * threshold a redactor built without an explicit one uses. A caller that wants
     * another threshold names it in the constructor that takes it, and the value is
     * fixed from that moment: the property is private, so neither a caller nor a
     * pass can change it.
     */
    private var fragmentThresholdBytes: Int = SecretPatternRegistry.MIN_MASKABLE_LENGTH

    /**
     * Creates a redactor that masks the pieces of a secret that are at least
     * `fragmentThresholdBytes` bytes long.
     *
     * This is the constructor that lets a caller choose the threshold. Its three
     * arguments are explicit and carry no default: a redactor built without them
     * masks the pieces that are at least
     * [SecretPatternRegistry.MIN_MASKABLE_LENGTH] bytes long, and a caller that
     * wants another threshold says so here.
     *
     * @param registry The [SecretPatternRegistry] providing the active patterns.
     * @param chunkSize The number of bytes to read from the underlying source
     *                  per `read()` call.
     * @param fragmentThresholdBytes The number of bytes a piece of a secret must have to
     *                  be masked. A value shorter than this is not masked at all, and
     *                  neither is any piece of it.
     */
    constructor(
        registry: SecretPatternRegistry,
        chunkSize: Int,
        fragmentThresholdBytes: Int,
    ) : this(registry, chunkSize) {
        this.fragmentThresholdBytes = fragmentThresholdBytes
    }

    /**
     * The maximum encoded literal byte length.
     * This is the bound for the pending buffer: we need to hold at most this many
     * bytes to have sufficient lookahead to rule out any secret match at the
     * pending head.
     *
     * It is the window of the registry AS IT IS NOW, and not the window of the
     * registry when the redactor was built: a value registered later makes the
     * window wider, and the pass follows it.
    */
    val maxLiteralByteLength: Int
        get() = registry.maxLiteralByteLength()

    /**
     * A fresh, independent [RedactorState] for one redaction pass.
     * Each call returns a state with its own buffers, lookahead, and output
     * queue, and with its own copy of the seam: the state of one pass is not the
     * state of another, and neither is the seam they hold.
*/
    fun initialState(chunkSize: Int = this.chunkSize): RedactorState {
        val seam = registry.seamSnapshot(fragmentThresholdBytes)
        val state = RedactorState(seam.maxLiteralByteLength, chunkSize)
        state.matcher = FragmentMatcher.of(fragmentThresholdBytes, seam.fragments)
        state.seamVersion = seam.version
        return state
    }

    /**
     * Reloads the seam of the registry into [state] when the registry has changed
     * since the pass captured it.
     *
     * The check is one read of the registry's version, and the seam is rebuilt only
     * when that read says the seam moved: a pass that scrubs a megabyte of transcript
     * pays for the reload once per registration and not once per fragment.
     */
    private fun refreshSeam(state: RedactorState) {
        if (registry.seamVersion == state.seamVersion) return
        val seam = registry.seamSnapshot(fragmentThresholdBytes)
        state.matcher = FragmentMatcher.of(fragmentThresholdBytes, seam.fragments)
        state.seamVersion = seam.version
        state.widenWindow(seam.maxLiteralByteLength)
    }

    /**
     * Feeds [len] bytes from [bytes] (offset [off]) into the lookahead of
     * [state], emitting whatever is now decidable. The call is non-blocking
     * by design: it never reads from an [InputStream], only from the byte
     * array the caller hands it. EOF on the source is signalled separately
     * by [finish]; conflating the two is the bug the state machine removes.
     * @return the updated [RedactorState]. The state is mutated in place
     *   and also returned, mirroring the typical Kotlin mutable-bag idiom.
*/
    fun feed(state: RedactorState, bytes: ByteArray, off: Int, len: Int): RedactorState {
        if (state.sourceExhausted) return state
        require(off >= 0 && len >= 0 && off + len <= bytes.size) {
            "feed($off, $len) is outside a ${bytes.size}-byte array"
        }
        // The seam is reloaded BEFORE any byte of this fragment is decided: a value that
        // was registered after the pass started must be masked by the bytes that arrive
        // after the registration, and the bytes that are still pending are decided by
        // the seam that is current when they are decided.
        refreshSeam(state)
        var i = 0
        while (i < len) {
            // 1. Drain whatever was already decided before adding more.
            drainDecidedHeads(state)
            // 2. Add a byte to the lookahead (if there's room).
            if (state.ringCount < state.maxLiteral) {
                state.ring[(state.ringHead + state.ringCount) % state.ring.size] = bytes[off + i]
                state.ringCount++
                i++
                continue
            }
            // 3. Ring is full but no decision was made. The head is decided
            //    here, and it is masked when a fragment covers it: the ring
            //    cannot grow, so waiting for bytes that cannot arrive would
            //    stall the transcript.
            relieveFullRing(state)
        }
        // One more pass to drain any decision that became possible after the
        // last byte was added: the last byte of a write is decided now when nothing can start
        // with it, and not when the next write happens to arrive.
        drainDecidedHeads(state)
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
     * The state's own buffers are erased before returning, so a state the
     * caller is about to drop carries no bytes with it.
     * [finish] is idempotent: a second call returns an empty array.
*/
    fun finish(state: RedactorState): ByteArray {
        if (state.sourceExhausted) return ByteArray(0)
        // The tail is decided by the seam that is current NOW: a value registered
        // after the last fragment is still masked in the bytes that are still pending.
        refreshSeam(state)
        state.sourceExhausted = true
        // The lookahead is now decidable. Run the same one-head decision
        // loop as [feed] until the ring is empty.
        while (state.ringCount > 0) {
            // `atEof = true`: no more bytes can arrive, so a literal longer than what is buffered
            // must be matched by the prefix that IS present rather than deferred forever, and
            // every head is decided one way or the other.
            drainOneHead(state, atEof = true)
        }
        val out = ByteArray(state.outputCount)
        for (i in 0 until state.outputCount) {
            out[i] = state.outputQueue[(state.outputHead + i) % state.outputQueue.size]
        }
        // The state is terminal, so nothing else will read from it: every byte
        // it still holds is erased here. The lookahead is the one that matters
        // — it holds RAW bytes straight off the pipe, including the bytes of a
        // secret that was just matched, and a matched secret is still a secret
        // until the bytes are gone.
        eraseBuffers(state)
        return out
    }

    /**
     * Erases every byte the [state] still holds.
     *
     * The lookahead holds raw bytes off the pipe and the output queue holds the
     * bytes that were decided; both can carry the shape of a secret. A state
     * that has been finished or closed is a state the caller is about to drop,
     * and a dropped object is one the collector keeps for a while — so the
     * bytes have to go before the object does.
     */
    private fun eraseBuffers(state: RedactorState) {
        java.util.Arrays.fill(state.ring, 0.toByte())
        state.ringHead = 0
        state.ringCount = 0
        java.util.Arrays.fill(state.outputQueue, 0.toByte())
        state.outputHead = 0
        state.outputCount = 0
    }

    /**
     * The only way out of a full ring.
     *
     * A full ring means the lookahead holds as many bytes as it can, and the head has to be
     * decided to make room for the next one. The head is always decidable at that point: a
     * head no fragment begins with is plain text, and a head a fragment begins with is masked
     * together with the run that fragment covers, even when the run may still grow - the ring
     * cannot grow, and a redactor that waits for bytes that cannot arrive stalls the
     * transcript forever.
     *
     * Called from two places, and that is the whole point. [feed] reached it while it still had
     * input to consume; the InputStream wrapper needs it too, because the wrapper's feed is
     * bounded by the ring's FREE SPACE - so when the ring is already full the wrapper can never
     * call `feed` at all, and without this method it has no branch that makes progress.
*/
    private fun relieveFullRing(state: RedactorState) {
        if (state.ringCount < state.maxLiteral) return
        if (drainOneHead(state)) return
        // The head could not be decided, which the design above says cannot happen. The head is
        // released as plain text anyway: a redactor that cannot decide must not spin on a full
        // ring, and the transcript has to reach the Output Plane.
        emitHeadAsPlain(state)
    }

    /** Move the ring head to the output queue verbatim, and make room in the ring. */
    private fun emitHeadAsPlain(state: RedactorState) {
        state.reserveOutput(1)
        state.outputQueue[(state.outputHead + state.outputCount) % state.outputQueue.size] =
            state.ring[state.ringHead]
        state.outputCount++
        state.ringHead = (state.ringHead + 1) % state.ring.size
        state.ringCount--
    }

    /** Decide heads until one is undecided or the ring is empty. */
    private fun drainDecidedHeads(state: RedactorState) {
        while (drainOneHead(state)) Unit
    }

    /**
     * Decide the ring head, when it can be decided.
     *
     * The head is masked when a fragment of a secret matches at it: the bytes the fragments
     * cover, taken as ONE run, are replaced by the SCRUB_MARKER. The run is what makes a
     * value that arrives in pieces come out as one marker and not as one marker per piece,
     * and it is why the fragments that overlap are consumed together instead of one after
     * another: consuming them one by one would leave the tail of the last one in the clear.
     *
     * The head is plain text when no fragment can match at it, and it is then released at
     * once: **a byte that cannot begin a secret has nothing to wait for**, and a lookahead
     * that keeps it anyway holds the terminator of every line until the step writes the
     * next one. It stays undecided only while the bytes that would settle it have not
     * arrived.
     *
     * @return true if the head was decided (a marker or a plain byte left the ring), false if it is
     *   still undecided or the ring is empty.
*/
    private fun drainOneHead(state: RedactorState, atEof: Boolean = false): Boolean {
        if (state.ringCount == 0) return false
        val matcher = state.matcher
        val available = minOf(matcher.threshold, state.ringCount)
        when (matcher.matchAt(state.ring, state.ringHead, state.ring.size, 0, available)) {
            FragmentMatcher.Match.NONE -> {
                // No fragment begins with this byte, so no secret can span it.
                emitHeadAsPlain(state)
                return true
            }
            FragmentMatcher.Match.UNDECIDED -> {
                if (!atEof) return false
                // No more bytes are coming, so no fragment can begin here either.
                emitHeadAsPlain(state)
                return true
            }
            FragmentMatcher.Match.MATCH -> Unit
        }

        val run = coveredRun(state, matcher, atEof)
        if (!run.settled && !atEof && state.ringCount < state.maxLiteral) {
            // The run may still grow: a fragment that starts inside it and ends after it has
            // not arrived yet, and consuming the run now would leave that tail in the clear.
            // The bytes that settle it are the ones that follow, so the head waits for them.
            return false
        }
        maskRun(state, run.length)
        return true
    }

    /**
     * The run the fragments cover from the head, and whether that run is settled.
     *
     * The fragments that overlap form a run, and the run is what a masked value is: one
     * marker for the whole run, not one marker for each fragment. The run ends where no
     * fragment that starts inside it reaches any further, and only the bytes that are in the
     * lookahead can say so.
     *
     * A run that is NOT settled may still grow: a fragment that starts inside it and ends
     * after it has not arrived yet. At the end of the stream nothing more arrives, so the
     * run is settled there: a fragment that cannot fit in the bytes that are left cannot
     * begin at all.
     */
    private fun coveredRun(state: RedactorState, matcher: FragmentMatcher, atEof: Boolean): CoveredRun {
        var end = matcher.threshold
        var settled = true
        var offset = 1
        while (offset < end) {
            val available = minOf(matcher.threshold, state.ringCount - offset)
            if (available <= 0) break
            when (matcher.matchAt(state.ring, state.ringHead, state.ring.size, offset, available)) {
                FragmentMatcher.Match.MATCH ->
                    if (offset + matcher.threshold > end) end = offset + matcher.threshold
                FragmentMatcher.Match.NONE -> Unit
                FragmentMatcher.Match.UNDECIDED ->
                    if (!atEof && offset + matcher.threshold > end) settled = false
            }
            offset++
        }
        return CoveredRun(end, settled)
    }

    /**
     * A run of covered bytes, and whether that run has stopped growing.
     */
    private class CoveredRun(val length: Int, val settled: Boolean)

    /**
     * Replace the first [length] bytes of the lookahead with the SCRUB_MARKER.
     *
     * The bytes are consumed from the ring and the marker goes to the output queue: the
     * marker is four bytes long and the run it stands for may be longer or shorter, and the
     * queue is what lets a reader take the decided bytes in pieces.
    */
    private fun maskRun(state: RedactorState, length: Int) {
        repeat(length) {
            state.ringHead = (state.ringHead + 1) % state.ring.size
            state.ringCount--
        }
        val marker = SecretPatternRegistry.SCRUB_MARKER.toByteArray(StandardCharsets.UTF_8)
        state.reserveOutput(marker.size)
        for (byte in marker) {
            state.outputQueue[(state.outputHead + state.outputCount) % state.outputQueue.size] = byte
            state.outputCount++
        }
    }

    /**
     * Decorates an [InputStream] with secret redaction across chunk boundaries.
     * The returned stream must be closed by the caller to trigger final flush
     * and zero the internal buffers.
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
     * Each wrapped stream has its own state, so multiple concurrent wraps
     * are safe.
*/
    /**
     * The previous implementation used
     * `ArrayDeque<Byte>` (one boxed Byte per byte) plus a full pendingArray copy
     * and a linear scan over every seam literal PER BYTE. Throughput measured at
     * ~100 KB/s, which stalls any pipeline whose child writes >~100 MiB to the
     * console transcript (observed: 200 MiB run busy-spinning at 100% CPU for
     * 36+ minutes without completing).
     * This rewrite keeps the EXACT same observable contract (see the class docs):
     * - never returns 0 from read(b, off, len>0);
     * - a byte is only emitted once maxLiteral lookahead rules out every seam literal;
     * - at EOF remaining pending bytes are emitted as-is;
     * - close drains pending, clears buffers, closes the source.
     * Implementation: ring buffer of primitive bytes with the fragments addressed by
     * their bytes. Per emitted byte the cost is one table lookup for the bytes that
     * can begin a fragment, and nothing at all for the bytes that cannot.
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

        /** True after close has been called */
        private var closed = false

        /**
         * The part of the EOF flush this call could not hand back.
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
                // was called via close) and pending empty → EOF.
                if (state.sourceExhausted && state.ringCount == 0) {
                    return if (written > 0) written else -1
                }

                // Step 2.5 — hand back what has been DECIDED, before touching the source again.
                // This is the same guard that used to sit after Step 4, where it could not fire in
                // the case that mattered. Step 3 refills `inputBuffer` by BLOCKING on
                // `source.read`, and it runs first, so by the time the old guard was evaluated the
                // buffer was full again and its `inputOffset >= inputLimit` condition no longer
                // held. The guard could therefore only ever fire after a blocking read — which is
                // the opposite of what it was written to prevent.
                // The defect that produced: the transcript pump asks for a whole
                // `TRANSCRIPT_LIVE_WINDOW_BYTES` (64 KiB) and relies on this returning as soon as
                // anything is available. With the guard unreachable the wrapper instead filled the
                // entire buffer or blocked, so:
                //   15-byte payload     -> nothing committed mid-step (waits for EOF)
                //   128 KiB payload     -> exactly one 64 KiB window committed, then a block
                // Both were measured by the rows. Placing the guard BEFORE the refill is the
                // minimal change: it can only fire when `written > 0` and the input buffer is
                // drained, so it never returns before any decision has been made.
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
                    // This is the branch that used to not exist. The feed above is bounded by the
                    // ring's FREE SPACE, so a full ring means the wrapper can never call `feed`,
                    // and the early-return below requires room to return. Every path through the
                    // loop then left `written` unchanged: a busy loop at 100% CPU that consumed
                    // nothing while the child kept writing. An empty registry made it near-certain,
                    // because with no secrets almost no head is ever decidable and the ring fills
                    // after a handful of bytes.
                    // Emitting the head is the only way out, and it is SAFE: the head is being
                    // emitted precisely because no literal starts with it, so no secret can span
                    // it. It is also not optional for correctness — a redactor that cannot emit a
                    // byte it has already decided is not withholding anything a secret needs.
                    this@StreamingRedactor.relieveFullRing(state)
                    continue
                }

                // Step 4: pending.size < maxLiteral and source still has data → fill
                // lookahead. Return what we have rather than blocking on the source.
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
            // `inputBuffer` is zeroed too, and that is not incidental. It holds RAW bytes straight
            // off the pipe — including any that were never fed, because the ring filled first —
            // so leaving them in a discarded object is exactly the leak the whole class exists to
            // Prevent. This was already asserted by "close clears all buffers"; made it
            // reachable by letting the read loop leave the buffer partly consumed.
            java.util.Arrays.fill(inputBuffer, 0.toByte())
            inputOffset = 0
            inputLimit = 0
            if (finalFlush.isNotEmpty()) java.util.Arrays.fill(finalFlush, 0.toByte())
            finalFlush = ByteArray(0)
            finalFlushOffset = 0
            // The state carries the same kind of bytes as the buffers above,
            // and it is erased by the same rule whether it was finished here
            // or earlier.
            this@StreamingRedactor.eraseBuffers(state)
            source.close()
        }
    }
}
