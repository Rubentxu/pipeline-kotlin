package dev.rubentxu.pipeline.context.managers

import dev.rubentxu.pipeline.context.managers.interfaces.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope

/**
 * Default implementation of ISecretManager.
 * It depends on a SecretProvider to fetch secrets and an IEnvironmentManager
 * to inject them into a temporary scope.
 */
class DefaultSecretManager(
    private val environmentManager: IEnvironmentManager,
    private val secretProvider: SecretProvider
) : ISecretManager {

    override suspend fun <T> withCredentials(
        bindings: List<CredentialBinding>,
        block: suspend CoroutineScope.() -> T
    ): T {
        val envVarsToInject = mutableMapOf<String, String>()
        val secretsToCleanup = mutableListOf<SecretValue>()

        try {
            for (binding in bindings) {
                val secretValue = secretProvider.getSecret(binding.credentialId)
                    ?: throw IllegalArgumentException("Credential '${binding.credentialId}' not found.")

                secretsToCleanup.add(secretValue)

                // Use a type-safe 'when' block to handle each binding type
                when (binding) {
                    is CredentialBinding.StringBinding -> {
                        val plainText = secretValue as? SecretValue.PlainText
                            ?: throw MismatchedSecretException(binding.credentialId, "PlainText")
                        envVarsToInject[binding.variable] = plainText.value
                    }

                    is CredentialBinding.UserPasswordBinding -> {
                        val userPass = secretValue as? SecretValue.UsernamePassword
                            ?: throw MismatchedSecretException(binding.credentialId, "UsernamePassword")
                        envVarsToInject[binding.userVariable] = userPass.user
                        envVarsToInject[binding.passwordVariable] = userPass.pass
                    }

                    is CredentialBinding.FileBinding -> {
                        val path = when (secretValue) {
                            is SecretValue.FileBased -> secretValue.path
                            is SecretValue.SshPrivateKey -> secretValue.privateKeyPath
                            is SecretValue.Certificate -> secretValue.keystorePath
                            else -> throw MismatchedSecretException(
                                binding.credentialId,
                                "any file-based type (FileBased, SshPrivateKey, Certificate)"
                            )
                        }
                        envVarsToInject[binding.variable] = path.toString()
                    }

                    is CredentialBinding.SshPrivateKeyBinding -> {
                        val sshKey = secretValue as? SecretValue.SshPrivateKey
                            ?: throw MismatchedSecretException(binding.credentialId, "SshPrivateKey")
                        envVarsToInject[binding.userVariable] = sshKey.username
                        envVarsToInject[binding.privateKeyPathVariable] = sshKey.privateKeyPath.toString()

                        // Optionally handle the passphrase if it exists
                        sshKey.passphraseSecretId?.let { passphraseId ->
                            // In a real scenario, you might not want to expose the passphrase as an env var,
                            // but for completeness, we show how it could be done.
                            // A better approach might be a more specific binding if needed.
                            println("Note: SshPrivateKey passphrase is not automatically bound to an environment variable.")
                        }
                    }

                    is CredentialBinding.AwsCredentialsBinding -> {
                        val awsCreds = secretValue as? SecretValue.AwsCredentials
                            ?: throw MismatchedSecretException(binding.credentialId, "AwsCredentials")
                        envVarsToInject[binding.accessKeyIdVariable] = awsCreds.accessKeyId
                        envVarsToInject[binding.secretAccessKeyVariable] = awsCreds.secretAccessKey
                    }

                    is CredentialBinding.CertificateBinding -> {
                        val cert = secretValue as? SecretValue.Certificate
                            ?: throw MismatchedSecretException(binding.credentialId, "Certificate")
                        envVarsToInject[binding.keystorePathVariable] = cert.keystorePath.toString()

                        // Handle the linked password secret
                        val passwordSecretId = cert.passwordSecretId
                            ?: throw IllegalArgumentException("Certificate '${binding.credentialId}' requires a password, but none was configured in the secret.")

                        val passwordSecret = secretProvider.getSecret(passwordSecretId)
                            ?: throw IllegalArgumentException("Password secret with ID '$passwordSecretId' for certificate '${binding.credentialId}' not found.")

                        secretsToCleanup.add(passwordSecret) // Also ensure the password secret is cleaned up

                        val passwordText = passwordSecret as? SecretValue.PlainText
                            ?: throw MismatchedSecretException(passwordSecretId, "PlainText (for certificate password)")

                        envVarsToInject[binding.passwordVariable] = passwordText.value
                    }
                }
            }

            // Use the environment manager to create a temporary, isolated scope for the secrets
            return environmentManager.withScope("credentials-scope", envVarsToInject) {
                // The user's block runs here, inside the temporary scope
                coroutineScope {
                    block()
                }
            }

        } finally {
            // Guaranteed cleanup, even if the block fails
            secretsToCleanup.forEach { secretProvider.cleanup(it) }
        }
    }
}

/**
 * Custom exception for better error messages when a binding and secret type do not match.
 */
class MismatchedSecretException(credentialId: String, expectedType: String) :
    IllegalArgumentException("Credential '$credentialId' is not of the expected type '$expectedType'.")
