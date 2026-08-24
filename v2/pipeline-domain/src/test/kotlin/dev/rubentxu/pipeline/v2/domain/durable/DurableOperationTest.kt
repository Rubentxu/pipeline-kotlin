package dev.rubentxu.pipeline.v2.domain.durable

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DurableOperationTest {

    @Test
    fun `sealed hierarchy is exhaustive for RerunOperation`() {
        val op = RerunOperation(
            id = "op-1",
            fingerprint = Fingerprint("a".repeat(64)),
            input = OperationInput(
                stepId = "step-echo",
                params = mapOf("text" to kotlinx.serialization.json.JsonPrimitive("hello")),
                runId = "run-1",
                attempt = 1,
            ),
            output = null,
            status = OperationStatus.PENDING,
            attempt = 1,
        )
        assertEquals(ReplayPolicy.RERUN, op.replayPolicy)
        assertEquals("op-1", op.id)
    }

    @Test
    fun `sealed hierarchy is exhaustive for MemoizedOperation`() {
        val op = MemoizedOperation(
            id = "op-2",
            fingerprint = Fingerprint("b".repeat(64)),
            input = OperationInput(
                stepId = "step-echo",
                params = mapOf("text" to kotlinx.serialization.json.JsonPrimitive("hello")),
                runId = "run-1",
                attempt = 1,
            ),
            output = null,
            status = OperationStatus.PENDING,
            attempt = 1,
            cachedOutput = null,
        )
        assertEquals(ReplayPolicy.MEMOIZED, op.replayPolicy)
        assertEquals("op-2", op.id)
    }

    @Test
    fun `sealed hierarchy is exhaustive for CompositeOperation`() {
        val sub = RerunOperation(
            id = "sub-op",
            fingerprint = Fingerprint("c".repeat(64)),
            input = OperationInput(
                stepId = "step-echo",
                params = mapOf("text" to kotlinx.serialization.json.JsonPrimitive("inner")),
                runId = "run-1",
                attempt = 1,
            ),
            output = null,
            status = OperationStatus.PENDING,
            attempt = 1,
        )
        val op = CompositeOperation(
            id = "op-3",
            fingerprint = Fingerprint("d".repeat(64)),
            input = OperationInput(
                stepId = "step-composite",
                params = mapOf(),
                runId = "run-1",
                attempt = 1,
            ),
            output = null,
            status = OperationStatus.PENDING,
            attempt = 1,
            subOperations = listOf(sub),
        )
        assertEquals(ReplayPolicy.MEMOIZED, op.replayPolicy)
        assertEquals(1, op.subOperations.size)
    }
}
