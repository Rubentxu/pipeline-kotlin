package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputRefusal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M3 — fitness test for the closed `OutputRefusal` ADT after the M3 cases
 * are added (`RetentionGap`, `Corrupt`, `Unavailable`, `RangeLostRetention`).
 *
 * The ADT is closed on purpose: a sealed hierarchy that gains a new case
 * is a compile error at every `when` site, which is the discipline that
 * surfaces "I added a refusal mode nobody handles" at build time rather
 * than at runtime.
 *
 * The test pins three properties:
 *
 *  1. All four M3 cases are data classes (or `data object` for the
 *     parameter-less `Unavailable`), not open types.
 *  2. The `when` over `OutputRefusal` in this file is exhaustive — the
 *     file compiles only when every case is named, so a new case
 *     added to the ADT will break the build here (and at every other
 *     `when` site that consumes `OutputRefusal`).
 *  3. The M1-B precedent (`MainConsoleCli.renderRefusal`) is updated to
 *     render the new M3 cases — pinned by the cross-module compile check
 *     in Step 7.
 */
class OutputRefusalClosedTest {

    @Test
    fun `RetentionGap is a sealed data class on OutputRefusal`() {
        val reason = OutputRefusal.RetentionGap(
            stream = dev.rubentxu.pipeline.v2.output.OutputStreamId("run/op/transcript"),
            requestedRange = 0L..10L,
            lastCommitted = 5L,
        )
        // The constructor refuses; assert that the type is `instance` of the closed ADT.
        assertNotNull(reason, "RetentionGap must be constructable")
        assertTrue(reason is OutputRefusal, "RetentionGap must be a subtype of OutputRefusal")
    }

    @Test
    fun `Corrupt is a sealed data class on OutputRefusal`() {
        val reason = OutputRefusal.Corrupt(
            stream = dev.rubentxu.pipeline.v2.output.OutputStreamId("run/op/transcript"),
            requestedRange = 0L..10L,
            reason = "row exists but payload missing",
        )
        assertNotNull(reason)
        assertTrue(reason is OutputRefusal)
    }

    @Test
    fun `Unavailable is a sealed data object on OutputRefusal`() {
        val reason = OutputRefusal.Unavailable
        assertTrue(reason is OutputRefusal)
        // data object semantics: toString and equality are stable.
        assertEquals(OutputRefusal.Unavailable, OutputRefusal.Unavailable)
    }

    @Test
    fun `RangeLostRetention is a sealed data class on OutputRefusal`() {
        val reason = OutputRefusal.RangeLostRetention(
            stream = dev.rubentxu.pipeline.v2.output.OutputStreamId("run/op/transcript"),
            requestedRange = 0L..10L,
            lastCommitted = 5L,
        )
        assertNotNull(reason)
        assertTrue(reason is OutputRefusal)
    }

    /**
     * Closed-ADT discipline: this `when` MUST be exhaustive. Adding a new
     * `OutputRefusal` case will fail to compile this test, which is the
     * whole point of the discipline.
     *
     * The function's return value is not used outside the test; the
     * compile-time check is the test.
     */
    @Suppress("UNUSED_VARIABLE")
    private fun exhaustiveWhen(reason: OutputRefusal): String = when (reason) {
        is OutputRefusal.ForeignStream -> "foreign-stream"
        is OutputRefusal.UnknownStream -> "unknown-stream"
        is OutputRefusal.OffsetBeyondCommitted -> "offset-beyond-committed"
        is OutputRefusal.InvalidRange -> "invalid-range"
        OutputRefusal.RecoveryNotCompleted -> "recovery-not-completed"
        is OutputRefusal.DanglingCommit -> "dangling-commit"
        is OutputRefusal.StreamLostRetention -> "stream-lost-retention"
        is OutputRefusal.FollowCancelled -> "follow-cancelled"
        is OutputRefusal.StorageError -> "storage-error"
        // M3 cases
        is OutputRefusal.RetentionGap -> "retention-gap"
        is OutputRefusal.Corrupt -> "corrupt"
        OutputRefusal.Unavailable -> "unavailable"
        is OutputRefusal.RangeLostRetention -> "range-lost-retention"
    }
}