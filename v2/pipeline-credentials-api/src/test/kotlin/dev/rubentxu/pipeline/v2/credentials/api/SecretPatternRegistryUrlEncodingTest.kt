package dev.rubentxu.pipeline.v2.credentials.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A secret reaches a log in more forms than its literal bytes. A URL-encoded
 * credential is the form that matters here: `git remote`, a curl URL, or a
 * stack-trace that percent-escapes its own arguments all print
 * `p%40ss%20w0rd`, not `p@ss w0rd`. The registry enumerates a URL-encoded
 * variant per registered secret precisely so that form is scrubbed too.
 *
 * The variant is built by replacing the characters that require encoding and
 * leaving every other character as it is. A secret is mostly unreserved
 * characters — letters and digits — so "replace the ones that need it" has to
 * mean "keep the rest", not "keep only those".
 *
 * Every test builds its own [SecretPatternRegistry]. The class holds per-instance
 * state, so a fresh instance is full isolation and no test can observe another's
 * registration; there is nothing to tear down and no order to depend on. Each
 * secret is at least [SecretPatternRegistry.MIN_MASKABLE_LENGTH] characters,
 * because a shorter value is silently left unregistered and would therefore
 * not be scrubbed at all — the assertion below would see an untouched string
 * and read as a redactor failure rather than as an under-length input.
 */
class SecretPatternRegistryUrlEncodingTest {

    /** 9 chars: two reserved characters (`@`, space) among ordinary letters. */
    private val mixedSecret = "p@ss w0rd"

    /** The form that appears in a URL or a percent-escaping stack trace. */
    private val mixedSecretUrlEncoded = "p%40ss%20w0rd"

    /** 8 chars, every one of them reserved — encodes to `%2B%2F%3D%26%40%23%21`. */
    private val allReservedSecret = "+/=%&@#!"

    private fun registryWith(secret: String) = SecretPatternRegistry().apply {
        addSecretValue(secret)
    }

    @Test
    fun `a URL-encoded secret is scrubbed, not only its literal form`() {
        val scrubbed = registryWith(mixedSecret).scrub("token=$mixedSecretUrlEncoded;")

        assertEquals(
            "token=****;",
            scrubbed,
            "the URL-encoded form of a registered secret must be scrubbed too; a literal-" +
                "only match leaks the credential through every URL that carries it",
        )
    }

    @Test
    fun `the literal form is still scrubbed`() {
        // The specific defect must not be "fixed" by dropping the literal pattern.
        assertEquals(
            "token=****;",
            registryWith(mixedSecret).scrub("token=$mixedSecret;"),
        )
    }

    @Test
    fun `a secret made entirely of reserved characters still round-trips`() {
        // Every character here is reserved, `%` included -- `%` encodes to `%25`, so
        // the encoding is 24 characters, not the 22 a partial table would suggest.
        val encoded = "%2B%2F%3D%25%26%40%23%21"

        assertEquals(
            "creds: ****",
            registryWith(allReservedSecret).scrub("creds: $encoded"),
            "a secret whose characters all need encoding has no unreserved remainder, " +
                "so encoding it must preserve every character",
        )
    }

    @Test
    fun `text that merely contains the reserved characters is left alone`() {
        // The other half of the same defect. A variant built from the reserved
        // characters ALONE is a short literal that matches unrelated output, so
        // the redactor destroys observability for text that contains no secret.
        // The mangled pattern for `p@ss w0rd` is `%40%20`: the letters between the
        // reserved characters are dropped, leaving the two encodings adjacent. So
        // this input must be built to actually CONTAIN that adjacency -- an earlier
        // version used `a%40b%20c`, which does not, and passed by accident.
        val innocent = "GET /q%40%20r HTTP/1.1"

        val scrubbed = registryWith(mixedSecret).scrub(innocent)

        assertEquals(
            innocent,
            scrubbed,
            "an input containing no registered secret must survive byte for byte; " +
                "scrubbing it means the variant matched reserved characters instead of " +
                "the whole encoded secret",
        )
    }

    @Test
    fun `a secret with no reserved characters is still scrubbed literally`() {
        val plain = "correct-horse-9"

        val registry = registryWith(plain)

        assertTrue(registry.scrub("value=$plain").let { !it.contains(plain) })
    }

    @Test
    fun `input carrying no secret is returned unchanged`() {
        val innocent = "nothing sensitive in this line at all"

        assertEquals(innocent, registryWith(mixedSecret).scrub(innocent))
    }
}
