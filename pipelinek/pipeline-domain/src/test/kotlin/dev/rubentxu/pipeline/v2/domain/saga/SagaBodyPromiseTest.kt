package dev.rubentxu.pipeline.v2.domain.saga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whether the body that is about to run is the body that was promised.
 *
 * A promise says which body will undo the effect, and the engine measures that body when the
 * promise is made and again when the compensation is about to run. These are the rules that
 * decide whether the two measurements agree, and they are the whole of what keeps a
 * compensation from undoing an effect with a body nobody promised.
 */
class SagaBodyPromiseTest {

    @Test
    fun `the promised body is the body that runs`() {
        val verdict = SagaBodyPromise.verdict(promised = BODY, current = BODY)

        assertEquals(SagaBodyVerdict.Agreed, verdict)
    }

    @Test
    fun `another body is a divergence, and the verdict names both`() {
        val verdict = SagaBodyPromise.verdict(promised = BODY, current = OTHER_BODY)

        assertEquals(
            SagaBodyVerdict.Diverged(promised = BODY, current = OTHER_BODY),
            verdict,
        )
    }

    @Test
    fun `a body that is not there is a divergence too`() {
        val verdict = SagaBodyPromise.verdict(promised = BODY, current = null)

        assertEquals(
            SagaBodyVerdict.Diverged(promised = BODY, current = null),
            verdict,
        )
    }

    @Test
    fun `a promise that names no body cannot contradict one`() {
        assertEquals(SagaBodyVerdict.Agreed, SagaBodyPromise.verdict(promised = null, current = BODY))
        assertEquals(SagaBodyVerdict.Agreed, SagaBodyPromise.verdict(promised = null, current = null))
    }

    @Test
    fun `the verdict is a closed vocabulary, and it always answers`() {
        val verdicts = listOf(
            SagaBodyPromise.verdict(BODY, BODY),
            SagaBodyPromise.verdict(BODY, OTHER_BODY),
            SagaBodyPromise.verdict(BODY, null),
            SagaBodyPromise.verdict(null, BODY),
            SagaBodyPromise.verdict(null, null),
        )

        assertTrue(
            verdicts.all { it is SagaBodyVerdict.Agreed || it is SagaBodyVerdict.Diverged },
        )
        assertEquals(3, verdicts.count { it is SagaBodyVerdict.Agreed })
        assertEquals(2, verdicts.count { it is SagaBodyVerdict.Diverged })
    }

    @Test
    fun `the sentence names the promised body and the body that would run`() {
        val sentence = SagaBodyVerdict.Diverged(promised = BODY, current = OTHER_BODY).describe()

        assertTrue(sentence.contains(BODY.hex), sentence)
        assertTrue(sentence.contains(OTHER_BODY.hex), sentence)
    }

    @Test
    fun `the sentence of a body that is not there says so`() {
        val sentence = SagaBodyVerdict.Diverged(promised = BODY, current = null).describe()

        assertTrue(sentence.contains(BODY.hex), sentence)
        assertTrue(sentence.contains("no body"), sentence)
    }

    @Test
    fun `a blank digest is not a digest`() {
        assertThrows(IllegalArgumentException::class.java) { CompensationBodyDigest("  ") }
    }

    private companion object {
        val BODY = CompensationBodyDigest("a".repeat(64))
        val OTHER_BODY = CompensationBodyDigest("b".repeat(64))
    }
}
