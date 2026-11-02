package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: the run's result has exactly one vocabulary, and a word that
 * is not part of it is refused instead of guessed.
 *
 * ## What this test is for
 *
 * The result crosses the wire as a word, and the word is recovered into the
 * typed result at one boundary. If that boundary could answer "failure" for a
 * word it does not know, then a run whose result word this build cannot read
 * would be reported as a failed run — a failure nobody observed, asserted by a
 * reader that never saw one. And if the list of words were written twice, the
 * two lists would drift the first time a case is added, and the reader would
 * stop understanding a run that the writer still believes it described.
 *
 * So the rule is a closed set with a single list, and the boundary refuses
 * outside it.
 *
 * ## Why the rule refuses instead of encoding
 *
 * A default here would make every word look like a failure and hide the
 * difference between "the run failed" and "this build cannot read the word".
 * The difference is the whole reason the boundary exists.
 *
 * The rule is pure, so this table is the whole guarantee: no filesystem, no
 * process and no clock.
 */
class RunOutcomeVocabularyTest {

    /**
     * The words the result is written as, and the case each one names.
     *
     * The first three name a case that carries no detail of its own, so the
     * word and the case are the same thing. The failure word names a case that
     * carries a detail, and the word alone does not say which: it says that
     * the run failed, and the failure's own fact says why.
     */
    @Test
    fun `cada palabra del vocabulario nombra su caso`() {
        assertEquals(RunOutcome.Success, RunOutcome.parse(RunOutcome.SUCCESS_TOKEN))
        assertEquals(RunOutcome.Unstable, RunOutcome.parse(RunOutcome.UNSTABLE_TOKEN))
        assertEquals(RunOutcome.Aborted, RunOutcome.parse(RunOutcome.ABORTED_TOKEN))

        val failure = RunOutcome.parse(RunOutcome.FAILURE_TOKEN)
        assertTrue(failure is RunOutcome.Failure, "'failure' must name the failure case")
    }

    @Test
    fun `cada caso escribe una palabra del vocabulario`() {
        val outcomes = listOf<RunOutcome>(
            RunOutcome.Success,
            RunOutcome.Unstable,
            RunOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "the step exited nonzero")),
            RunOutcome.Aborted,
        )

        for (outcome in outcomes) {
            assertTrue(
                outcome.wireToken in RunOutcome.supportedTokens,
                "'${outcome.wireToken}' must be a word this build reads",
            )
        }
    }

    @Test
    fun `la lista de palabras y las palabras de los casos son la misma cosa`() {
        val written = listOf(
            RunOutcome.Success,
            RunOutcome.Unstable,
            RunOutcome.Failure(PipelineFailure(FailureKind.SCRIPT, "the step exited nonzero")),
            RunOutcome.Aborted,
        ).map { it.wireToken }.toSet()

        assertEquals(
            written,
            RunOutcome.supportedTokens,
            "the words a case writes and the words this build reads must be one vocabulary",
        )
    }

    @Test
    fun `una palabra fuera del vocabulario no se decodifica`() {
        assertNull(RunOutcome.parse("degraded"), "a word outside the vocabulary must not decode")
        assertNull(RunOutcome.parse(""), "an empty word must not decode")
        assertNull(RunOutcome.parse("succeeded"), "'succeeded' is another vocabulary's word")
        assertNull(RunOutcome.parse("cancelled"), "'cancelled' is another vocabulary's word")
    }

    @Test
    fun `la palabra se lee exactamente como se escribe`() {
        assertNull(RunOutcome.parse("SUCCESS"), "the word is case-sensitive")
        assertNull(RunOutcome.parse("Success"), "the word is case-sensitive")
        assertNull(RunOutcome.parse(" success"), "the word is not trimmed")
    }

    @Test
    fun `la palabra de un fallo nombra un fallo sin atribuir el motivo`() {
        val failure = RunOutcome.parse(RunOutcome.FAILURE_TOKEN) as RunOutcome.Failure

        assertEquals(FailureKind.UNKNOWN, failure.failure.kind)
        assertTrue(
            failure.failure.message.isNotBlank(),
            "the carrier must say what the word does not say",
        )
    }
}
