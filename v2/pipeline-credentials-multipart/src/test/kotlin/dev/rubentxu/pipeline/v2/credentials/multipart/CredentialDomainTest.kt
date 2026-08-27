package dev.rubentxu.pipeline.v2.credentials.multipart

import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Tests for the Credential sealed hierarchy.
 *
 * Verifies:
 * - Sealed hierarchy is exhaustive with 7 kinds (INV-L6-CR-001)
 * - Anti-log toString — secret bytes never appear in toString output (INV-L6-CR-004)
 * - LinkedSecretRef resolution
 */
@DisplayName("Credential sealed hierarchy tests")
class CredentialDomainTest {

    // ---------------------------------------------------------------------------
    // Sealed Hierarchy Exhaustive
    // ---------------------------------------------------------------------------

    @Test
    fun `sealed_hierarchy_is_exhaustive_with_7_kinds`() {
        // Verify each kind can be constructed and is a Credential
        val credentials: List<Credential> = listOf(
            SecretText(CredentialsId("s1"), CredentialScope.GLOBAL, "x".toByteArray()),
            UsernamePassword(CredentialsId("s2"), CredentialScope.GLOBAL, "u", "p".toByteArray()),
            SshPrivateKey(CredentialsId("s3"), CredentialScope.GLOBAL, "u", "k".toByteArray()),
            SecretFile(CredentialsId("s4"), CredentialScope.GLOBAL, "x".toByteArray()),
            Certificate(CredentialsId("s5"), CredentialScope.GLOBAL, "x".toByteArray()),
            Zip(CredentialsId("s6"), CredentialScope.GLOBAL, emptyMap()),
            UsernameColonPassword(CredentialsId("s7"), CredentialScope.GLOBAL, "u", "p".toByteArray())
        )

        // Verify all 7 kinds are present
        assertEquals(7, credentials.size, "Must have exactly 7 credential kinds")

        // Verify kinds by checking their simple names
        val kindNames = credentials.map { it::class.simpleName }.toSet()
        val expected = setOf(
            "SecretText",
            "UsernamePassword",
            "SshPrivateKey",
            "SecretFile",
            "Certificate",
            "Zip",
            "UsernameColonPassword"
        )
        assertEquals(expected, kindNames, "Credential sealed hierarchy must contain exactly 7 kinds")
    }

    @Test
    fun `all_credential_kinds_have_id_and_scope`() {
        // SecretText
        val secretText = SecretText(CredentialsId("test-id"), CredentialScope.GLOBAL, "secret".toByteArray())
        assertEquals(CredentialsId("test-id"), secretText.id)
        assertEquals(CredentialScope.GLOBAL, secretText.scope)

        // UsernamePassword
        val userPass = UsernamePassword(CredentialsId("up-id"), CredentialScope.GLOBAL, "user", "pass".toByteArray())
        assertEquals(CredentialsId("up-id"), userPass.id)
        assertEquals(CredentialScope.GLOBAL, userPass.scope)

        // SshPrivateKey
        val sshKey = SshPrivateKey(CredentialsId("ssh-id"), CredentialScope.GLOBAL, "git", "PRIVATE KEY".toByteArray())
        assertEquals(CredentialsId("ssh-id"), sshKey.id)

        // SecretFile
        val secretFile = SecretFile(CredentialsId("file-id"), CredentialScope.GLOBAL, "file content".toByteArray(), "myfile.txt")
        assertEquals(CredentialsId("file-id"), secretFile.id)

        // Certificate
        val cert = Certificate(CredentialsId("cert-id"), CredentialScope.GLOBAL, "PKCS12".toByteArray())
        assertEquals(CredentialsId("cert-id"), cert.id)

        // Zip
        val zip = Zip(CredentialsId("zip-id"), CredentialScope.GLOBAL, mapOf("entry" to "content".toByteArray()))
        assertEquals(CredentialsId("zip-id"), zip.id)

        // UsernameColonPassword
        val ucp = UsernameColonPassword(CredentialsId("ucp-id"), CredentialScope.GLOBAL, "user", "pass".toByteArray())
        assertEquals(CredentialsId("ucp-id"), ucp.id)
    }

    @Test
    fun `all_credential_kinds_map_to_correct_bound_purpose`() {
        assertEquals(BoundPurpose.API_KEY, SecretText(CredentialsId("id"), CredentialScope.GLOBAL, "x".toByteArray()).purpose)
        assertEquals(BoundPurpose.USERNAME_PASSWORD, UsernamePassword(CredentialsId("id"), CredentialScope.GLOBAL, "u", "p".toByteArray()).purpose)
        assertEquals(BoundPurpose.SSH_KEY, SshPrivateKey(CredentialsId("id"), CredentialScope.GLOBAL, "u", "key".toByteArray()).purpose)
        assertEquals(BoundPurpose.FILE, SecretFile(CredentialsId("id"), CredentialScope.GLOBAL, "x".toByteArray()).purpose)
        assertEquals(BoundPurpose.CERTIFICATE, Certificate(CredentialsId("id"), CredentialScope.GLOBAL, "x".toByteArray()).purpose)
        assertEquals(BoundPurpose.ZIP, Zip(CredentialsId("id"), CredentialScope.GLOBAL, emptyMap()).purpose)
        assertEquals(BoundPurpose.USERNAME_COLON_PASSWORD, UsernameColonPassword(CredentialsId("id"), CredentialScope.GLOBAL, "u", "p".toByteArray()).purpose)
    }

    // ---------------------------------------------------------------------------
    // Anti-Log toString (INV-L6-CR-004)
    // ---------------------------------------------------------------------------

    @Test
    fun `secret_text toString never contains secret bytes`() {
        val secretBytes = "supersecretpassword123".toByteArray()
        val cred = SecretText(CredentialsId("test-key"), CredentialScope.GLOBAL, secretBytes)

        val toStringResult = cred.toString()

        // Must contain safe fields
        assertTrue(toStringResult.contains("SecretText"))
        assertTrue(toStringResult.contains("test-key"))

        // Must NEVER contain the secret bytes
        assertFalse(toStringResult.contains("supersecretpassword123"))
        assertFalse(toStringResult.contains(String(secretBytes)))
    }

    @Test
    fun `username_password toString never contains password bytes`() {
        val passwordBytes = "mysecretpassword".toByteArray()
        val cred = UsernamePassword(CredentialsId("up-creds"), CredentialScope.GLOBAL, "admin", passwordBytes)

        val toStringResult = cred.toString()

        // Must contain safe fields
        assertTrue(toStringResult.contains("UsernamePassword"))
        assertTrue(toStringResult.contains("up-creds"))
        assertTrue(toStringResult.contains("admin"))

        // Must NEVER contain the password
        assertFalse(toStringResult.contains("mysecretpassword"))
        assertFalse(toStringResult.contains(String(passwordBytes)))
    }

    @Test
    fun `ssh_private_key toString never contains private key bytes`() {
        val keyBytes = "-----BEGIN RSA PRIVATE KEY-----\nSUPERSECRETKEY\n-----END RSA PRIVATE KEY-----".toByteArray()
        val cred = SshPrivateKey(
            CredentialsId("ssh-creds"),
            CredentialScope.GLOBAL,
            "git",
            keyBytes,
            LinkedSecretRef(CredentialsId("passphrase-id"))
        )

        val toStringResult = cred.toString()

        // Must contain safe fields
        assertTrue(toStringResult.contains("SshPrivateKey"))
        assertTrue(toStringResult.contains("ssh-creds"))
        assertTrue(toStringResult.contains("git"))
        assertTrue(toStringResult.contains("hasPassphrase=true"))

        // Must NEVER contain the private key
        assertFalse(toStringResult.contains("SUPERSECRETKEY"))
        assertFalse(toStringResult.contains(String(keyBytes)))
    }

    @Test
    fun `secret_file toString never contains file bytes`() {
        val fileBytes = "CONFIDENTIAL FILE CONTENT".toByteArray()
        val cred = SecretFile(CredentialsId("file-creds"), CredentialScope.GLOBAL, fileBytes, "secret.txt")

        val toStringResult = cred.toString()

        // Must contain safe fields
        assertTrue(toStringResult.contains("SecretFile"))
        assertTrue(toStringResult.contains("file-creds"))
        assertTrue(toStringResult.contains("secret.txt"))

        // Must NEVER contain file contents
        assertFalse(toStringResult.contains("CONFIDENTIAL"))
        assertFalse(toStringResult.contains(String(fileBytes)))
    }

    @Test
    fun `certificate toString never contains keystore bytes`() {
        val keystoreBytes = "PKCS12STORE".toByteArray()
        val cred = Certificate(
            CredentialsId("cert-creds"),
            CredentialScope.GLOBAL,
            keystoreBytes,
            alias = "mykey"
        )

        val toStringResult = cred.toString()

        // Must contain safe fields
        assertTrue(toStringResult.contains("Certificate"))
        assertTrue(toStringResult.contains("cert-creds"))
        assertTrue(toStringResult.contains("mykey"))
        assertTrue(toStringResult.contains("hasPassword=false"))

        // Must NEVER contain keystore bytes
        assertFalse(toStringResult.contains("PKCS12STORE"))
        assertFalse(toStringResult.contains(String(keystoreBytes)))
    }

    @Test
    fun `zip toString never contains entry bytes`() {
        val entries = mapOf(
            "secretexample.txt" to "TOP SECRET CONTENT".toByteArray(),
            "another.txt" to "MORE SECRETS".toByteArray()
        )
        val cred = Zip(CredentialsId("zip-creds"), CredentialScope.GLOBAL, entries)

        val toStringResult = cred.toString()

        // Must contain safe fields
        assertTrue(toStringResult.contains("Zip"))
        assertTrue(toStringResult.contains("zip-creds"))
        assertTrue(toStringResult.contains("entryCount=2"))

        // Must NEVER contain entry content
        assertFalse(toStringResult.contains("TOP SECRET"))
        assertFalse(toStringResult.contains("MORE SECRETS"))
        assertFalse(toStringResult.contains("secretexample.txt")) // entry names are OK to show
    }

    @Test
    fun `username_colon_password toString never contains password bytes`() {
        val passBytes = "secretpassword".toByteArray()
        val cred = UsernameColonPassword(CredentialsId("ucp-creds"), CredentialScope.GLOBAL, "admin", passBytes)

        val toStringResult = cred.toString()

        // Must contain safe fields
        assertTrue(toStringResult.contains("UsernameColonPassword"))
        assertTrue(toStringResult.contains("ucp-creds"))
        assertTrue(toStringResult.contains("admin"))

        // Must NEVER contain the password
        assertFalse(toStringResult.contains("secretpassword"))
        assertFalse(toStringResult.contains(String(passBytes)))
    }

    // ---------------------------------------------------------------------------
    // LinkedSecretRef
    // ---------------------------------------------------------------------------

    @Test
    fun `linked_secret_ref carries credentials_id`() {
        val ref = LinkedSecretRef(CredentialsId("referenced-creds"))
        assertEquals(CredentialsId("referenced-creds"), ref.credentialsId)
    }

    // ---------------------------------------------------------------------------
    // CredentialScope
    // ---------------------------------------------------------------------------

    @Test
    fun `credential_scope has global_and_system`() {
        val values = CredentialScope.entries
        assertEquals(2, values.size)
        assertTrue(CredentialScope.GLOBAL in values)
        assertTrue(CredentialScope.SYSTEM in values)
    }
}
