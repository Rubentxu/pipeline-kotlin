package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.executor.SecretMaskingStrictness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Which masking posture a run applies.
 *
 * ## The guarantee
 *
 * Two decisions meet in ONE place: the branch asks for the posture it needs (the
 * durable branch persists the transcript, the volatile one does not), and the operator
 * may move it with one environment variable. Neither one silently wins over the other.
 *
 * A variable that names a posture this product does not have is a decision that cannot
 * be honoured, and reading it as the nearest posture it resembles is guessing with a
 * security switch. The answer is `null`, and the caller refuses the run.
 *
 * ## Why this instrument is this cheap
 * The resolution is a pure call over plain data: two postures and a string. What is
 * observed is the answer the composition root will act on, so the refusal path is
 * reachable from a unit row.
 */
class MaskingPostureResolutionTest {

    @Test
    fun `a run with no declaration keeps the posture its branch asked for`() {
        assertEquals(
            SecretMaskingStrictness.STRICT,
            resolveMaskingStrictness(SecretMaskingStrictness.STRICT, declared = null),
        )
        assertEquals(
            SecretMaskingStrictness.ADVISORY,
            resolveMaskingStrictness(SecretMaskingStrictness.ADVISORY, declared = null),
        )
    }

    @Test
    fun `a declaration moves the posture of the run`() {
        assertEquals(
            SecretMaskingStrictness.ADVISORY,
            resolveMaskingStrictness(SecretMaskingStrictness.STRICT, declared = "best-effort"),
        )
        assertEquals(
            SecretMaskingStrictness.STRICT,
            resolveMaskingStrictness(SecretMaskingStrictness.ADVISORY, declared = "required"),
        )
    }

    @Test
    fun `a declaration that names no posture of this product cannot be read`() {
        assertNull(
            resolveMaskingStrictness(SecretMaskingStrictness.STRICT, declared = "maybe"),
            "a posture that does not exist was read as the nearest one",
        )
        assertNull(
            resolveMaskingStrictness(SecretMaskingStrictness.STRICT, declared = "strict"),
            "a posture whose name is not the one this product publishes was read",
        )
    }
}
