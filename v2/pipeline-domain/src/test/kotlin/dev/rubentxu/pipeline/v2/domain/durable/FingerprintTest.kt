package dev.rubentxu.pipeline.v2.domain.durable

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlinx.serialization.json.JsonPrimitive

class FingerprintTest {

    @Test
    fun `valid 64-hex fingerprint is accepted`() {
        val valid = "a".repeat(64)
        val fp = Fingerprint(valid)
        assertEquals(valid, fp.hex)
    }

    @Test
    fun `invalid length is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Fingerprint("abc123")
        }
    }

    @Test
    fun `non-hex characters are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Fingerprint("g".repeat(64)) // 'g' is not hex
        }
    }

    @Test
    fun `uppercase hex is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Fingerprint("A".repeat(64)) // uppercase not allowed
        }
    }

    @Test
    fun `fingerprint is deterministic across MessageDigest instances`() {
        val input = OperationInput(
            stepId = "step-echo",
            params = mapOf("text" to JsonPrimitive("hello")),
            runId = "run-1",
            attempt = 1,
        )
        val fp1 = Fingerprint.compute(input, "step-echo", ReplayPolicy.MEMOIZED, 1)
        val fp2 = Fingerprint.compute(input, "step-echo", ReplayPolicy.MEMOIZED, 1)
        assertEquals(fp1.hex, fp2.hex)
    }

    @Test
    fun `different input produces different fingerprint`() {
        val input1 = OperationInput(
            stepId = "step-echo",
            params = mapOf("text" to JsonPrimitive("hello")),
            runId = "run-1",
            attempt = 1,
        )
        val input2 = OperationInput(
            stepId = "step-echo",
            params = mapOf("text" to JsonPrimitive("world")),
            runId = "run-1",
            attempt = 1,
        )
        val fp1 = Fingerprint.compute(input1, "step-echo", ReplayPolicy.MEMOIZED, 1)
        val fp2 = Fingerprint.compute(input2, "step-echo", ReplayPolicy.MEMOIZED, 1)
        assertNotEquals(fp1.hex, fp2.hex)
    }

    @Test
    fun `different stepId produces different fingerprint`() {
        val input = OperationInput(
            stepId = "step-echo",
            params = mapOf("text" to JsonPrimitive("hello")),
            runId = "run-1",
            attempt = 1,
        )
        val fp1 = Fingerprint.compute(input, "step-echo", ReplayPolicy.MEMOIZED, 1)
        val fp2 = Fingerprint.compute(input, "step-shell", ReplayPolicy.MEMOIZED, 1)
        assertNotEquals(fp1.hex, fp2.hex)
    }

    @Test
    fun `different replayPolicy produces different fingerprint`() {
        val input = OperationInput(
            stepId = "step-echo",
            params = mapOf("text" to JsonPrimitive("hello")),
            runId = "run-1",
            attempt = 1,
        )
        val fp1 = Fingerprint.compute(input, "step-echo", ReplayPolicy.MEMOIZED, 1)
        val fp2 = Fingerprint.compute(input, "step-echo", ReplayPolicy.RERUN, 1)
        assertNotEquals(fp1.hex, fp2.hex)
    }

    @Test
    fun `different attempt produces different fingerprint`() {
        val input = OperationInput(
            stepId = "step-echo",
            params = mapOf("text" to JsonPrimitive("hello")),
            runId = "run-1",
            attempt = 1,
        )
        val fp1 = Fingerprint.compute(input, "step-echo", ReplayPolicy.MEMOIZED, 1)
        val fp2 = Fingerprint.compute(input, "step-echo", ReplayPolicy.MEMOIZED, 2)
        assertNotEquals(fp1.hex, fp2.hex)
    }

    @Test
    fun `negative attempt throws IllegalArgumentException`() {
        val input = OperationInput(
            stepId = "step-echo",
            params = mapOf("text" to JsonPrimitive("hello")),
            runId = "run-1",
            attempt = 1,
        )
        assertThrows(IllegalArgumentException::class.java) {
            Fingerprint.compute(input, "step-echo", ReplayPolicy.MEMOIZED, 0)
        }
    }
}
