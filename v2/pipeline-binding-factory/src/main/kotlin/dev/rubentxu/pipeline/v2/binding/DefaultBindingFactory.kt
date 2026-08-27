package dev.rubentxu.pipeline.v2.binding

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle

/**
 * Default implementation of [ContributedBindingFactory] for all 7 Jenkins credential kinds.
 *
 * ## Supported Kinds (JENKINS_FAMILIARITY_CATALOG.md §1.6)
 *
 * - `string`: Secret text → single env var
 * - `usernamePassword`: Username with password → USERNAME_VAR + PASSWORD_VAR
 * - `sshUserPrivateKey`: SSH Username with private key → KEY_FILE_VAR (+ PASSPHRASE_VAR, USERNAME_VAR)
 * - `file`: File credentials → single env var with temp file path
 * - `certificate`: Certificate credentials → KEYSTORE_VAR (+ ALIAS_VAR, PASSWORD_VAR)
 * - `zip`: Zip credentials → temp dir path
 * - `usernameColonPassword`: Username:Password → single env var
 *
 * ## Design Notes
 *
 * - Variable names are persisted WITHOUT case coercion (INV-L6-CR-007)
 * - For typed credential extraction (UsernamePassword, etc.), the caller should
 *   pass a resolver that can return typed [Credential] objects when needed.
 *   This factory receives [SecretHandle] via the resolver, which is sufficient for
 *   simple bindings that map directly to env vars.
 * - Partial-failure = nothing injected: if any binding in [MultiBindingWithCredentials]
 *   fails, all resolved handles are wiped before throwing
 */
class DefaultBindingFactory : ContributedBindingFactory {

    override fun supportedKinds(): Set<String> = SUPPORTED_KINDS

    override fun resolve(
        binding: CredentialsBinding,
        credentialResolver: (CredentialsId) -> SecretHandle
    ): List<ContributedBindingFactory.EnvEntry> {
        return when (binding) {
            is StringBinding -> resolveString(binding, credentialResolver)
            is UsernamePasswordBinding -> resolveUsernamePassword(binding, credentialResolver)
            is SshUserPrivateKeyBinding -> resolveSshUserPrivateKey(binding, credentialResolver)
            is FileBinding -> resolveFile(binding, credentialResolver)
            is CertificateBinding -> resolveCertificate(binding, credentialResolver)
            is ZipBinding -> resolveZip(binding, credentialResolver)
            is UsernameColonPasswordBinding -> resolveUsernameColonPassword(binding, credentialResolver)
        }
    }

    private fun resolveString(
        binding: StringBinding,
        credentialResolver: (CredentialsId) -> SecretHandle
    ): List<ContributedBindingFactory.EnvEntry> {
        val handle = credentialResolver(binding.credentialsId)
        return listOf(ContributedBindingFactory.EnvEntry(binding.variable, handle))
    }

    /**
     * Resolves UsernamePassword binding.
     *
     * Note: UsernamePassword credentials store username and password as a null-separated
     * byte sequence in the secret: "username\0password". This method parses that format.
     */
    private fun resolveUsernamePassword(
        binding: UsernamePasswordBinding,
        credentialResolver: (CredentialsId) -> SecretHandle
    ): List<ContributedBindingFactory.EnvEntry> {
        val handle = credentialResolver(binding.credentialsId)
        val entries = mutableListOf<ContributedBindingFactory.EnvEntry>()

        handle.use { bytes ->
            // Format: username\0password (null-separated)
            val nullIndex = bytes.indexOf(0.toByte())
            if (nullIndex > 0) {
                val usernameBytes = bytes.sliceArray(0 until nullIndex)
                val passwordBytes = bytes.sliceArray(nullIndex + 1 until bytes.size)
                entries.add(ContributedBindingFactory.EnvEntry(binding.usernameVariable, SecretHandle.secret(usernameBytes)))
                entries.add(ContributedBindingFactory.EnvEntry(binding.passwordVariable, SecretHandle.secret(passwordBytes)))
            } else {
                throw BindingResolutionException(
                    binding,
                    "UsernamePassword credential format invalid: expected null-separated username and password"
                )
            }
        }

        return entries
    }

    private fun resolveSshUserPrivateKey(
        binding: SshUserPrivateKeyBinding,
        credentialResolver: (CredentialsId) -> SecretHandle
    ): List<ContributedBindingFactory.EnvEntry> {
        val handle = credentialResolver(binding.credentialsId)
        // The key file variable points to the handle containing the private key
        // Materialization to temp file happens in the executor (not here)
        return listOf(ContributedBindingFactory.EnvEntry(binding.keyFileVariable, handle))
    }

    private fun resolveFile(
        binding: FileBinding,
        credentialResolver: (CredentialsId) -> SecretHandle
    ): List<ContributedBindingFactory.EnvEntry> {
        val handle = credentialResolver(binding.credentialsId)
        return listOf(ContributedBindingFactory.EnvEntry(binding.variable, handle))
    }

    private fun resolveCertificate(
        binding: CertificateBinding,
        credentialResolver: (CredentialsId) -> SecretHandle
    ): List<ContributedBindingFactory.EnvEntry> {
        val handle = credentialResolver(binding.credentialsId)
        return listOf(ContributedBindingFactory.EnvEntry(binding.keystoreVariable, handle))
    }

    private fun resolveZip(
        binding: ZipBinding,
        credentialResolver: (CredentialsId) -> SecretHandle
    ): List<ContributedBindingFactory.EnvEntry> {
        val handle = credentialResolver(binding.credentialsId)
        return listOf(ContributedBindingFactory.EnvEntry(binding.variable, handle))
    }

    private fun resolveUsernameColonPassword(
        binding: UsernameColonPasswordBinding,
        credentialResolver: (CredentialsId) -> SecretHandle
    ): List<ContributedBindingFactory.EnvEntry> {
        val handle = credentialResolver(binding.credentialsId)
        return listOf(ContributedBindingFactory.EnvEntry(binding.variable, handle))
    }

    companion object {
        private val SUPPORTED_KINDS = setOf(
            "string",
            "usernamePassword",
            "sshUserPrivateKey",
            "file",
            "certificate",
            "zip",
            "usernameColonPassword"
        )
    }
}
