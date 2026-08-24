package com.pipeline.v2.domain.durable

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class OperationStatusTest {

    @Test
    fun `PENDING to RUNNING is valid`() {
        val result = OperationStatus.transition(OperationStatus.PENDING, OperationStatus.RUNNING)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `RUNNING to SUCCEEDED is valid`() {
        val result = OperationStatus.transition(OperationStatus.RUNNING, OperationStatus.SUCCEEDED)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `RUNNING to FAILED is valid`() {
        val result = OperationStatus.transition(OperationStatus.RUNNING, OperationStatus.FAILED)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `RUNNING to ABORTED is valid`() {
        val result = OperationStatus.transition(OperationStatus.RUNNING, OperationStatus.ABORTED)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `RUNNING to DIVERGENT is valid`() {
        val result = OperationStatus.transition(OperationStatus.RUNNING, OperationStatus.DIVERGENT)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `same state transition is valid`() {
        val result = OperationStatus.transition(OperationStatus.SUCCEEDED, OperationStatus.SUCCEEDED)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `PENDING to SUCCEEDED is invalid`() {
        val result = OperationStatus.transition(OperationStatus.PENDING, OperationStatus.SUCCEEDED)
        assertTrue(result.isFailure)
    }

    @Test
    fun `terminal state to any state is invalid`() {
        val terminalStates = listOf(
            OperationStatus.SUCCEEDED,
            OperationStatus.FAILED,
            OperationStatus.ABORTED,
            OperationStatus.DIVERGENT,
        )
        for (from in terminalStates) {
            for (to in OperationStatus.entries) {
                if (from != to) {
                    val result = OperationStatus.transition(from, to)
                    assertTrue(result.isFailure, "Expected failure for $from -> $to")
                }
            }
        }
    }
}
