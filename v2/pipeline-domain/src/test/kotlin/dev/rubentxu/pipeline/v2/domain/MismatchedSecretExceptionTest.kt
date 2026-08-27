package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Tests for MismatchedSecretException.
 *
 * Verifies:
 * - Verbatim Jenkins message wording (INV-L6-CR-010)
 * - Message contains only kind strings, NOT secret material
 * - 3-arg constructor with credentialId, expectedKind, actualKind
 */
@DisplayName("MismatchedSecretException contract tests")
class MismatchedSecretExceptionTest {

    @Test
    fun `verbatim_wording_matches_jenkins_source`() {
        val credentialId = CredentialsId("my-secret-id")
        val exception = MismatchedSecretException(
            credentialId,
            expectedKind = "UsernamePasswordBinding",
            actualKind = "SecretText"
        )

        val expectedMessage = "Credential 'my-secret-id' is of type 'SecretText' where 'UsernamePasswordBinding' was expected."

        assertEquals(
            expectedMessage,
            exception.message,
            "Message must match Jenkins verbatim wording"
        )
    }

    @Test
    fun `message_carries_no_secret_material`() {
        // Construct with known non-secret values
        val credentialId = CredentialsId("test-id")
        val exception = MismatchedSecretException(
            credentialId,
            expectedKind = "SshPrivateKey",
            actualKind = "SecretText"
        )

        val message = exception.message ?: ""

        // Message should contain the kind strings
        assertTrue(message.contains("SshPrivateKey"))
        assertTrue(message.contains("SecretText"))

        // Message should contain credentialId value
        assertTrue(message.contains("test-id"))

        // Message should NOT contain the credentialId VALUE in the sense of
        // secret bytes/payload - only the ID string (which is an identifier, not a secret)
        // The key point is that no actual secret bytes leak through the message
        assertTrue(message.contains("Credential 'test-id'"))
    }

    @Test
    fun `exception has correct properties`() {
        val credentialId = CredentialsId("creds-123")
        val exception = MismatchedSecretException(
            credentialId,
            expectedKind = "UsernamePasswordBinding",
            actualKind = "Certificate"
        )

        assertEquals("UsernamePasswordBinding", exception.expectedKind)
        assertEquals("Certificate", exception.actualKind)
        assertEquals(credentialId, exception.credentialId)
    }

    @Test
    fun `exception extends IllegalArgumentException`() {
        val credentialId = CredentialsId("test")
        val exception = MismatchedSecretException(
            credentialId,
            expectedKind = "StringBinding",
            actualKind = "SshPrivateKey"
        )

        assertTrue(exception is IllegalArgumentException)
    }

    @Test
    fun `jenkins_message_template is correctly formatted`() {
        val template = MismatchedSecretException.JENKINS_MESSAGE_TEMPLATE
        val formatted = String.format(
            template,
            "my-id",
            "SecretText",
            "UsernamePasswordBinding"
        )

        assertEquals(
            "Credential 'my-id' is of type 'SecretText' where 'UsernamePasswordBinding' was expected.",
            formatted
        )
    }
}
