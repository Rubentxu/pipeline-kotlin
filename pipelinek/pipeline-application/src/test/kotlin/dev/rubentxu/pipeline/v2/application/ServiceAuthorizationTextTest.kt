package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The text of an authorization, and what it does not carry.
 *
 * ## The guarantee under trial
 *
 * That the text of an authorization says WHICH authorization it is —its
 * label, its scope and its grants— and does NOT say the token that opens it.
 * And that the same holds for the objects that carry an authorization or a
 * token: a value that holds one inside and is printed whole takes the secret
 * to the console with it.
 *
 * ## The capacity this serves
 *
 * The token is a secret the operator issued. A line of log, a trace or a
 * diagnostic that carried it would publish it to whoever reads the log, and
 * the log is read by whoever gets to read it. Redacting the type that OWNS
 * the value does not redact the types that CARRY it: the `toString()` the
 * compiler generates for a `data class` prints its properties, so the token
 * was printed in clear the moment anybody printed the object.
 *
 * ## Why this instrument
 *
 * The defect is not a path of execution: no line of the tree prints an
 * authorization today. It is a LATENT defect, and the one who measured it was
 * the first consumer of the published artifact, which was going to print the
 * `data class` it was handed. A value that does not leave the tree yet still
 * leaves by the door of the artifact.
 *
 * ## What this does not freeze
 *
 * The FORMAT of the authorizations file: the token travels in the file, which
 * is where it has to travel, and the rows here check that it still does. And
 * the semantics of the scope: this file does not decide who may ask what.
 */
class ServiceAuthorizationTextTest {

    @Test
    fun `the text of an authorization does not carry the token`() {
        val authorization = ServiceAuthorization(TOKEN, "operator", ServiceScope.All)

        val text = authorization.toString()

        assertFalse(
            text.contains(TOKEN),
            "the token is the secret the client presents, and a text that carries it publishes it: $text",
        )
    }

    @Test
    fun `the label, the scope and the grants are legible in the text of an authorization`() {
        val authorization = ServiceAuthorization(
            token = TOKEN,
            label = "operator",
            scope = ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start)),
            credentialGrants = setOf(CredentialGrant("api-token", BrokeredCredentialKind.STRING)),
        )

        val text = authorization.toString()

        assertTrue(
            text.contains("operator"),
            "the label is what tells two authorizations apart, and a text without it is not worth reading: $text",
        )
        assertTrue(
            text.contains("Start"),
            "the scope says what the authorization may ask, and the operator reads it to know: $text",
        )
        assertTrue(
            text.contains("api-token"),
            "a grant is a name and a kind, so it carries no secret and the operator needs it: $text",
        )
    }

    @Test
    fun `the text of two authorizations with the same label and different tokens is the same`() {
        val first = ServiceAuthorization("token-of-one-client", "operator", ServiceScope.All)
        val second = ServiceAuthorization("token-of-another-client", "operator", ServiceScope.All)

        assertEquals(
            first.toString(),
            second.toString(),
            "the text of an authorization is about the authorization and not about the secret that opens it",
        )
    }

    @Test
    fun `an object that carries an authorization does not print the token`() {
        val reading = ServiceAuthorizationsReading.Read(
            listOf(ServiceAuthorization(TOKEN, "operator", ServiceScope.All)),
        )

        val text = reading.toString()

        assertFalse(
            text.contains(TOKEN),
            "the reading carries the authorizations, and the text of the reading carries what they carry: $text",
        )
    }

    @Test
    fun `the token found in a file is not printed`() {
        val reading = ServiceCredentialReading.Found(TOKEN)

        val text = reading.toString()

        assertFalse(
            text.contains(TOKEN),
            "the reading holds the token the client presents, and its text is printed by whoever handles it: $text",
        )
    }

    @Test
    fun `the file of authorizations still travels with its tokens`() {
        val authorization = ServiceAuthorization(TOKEN, "operator", ServiceScope.All)

        val text = ServiceAuthorizations.format(listOf(authorization))

        assertTrue(
            text.contains(TOKEN),
            "the file is where the token lives: a client that has to present it reads it there",
        )
        assertTrue(
            text.contains("operator"),
            "the file is a file a human reads and edits: the label travels with the token",
        )
    }

    @Test
    fun `the file of authorizations is still read with its tokens`() {
        val text = ServiceAuthorizations.format(
            listOf(ServiceAuthorization(TOKEN, "operator", ServiceScope.All)),
        )

        val reading = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            ServiceAuthorizations.parse(text),
        )

        assertEquals(
            TOKEN,
            reading.authorizations.single().token,
            "the redaction is about the TEXT of the authorization, not about what the file carries",
        )
    }

    @Test
    fun `a client that presents the token is still authorized`() {
        val authorizations = ServiceAuthorizations.of(
            listOf(ServiceAuthorization(TOKEN, "operator", ServiceScope.All)),
        )

        assertEquals(
            ServiceAuthorizationOutcome.Authorized,
            authorizations.decide(TOKEN, null),
            "the token still opens the service: the redaction changed the text and nothing else",
        )
    }

    companion object {
        /** The token the rows use, a marker nobody would use for a real one. */
        const val TOKEN = "token-marker-of-this-test"
    }
}
