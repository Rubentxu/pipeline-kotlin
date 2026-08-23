package com.pipeline.v2.domain.durable

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlinx.serialization.json.JsonPrimitive

class DivergenceDetectorTest {

    private val detector = DivergenceDetector()

    @Test
    fun `matching fingerprints returns success`() {
        val fp = Fingerprint("a".repeat(64))
        val current = RerunOperation(
            id = "op-1",
            fingerprint = fp,
            input = OperationInput("step", mapOf(), "run-1", 1),
            output = null,
            status = OperationStatus.PENDING,
            attempt = 1,
        )
        val journaled = RerunOperation(
            id = "op-1",
            fingerprint = fp,
            input = OperationInput("step", mapOf(), "run-1", 1),
            output = null,
            status = OperationStatus.SUCCEEDED,
            attempt = 1,
        )
        val result = detector.check(current, journaled)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `mismatched fingerprints returns failure with DivergenceException`() {
        val currentFp = Fingerprint("a".repeat(64))
        val journaledFp = Fingerprint("b".repeat(64))
        val current = RerunOperation(
            id = "op-1",
            fingerprint = currentFp,
            input = OperationInput("step", mapOf(), "run-1", 1),
            output = null,
            status = OperationStatus.PENDING,
            attempt = 1,
        )
        val journaled = RerunOperation(
            id = "op-1",
            fingerprint = journaledFp,
            input = OperationInput("step", mapOf(), "run-1", 1),
            output = null,
            status = OperationStatus.SUCCEEDED,
            attempt = 1,
        )
        val result = detector.check(current, journaled)
        assertTrue(result.isFailure)
        val exc = result.exceptionOrNull() as DivergenceException
        assertEquals(journaledFp, exc.expected)
        assertEquals(currentFp, exc.actual)
        assertEquals("op-1", exc.opId)
    }

    @Test
    fun `null journaled returns success`() {
        val current = RerunOperation(
            id = "op-1",
            fingerprint = Fingerprint("a".repeat(64)),
            input = OperationInput("step", mapOf(), "run-1", 1),
            output = null,
            status = OperationStatus.PENDING,
            attempt = 1,
        )
        val result = detector.check(current, null)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `DivergenceException contains both fingerprints and identifiers`() {
        val expectedFp = Fingerprint("1".repeat(64))
        val actualFp = Fingerprint("2".repeat(64))
        val current = RerunOperation(
            id = "op-1",
            fingerprint = actualFp,
            input = OperationInput("step", mapOf(), "run-1", 1),
            output = null,
            status = OperationStatus.PENDING,
            attempt = 1,
        )
        val journaled = RerunOperation(
            id = "op-1",
            fingerprint = expectedFp,
            input = OperationInput("step", mapOf(), "run-1", 1),
            output = null,
            status = OperationStatus.SUCCEEDED,
            attempt = 1,
        )
        val result = detector.check(current, journaled)
        val exc = result.exceptionOrNull() as DivergenceException
        assertEquals(expectedFp, exc.expected)
        assertEquals(actualFp, exc.actual)
        assertEquals("op-1", exc.opId)
        assertEquals("run-1", exc.runId)
    }
}
