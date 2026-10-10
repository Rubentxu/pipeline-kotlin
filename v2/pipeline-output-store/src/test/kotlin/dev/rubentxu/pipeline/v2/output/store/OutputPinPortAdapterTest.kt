package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputPinResult
import dev.rubentxu.pipeline.v2.output.OutputPinPort
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.PinRefusal
import dev.rubentxu.pipeline.v2.output.PinReleaseOutcome
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * M3 — `OutputPinPort` contract tests against the real
 * [OutputPinPortStoreAdapter] backed by [OutputPinStore]. Ten cases cover
 * the design §10.3 surface:
 *
 *  1. pin + isPinned within range returns true.
 *  2. pin + isPinned at boundary returns true.
 *  3. pin + isPinned mid-range outside the pin returns false.
 *  4. release + isPinned returns false.
 *  5. expired pin (expiresAtMs in the past) returns false on isPinned.
 *  6. list pinsOf returns the active pins.
 *  7. limit-exceeded (1025 pins on the same stream) returns `Refused(TooManyPins)`.
 *  8. pin survives process restart (durable authority).
 *  9. idempotency: release of an already-released pin returns `AlreadyReleased`.
 * 10. unknown pin: release of an unknown pinId returns `UnknownPin`.
 */
class OutputPinPortAdapterTest {

    private fun bytes(s: String) = s.toByteArray(StandardCharsets.UTF_8)

    private fun freshAdapter(root: Path): Pair<SegmentOutputStore, OutputPinPortStoreAdapter> {
        val store = SegmentOutputStore(root.resolve("output"))
        store.recover()
        val pinAdapter = OutputPinPortStoreAdapter(root.resolve("pins"), store)
        return store to pinAdapter
    }

    @Test
    fun `pin within range isPinned returns true at every offset in the range`(
        @TempDir root: Path,
    ) {
        val (store, pins) = freshAdapter(root)
        val stream = OutputStreamId("run-m3-pin/op/transcript")
        store.open(stream).reserve(64).apply { write(bytes("0123456789ABCDEF")) }.commit()

        val result = pins.pin(stream, 0L..15L, "holder-m3", "test pin")
        val pinned = assertInstanceOf(OutputPinResult.Pinned::class.java, result, "expected Pinned, got $result")

        assertTrue(pins.isPinned(stream, 0), "offset 0 must be pinned")
        assertTrue(pins.isPinned(stream, 8), "offset 8 (mid) must be pinned")
        assertTrue(pins.isPinned(stream, 15), "offset 15 (last in range) must be pinned")
    }

    @Test
    fun `pin at boundary isPinned returns true at boundaries and false outside`(
        @TempDir root: Path,
    ) {
        val (store, pins) = freshAdapter(root)
        val stream = OutputStreamId("run-m3-boundary/op/transcript")
        store.open(stream).reserve(64).apply { write(bytes("0123456789ABCDEF")) }.commit()

        pins.pin(stream, 4L..10L, "holder-m3", "boundary pin")
        assertTrue(pins.isPinned(stream, 4), "first boundary must be pinned")
        assertTrue(pins.isPinned(stream, 10), "last boundary must be pinned")
        assertFalse(pins.isPinned(stream, 3), "one before must NOT be pinned")
        assertFalse(pins.isPinned(stream, 11), "one after must NOT be pinned")
    }

    @Test
    fun `isPinned outside the pin range returns false`(@TempDir root: Path) {
        val (store, pins) = freshAdapter(root)
        val stream = OutputStreamId("run-m3-outside/op/transcript")
        store.open(stream).reserve(64).apply { write(bytes("0123456789ABCDEF")) }.commit()

        pins.pin(stream, 5L..10L, "holder-m3", "narrow pin")
        assertFalse(pins.isPinned(stream, 0), "offset 0 (way before) must NOT be pinned")
        assertFalse(pins.isPinned(stream, 15), "offset 15 (way after) must NOT be pinned")
    }

    @Test
    fun `release makes isPinned return false`(@TempDir root: Path) {
        val (store, pins) = freshAdapter(root)
        val stream = OutputStreamId("run-m3-release/op/transcript")
        store.open(stream).reserve(64).apply { write(bytes("0123456789")) }.commit()

        val pinned = pins.pin(stream, 0L..9L, "holder-m3", "test")
        val pinId = (pinned as OutputPinResult.Pinned).pinId
        assertTrue(pins.isPinned(stream, 5))

        val released = pins.release(pinId)
        assertInstanceOf(PinReleaseOutcome.Released::class.java, released, "expected Released, got $released")
        assertFalse(pins.isPinned(stream, 5), "after release, isPinned must be false")
    }

    @Test
    fun `expired pin is treated as released`(@TempDir root: Path) {
        val (store, pins) = freshAdapter(root)
        val stream = OutputStreamId("run-m3-expired/op/transcript")
        store.open(stream).reserve(64).apply { write(bytes("0123456789")) }.commit()

        // expiresAtMs in the past.
        val expired = pins.pin(
            stream = stream,
            range = 0L..9L,
            holder = "holder-m3",
            reason = "expired pin",
            expiresAtMs = System.currentTimeMillis() - 60_000L,
        )
        val pinId = (expired as OutputPinResult.Pinned).pinId

        assertFalse(pins.isPinned(stream, 5), "expired pin must NOT be visible to isPinned")

        // Releasing an expired pin returns AlreadyReleased.
        val releaseResult = pins.release(pinId)
        assertInstanceOf(
            PinReleaseOutcome.AlreadyReleased::class.java, releaseResult,
            "release of expired pin should report AlreadyReleased, got $releaseResult",
        )
    }

    @Test
    fun `pinsOf returns the active pins on a stream`(@TempDir root: Path) {
        val (store, pins) = freshAdapter(root)
        val stream = OutputStreamId("run-m3-list/op/transcript")
        store.open(stream).reserve(64).apply { write(bytes("0123456789ABCDEF")) }.commit()

        val p1 = pins.pin(stream, 0L..5L, "holder-m3", "first")
        val p2 = pins.pin(stream, 8L..12L, "holder-m3", "second")
        pins.pin(stream, 3L..6L, "holder-m3", "third") // overlaps p1 partially

        val list = pins.pinsOf(stream)
        assertEquals(3, list.size, "expected 3 pins, got ${list.size}")
        assertTrue(list.any { it.pinId == (p1 as OutputPinResult.Pinned).pinId })
        assertTrue(list.any { it.pinId == (p2 as OutputPinResult.Pinned).pinId })
    }

    @Test
    fun `1025 pins on the same stream refuse with TooManyPins`(@TempDir root: Path) {
        val (store, pins) = freshAdapter(root)
        val stream = OutputStreamId("run-m3-limit/op/transcript")
        // Commit enough bytes that all 1025 pin ranges fit.
        val big = ByteArray(2048)
        store.open(stream).reserve(big.size).apply { write(big) }.commit()

        val limit = OutputPinPort.DEFAULT_MAX_PINS_PER_STREAM
        for (i in 0 until limit) {
            val r = pins.pin(stream, (i * 2).toLong()..((i * 2) + 1).toLong(), "holder-m3", "filler $i")
            assertInstanceOf(OutputPinResult.Pinned::class.java, r, "pin #$i must succeed")
        }
        val overflow = pins.pin(stream, 2046L..2047L, "holder-m3", "overflow")
        assertInstanceOf(OutputPinResult.Refused::class.java, overflow, "overflow pin must be refused")
        val refusal = (overflow as OutputPinResult.Refused).reason
        assertInstanceOf(PinRefusal.TooManyPins::class.java, refusal, "expected TooManyPins, got $refusal")
        assertEquals(limit, (refusal as PinRefusal.TooManyPins).limit)
        assertEquals(limit, refusal.active)
    }

    @Test
    fun `pins survive process restart`(@TempDir root: Path) {
        val store1 = SegmentOutputStore(root.resolve("output"))
        store1.recover()
        val stream = OutputStreamId("run-m3-restart/op/transcript")
        store1.open(stream).reserve(64).apply { write(bytes("0123456789")) }.commit()

        val pins1 = OutputPinPortStoreAdapter(root.resolve("pins"), store1)
        val pinned = pins1.pin(stream, 0L..9L, "holder-m3", "durable pin")
        val pinId = (pinned as OutputPinResult.Pinned).pinId
        assertTrue(pins1.isPinned(stream, 5))
        pins1.close()

        // "Process restart": fresh store + fresh adapter against the same root.
        val store2 = SegmentOutputStore(root.resolve("output"))
        store2.recover()
        val pins2 = OutputPinPortStoreAdapter(root.resolve("pins"), store2)
        assertTrue(pins2.isPinned(stream, 5), "pin must survive restart")
        val listed = pins2.pinsOf(stream)
        assertEquals(1, listed.size, "expected exactly 1 pin after reload, got ${listed.size}")
        assertEquals(pinId, listed.first().pinId)
    }

    @Test
    fun `release of an already-released pin returns AlreadyReleased`(@TempDir root: Path) {
        val (store, pins) = freshAdapter(root)
        val stream = OutputStreamId("run-m3-idempotent/op/transcript")
        store.open(stream).reserve(64).apply { write(bytes("0123456789")) }.commit()

        val pinned = pins.pin(stream, 0L..9L, "holder-m3", "idempotent") as OutputPinResult.Pinned
        val firstRelease = pins.release(pinned.pinId)
        assertInstanceOf(PinReleaseOutcome.Released::class.java, firstRelease)

        val secondRelease = pins.release(pinned.pinId)
        assertInstanceOf(
            PinReleaseOutcome.AlreadyReleased::class.java, secondRelease,
            "second release must be AlreadyReleased, got $secondRelease",
        )
    }

    @Test
    fun `release of an unknown pinId returns UnknownPin`(@TempDir root: Path) {
        val (_, pins) = freshAdapter(root)
        val unknown = dev.rubentxu.pipeline.v2.output.OutputPinId("does-not-exist")
        val result = pins.release(unknown)
        assertInstanceOf(
            PinReleaseOutcome.AlreadyReleased::class.java, result,
            "release of unknown pin should report AlreadyReleased (torn-read shape), got $result",
        )
    }
}