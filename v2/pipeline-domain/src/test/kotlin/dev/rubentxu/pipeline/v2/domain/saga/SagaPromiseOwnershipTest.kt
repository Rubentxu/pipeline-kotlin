package dev.rubentxu.pipeline.v2.domain.saga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Who may write to a promise.
 *
 * A promise is a durable claim that something will be undone later, and it belongs to the
 * plugin that made it. The plan fingerprint says WHICH effect is promised, but it says nothing
 * about WHO promised it: two plugins can declare the same action, the same resource and the
 * same slot names, and their fingerprints are then identical even though their bodies are
 * not. These are the rules that keep one plugin's promise from being written by another.
 */
class SagaPromiseOwnershipTest {

    @Test
    fun `the plugin that armed the promise may write to it`() {
        val verdict = SagaPromiseOwnership.verdict(
            stored = origin("example.saga"),
            writer = origin("example.saga"),
        )

        assertEquals(SagaOwnershipVerdict.Allowed, verdict)
    }

    @Test
    fun `another plugin may not write to it, and the refusal names both`() {
        val verdict = SagaPromiseOwnership.verdict(
            stored = origin("example.saga"),
            writer = origin("other.saga"),
        )

        assertEquals(
            SagaOwnershipVerdict.ForeignOwner(owner = "example.saga", writer = "other.saga"),
            verdict,
        )
    }

    @Test
    fun `a promise whose owner was never recorded cannot prove a conflict`() {
        val stored = SagaPromiseOrigin(pluginId = null, intentVersion = 1)

        val verdict = SagaPromiseOwnership.verdict(stored = stored, writer = origin("other.saga"))

        assertEquals(SagaOwnershipVerdict.Allowed, verdict)
    }

    @Test
    fun `a writer that does not state who it is claims nothing to contradict`() {
        val verdict = SagaPromiseOwnership.verdict(stored = origin("example.saga"), writer = null)

        assertEquals(SagaOwnershipVerdict.Allowed, verdict)
    }

    @Test
    fun `the refusal is a closed vocabulary, and a foreign owner is always one of them`() {
        val verdicts = listOf(
            SagaPromiseOwnership.verdict(origin("a"), origin("a")),
            SagaPromiseOwnership.verdict(origin("a"), origin("b")),
            SagaPromiseOwnership.verdict(SagaPromiseOrigin(null, 1), origin("b")),
            SagaPromiseOwnership.verdict(origin("a"), null),
        )

        assertTrue(
            verdicts.all { it is SagaOwnershipVerdict.Allowed || it is SagaOwnershipVerdict.ForeignOwner },
        )
        assertEquals(1, verdicts.count { it is SagaOwnershipVerdict.ForeignOwner })
    }

    private fun origin(pluginId: String) = SagaPromiseOrigin(pluginId = pluginId, intentVersion = 1)
}
