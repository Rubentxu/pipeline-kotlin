package dev.rubentxu.pipeline.v2.domain.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: the durable status of an operation says the same thing everywhere.
 *
 * The status is the one fact a run's fate is written in, and it is read by the
 * introspection plane, by the recover decider, by the retry engine and by the
 * reconcilers. Each of them used to answer "is this a failure?" with its own
 * list of cases, and a list is a second copy of the vocabulary: the copies
 * agreed until the vocabulary grew a case they had never heard of.
 *
 * The two that disagreed the most are the ones a run is judged by. LOST means
 * the worker died and the outcome is unknown, and unknown never means success,
 * so LOST is a failure. UNSTABLE means the work RAN and asked for attention,
 * so it is terminal and it is NOT a failure, and not a poll failure either.
 *
 * This file is the cheapest instrument that can fail that: the predicates are
 * pure, so a table of the nine states against the three answers is the whole
 * guarantee, with no journal and no clock.
 */
class OperationStatusTest {

    /**
     * What each state answers. Written out one row per state, because the
     * table IS the vocabulary: a case added to the enum has to be given its
     * three answers here, and the next row says so out loud.
     */
    private data class Row(
        val status: OperationStatus,
        val terminal: Boolean,
        val failure: Boolean,
        val pollFailure: Boolean,
    )

    private val rows = listOf(
        Row(OperationStatus.PENDING, terminal = false, failure = false, pollFailure = false),
        Row(OperationStatus.RUNNING, terminal = false, failure = false, pollFailure = false),
        Row(OperationStatus.SUCCEEDED, terminal = true, failure = false, pollFailure = false),
        Row(OperationStatus.FAILED, terminal = true, failure = true, pollFailure = true),
        Row(OperationStatus.UNSTABLE, terminal = true, failure = false, pollFailure = false),
        Row(OperationStatus.ABORTED, terminal = true, failure = false, pollFailure = true),
        Row(OperationStatus.DIVERGENT, terminal = true, failure = true, pollFailure = true),
        Row(OperationStatus.LOST, terminal = true, failure = true, pollFailure = true),
        Row(OperationStatus.FAILED_TIMEOUT, terminal = true, failure = true, pollFailure = true),
    )

    /**
     * The three answers, for every state of the vocabulary.
     */
    @Test
    fun `cada estado decide sus tres predicados`() {
        rows.forEach { row ->
            assertEquals(
                row.terminal,
                row.status.isTerminal,
                "${row.status.name} answers isTerminal wrongly",
            )
            assertEquals(
                row.failure,
                row.status.isFailure,
                "${row.status.name} answers isFailure wrongly",
            )
            assertEquals(
                row.pollFailure,
                row.status.isPollFailure,
                "${row.status.name} answers isPollFailure wrongly",
            )
        }
    }

    /**
     * The table covers the whole vocabulary.
     *
     * The predicates are exhaustive `when`s, so a case added to the enum
     * breaks the COMPILER. This row is what breaks the TABLE: without it, a
     * new case could sit in the enum, answer nothing in the table, and the
     * table would still be green because it never asked.
     */
    @Test
    fun `la tabla cubre el vocabulario entero`() {
        assertEquals(
            OperationStatus.entries.toSet(),
            rows.map { it.status }.toSet(),
            "the table does not decide every status of the vocabulary; " +
                "a status with no row would answer nothing and the table would still be green",
        )
    }

    /**
     * The two cases that carry the weight, said out loud.
     *
     * They are the ones a copy of the vocabulary gets wrong: LOST is a failure
     * because its outcome is unknown, and UNSTABLE is not one because the work
     * ran.
     */
    @Test
    fun `LOST es fallo y UNSTABLE no lo es`() {
        assertTrue(OperationStatus.LOST.isFailure, "a lost operation's outcome is unknown, and unknown is not success")
        assertTrue(OperationStatus.UNSTABLE.isTerminal, "an unstable operation finished")
        assertFalse(OperationStatus.UNSTABLE.isFailure, "the work ran and asked for attention; that is not a failure")
        assertFalse(OperationStatus.UNSTABLE.isPollFailure, "a finished operation never advances an iteration")
    }

    /**
     * A terminal status is final: it accepts only itself.
     */
    @Test
    fun `un estado terminal no transita a otro`() {
        OperationStatus.entries
            .filter { it.isTerminal }
            .forEach { terminal ->
                OperationStatus.entries
                    .filter { it != terminal }
                    .forEach { other ->
                        val result = OperationStatus.transition(terminal, other)
                        assertTrue(
                            result.isFailure,
                            "$terminal must not transition to $other; it is terminal",
                        )
                    }
            }
    }

    /**
     * The two moves the state machine allows, and the one it refuses.
     */
    @Test
    fun `las transiciones validas son PENDING a RUNNING y RUNNING a terminal`() {
        assertTrue(
            OperationStatus.transition(OperationStatus.PENDING, OperationStatus.RUNNING).isSuccess,
            "PENDING to RUNNING is the start of the work",
        )
        OperationStatus.entries
            .filter { it.isTerminal }
            .forEach { terminal ->
                assertTrue(
                    OperationStatus.transition(OperationStatus.RUNNING, terminal).isSuccess,
                    "RUNNING to $terminal is how the work ends",
                )
            }
        assertTrue(
            OperationStatus.transition(OperationStatus.PENDING, OperationStatus.SUCCEEDED).isFailure,
            "an operation that never started cannot have succeeded",
        )
        assertTrue(
            OperationStatus.transition(OperationStatus.RUNNING, OperationStatus.PENDING).isFailure,
            "work in flight cannot go back to not having started",
        )
    }
}
