package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The classifier decides whether a failed evaluation may be retried, and
 * the two rows it refuses to blur are the ones the cache's safety depends
 * on: an artifact that never loaded may be recompiled, and a script body
 * that threw may never be run again behind the cache's back.
 */
class EvaluationFailureTest {

    @Test
    fun `the lazy loader's refusal is an artifact failure, retriable by recompiling`() {
        assertEquals(
            EvaluationFailure.UnloadedArtifact,
            classifyEvaluationFailure(RuntimeException("Compiled script is not loaded yet")),
        )
    }

    @Test
    fun `any other throwable is the script's own failure and is never retried`() {
        assertEquals(
            EvaluationFailure.ScriptBody,
            classifyEvaluationFailure(RuntimeException("boom from the script body")),
        )
        assertEquals(
            EvaluationFailure.ScriptBody,
            classifyEvaluationFailure(IllegalStateException("network call already sent")),
        )
        assertEquals(
            EvaluationFailure.ScriptBody,
            classifyEvaluationFailure(RuntimeException("Compiled script is not loaded ye")),
        )
    }
}
