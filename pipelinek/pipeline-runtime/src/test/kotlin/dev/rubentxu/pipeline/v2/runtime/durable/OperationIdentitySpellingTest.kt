package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The SPELLINGS of a durable operation's identity, and the operation each one belongs to.
 *
 * ## The three spellings this repository has written
 *
 * | spelling | what it hashes | the operation it belongs to |
 * |---|---|---|
 * | the canonical one | the canonical payload, keys sorted | every step operation |
 * | the historical rejection one | the payload with the `RERUN` policy | a step REFUSED at the typed admission |
 * | the historical receipt one | the operation id, then the encoded input | the cancellation and recovery receipts |
 *
 * The last two are HISTORY: the product writes the canonical one now, and the rows that carry
 * the other two are still in the journals that shipped. A digest that is already durable is
 * read with the spelling it was written with, so both historical spellings have to stay
 * describable — which is what this file is for.
 *
 * ## Why the historical spellings were a second authority
 *
 * The canonical spelling sorts the parameter keys, and it does so on purpose: the serialization
 * runtime writes a map in iteration order, so two equal parameter maps assembled in a different
 * order would otherwise acquire two identities. The historical receipt spelling had no such
 * sort, and it hashed the operation id into the identity as well. The historical rejection
 * spelling had the right payload and the wrong POLICY: the policy is hashed into the identity,
 * so it is the only record of the policy a journal row carries.
 *
 * ## What this file is and is not
 *
 * It characterizes: it says what each spelling IS and which operation it belonged to, and it
 * pins the digest a shipped candidate wrote for a known receipt. It does not test the product's
 * behaviour — the rows that DO are in [SchemaRejectionIdentityTest] and in the adapters' own
 * tests. The historical formulas are reproduced here on purpose: a formula that no longer
 * exists anywhere else would rot, and the digests it produced are still in the journals.
 *
 * Pure and total: no journal, no store, no clock, no filesystem.
 */
class OperationIdentitySpellingTest {

    // ---------------------------------------------------------------------
    // the canonical spelling
    // ---------------------------------------------------------------------

    /**
     * The canonical spelling does not depend on the ORDER the parameters were assembled in.
     *
     * The serialization runtime writes a map in iteration order, so the sort is the only thing
     * that makes two equal parameter maps one identity. Without it, the same operation would
     * acquire two identities depending on an accident of how its parameters were built.
     */
    @Test
    fun `the canonical spelling does not depend on the order of the parameters`() {
        val one = input(params = linkedMapOf("kind" to JsonPrimitive("cancel"), "runId" to JsonPrimitive(RUN_ID)))
        val other = input(params = linkedMapOf("runId" to JsonPrimitive(RUN_ID), "kind" to JsonPrimitive("cancel")))

        assertEquals(
            one,
            other,
            "the two inputs hold the same parameters; only the order they were assembled in differs",
        )
        assertEquals(
            Fingerprint.compute(one, STEP_ID, ReplayPolicy.MEMOIZED, ATTEMPT),
            Fingerprint.compute(other, STEP_ID, ReplayPolicy.MEMOIZED, ATTEMPT),
            "the same parameters are the same operation, whichever order they were assembled in",
        )
    }

    /**
     * The canonical spelling is a function of the DECLARED policy.
     *
     * The policy is hashed into the identity, and it is the only record of the policy a journal
     * row carries: nothing writes a `MemoizedOperation`, so every step row travels under the
     * kind `RERUN` and the fingerprint is the one place the policy is written down. That is why
     * a row that spells the identity with the wrong policy records a policy the step never
     * declared — and why the historical rejection spelling below is a second authority and not
     * a harmless variation.
     */
    @Test
    fun `the canonical spelling is a function of the declared policy`() {
        val input = input()

        assertNotEquals(
            Fingerprint.compute(input, STEP_ID, ReplayPolicy.MEMOIZED, ATTEMPT),
            Fingerprint.compute(input, STEP_ID, ReplayPolicy.RERUN, ATTEMPT),
            "the policy is part of the identity: the same program under two policies is two " +
                "identities, and the journal can only record one of them",
        )
        assertNotEquals(
            Fingerprint.compute(input, STEP_ID, ReplayPolicy.MEMOIZED, ATTEMPT),
            Fingerprint.compute(input, STEP_ID, ReplayPolicy.MEMOIZED, ATTEMPT + 1),
            "the attempt is part of the identity: the same program in a second attempt is a " +
                "second operation",
        )
    }

    // ---------------------------------------------------------------------
    // the historical rejection spelling
    // ---------------------------------------------------------------------

    /**
     * The historical rejection spelling HID the declared policy.
     *
     * A refusal at the typed admission wrote the canonical payload with the `RERUN` policy,
     * whatever the step had declared. The row was therefore the identity of a DIFFERENT
     * operation than the one the step describes — and the next attempt of the same run computed
     * the declared policy's identity, compared the two and declared a divergence for a program
     * that had not changed.
     */
    @Test
    fun `the historical rejection spelling hid the declared policy`() {
        val input = input()

        assertEquals(
            Fingerprint.compute(input, STEP_ID, ReplayPolicy.RERUN, ATTEMPT),
            historicalRejectionDigest(input),
            "the historical rejection spelling IS the canonical payload under the RERUN policy",
        )
        assertNotEquals(
            Fingerprint.compute(input, STEP_ID, ReplayPolicy.MEMOIZED, ATTEMPT),
            historicalRejectionDigest(input),
            "and that is why the row of a refused step recorded a policy the step never " +
                "declared",
        )
    }

    // ---------------------------------------------------------------------
    // the historical receipt spelling
    // ---------------------------------------------------------------------

    /**
     * The historical receipt spelling depended on the ORDER the parameters were assembled in.
     *
     * It hashed the ENCODED input, and the encoding writes a map in iteration order. The
     * canonical spelling sorts the keys precisely so that this cannot happen; the receipt
     * spelling had no sort, so the identity of a receipt depended on an accident of how its
     * parameters were built.
     */
    @Test
    fun `the historical receipt spelling depended on the order of the parameters`() {
        val one = input(params = linkedMapOf("kind" to JsonPrimitive("cancel"), "runId" to JsonPrimitive(RUN_ID)))
        val other = input(params = linkedMapOf("runId" to JsonPrimitive(RUN_ID), "kind" to JsonPrimitive("cancel")))

        assertNotEquals(
            historicalReceiptDigest("cancel-$RUN_ID", one),
            historicalReceiptDigest("cancel-$RUN_ID", other),
            "the historical receipt spelling hashed the encoded input, and the encoding writes " +
                "a map in iteration order: the same receipt in two orders was two identities",
        )
    }

    /**
     * The historical receipt spelling depended on the OPERATION ID.
     *
     * The identity of an operation is the identity of the thing it did; the operation id is the
     * row's NAME, and the canonical payload does not carry it. A receipt's identity that moves
     * with its name is a second authority about which row it is.
     */
    @Test
    fun `the historical receipt spelling depended on the operation id`() {
        val input = input()

        assertNotEquals(
            historicalReceiptDigest("cancel-$RUN_ID", input),
            historicalReceiptDigest("cancel-other-run", input),
            "the historical receipt spelling hashed the operation id into the identity",
        )
    }

    /**
     * The historical receipt spelling reproduces the digest a shipped candidate wrote.
     *
     * The literal is the value the product wrote for a known receipt, computed over the bytes
     * the receipt was made of and checked with a tool that is not this product's code. A
     * formula that no longer reproduces it would be a formula that rots, and the journals that
     * shipped carry exactly those bytes.
     */
    @Test
    fun `the historical receipt spelling reproduces the digest a shipped candidate wrote`() {
        val receipt = OperationInput(
            stepId = "cancel",
            params = linkedMapOf(
                "kind" to JsonPrimitive("cancel"),
                "runId" to JsonPrimitive(RUN_ID),
                "reason" to JsonPrimitive("user-requested"),
                "at" to JsonPrimitive("1767225600000"),
            ),
            runId = RUN_ID,
            attempt = ATTEMPT,
        )

        assertEquals(
            HISTORICAL_CANCEL_RECEIPT_DIGEST,
            historicalReceiptDigest("cancel-$RUN_ID", receipt).hex,
            "the digest a shipped candidate wrote for this receipt is this value, and a formula " +
                "that stopped reproducing it would leave the journals that shipped unreadable",
        )
        assertTrue(
            HISTORICAL_CANCEL_RECEIPT_DIGEST.matches(Regex("[0-9a-f]{64}")),
            "a fingerprint is 64 lowercase hex characters",
        )
    }

    // ---------------------------------------------------------------------
    // the fixtures
    // ---------------------------------------------------------------------

    /** One durable input, with the parameters given in the order they are assembled in. */
    private fun input(
        params: Map<String, JsonPrimitive> = linkedMapOf("payload" to JsonPrimitive("value")),
    ): OperationInput = OperationInput(
        stepId = STEP_ID,
        params = params,
        runId = RUN_ID,
        attempt = ATTEMPT,
    )

    /**
     * The spelling the typed rejection used to write.
     *
     * The canonical payload with the `RERUN` policy, whatever the step had declared.
     */
    private fun historicalRejectionDigest(input: OperationInput): Fingerprint =
        Fingerprint.compute(input, input.stepId, ReplayPolicy.RERUN, ATTEMPT)

    /**
     * The spelling the two control receipts used to write.
     *
     * The operation id, then the encoded input, under the JSON configuration those two files
     * carried. The configuration matters: it is a THIRD one, beside the domain's, and the bytes
     * it produced are the bytes the shipped journals hold.
     */
    private fun historicalReceiptDigest(opId: String, input: OperationInput): Fingerprint =
        Fingerprint(Sha256.ofText("$opId|${HISTORICAL_JSON.encodeToString(input)}"))

    private companion object {
        const val STEP_ID = "core.pwd"
        const val RUN_ID = "run-42"
        const val ATTEMPT = 1

        /** The digest the shipped candidate wrote for the cancellation of [RUN_ID]. */
        const val HISTORICAL_CANCEL_RECEIPT_DIGEST =
            "ba05e3e65fdc478e6f58c94c02a6534cb1898387fa554c1670e93dbfeb325112"

        /** The JSON configuration the two control receipt files carried. */
        val HISTORICAL_JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
