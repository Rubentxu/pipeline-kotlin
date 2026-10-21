package dev.rubentxu.pipeline.v2.domain.saga

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The CANONICAL FORM of a compensation plan, pinned.
 *
 * ## What this test is for
 *
 * A plan fingerprint is the engine's memory of a promise: the durable obligation carries the
 * digest, not the bodies, so the digest is the only thing that lets a later process tell "the
 * same promise" from "the same key with another plan under it".
 *
 * That only works if EVERYONE derives the digest the same way. Before this form was published,
 * two implementations inside this very repository did not: one hashed the plan WITH the bodies
 * and one WITHOUT them, and both claimed the same version. Two plans, two digests, and the
 * engine's own divergence check comparing apples to oranges.
 *
 * So the form is a contract, and a contract needs a test that fails when it moves. This is that
 * test: it pins the form's TEXT, the digest it produces, the order of its fields, and the
 * refusal of a field that would make two plans spell the same text.
 *
 * It is a pure domain test: no filesystem, no subprocess, no network. It computes values and
 * compares them, which is the whole of what a fingerprint is.
 */
class SagaPlanFingerprintCanonicalFormTest {

    @Test
    @DisplayName("the canonical form is the version and the plan's fields, in a fixed order")
    fun `the canonical form spells the plan exactly`() {
        val form = SagaPlanFingerprint.canonicalFormOf(SAGA, PLAN)

        assertEquals(
            "saga-v1|checkout|reserve|release|stock/42|" +
                "example.saga.reserve.forward|example.saga.reserve.compensate|RESTORE",
            form,
        )
    }

    @Test
    @DisplayName("the version is part of the form, so a form change is a digest change")
    fun `the version is inside the form`() {
        val form = SagaPlanFingerprint.canonicalFormOf(SAGA, PLAN)

        assertTrue(
            form.startsWith("${SagaPlanFingerprint.CANONICAL_FORM_VERSION}|"),
            "the form must start with its version, got: $form",
        )
    }

    @Test
    @DisplayName("the digest of a known plan is frozen")
    fun `the digest of a known plan is frozen`() {
        // The value is NOT recomputed here on purpose: this is the vector an implementation
        // outside this repository checks itself against. Recomputing it would make the test
        // agree with whatever the code does today, and a form that drifts would drift
        // silently.
        //
        // It is not a made-up digest either. It is what the PRODUCT wrote to its durable
        // store: the `CompensationArmed` row of the `reserve` action of the demo chain,
        // read back from a real run. A published form that did not reproduce the digests
        // already on disk would re-arm every promise of every past run as a divergence.
        assertEquals(
            "b17b1d63381bc4c94167243a4028b5a5180b864f0790520a48042c52d9b1bac0",
            SagaPlanFingerprint.compute(SAGA, PLAN).hex,
        )
    }

    @Test
    @DisplayName("the digest is 64 lowercase hex characters")
    fun `the digest has the shape the type demands`() {
        val hex = SagaPlanFingerprint.compute(SAGA, PLAN).hex

        assertEquals(64, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' }, "not lowercase hex: $hex")
    }

    @Test
    @DisplayName("the ORDER of the fields is part of the contract")
    fun `the order of the fields matters`() {
        val swapped = PLAN.copy(
            actionId = SagaActionId.of("release"),
            compensationId = CompensationId.of("reserve"),
        )

        assertNotEquals(
            SagaPlanFingerprint.compute(SAGA, PLAN),
            SagaPlanFingerprint.compute(SAGA, swapped),
            "two plans whose fields are swapped must not spell the same text",
        )
    }

    @Test
    @DisplayName("the bodies are part of the form: a different body is a different plan")
    fun `the bodies are part of the form`() {
        val otherBody = PLAN.copy(compensateSlot = PluginStepId("another-body"))

        assertNotEquals(
            SagaPlanFingerprint.compute(SAGA, PLAN),
            SagaPlanFingerprint.compute(SAGA, otherBody),
            "a plan that compensates with another body is another plan",
        )
    }

    @Test
    @DisplayName("the saga is part of the form: the same action in another saga is another plan")
    fun `the saga is part of the form`() {
        assertNotEquals(
            SagaPlanFingerprint.compute(SAGA, PLAN),
            SagaPlanFingerprint.compute(SagaId.of("other-saga"), PLAN),
        )
    }

    @Test
    @DisplayName("a field carrying the separator is refused, by name")
    fun `a field carrying the separator is refused`() {
        val ambiguous = PLAN.copy(resourceRef = ResourceRef.of("stock|42"))

        val refusal = assertThrows(IllegalArgumentException::class.java) {
            SagaPlanFingerprint.canonicalFormOf(SAGA, ambiguous)
        }

        assertTrue(
            refusal.message?.contains("resourceRef") == true,
            "the refusal must name the field, got: ${refusal.message}",
        )
    }

    @Test
    @DisplayName("the refusal happens BEFORE any digest is computed")
    fun `the refusal happens before the digest`() {
        val ambiguous = PLAN.copy(compensateSlot = PluginStepId("body|with-separator"))

        assertThrows(IllegalArgumentException::class.java) {
            SagaPlanFingerprint.compute(SAGA, ambiguous)
        }
    }

    private companion object {
        val SAGA: SagaId = SagaId.of("checkout")

        val PLAN: SagaActionPlan = SagaActionPlan(
            actionId = SagaActionId.of("reserve"),
            compensationId = CompensationId.of("release"),
            resourceRef = ResourceRef.of("stock/42"),
            kind = CompensationKind.RESTORE,
            // The slot names the demo chain really gives its bodies, so the frozen digest
            // above is the one a real run wrote to its durable store.
            forwardSlot = PluginStepId("example.saga.reserve.forward"),
            compensateSlot = PluginStepId("example.saga.reserve.compensate"),
        )
    }
}
