package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PipelineFailureTest {
    @Test
    fun `failure carries kind, message and optional cause`() {
        val cause = IllegalStateException("boom")
        val failure = PipelineFailure(FailureKind.SCRIPT, "script exited 1", cause)

        assertEquals(FailureKind.SCRIPT, failure.kind)
        assertEquals("script exited 1", failure.message)
        assertSame(cause, failure.cause)
    }

    @Test
    fun `cause defaults to null when omitted`() {
        val failure = PipelineFailure(FailureKind.NETWORK, "dns unreachable")

        assertEquals(null, failure.cause)
    }

    @Test
    fun `blank message is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PipelineFailure(FailureKind.USER, " ") }
        assertThrows(IllegalArgumentException::class.java) { PipelineFailure(FailureKind.UNKNOWN, "") }
    }
}
