package dev.rubentxu.pipeline.v2.binding

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * CredentialsBindingFactory tests — CR-BD-020, CR-BP-001..007.
 *
 * ## Scenario Coverage
 *
 * | Scenario ID | Description | Test Method |
 * |------------|-------------|-------------|
 * | CR-BD-020 | Factory variable persistence — D-E closure | `string_factory_carries_variable`, `usernamePassword_factory_carries_both_variables` |
 * | CR-BP-001 | string factory carries variable into binding payload | `string_factory_carries_variable` |
 * | CR-BP-002 | usernamePassword factory carries both variables | `usernamePassword_factory_carries_both_variables` |
 * | CR-BP-003 | sshUserPrivateKey factory — keyFileVariable REQUIRED | `sshUserPrivateKey_factory_keyfile_required` |
 * | CR-BP-004 | file factory carries variable | `file_factory_carries_variable` |
 * | CR-BP-005 | certificate factory — optionals nullability | `certificate_factory_optionals` |
 * | CR-BP-006 | zip factory carries variable | `zip_factory_carries_variable` |
 * | CR-BP-007 | usernameColonPassword factory carries variable | `usernameColonPassword_factory_carries_variable` |
 */
@DisplayName("CredentialsBindingFactory — variable persistence + Jenkins parity")
class CredentialsBindingFactoryTest {

    private val testId = CredentialsId.from("test-creds")

    // ─── CR-BD-020 / CR-BP-001 — string factory carries variable ─────────────

    @Test
    fun `string_factory_carries_variable_into_binding_payload`() {
        // CR-BP-001: string(creds, "API_KEY") factory must carry variable into binding
        val binding = CredentialsBindingFactory.string(testId, "API_KEY")

        // CR-BD-020: variable must be stored in the binding payload (D-E closure)
        assertEquals("API_KEY", binding.variable,
            "StringBinding.variable must equal the factory argument 'API_KEY'")
        assertEquals(testId, binding.credentialsId)
        assertEquals("string", binding.kind)
    }

    @Test
    fun `string_binding_data_class_has_correct_properties`() {
        val binding = StringBinding(testId, "SECRET_VAR")
        assertEquals(testId, binding.credentialsId)
        assertEquals("SECRET_VAR", binding.variable)
        assertEquals("string", binding.kind)
    }

    // ─── CR-BD-020 / CR-BP-002 — usernamePassword factory carries both ────────

    @Test
    fun `usernamePassword_factory_carries_both_variables`() {
        // CR-BP-002: usernamePassword(creds, "DB_U", "DB_P") factory carries both
        val binding = CredentialsBindingFactory.usernamePassword(testId, "DB_U", "DB_P")

        // CR-BD-020: both variables must be stored in the binding payload
        assertEquals("DB_U", binding.usernameVariable,
            "UsernamePasswordBinding.usernameVariable must equal factory argument 'DB_U'")
        assertEquals("DB_P", binding.passwordVariable,
            "UsernamePasswordBinding.passwordVariable must equal factory argument 'DB_P'")
        assertEquals(testId, binding.credentialsId)
        assertEquals("usernamePassword", binding.kind)
    }

    @Test
    fun `usernamePassword_binding_data_class_has_correct_properties`() {
        val binding = UsernamePasswordBinding(testId, "USER", "PASS")
        assertEquals(testId, binding.credentialsId)
        assertEquals("USER", binding.usernameVariable)
        assertEquals("PASS", binding.passwordVariable)
        assertEquals("usernamePassword", binding.kind)
    }

    // ─── CR-BP-003 — sshUserPrivateKey factory ────────────────────────────────

    @Test
    fun `sshUserPrivateKey_factory_keyfile_variable_is_required`() {
        // CR-BP-003: keyFileVariable is REQUIRED per Jenkins catalog line 111
        val binding = CredentialsBindingFactory.sshUserPrivateKey(testId, "SSH_KEY_FILE")

        assertEquals("SSH_KEY_FILE", binding.keyFileVariable,
            "keyFileVariable must be set (required)")
        assertNull(binding.passphraseVariable, "passphraseVariable defaults to null")
        assertNull(binding.usernameVariable, "usernameVariable defaults to null")
        assertEquals(testId, binding.credentialsId)
        assertEquals("sshUserPrivateKey", binding.kind)
    }

    @Test
    fun `sshUserPrivateKey_factory_with_optionals`() {
        val binding = CredentialsBindingFactory.sshUserPrivateKey(
            testId,
            "SSH_KEY",
            passphraseVariable = "PASSPHRASE",
            usernameVariable = "GIT_USER"
        )

        assertEquals("SSH_KEY", binding.keyFileVariable)
        assertEquals("PASSPHRASE", binding.passphraseVariable)
        assertEquals("GIT_USER", binding.usernameVariable)
    }

    @Test
    fun `sshUserPrivateKey_binding_data_class`() {
        val binding = SshUserPrivateKeyBinding(testId, "KEY", "PHRASE", "USER")
        assertEquals(testId, binding.credentialsId)
        assertEquals("KEY", binding.keyFileVariable)
        assertEquals("PHRASE", binding.passphraseVariable)
        assertEquals("USER", binding.usernameVariable)
        assertEquals("sshUserPrivateKey", binding.kind)
    }

    // ─── CR-BP-004 — file factory carries variable ───────────────────────────

    @Test
    fun `file_factory_carries_variable`() {
        // CR-BP-004: file(creds, "DEPLOY_PEM") factory carries variable
        val binding = CredentialsBindingFactory.file(testId, "DEPLOY_PEM")

        assertEquals("DEPLOY_PEM", binding.variable,
            "FileBinding.variable must equal factory argument 'DEPLOY_PEM'")
        assertEquals(testId, binding.credentialsId)
        assertEquals("file", binding.kind)
    }

    @Test
    fun `file_binding_data_class`() {
        val binding = FileBinding(testId, "MY_FILE")
        assertEquals(testId, binding.credentialsId)
        assertEquals("MY_FILE", binding.variable)
        assertEquals("file", binding.kind)
    }

    // ─── CR-BP-005 — certificate factory ─────────────────────────────────────

    @Test
    fun `certificate_factory_optionals_nullability`() {
        // CR-BP-005: certificate(creds, "KEYSTORE", alias?, pass?) — optionals nullability
        val bindingMinimal = CredentialsBindingFactory.certificate("KEYSTORE", testId)
        assertEquals("KEYSTORE", bindingMinimal.keystoreVariable)
        assertNull(bindingMinimal.aliasVariable, "aliasVariable defaults to null")
        assertNull(bindingMinimal.passwordVariable, "passwordVariable defaults to null")

        val bindingFull = CredentialsBindingFactory.certificate(
            "KEYSTORE", testId,
            aliasVariable = "my-alias",
            passwordVariable = "KEYSTORE_PASS"
        )
        assertEquals("my-alias", bindingFull.aliasVariable)
        assertEquals("KEYSTORE_PASS", bindingFull.passwordVariable)
    }

    @Test
    fun `certificate_binding_data_class`() {
        val binding = CertificateBinding("KS", testId, "alias", "pass")
        assertEquals("KS", binding.keystoreVariable)
        assertEquals(testId, binding.credentialsId)
        assertEquals("alias", binding.aliasVariable)
        assertEquals("pass", binding.passwordVariable)
        assertEquals("certificate", binding.kind)
    }

    // ─── CR-BP-006 — zip factory carries variable ─────────────────────────────

    @Test
    fun `zip_factory_carries_variable`() {
        // CR-BP-006: zip(creds, "ZIP_PATH") factory carries variable
        val binding = CredentialsBindingFactory.zip("ZIP_PATH", testId)

        assertEquals("ZIP_PATH", binding.variable,
            "ZipBinding.variable must equal factory argument 'ZIP_PATH'")
        assertEquals(testId, binding.credentialsId)
        assertEquals("zip", binding.kind)
    }

    @Test
    fun `zip_binding_data_class`() {
        val binding = ZipBinding("PATH", testId)
        assertEquals("PATH", binding.variable)
        assertEquals(testId, binding.credentialsId)
        assertEquals("zip", binding.kind)
    }

    // ─── CR-BP-007 — usernameColonPassword factory carries variable ────────────

    @Test
    fun `usernameColonPassword_factory_carries_variable`() {
        // CR-BP-007: usernameColonPassword(creds, "U_P") factory carries variable
        val binding = CredentialsBindingFactory.usernameColonPassword("U_P", testId)

        assertEquals("U_P", binding.variable,
            "UsernameColonPasswordBinding.variable must equal factory argument 'U_P'")
        assertEquals(testId, binding.credentialsId)
        assertEquals("usernameColonPassword", binding.kind)
    }

    @Test
    fun `usernameColonPassword_binding_data_class`() {
        val binding = UsernameColonPasswordBinding("CREDS", testId)
        assertEquals("CREDS", binding.variable)
        assertEquals(testId, binding.credentialsId)
        assertEquals("usernameColonPassword", binding.kind)
    }
}
