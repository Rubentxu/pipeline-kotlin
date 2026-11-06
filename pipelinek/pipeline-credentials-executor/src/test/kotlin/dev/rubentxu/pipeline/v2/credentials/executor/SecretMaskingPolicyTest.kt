package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.api.SecretShorterThanMinimumMaskableLengthWarning
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.StringBindingSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a value a binding exposes MEANS for masking.
 *
 * ## The guarantee
 *
 * Three facts, and the product used to tell two of them apart by accident:
 *
 *  - a secret of seven characters is too short to mask: it is reported and it is NOT masked;
 *  - a secret of eight characters is masked, and it is not reported;
 *  - a value that is NOT THERE is neither masked nor reported — there is nothing to leak,
 *    and a report about a secret that does not exist is a lie about the run;
 *  - a value that is not a secret (a PATH) is neither masked nor reported either.
 *
 * ## Why this instrument
 *
 * The seam is a pure decision plus a registration over a binding, its handles and a
 * registry. Whether a value is masked is asked of the REGISTRY, which is the
 * authority: `scrub` is the product's own answer, not a recomputation of the rule.
 * The boundary rows use seven and eight characters because eight is the threshold
 * the registry declares and seven is the character below it.
 *
 * ## What this does not freeze
 *
 * The wording of the report, and the shape of the warning beyond the two facts it
 * carries. The rows check that the credential is named, that the length is right,
 * and that the VALUE never appears in the report.
 */
class SecretMaskingPolicyTest {

    // --- the decision, asked directly ----------------------------------------------

    @Test
    fun `a secret of seven characters is too short to mask`() {
        assertEquals(
            SecretMaskingOutcome.TooShortToMask(7),
            classifySecretValue("abcdefg", isSecret = true),
            "seven characters is below the minimum maskable length and the policy says so",
        )
    }

    @Test
    fun `a secret of eight characters is maskable`() {
        assertEquals(
            SecretMaskingOutcome.Masked,
            classifySecretValue("abcdefgh", isSecret = true),
            "eight characters is the minimum maskable length and the policy says so",
        )
    }

    @Test
    fun `a value that is not there is not a secret too short to mask`() {
        assertEquals(
            SecretMaskingOutcome.Absent,
            classifySecretValue("", isSecret = true),
            "there is no secret to be too short: there is no secret",
        )
        assertEquals(
            SecretMaskingOutcome.Absent,
            classifySecretValue(null, isSecret = true),
            "a value that is not there is the same fact whether it is empty or missing",
        )
    }

    @Test
    fun `a value that is not a secret is never a secret too short to mask`() {
        assertEquals(
            SecretMaskingOutcome.NotASecret,
            classifySecretValue("ab", isSecret = false),
            "a reference is not a secret, and a short reference is not a short secret",
        )
    }

    // --- and what the binding does with each of them ------------------------------

    @Test
    fun `a secret of seven characters is reported, is not masked, and is named`() {
        val registry = SecretPatternRegistry()
        val warnings = mutableListOf<SecretShorterThanMinimumMaskableLengthWarning>()

        val unmaskable = BoundSecretRegistration.register(
            registry = registry,
            spec = StringBindingSpec(CredentialsId("cred-7"), "TOKEN"),
            bindings = mapOf("TOKEN" to handle("abcdefg")),
            warn = { warnings += it },
        )

        assertEquals(
            listOf(UnmaskableSecret(CredentialsId("cred-7"), 7)),
            unmaskable,
            "the caller is not told which value cannot be masked",
        )
        assertEquals(1, warnings.size, "the operator is not told that the secret is not masked")
        assertEquals("cred-7", warnings.single().credentialsId, "the report names the credential")
        assertEquals(7, warnings.single().length, "the report carries the length of the secret")
        assertFalse(
            warnings.single().toString().contains("abcdefg"),
            "the report about a secret carries the secret",
        )
        assertEquals(
            "the token is abcdefg",
            registry.scrub("the token is abcdefg"),
            "a secret of seven characters is not masked, and the transcript carries it in the clear",
        )
    }

    @Test
    fun `a secret of eight characters is masked and is not reported`() {
        val registry = SecretPatternRegistry()
        val warnings = mutableListOf<SecretShorterThanMinimumMaskableLengthWarning>()

        val unmaskable = BoundSecretRegistration.register(
            registry = registry,
            spec = StringBindingSpec(CredentialsId("cred-8"), "TOKEN"),
            bindings = mapOf("TOKEN" to handle("abcdefgh")),
            warn = { warnings += it },
        )

        assertEquals(
            emptyList<UnmaskableSecret>(),
            unmaskable,
            "a maskable secret is reported as unmaskable",
        )
        assertTrue(warnings.isEmpty(), "a maskable secret is reported: $warnings")
        assertEquals(
            "the token is ${SecretPatternRegistry.SCRUB_MARKER}",
            registry.scrub("the token is abcdefgh"),
            "a secret of eight characters is masked",
        )
    }

    @Test
    fun `a value that is not there is not reported as a secret too short to mask`() {
        val registry = SecretPatternRegistry()
        val warnings = mutableListOf<SecretShorterThanMinimumMaskableLengthWarning>()

        val unmaskable = BoundSecretRegistration.register(
            registry = registry,
            spec = StringBindingSpec(CredentialsId("cred-empty"), "TOKEN"),
            bindings = mapOf("TOKEN" to SecretHandle.secret(ByteArray(0))),
            warn = { warnings += it },
        )

        assertEquals(
            emptyList<UnmaskableSecret>(),
            unmaskable,
            "an absent value is reported as a value that cannot be masked",
        )
        assertTrue(
            warnings.isEmpty(),
            "an absent value is reported as a short secret that will reach the transcript in the " +
                "clear: $warnings",
        )
    }

    @Test
    fun `a path is neither masked nor reported`() {
        val registry = SecretPatternRegistry()
        val warnings = mutableListOf<SecretShorterThanMinimumMaskableLengthWarning>()

        val unmaskable = BoundSecretRegistration.register(
            registry = registry,
            spec = StringBindingSpec(CredentialsId("cred-path"), "TOOL"),
            bindings = mapOf("TOOL" to SecretHandle.masked("/usr/bin/tool")),
            warn = { warnings += it },
        )

        assertEquals(
            emptyList<UnmaskableSecret>(),
            unmaskable,
            "a path is not a secret that cannot be masked",
        )
        assertTrue(warnings.isEmpty(), "a path is reported as a secret: $warnings")
        assertEquals(
            "the tool is /usr/bin/tool",
            registry.scrub("the tool is /usr/bin/tool"),
            "a path is masked, and a masked path would break the binding that needs it",
        )
    }

    private fun handle(value: String): SecretHandle =
        SecretHandle.secret(value.toByteArray(Charsets.UTF_8))
}
