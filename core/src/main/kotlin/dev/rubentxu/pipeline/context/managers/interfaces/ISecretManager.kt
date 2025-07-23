package dev.rubentxu.pipeline.context.managers.interfaces

import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/**
 * Represents the different kinds of values a secret can hold, modeled after Jenkins' credential types.
 * All implementations override `toString()` to prevent accidental logging of sensitive values.
 */
sealed interface SecretValue {
    /**
     * A plain text secret, like an API token.
     * Maps to Jenkins 'Secret text'.
     */
    data class PlainText(val value: String) : SecretValue {
        override fun toString(): String = "[SECRET PlainText]"
    }

    /**
     * A standard username and password pair.
     * Maps to Jenkins 'Username with password'.
     */
    data class UsernamePassword(val user: String, val pass: String) : SecretValue {
        override fun toString(): String = "[SECRET UsernamePassword(user=$user)]"
    }

    /**
     * A generic secret file made available at a temporary path.
     * Maps to Jenkins 'Secret file'.
     */
    data class FileBased(val path: Path) : SecretValue {
        override fun toString(): String = "[SECRET FileBased(path=$path)]"
    }

    /**
     * SSH credentials with a private key.
     * The private key is made available as a temporary file. The passphrase, if present,
     * would be another secret managed by the provider.
     * Maps to Jenkins 'SSH Username with private key'.
     */
    data class SshPrivateKey(
        val username: String,
        val privateKeyPath: Path,
        val passphraseSecretId: String? = null // Optional ID for a PlainText secret
    ) : SecretValue {
        override fun toString(): String = "[SECRET SshPrivateKey(user=$username)]"
    }

    /**
     * A certificate file (e.g., PKCS#12) with an optional password.
     * The password would be another secret managed by the provider.
     * Maps to Jenkins 'Certificate'.
     */
    data class Certificate(
        val keystorePath: Path,
        val passwordSecretId: String? = null // Optional ID for a PlainText secret
    ) : SecretValue {
        override fun toString(): String = "[SECRET Certificate]"
    }

    /**
     * AWS credentials.
     * Maps to Jenkins 'AWS Credentials'.
     */
    data class AwsCredentials(
        val accessKeyId: String,
        val secretAccessKey: String
    ) : SecretValue {
        override fun toString(): String = "[SECRET AwsCredentials(accessKeyId=$accessKeyId)]"
    }
}

/**
 * Defines how a credential should be "bound" to the environment.
 * The different types provide a type-safe way to map secrets to environment variables.
 */
sealed interface CredentialBinding {
    val credentialId: String

    /** Binds a PlainText secret to a single environment variable. */
    data class StringBinding(override val credentialId: String, val variable: String) : CredentialBinding

    /** Binds a UsernamePassword secret to user and password environment variables. */
    data class UserPasswordBinding(override val credentialId: String, val userVariable: String, val passwordVariable: String) : CredentialBinding

    /** Binds a FileBased, SshPrivateKey, or Certificate secret to an environment variable holding its file path. */
    data class FileBinding(override val credentialId: String, val variable: String) : CredentialBinding

    /** Binds an SshPrivateKey secret to username and key file path variables. */
    data class SshPrivateKeyBinding(
        override val credentialId: String,
        val userVariable: String,
        val privateKeyPathVariable: String
    ) : CredentialBinding

    /** Binds an AwsCredentials secret to access key and secret key variables. */
    data class AwsCredentialsBinding(
        override val credentialId: String,
        val accessKeyIdVariable: String,
        val secretAccessKeyVariable: String
    ) : CredentialBinding

    /** Binds a Certificate secret to keystore path and password variables. */
    data class CertificateBinding(
        override val credentialId: String,
        val keystorePathVariable: String,
        val passwordVariable: String
    ) : CredentialBinding
}

/**
 * Pluggable provider for retrieving secrets from a secure backend (e.g., Vault, Jenkins Credentials).
 */
interface SecretProvider {
    fun getSecret(credentialId: String): SecretValue?
    fun cleanup(secretValue: SecretValue)
}

/**
 * Manages secure access to credentials for the pipeline.
 * Its only public-facing capability is the `withCredentials` block.
 */
interface ISecretManager {
    /**
     * Executes a block of code with credentials securely injected into the
     * environment as temporary variables.
     */
    suspend fun <T> withCredentials(
        bindings: List<CredentialBinding>,
        block: suspend CoroutineScope.() -> T
    ): T
}

// --- IMPLEMENTACIÓN ---

