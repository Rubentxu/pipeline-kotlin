package dev.rubentxu.pipeline.v2.domain.durable

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/**
 * The input of a durable operation is what the identity of that operation is
 * computed from, and it carries two kinds of rule: the shape of the operation
 * itself, and the rules about the parameters it carries.
 *
 * Both kinds fail silently if untrue. An operation with no step identifier, or
 * with an attempt of zero, still produces a digest: it looks well-formed to
 * everything downstream and it is journalled as an operation that never
 * existed. A parameter whose key looks like a secret is written to the journal
 * and folded into the digest, where it stays readable by everything that can
 * read the journal.
 *
 * This file is the only place where those rules are exercised. The type had no
 * test file at all, and the rows that used it (`FingerprintTest`,
 * `StrictFingerprintDivergenceDetectorTest`) built valid inputs only, so every
 * refusal was untested and every mutation of a refusal survived.
 *
 * The limit of the class is on the class, not on the rows: a row without a
 * limit does not fail, it hangs the lane that runs it.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OperationInputTest {

    /** The maximum serialized size the type declares for the value of a parameter. */
    private val maxSizeBytes = 64 * 1024

    private fun input(
        stepId: String = "compile",
        runId: String = "run-1",
        attempt: Int = 1,
        params: Map<String, JsonElement> = mapOf("target" to JsonPrimitive("prod")),
    ) = OperationInput(stepId = stepId, runId = runId, attempt = attempt, params = params)

    /**
     * A value whose SERIALIZED form is exactly [length] characters.
     *
     * The limit of the type is on the serialized form, not on the payload, so
     * the fixture measures the overhead of the encoding once and asks for the
     * payload that makes the whole thing the wanted length. A fixture that
     * guessed the overhead would quietly test a different boundary the day the
     * encoding changed.
     */
    private fun valueOfSerializedLength(length: Int): JsonPrimitive {
        val overhead = JsonPrimitive("x").toString().length - 1
        return JsonPrimitive("x".repeat(length - overhead))
    }

    @Test
    @DisplayName("a blank step id is refused")
    fun aBlankStepIdIsRefused() {
        val refusal = assertThrows<IllegalArgumentException> { input(stepId = "   ") }
        assertTrue(
            refusal.message.orEmpty().contains("stepId"),
            "the refusal did not name the field it is about: ${refusal.message}",
        )
    }

    @Test
    @DisplayName("a blank run id is refused")
    fun aBlankRunIdIsRefused() {
        val refusal = assertThrows<IllegalArgumentException> { input(runId = "  ") }
        assertTrue(
            refusal.message.orEmpty().contains("runId"),
            "the refusal did not name the field it is about: ${refusal.message}",
        )
    }

    @Test
    @DisplayName("an attempt below one is refused")
    fun anAttemptBelowOneIsRefused() {
        // The attempt is 1-based: an attempt of zero would collide with "no
        // attempt yet" and would silently become a valid identity.
        val refusal = assertThrows<IllegalArgumentException> { input(attempt = 0) }
        assertTrue(
            refusal.message.orEmpty().contains("attempt"),
            "the refusal did not name the field it is about: ${refusal.message}",
        )
        assertThrows<IllegalArgumentException> { input(attempt = -1) }
    }

    @Test
    @DisplayName("an operation that names itself is accepted")
    fun anOperationThatNamesItselfIsAccepted() {
        // The counterpart of the three refusals above: without it, a guard that
        // refused EVERY input would pass them.
        val accepted = input()
        assertEquals("compile", accepted.stepId)
        assertEquals("run-1", accepted.runId)
        assertEquals(1, accepted.attempt)
    }

    @Test
    @DisplayName("a parameter whose key looks like a secret is refused when the input is built")
    fun aParameterWhoseKeyLooksLikeASecretIsRefusedWhenTheInputIsBuilt() {
        // The refusal has to happen at CONSTRUCTION: an input that carries a
        // secret is journalled and digested, so a rule that only ran when
        // somebody asked would arrive too late.
        val refusal = assertThrows<IllegalArgumentException> {
            input(params = mapOf("password" to JsonPrimitive("not-a-secret")))
        }
        assertTrue(
            refusal.message.orEmpty().contains("password"),
            "the refusal did not name the parameter it is about: ${refusal.message}",
        )
    }

    @Test
    @DisplayName("a parameter whose key looks like a secret is refused whatever its case")
    fun aParameterWhoseKeyLooksLikeASecretIsRefusedWhateverItsCase() {
        // The key is compared in lower case: a rule that only read the
        // declaration as it was written would let "API_TOKEN" through.
        assertThrows<IllegalArgumentException> {
            input(params = mapOf("API_TOKEN" to JsonPrimitive("not-a-secret")))
        }
    }

    @Test
    @DisplayName("a parameter on the allowlist is accepted")
    fun aParameterOnTheAllowlistIsAccepted() {
        // "credentialsId" carries the NAME of a credential, not a credential.
        // It has to be admitted, or the allowlist would be a rule that refuses
        // everything it names.
        val accepted = input(params = mapOf("credentialsId" to JsonPrimitive("prod-deploy")))
        assertEquals(
            JsonPrimitive("prod-deploy"),
            accepted.params["credentialsId"],
            "the allowlisted parameter did not reach the input",
        )
    }

    @Test
    @DisplayName("a value bigger than the declared maximum is refused")
    fun aValueBiggerThanTheDeclaredMaximumIsRefused() {
        val refusal = assertThrows<IllegalArgumentException> {
            input(params = mapOf("blob" to valueOfSerializedLength(maxSizeBytes + 1)))
        }
        assertTrue(
            refusal.message.orEmpty().contains("blob"),
            "the refusal did not name the parameter it is about: ${refusal.message}",
        )
    }

    @Test
    @DisplayName("a value at the declared maximum is accepted")
    fun aValueAtTheDeclaredMaximumIsAccepted() {
        // The boundary is inclusive: a value of exactly the declared maximum
        // is admissible, and one character more is not. A rule that refused
        // the boundary would refuse a legitimate parameter.
        val atLimit = valueOfSerializedLength(maxSizeBytes)
        assertEquals(
            maxSizeBytes,
            atLimit.toString().length,
            "the fixture does not sit exactly at the declared limit",
        )
        val accepted = input(params = mapOf("blob" to atLimit))
        assertEquals(atLimit, accepted.params["blob"], "the parameter at the limit did not reach the input")
    }

    @Test
    @DisplayName("the parameters can be checked before the input is built")
    fun theParametersCanBeCheckedBeforeTheInputIsBuilt() {
        // The rule is public on purpose: a caller can ask whether a map is
        // admissible before building a value out of it.
        assertThrows<IllegalArgumentException> {
            OperationInput.validate(mapOf("secret" to JsonPrimitive("not-a-secret")))
        }
        OperationInput.validate(mapOf("target" to JsonPrimitive("prod")))
    }

    @Test
    @DisplayName("the input delivers the values it was built with")
    fun theInputDeliversTheValuesItWasBuiltWith() {
        val delivered = input(
            stepId = "package",
            runId = "run-42",
            attempt = 3,
            params = mapOf("target" to JsonPrimitive("prod")),
        )
        assertEquals("package", delivered.stepId)
        assertEquals("run-42", delivered.runId)
        assertEquals(3, delivered.attempt)
        assertEquals(mapOf("target" to JsonPrimitive("prod")), delivered.params)
    }
}
