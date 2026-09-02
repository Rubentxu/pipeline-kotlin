package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegacyOutcomeMapperTest {

    @Test
    fun `success maps to RunOutcome Success`() {
        assertEquals(RunOutcome.Success, LegacyOutcomeMapper.toRunOutcome("success"))
    }

    @Test
    fun `completed maps to RunOutcome Success as historical synonym`() {
        assertEquals(RunOutcome.Success, LegacyOutcomeMapper.toRunOutcome("completed"))
    }

    @Test
    fun `unstable maps to RunOutcome Unstable`() {
        assertEquals(RunOutcome.Unstable, LegacyOutcomeMapper.toRunOutcome("unstable"))
    }

    @Test
    fun `failure maps to RunOutcome Failure with UNKNOWN kind`() {
        val outcome = LegacyOutcomeMapper.toRunOutcome("failure")

        assertTrue(outcome is RunOutcome.Failure)
        assertEquals(FailureKind.UNKNOWN, (outcome as RunOutcome.Failure).failure.kind)
    }

    @Test
    fun `failed maps to RunOutcome Failure as historical synonym`() {
        val outcome = LegacyOutcomeMapper.toRunOutcome("failed")

        assertTrue(outcome is RunOutcome.Failure)
    }

    @Test
    fun `aborted maps to RunOutcome Aborted`() {
        assertEquals(RunOutcome.Aborted, LegacyOutcomeMapper.toRunOutcome("aborted"))
    }

    @Test
    fun `unknown token fails closed — never silently a success`() {
        assertThrows(IllegalArgumentException::class.java) {
            LegacyOutcomeMapper.toRunOutcome(" SUCCESS")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LegacyOutcomeMapper.toRunOutcome("Success")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LegacyOutcomeMapper.toRunOutcome("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LegacyOutcomeMapper.toRunOutcome("ok")
        }
    }

    @Test
    fun `mapping is deterministic for every token in the closed set`() {
        val tokens = listOf("success", "completed", "unstable", "failure", "failed", "aborted")

        tokens.forEach { token ->
            val first = LegacyOutcomeMapper.toRunOutcome(token)
            repeat(5) {
                assertEquals(first, LegacyOutcomeMapper.toRunOutcome(token), "token '$token' must map deterministically")
            }
        }
    }
}
