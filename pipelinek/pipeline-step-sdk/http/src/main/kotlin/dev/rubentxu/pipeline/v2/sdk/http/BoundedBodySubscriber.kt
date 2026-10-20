package dev.rubentxu.pipeline.v2.sdk.http

import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

/**
 * What a response body is allowed to become: a bounded prefix, plus the identity of
 * the WHOLE body.
 *
 * Not a `data class` on purpose. [prefix] is a `ByteArray`, and `ByteArray` equality
 * is reference equality, so a generated `equals` would report two bodies with the
 * same bytes as different and two bodies with different bytes as the same. A value
 * that lies about equality is worse than one that has no equality at all, so this is
 * a plain class and callers compare the fields they mean.
 */
internal class BoundedBody(
    /** At most `maxMaterializedBytes` bytes, in arrival order. A prefix, not the body. */
    val prefix: ByteArray,
    /** SHA-256 of the COMPLETE body, even when [prefix] was truncated. */
    val sha256: String,
    /** Size of the COMPLETE body, counted but never fully materialised. */
    val totalBytes: Long,
    /** True when the body exceeded the cap, so [prefix] is a prefix. */
    val truncated: Boolean,
) {
    override fun toString(): String =
        "BoundedBody(total=$totalBytes, prefix=${prefix.size}, truncated=$truncated, sha256=$sha256)"
}

/**
 * H4 — the body subscriber that makes `maxBodyBytes` a MEMORY guarantee.
 *
 * ## The defect this replaces
 *
 * ```text
 * BodyHandlers.ofByteArray()
 *      ↓
 * a 10 GB response becomes a 10 GB ByteArray
 *      ↓
 * sha256(10 GB)
 *      ↓
 * copy the first 1 MiB and throw the other 9.999 GB away
 * ```
 *
 * `maxBodyBytes` limited the value that came back, not the memory that was used, and
 * the value that came back is the smaller of the two. `BodySubscribers.ofByteArray`
 * accumulates the complete body before it produces a response, so a declared cap was
 * enforced strictly after the fact.
 *
 * ## The law
 *
 * > For a response of N bytes, PipelineK retains at most `maxMaterializedBytes` of
 * > body, and counts and SHA-256s all N bytes without materialising them.
 *
 * ## Demand is expressed, not assumed
 *
 * `request(1)` on subscribe, and `request(1)` again after each batch is consumed.
 * `request(Long.MAX_VALUE)` would also fit in memory — this subscriber only keeps a
 * bounded prefix — but it is not backpressure, it is a promise the publisher is never
 * asked to keep. Asking for one batch at a time is what makes the window real and
 * what makes C7 (a slow subscriber is not given unlimited demand) a real property
 * rather than an accident of the JDK's internal buffer.
 *
 * ## No coroutines here
 *
 * `onNext` runs on a JDK HTTP thread and stays synchronous and short: update the
 * digest, bump the counter, copy the bounded prefix, ask for the next batch. The
 * asynchrony already exists — it is `sendAsync`. Putting a suspension point inside a
 * `Flow` callback would add a lifecycle the JDK never agreed to.
 */
internal class BoundedBodySubscriber(
    private val maxMaterializedBytes: Int,
) : HttpResponse.BodySubscriber<BoundedBody> {

    private val digest: MessageDigest = try {
        MessageDigest.getInstance("SHA-256")
    } catch (e: NoSuchAlgorithmException) {
        throw IllegalStateException("SHA-256 is required by the platform", e)
    }

    private val result = CompletableFuture<BoundedBody>()
    private var subscription: Flow.Subscription? = null

    /**
     * The retained prefix, grown geometrically but CAPPED at [maxMaterializedBytes].
     *
     * Cap matters: an uncapped doubling buffer would peak at up to 2x the limit during
     * the last growth, which is exactly the kind of "it is bounded, roughly" that
     * turns into an out-of-memory at the worst possible moment.
     */
    private var prefix = ByteArray(minOf(maxMaterializedBytes, INITIAL_PREFIX_CAPACITY))
    private var prefixSize = 0
    private var totalBytes = 0L

    init {
        require(maxMaterializedBytes >= 0) {
            "maxMaterializedBytes must not be negative, got $maxMaterializedBytes. " +
                "A negative cap silently truncates every response to nothing, which is a " +
                "lie about the response rather than a bound on it."
        }
    }

    /**
     * The high-water mark of bytes this subscriber ever held at once.
     *
     * Exposed so the memory property is ASSERTED rather than argued: C4 drives a body
     * far larger than the cap and checks this never crosses it.
     */
    val peakRetainedBytes: Int get() = prefix.size

    /** Bytes consumed so far. Readable after a failure, which is how `ResponseInterrupted` learns where it stopped. */
    val bytesReceived: Long get() = totalBytes

    /**
     * Whether the exchange ever reached the BODY phase.
     *
     * This is the discriminator between "the host could not be reached" and "the host
     * answered and the body stopped", and it is asked of the subscriber rather than
     * inferred from which call threw. That inference is wrong: the JDK is free to
     * surface a mid-body failure through the OUTER response future, and a transport
     * that classified by exception site would call a server that just answered
     * "unreachable". `onSubscribe` on the body fires exactly when the headers are in,
     * so this is a fact rather than a guess.
     */
    val bodyStarted: Boolean get() = subscription != null

    /**
     * The body this subscriber is building.
     *
     * `HttpResponse.BodySubscriber` re-declares `getBody()` with a `CompletionStage`
     * return instead of `Flow.Subscriber`'s `List<ByteBuffer>`, so this method is the
     * subscriber's OWN handle on its result — not a second subscription. The JDK calls
     * [onSubscribe]/[onNext] on this very object and then completes this stage.
     */
    override fun getBody(): CompletionStage<BoundedBody> = result

    override fun onSubscribe(subscription: Flow.Subscription) {
        // A second subscription is a protocol violation by the publisher. Accepting it
        // would mean two writers into one prefix buffer, so it fails closed and the
        // second subscription is cancelled rather than left dangling.
        if (this.subscription != null) {
            subscription.cancel()
            result.completeExceptionally(
                IllegalStateException("the body was subscribed to twice; the second subscription was refused"),
            )
            return
        }
        this.subscription = subscription
        // Bounded demand. Never Long.MAX_VALUE.
        subscription.request(DEMAND_PER_BATCH)
    }

    override fun onNext(item: List<ByteBuffer>) {
        try {
            for (buffer in item) {
                if (!buffer.hasRemaining()) continue
                val length = buffer.remaining()
                // The digest sees EVERY byte, including the ones past the cap. A
                // digest of the prefix cannot be told apart from a genuinely short
                // body, and "what the server actually said" is the thing worth keeping.
                digest.update(buffer.duplicate())
                totalBytes += length
                appendBoundedPrefix(buffer)
            }
        } catch (t: Throwable) {
            // A failure raised INSIDE onNext would otherwise vanish: the publisher
            // believes it delivered a batch and moves on. Completing the stage is the
            // only way the awaiting side learns the body was cut short.
            fail(t)
            return
        }
        // Ask for exactly one more batch, now that this one is fully consumed.
        subscription?.request(DEMAND_PER_BATCH)
    }

    /** Copies at most the room that is left, and never more. */
    private fun appendBoundedPrefix(buffer: ByteBuffer) {
        val room = maxMaterializedBytes - prefixSize
        if (room <= 0) return
        val take = minOf(room, buffer.remaining())
        if (take <= 0) return
        ensureCapacity(prefixSize + take)
        val slice = buffer.duplicate()
        slice.limit(slice.position() + take)
        slice.get(prefix, prefixSize, take)
        prefixSize += take
    }

    private fun ensureCapacity(required: Int) {
        if (required <= prefix.size) return
        val grown = minOf(maxMaterializedBytes, maxOf(required, prefix.size * 2))
        prefix = prefix.copyOf(grown)
    }

    override fun onError(throwable: Throwable) {
        fail(throwable)
    }

    override fun onComplete() {
        if (result.isDone) return
        result.complete(
            BoundedBody(
                // copyOf(prefixSize) so the delivered array is exactly the retained
                // bytes, not the capacity that happened to be allocated.
                prefix = prefix.copyOf(prefixSize),
                sha256 = digest.digest().toHex(),
                totalBytes = totalBytes,
                truncated = totalBytes > maxMaterializedBytes,
            ),
        )
    }

    private fun fail(throwable: Throwable) {
        if (result.completeExceptionally(throwable)) {
            subscription?.cancel()
        }
    }

    /**
     * Stops the exchange.
     *
     * Awaiting the completion stage already cancels it on coroutine cancellation, and
     * the JDK propagates that to the exchange — but "already" is a claim about a
     * library, and the whole point of C6 is that cancellation reaches the socket. So
     * the subscription is cancelled explicitly rather than trusted to.
     */
    fun cancel() {
        subscription?.cancel()
        result.cancel(false)
    }

    private companion object {
        /** One batch at a time. The unit of demand is a decision, not a number to tune. */
        const val DEMAND_PER_BATCH: Long = 1L

        /** Small enough that a short body never allocates a megabyte, large enough to avoid churn. */
        const val INITIAL_PREFIX_CAPACITY: Int = 8 * 1024
    }
}

private val HEX = "0123456789abcdef".toCharArray()

/**
 * Lower-case hex, written out rather than delegated to `String.format`.
 *
 * `"%02x".format(byte)` is correct but allocates a `Formatter` per byte, and this
 * runs once per response over 32 bytes — cheap enough that the real argument is the
 * other one: a hand-written table cannot be surprised by locale.
 */
private fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xFF
        out[i * 2] = HEX[value ushr 4]
        out[i * 2 + 1] = HEX[value and 0x0F]
    }
    return String(out)
}
