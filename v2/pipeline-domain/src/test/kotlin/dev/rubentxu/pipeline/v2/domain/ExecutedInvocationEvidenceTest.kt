package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.durable.ExecutedInvocationEvidence
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * XCA-2A/A5 — characterization of "crossed the execution boundary".
 *
 * The law is `observed <=> status != PENDING`. It is sound because
 * [OperationStatus.transition] makes PENDING write-once: `RUNNING -> PENDING` and
 * `terminal -> PENDING` both fail by construction, so no persisted operation can return to
 * a state indistinguishable from "not yet executed".
 *
 * These assertions are POSITIVE on purpose. It is not enough to assert that PENDING is
 * unobserved; the non-terminal EXECUTED state must be asserted too, because the tempting
 * future "simplification" is `status.isTerminal`, and that would silently drop `RUNNING`
 * (non-terminal) while keeping `LOST` (terminal) — a partial, asymmetric failure that would
 * look almost correct and would make every downstream coverage number quietly optimistic.
 */
@Timeout(30)
class ExecutedInvocationEvidenceTest {

    private fun evidence(status: OperationStatus) = ExecutedInvocationEvidence(
        invocationId = OperationId("op-1"),
        stepKey = PluginStepId("core.echo"),
        status = status,
    )

    @Test
    fun `PENDING has not crossed the execution boundary`() {
        assertFalse(evidence(OperationStatus.PENDING).isObserved)
    }

    @Test
    fun `RUNNING has crossed the execution boundary (non-terminal, no success required)`() {
        assertTrue(evidence(OperationStatus.RUNNING).isObserved)
    }

    @Test
    fun `LOST has crossed the execution boundary (terminal, no success required)`() {
        assertTrue(evidence(OperationStatus.LOST).isObserved)
    }

    @Test
    fun `every terminal state except none is observed`() {
        val observed = listOf(
            OperationStatus.RUNNING,
            OperationStatus.SUCCEEDED,
            OperationStatus.FAILED,
            OperationStatus.ABORTED,
            OperationStatus.DIVERGENT,
            OperationStatus.LOST,
        )
        observed.forEach { status ->
            assertTrue(
                evidence(status).isObserved,
                "$status proves execution and MUST count as observed",
            )
        }
    }

    @Test
    fun `execution is not success - a FAILED step is observed`() {
        assertTrue(evidence(OperationStatus.FAILED).isObserved)
        assertTrue(evidence(OperationStatus.ABORTED).isObserved)
        assertTrue(evidence(OperationStatus.DIVERGENT).isObserved)
    }

    @Test
    fun `RUNNING and LOST are the asymmetric pair that status_isTerminal would split`() {
        assertEquals(false, OperationStatus.RUNNING.isTerminal, "RUNNING must NOT be terminal")
        assertEquals(true, OperationStatus.LOST.isTerminal, "LOST IS terminal")
        // Both are observed despite differing on terminality: the law is about crossing the
        // execution boundary, not about reaching a terminal state.
        assertTrue(evidence(OperationStatus.RUNNING).isObserved)
        assertTrue(evidence(OperationStatus.LOST).isObserved)
    }

    @Test
    fun `only PENDING is unobserved across the whole status set`() {
        val unobserved = OperationStatus.entries.filter { !evidence(it).isObserved }
        assertEquals(listOf(OperationStatus.PENDING), unobserved)
    }
}
