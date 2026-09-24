package dev.rubentxu.pipeline.v2.spike.stagescoped

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure tests for [LexicalOrderSpec].
 *
 * No facade, no filesystem, no clock. These are HF0 (pure contract) tests:
 * they don't even need a JVM runtime beyond the spec itself.
 */
class LexicalOrderSpecTest {

    private fun suspend(ordinal: Int, call: SuspendCall = SuspendCall.IsUnix) =
        StageOp.Suspend(ordinal = ordinal, call = call)

    @Test
    @DisplayName("empty op list is trivially valid")
    fun emptyOpsIsValid() {
        val verdict = LexicalOrderSpec.check(emptyList())
        assertSame(LexicalOrderSpec.Result.Valid, verdict)
    }

    @Test
    @DisplayName("a single suspend op at ordinal 1 is valid")
    fun singleSuspendAtOneIsValid() {
        val verdict = LexicalOrderSpec.check(listOf(suspend(1)))
        assertSame(LexicalOrderSpec.Result.Valid, verdict)
    }

    @Test
    @DisplayName("strictly monotonic 1..N is valid, mixed with eager ops")
    fun monotonicMixedWithEagerIsValid() {
        val ops = listOf(
            suspend(1, SuspendCall.Pwd(tmp = false)),
            StageOp.Eager(echo("a")),
            suspend(2, SuspendCall.IsUnix),
            StageOp.Eager(echo("b")),
            suspend(3, SuspendCall.FileExists("file.txt")),
        )
        assertSame(LexicalOrderSpec.Result.Valid, LexicalOrderSpec.check(ops))
    }

    @Test
    @DisplayName("duplicate ordinal is rejected with DuplicateOrdinal reason")
    fun duplicateOrdinalIsRejected() {
        val verdict = LexicalOrderSpec.check(listOf(suspend(1), suspend(1)))
        val invalid = assertInstanceOf(LexicalOrderSpec.Result.Invalid::class.java, verdict)
        val reason = assertInstanceOf(LexicalOrderSpec.Reason.DuplicateOrdinal::class.java, invalid.reason)
        assertEquals(1, reason.ordinal)
    }

    @Test
    @DisplayName("non-contiguous ordinal is rejected with OrdinalGap reason")
    fun nonContiguousOrdinalIsRejected() {
        val verdict = LexicalOrderSpec.check(listOf(suspend(1), suspend(3)))
        val invalid = assertInstanceOf(LexicalOrderSpec.Result.Invalid::class.java, verdict)
        val reason = assertInstanceOf(LexicalOrderSpec.Reason.OrdinalGap::class.java, invalid.reason)
        assertEquals(2, reason.expected)
        assertEquals(3, reason.actual)
    }

    @Test
    @DisplayName("first ordinal not equal to 1 is rejected with OrdinalMustStartAtOne")
    fun firstOrdinalMustBeOne() {
        // To bypass the duplicate-ordinal check, we use a single-element list.
        val verdict = LexicalOrderSpec.check(listOf(suspend(2)))
        val invalid = assertInstanceOf(LexicalOrderSpec.Result.Invalid::class.java, verdict)
        assertSame(LexicalOrderSpec.Reason.OrdinalMustStartAtOne, invalid.reason)
    }

    @Test
    @DisplayName("ordinal 0 is rejected with OrdinalMustStartAtOne (defensive)")
    fun ordinalZeroIsRejected() {
        val verdict = LexicalOrderSpec.check(listOf(suspend(0)))
        val invalid = assertInstanceOf(LexicalOrderSpec.Result.Invalid::class.java, verdict)
        assertSame(LexicalOrderSpec.Reason.OrdinalMustStartAtOne, invalid.reason)
    }

    @Test
    @DisplayName("all-eager ops are trivially valid (no suspend ordinals to check)")
    fun onlyEagerOpsAreValid() {
        val ops = listOf(
            StageOp.Eager(echo("x")),
            StageOp.Eager(echo("y")),
        )
        assertSame(LexicalOrderSpec.Result.Valid, LexicalOrderSpec.check(ops))
    }

    private fun echo(text: String): dev.rubentxu.pipeline.v2.dsl.StepSpec =
        dev.rubentxu.pipeline.v2.dsl.StepSpec.Echo(text)
}
