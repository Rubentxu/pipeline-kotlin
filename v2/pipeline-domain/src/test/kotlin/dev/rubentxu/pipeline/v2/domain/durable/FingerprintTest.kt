package dev.rubentxu.pipeline.v2.domain.durable

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The fingerprint is the operation identity that replay, divergence and resume
 * are decided against, so its two guarantees are worth pinning permanently.
 *
 * `Fingerprint`'s KDoc claims canonical encoding "guarantees stable field
 * ordering" and that this "ensures cross-process determinism". Both are claims
 * about behaviour, and both fail silently if untrue: a fingerprint that varies
 * with map iteration order would make a resumed run miss its own completion and
 * redo work, with nothing in the output to say why.
 */
class FingerprintTest {

    private fun input(
        stepId: String = "compile",
        runId: String = "run-1",
        attempt: Int = 1,
        params: Map<String, kotlinx.serialization.json.JsonElement> = mapOf(
            "target" to JsonPrimitive("prod"),
            "flags" to JsonPrimitive("--strict"),
        ),
    ) = OperationInput(stepId = stepId, runId = runId, attempt = attempt, params = params)

    private fun fp(
        operationInput: OperationInput = input(),
        stepId: String? = null,
        replayPolicy: ReplayPolicy = ReplayPolicy.MEMOIZED,
        attempt: Int? = null,
    ) = Fingerprint.compute(
        input = operationInput,
        stepId = stepId ?: operationInput.stepId,
        replayPolicy = replayPolicy,
        attempt = attempt ?: operationInput.attempt,
    )

    @Nested
    @DisplayName("the encoding is canonical, so the value is stable")
    inner class Determinism {

        @Test
        fun `the same input yields the same fingerprint`() {
            assertEquals(fp(), fp(), "recomputing an identical input changed the identity")
        }

        /**
         * The load-bearing claim. `params` is a `Map`, and a `LinkedHashMap`
         * built in a different order is still an equal `Map` in Kotlin — so if
         * the encoder did not canonicalise, these two would disagree while
         * comparing as the same value. Nothing else in the type system would
         * have caught it.
         */
        @Test
        fun `parameter map iteration order does not change the fingerprint`() {
            val forward = mapOf(
                "target" to JsonPrimitive("prod"),
                "flags" to JsonPrimitive("--strict"),
                "depth" to JsonPrimitive(3),
            )
            val reversed = mapOf(
                "depth" to JsonPrimitive(3),
                "flags" to JsonPrimitive("--strict"),
                "target" to JsonPrimitive("prod"),
            )
            assertEquals(
                fp(input(params = forward)),
                fp(input(params = reversed)),
                "the same map encoded differently by iteration order; encoding is not canonical",
            )
        }

        /**
         * A parameter whose value is itself an object. Sorting only the top
         * level would leave the nested keys order-dependent, which is the same
         * defect one level down and would pass a shallower version of the test
         * above while the identity remained unstable in practice.
         */
@Test
        fun `nested object key order does not change the fingerprint`() {
            val forward = buildJsonObject {
                put("opts", buildJsonObject { put("alpha", JsonPrimitive(1)); put("beta", JsonPrimitive(2)) })
            }
            val reversed = buildJsonObject {
                put("opts", buildJsonObject { put("beta", JsonPrimitive(2)); put("alpha", JsonPrimitive(1)) })
            }
            assertEquals(
                fp(input(params = mapOf("opts" to forward))),
                fp(input(params = mapOf("opts" to reversed))),
                "a nested object encoded in a different key order changed the identity",
            )
        }

        @Test
        fun `the fingerprint is 64 lowercase hex characters`() {
            val hex = fp().hex
            assertEquals(64, hex.length)
            assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' }, "unexpected characters in $hex")
        }
    }

    /**
     * Every field of the canonical payload must actually reach the digest. A
     * field that is declared, serialised and then ignored would leave two
     * genuinely different operations sharing one identity — which is precisely
     * the collision the fingerprint exists to prevent. Naming the fields is
     * not evidence that they participate; this is.
     */
    @Nested
    @DisplayName("every payload field participates in the identity")
    inner class FieldSensitivity {

        @Test
        fun `a different step id changes the fingerprint`() {
            assertNotEquals(fp(), fp(stepId = "package"))
        }

        @Test
        fun `a different run id changes the fingerprint`() {
            assertNotEquals(fp(), fp(operationInput = input(runId = "run-2")))
        }

        @Test
        fun `a different attempt changes the fingerprint`() {
            assertNotEquals(fp(), fp(attempt = 2))
        }

        @Test
        fun `a different parameter value changes the fingerprint`() {
            val changed = mapOf("target" to JsonPrimitive("staging"), "flags" to JsonPrimitive("--strict"))
            assertNotEquals(fp(), fp(operationInput = input(params = changed)))
        }

        @Test
        fun `a different replay policy changes the fingerprint`() {
            // MEMOIZED and NEVER are opposite answers to "may this completion be
            // reused", so a journal that conflated them could hand back a
            // completion the policy forbids.
            assertNotEquals(fp(replayPolicy = ReplayPolicy.MEMOIZED), fp(replayPolicy = ReplayPolicy.NEVER))
            assertNotEquals(fp(replayPolicy = ReplayPolicy.RERUN), fp(replayPolicy = ReplayPolicy.NEVER))
        }

        @Test
        fun `dropping a parameter changes the fingerprint`() {
            // Omitting a key is not the same operation as supplying it, even
            // when every remaining key is untouched.
            val withoutFlags = mapOf("target" to JsonPrimitive("prod"))
            assertNotEquals(fp(operationInput = input(params = withoutFlags)), fp())
        }
    }

    @Nested
    @DisplayName("the attempt number is refused below one")
    inner class Attempt {

        @Test
        fun `attempt zero is refused at the boundary`() {
            // attempt is 1-based: 0 would collide with "no attempt yet" and
            // silently become a valid identity for a real operation.
            assertThrows<IllegalArgumentException> { fp(attempt = 0) }
            assertThrows<IllegalArgumentException> { fp(attempt = -1) }
        }

        @Test
        fun `attempt one is accepted`() {
            assertEquals(64, fp(attempt = 1).hex.length)
        }
    }

    @Nested
    @DisplayName("the value wrapper refuses anything that is not a digest")
    inner class Hex {

        @Test
        fun `a short hex string is refused`() {
            assertThrows<IllegalArgumentException> { Fingerprint("abc") }
        }

        @Test
        fun `uppercase hex is refused`() {
            // The contract is 64 lowercase characters, and accepting uppercase
            // would give one digest two spellings — the identity would then be
            // a function of how it was written rather than of the operation.
            val uppercase = "A".repeat(64)
            assertThrows<IllegalArgumentException> { Fingerprint(uppercase) }
        }

        @Test
        fun `a non-hex character is refused`() {
            val notHex = "z".repeat(64)
            assertThrows<IllegalArgumentException> { Fingerprint(notHex) }
        }
    }
}