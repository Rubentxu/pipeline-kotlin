package dev.rubentxu.pipeline.v2.sdk.http

import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * H4 — `maxBodyBytes` is a MEMORY guarantee, not a value truncated after the fact.
 *
 * ## What the canaries pin
 *
 * ```text
 * C1  a body under the cap arrives whole: no truncation, digest of the whole body
 * C2  a body exactly at the cap is not truncation
 * C3  a body over the cap: prefix is exact, counter is the FULL size, digest covers all N
 * C4  a body 64x the cap never makes the subscriber retain more than the cap
 * C5  a body that fails part-way is a FAILURE, not a short success
 * C6  cancelling stops the exchange, and the body never completes NORMALLY
 * C7  demand is one batch at a time — never an unbounded request
 * ```
 *
 * ## Why a synthetic publisher and not a 10 GB server
 *
 * C3 and C4 are about what the subscriber RETAINS, and a real socket would make that
 * unmeasurable: the bytes would have to exist somewhere to be sent, which is the very
 * thing under test. This publisher regenerates a repeating cycle on demand and holds
 * one chunk at a time, so a 64 MiB body costs 64 KiB to produce and the subscriber's
 * high-water mark is attributable to the subscriber alone.
 *
 * The expected digest is computed by a second, independent path
 * ([digestOfSyntheticBody]) rather than by reusing the subscriber's own code: a test
 * that hashes with the implementation it is testing proves nothing.
 */
class BoundedBodySubscriberTest {

    // ── C1: under the cap ───────────────────────────────────────────────────

    @Test
    fun `C1 a body under the cap arrives whole and is not marked truncated`() {
        val cap = 1_024 * 1_024
        val body = 4_096L

        val result = drain(body, cap)

        assertEquals(body, result.totalBytes, "the counter must see the whole body")
        assertFalse(result.truncated, "a body under the cap is not a truncation")
        assertEquals(4_096, result.prefix.size, "the whole body must be retained")
        assertEquals(digestOfSyntheticBody(body), result.sha256, "digest of the complete body")
    }

    // ── C2: exactly at the cap ─────────────────────────────────────────────

    @Test
    fun `C2 a body exactly at the cap is not marked truncated`() {
        val cap = 8_192
        val body = 8_192L

        val result = drain(body, cap)

        assertEquals(body, result.totalBytes)
        assertFalse(
            result.truncated,
            "a body of exactly the cap is a complete body. Marking it truncated would make " +
                "'the server sent exactly what I asked for' indistinguishable from 'it sent more'.",
        )
        assertEquals(cap, result.prefix.size)
        assertArrayEquals(
            syntheticBytes(0, cap.toLong()),
            result.prefix,
            "every retained byte must be the real byte, in arrival order",
        )
    }

    // ── C3: over the cap ───────────────────────────────────────────────────

    @Test
    fun `C3 a body over the cap keeps an exact prefix but digests and counts all of it`() {
        val cap = 4_096
        val body = 300_000L

        val result = drain(body, cap)

        assertTrue(result.truncated, "300000 bytes against a 4096 cap IS a truncation")
        assertEquals(cap, result.prefix.size, "the retained prefix is exactly the cap")
        assertArrayEquals(
            syntheticBytes(0, cap.toLong()),
            result.prefix,
            "the prefix must be the FIRST cap bytes, not the last ones that happened to fit",
        )
        assertEquals(
            body,
            result.totalBytes,
            "the counter must keep going past the cap. A counter that stops at the limit " +
                "reports every truncated response as exactly the limit, which is how a 5 GB " +
                "download becomes indistinguishable from a 1 MiB one.",
        )
        assertEquals(
            digestOfSyntheticBody(body),
            result.sha256,
            "the digest must be of ALL N bytes. A digest of the prefix cannot be told apart " +
                "from a genuinely short body, which defeats the point of keeping it.",
        )
    }

    // ── C4: memory is O(cap), not O(body) ──────────────────────────────────

    @Test
    fun `C4 a body 64 times the cap never makes the subscriber retain more than the cap`() {
        val cap = 1_024 * 1_024
        val body = 64L * 1_024 * 1_024

        val subscriber = BoundedBodySubscriber(cap)
        SyntheticBodyPublisher(totalBytes = body).subscribe(subscriber)
        val result = subscriber.completedOrThrow()

        assertEquals(body, result.totalBytes, "all 64 MiB must still be counted")
        assertEquals(cap.toLong(), result.prefix.size.toLong(), "exactly the cap is retained")
        assertTrue(
            subscriber.peakRetainedBytes <= cap,
            "the high-water mark was ${subscriber.peakRetainedBytes} for a cap of $cap. " +
                "Retained memory must be O(maxBodyBytes), not O(responseSize) — that is the " +
                "entire reason this subscriber exists instead of BodySubscribers.ofByteArray().",
        )
        assertEquals(
            digestOfSyntheticBody(body),
            result.sha256,
            "even at 64x the cap the digest covers the complete body",
        )
    }

    // ── C5: a body that fails part-way is a failure ────────────────────────

    @Test
    fun `C5 a body that fails after 100 KB fails the stage and still knows how much arrived`() {
        val cut = 100_000L
        val publisher = SyntheticBodyPublisher(
            totalBytes = cut + 1_000_000,
            failAfterBytes = cut,
        )
        val subscriber = BoundedBodySubscriber(8 * 1_024 * 1_024)
        publisher.subscribe(subscriber)

        val failure = subscriber.failureOrNull()

        assertTrue(
            failure is IOException,
            "the stage must fail with the publisher's cause, got $failure",
        )
        assertEquals(
            cut,
            subscriber.bytesReceived,
            "the subscriber must still know how many bytes arrived. Without it, 'cut short at " +
                "100 KB' and 'cut short at 0 bytes' produce the same report — and the whole " +
                "point of ResponseInterrupted is that they do not.",
        )
    }

    // ── C6: cancelling the subscription ────────────────────────────────────

    @Test
    fun `C6 cancelling stops the exchange and the body never completes normally`() {
        val total = 64L * 1_024 * 1_024
        val publisher = SyntheticBodyPublisher(totalBytes = total, manual = true)
        val subscriber = BoundedBodySubscriber(64 * 1_024)
        publisher.subscribe(subscriber)

        // Two batches, then the operator stops the run.
        publisher.deliverOneBatch()
        publisher.deliverOneBatch()
        val deliveredBeforeCancel = publisher.deliveredBytes
        subscriber.cancel()

        assertTrue(publisher.cancelled, "cancel() must reach the subscription")
        assertEquals(2L * 64 * 1_024, deliveredBeforeCancel, "two batches of 64 KiB")

        val future = subscriber.getBody().toCompletableFuture()
        if (future.isDone) {
            assertTrue(
                future.isCancelled || future.isCompletedExceptionally,
                "a cancelled body must not complete NORMALLY. Completing it hands back a prefix " +
                    "as if it were a whole response — the exact defect the withByteArray shape " +
                    "could not express.",
            )
        }
        // And the publisher must be inert afterwards: cancellation is not advisory.
        publisher.deliverOneBatch()
        assertEquals(
            deliveredBeforeCancel,
            publisher.deliveredBytes,
            "no further bytes may be delivered after cancel()",
        )
    }

    // ── C7: demand is expressed, not assumed ───────────────────────────────

    @Test
    fun `C7 the subscriber asks for one batch at a time and never for everything`() {
        val total = 4L * 1_024 * 1_024
        val publisher = SyntheticBodyPublisher(totalBytes = total)
        val subscriber = BoundedBodySubscriber(64 * 1_024)
        publisher.subscribe(subscriber)
        val result = subscriber.completedOrThrow()

        assertEquals(
            1L,
            publisher.maxSingleRequest,
            "the largest single request was ${publisher.maxSingleRequest}. " +
                "request(Long.MAX_VALUE) would also fit in memory, but it is not backpressure " +
                "— it is a promise the publisher is never asked to keep.",
        )
        assertEquals(
            1L,
            publisher.maxOutstandingDemand,
            "at most one batch may be in flight; the publisher saw " +
                "${publisher.maxOutstandingDemand} outstanding.",
        )
        assertEquals(total, publisher.deliveredBytes, "bounded demand must still deliver everything")
        assertEquals(total, result.totalBytes)
    }

    // ── harness ────────────────────────────────────────────────────────────

    private fun drain(totalBytes: Long, cap: Int): BoundedBody {
        val subscriber = BoundedBodySubscriber(cap)
        SyntheticBodyPublisher(totalBytes = totalBytes).subscribe(subscriber)
        return subscriber.completedOrThrow()
    }

    private fun BoundedBodySubscriber.completedOrThrow(): BoundedBody =
        getBody().toCompletableFuture().get(60, TimeUnit.SECONDS)

    /** The failure the stage carries, or `null` if it completed normally. */
    private fun BoundedBodySubscriber.failureOrNull(): Throwable? = try {
        getBody().toCompletableFuture().get(60, TimeUnit.SECONDS)
        null
    } catch (e: ExecutionException) {
        e.cause
    }
}

/**
 * 251 is prime and coprime with every power of two, so the cycle never aligns with a
 * chunk boundary — a bug that shifted a chunk by one would be visible in the digest.
 */
private val CYCLE: ByteArray = ByteArray(251) { it.toByte() }

/** Fills [length] bytes of [target] at [offset] with the cycle, aligned to absolute index [from]. */
private fun fillCycle(target: ByteArray, offset: Int, from: Long, length: Int) {
    var written = 0
    while (written < length) {
        val start = ((from + written) % 251L).toInt()
        val run = minOf(251 - start, length - written)
        System.arraycopy(CYCLE, start, target, offset + written, run)
        written += run
    }
}

/**
 * The first [count] bytes of the synthetic body, materialised for comparison.
 *
 * Only used for small prefixes: a test that builds a 64 MiB expectation to prove the
 * subscriber does not build a 64 MiB buffer would defeat its own point.
 */
private fun syntheticBytes(from: Long, count: Long): ByteArray {
    val out = ByteArray(count.toInt())
    fillCycle(out, 0, from, out.size)
    return out
}

/**
 * SHA-256 of the first [count] bytes of the synthetic body, computed WITHOUT the
 * subscriber — an independent path. A test that hashes with the code under test is a
 * test that cannot fail.
 */
private fun digestOfSyntheticBody(count: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val block = ByteArray(64 * 1_024)
    var written = 0L
    while (written < count) {
        val size = minOf(block.size.toLong(), count - written).toInt()
        fillCycle(block, 0, written, size)
        digest.update(block, 0, size)
        written += size
    }
    val hex = StringBuilder(64)
    for (b in digest.digest()) hex.append("%02x".format(b))
    return hex.toString()
}

/**
 * A `Flow.Publisher` that generates [totalBytes] without ever holding them.
 *
 * It is also the instrument: [maxSingleRequest] and [maxOutstandingDemand] turn "the
 * subscriber is well behaved" from a claim into a measurement. Demand is honoured
 * strictly — a batch is delivered only while demand is positive — so a subscriber that
 * requested everything up front would see the whole body immediately, and one that
 * asks for one at a time sees one at a time.
 *
 * With [manual] = true nothing is delivered automatically, which is what lets C6
 * cancel part-way through a body that would otherwise finish inside `subscribe`.
 */
private class SyntheticBodyPublisher(
    private val totalBytes: Long,
    private val chunkSize: Int = 64 * 1_024,
    private val failAfterBytes: Long = -1L,
    private val manual: Boolean = false,
) : Flow.Publisher<List<ByteBuffer>> {

    var maxSingleRequest: Long = 0L
        private set
    var maxOutstandingDemand: Long = 0L
        private set
    var deliveredBytes: Long = 0L
        private set
    var cancelled: Boolean = false
        private set

    private var subscription: SyntheticSubscription? = null

    override fun subscribe(subscriber: Flow.Subscriber<in List<ByteBuffer>>) {
        val sub = SyntheticSubscription(subscriber)
        subscription = sub
        subscriber.onSubscribe(sub)
    }

    /** Pushes exactly one batch, if there is demand for it. No-op once cancelled. */
    fun deliverOneBatch() {
        subscription?.deliverOne()
    }

    private inner class SyntheticSubscription(
        private val subscriber: Flow.Subscriber<in List<ByteBuffer>>,
    ) : Flow.Subscription {

        private var demand = 0L
        private var emitting = false
        private var terminated = false

        private val terminalAt: Long get() = if (failAfterBytes >= 0L) failAfterBytes else totalBytes

        override fun request(n: Long) {
            if (cancelled || terminated) return
            if (n <= 0L) {
                // The Reactive Streams contract: a non-positive request is a protocol error.
                terminated = true
                subscriber.onError(IllegalArgumentException("request($n) is not positive"))
                return
            }
            if (n > maxSingleRequest) maxSingleRequest = n
            demand = if (demand > Long.MAX_VALUE - n) Long.MAX_VALUE else demand + n
            if (demand > maxOutstandingDemand) maxOutstandingDemand = demand
            if (!manual) drain()
        }

        override fun cancel() {
            cancelled = true
        }

        fun deliverOne() {
            if (cancelled || terminated || demand <= 0L || deliveredBytes >= terminalAt) return
            deliverBatch()
            if (!cancelled && !terminated && deliveredBytes >= terminalAt) terminate()
        }

        private fun drain() {
            // `request` is re-entrant: the subscriber asks for the next batch from
            // inside `onNext`. Without this guard the recursion would nest once per
            // chunk and a 4 MiB body would blow the stack.
            if (emitting) return
            emitting = true
            try {
                while (demand > 0L && !cancelled && !terminated && deliveredBytes < terminalAt) {
                    deliverBatch()
                }
                if (!cancelled && !terminated && deliveredBytes >= terminalAt) terminate()
            } finally {
                emitting = false
            }
        }

        private fun terminate() {
            terminated = true
            if (failAfterBytes >= 0L) {
                subscriber.onError(IOException("the peer went away mid-body"))
            } else {
                subscriber.onComplete()
            }
        }

        private fun deliverBatch() {
            val size = minOf(chunkSize.toLong(), terminalAt - deliveredBytes).toInt()
            val buffer = ByteArray(size)
            fillCycle(buffer, 0, deliveredBytes, size)
            deliveredBytes += size
            demand--
            subscriber.onNext(listOf(ByteBuffer.wrap(buffer)))
        }
    }
}
