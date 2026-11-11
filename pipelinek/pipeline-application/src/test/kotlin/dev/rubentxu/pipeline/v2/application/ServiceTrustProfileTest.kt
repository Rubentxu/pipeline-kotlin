package dev.rubentxu.pipeline.v2.application

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The vocabulary of the trust profiles of the service: the words it serves,
 * the promise each one makes, and the word it refuses with.
 *
 * ## The guarantee under trial
 *
 * That a profile of AUTHENTICATION never wears the name of a boundary this
 * build cannot demonstrate, that the text the operator reads says the same
 * thing the profile promises, and that a refusal names the cause it really
 * rejects.
 *
 * ## Why the word `isolated` is CHECKED and not merely avoided
 *
 * Because the check is what keeps the promise: a vocabulary is a closed set,
 * and a set is only closed while something says so. The rows below read the
 * words the product serves and fail if one of them is the word that names a
 * separation this build does not demonstrate —the separation of two
 * processes of the same user, which no socket of a Unix domain can prove
 * here.
 *
 * ## Why the operator text is a row and not a comment
 *
 * Because the text the service prints when it starts IS the promise the
 * operator relies on, and a promise that only the code keeps is a promise
 * nobody reads. The row reads the printed boundary and asks it two things:
 * what it protects, and what it does not.
 *
 * ## What this instrument cannot see
 *
 * It does not start a service, does not open a socket and does not read a
 * file: it reads the vocabulary and the text the service prints. Whether the
 * service really reads the authorizations from the standard input is the
 * question of the lane that runs the product.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ServiceTrustProfileTest {

    @Test
    fun `the build serves exactly the two profiles of authentication`() {
        assertEquals(
            listOf("trusted-local", "private-auth"),
            ServiceTrustProfile.entries.map { it.word },
            "the profile is a closed vocabulary, and a word that is not in it is refused by name",
        )
    }

    @Test
    fun `no profile wears the word of a separation this build does not demonstrate`() {
        assertFalse(
            ServiceTrustProfile.entries.any { it.word == "isolated" },
            "a profile that reads an authorization from a place demonstrates no separation of " +
                "processes, files or sockets, and may not wear the name of one",
        )
    }

    @Test
    fun `the refusal of an empty standard input names the cause it rejects`() {
        assertEquals(
            "no-authorization-on-standard-input",
            ServiceStartupRefusal.NoAuthorizationOnStandardInput.reason,
            "the refusal names what was empty, and not a machine that cannot isolate anything",
        )
        assertFalse(
            ServiceStartupRefusal.entries.any { it.reason == "isolation-unavailable" },
            "the word that named a separation this build never proved is withdrawn",
        )
    }

    @Test
    fun `the text of the file profile says what it protects and what it does not`() {
        val text = boundaryText(ServiceTrustProfile.TrustedLocal)

        assertTrue(
            text.contains("trust-profile trusted-local"),
            "the operator reads the word of the profile the service runs: $text",
        )
        assertTrue(
            text.contains("file-system"),
            "the text says the frontier the profile protects: $text",
        )
        assertTrue(
            text.contains("not two processes of the same user"),
            "the text says the frontier the profile does NOT protect: $text",
        )
    }

    @Test
    fun `the text of the standard input profile says what it protects and what it does not`() {
        val text = boundaryText(ServiceTrustProfile.PrivateAuth)

        assertTrue(
            text.contains("trust-profile private-auth"),
            "the operator reads the word of the profile the service runs: $text",
        )
        assertTrue(
            text.contains("not-on-disk"),
            "the text says the frontier the profile protects: $text",
        )
        assertTrue(
            text.contains("while the service starts takes it"),
            "the text says the way another process of the same user still takes the " +
                "authorization: $text",
        )
        assertTrue(
            text.contains("not two processes of the same user"),
            "the text says the frontier the profile does NOT protect: $text",
        )
    }

    /**
     * The text the service prints when it starts with [profile].
     */
    private fun boundaryText(profile: ServiceTrustProfile): String {
        val bytes = ByteArrayOutputStream()
        val out = PrintStream(bytes, true, Charsets.UTF_8.name())
        describeBoundary(
            out = out,
            profile = profile,
            authorizationPath = Path.of("/nonexistent-authorizations"),
            authorizationCount = 1,
            credentialSessions = false,
        )
        out.flush()
        return bytes.toString(Charsets.UTF_8)
    }
}
