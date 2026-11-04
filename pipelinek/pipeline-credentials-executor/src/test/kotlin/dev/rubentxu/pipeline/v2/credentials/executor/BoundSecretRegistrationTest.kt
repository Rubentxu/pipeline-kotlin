package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.api.SecretShorterThanMinimumMaskableLengthWarning
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.StringBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePasswordBindingSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * What a bound credential tells the redaction registry, and what it tells the operator.
 *
 * ## The guarantee
 *
 * Two facts, and both of them are about a value the operator believes is protected:
 *
 *  - a value shorter than the minimum maskable length is NOT masked, so it reaches the
 *    transcript IN THE CLEAR, and the operator is told that — with the credential and the
 *    length, never with the value;
 *  - a username and a password are TWO values to the binding and ONE value to the world: the
 *    `user:pass` they make together is registered as well, so the base64 of it is masked too.
 *
 * ## Why this instrument
 *
 * The seam is a pure function over a binding, its handles and a registry. The registry is the
 * authority on masking, so the row asks the registry what it masks: `scrub` is the product's
 * own answer, not a recomputation of the rules.
 *
 * ## What this does not freeze
 *
 * The wording of the report and the shape of the warning beyond the two facts it carries. The
 * rows check that the credential is named, that the length is right, and that the VALUE never
 * appears in the report.
 */
class BoundSecretRegistrationTest {

    @Test
    fun `the operator is told which credential has a secret that is not masked, and never the secret`() {
        val registry = SecretPatternRegistry()
        val warnings = mutableListOf<SecretShorterThanMinimumMaskableLengthWarning>()

        BoundSecretRegistration.register(
            registry = registry,
            spec = StringBindingSpec(CredentialsId("short-credential"), "TOKEN"),
            bindings = mapOf("TOKEN" to handle("abc")),
            warn = { warnings += it },
        )

        assertEquals(1, warnings.size, "the operator is not told that the secret is not masked")
        assertEquals("short-credential", warnings.single().credentialsId, "the report names the credential")
        assertEquals(3, warnings.single().length, "the report carries the length of the secret")
        val spoken = warnings.single().toString()
        assertFalse(
            spoken.contains("abc"),
            "the report about a secret carries the secret: $spoken",
        )
    }

    @Test
    fun `a secret of the minimum maskable length is not reported`() {
        val registry = SecretPatternRegistry()
        val warnings = mutableListOf<SecretShorterThanMinimumMaskableLengthWarning>()

        BoundSecretRegistration.register(
            registry = registry,
            spec = StringBindingSpec(CredentialsId("long-credential"), "TOKEN"),
            bindings = mapOf("TOKEN" to handle("abcdefgh")),
            warn = { warnings += it },
        )

        assertTrue(warnings.isEmpty(), "a maskable secret is reported as unmaskable: $warnings")
    }

    @Test
    fun `the username and the password are masked as the value they make together`() {
        val registry = SecretPatternRegistry()

        BoundSecretRegistration.register(
            registry = registry,
            spec = UsernamePasswordBindingSpec(CredentialsId("db-credential"), "DB_USER", "DB_PASS"),
            bindings = mapOf(
                "DB_USER" to handle("operator"),
                "DB_PASS" to handle("s3cret"),
            ),
            warn = { },
        )

        val composed = "operator:s3cret"
        val encoded = Base64.getEncoder().encodeToString(composed.toByteArray())
        assertEquals(
            SecretPatternRegistry.SCRUB_MARKER,
            registry.scrub(encoded),
            "the base64 of the composed credential is not masked, and a script can print it",
        )
    }

    private fun handle(value: String): SecretHandle =
        SecretHandle.secret(value.toByteArray(Charsets.UTF_8))
}
