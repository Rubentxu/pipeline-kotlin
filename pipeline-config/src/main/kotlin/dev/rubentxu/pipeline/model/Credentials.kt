package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.model.config.Configuration
import dev.rubentxu.pipeline.model.config.MapConfigurationBuilder
import dev.rubentxu.pipeline.validation.validateAndGet




sealed class CredentialConfig : Configuration {
    abstract val id: IDConfig
    abstract val description: String

    companion object : MapConfigurationBuilder<CredentialConfig> {
        override fun build(data: Map<String, Any>): CredentialConfig {
            return when (data?.keys?.first()) {
                "basicSSHUserPrivateKey" -> BasicSSHUserPrivateKey.build(data.get(data?.keys?.first()) as Map<String, Any>)
                "usernamePassword" -> UsernamePassword.build(data.get(data?.keys?.first()) as Map<String, Any>)
                "string" -> StringCredentialConfig.build(data.get(data?.keys?.first()) as Map<String, Any>)
                "aws" -> AwsCredentialConfig.build(data.get(data?.keys?.first()) as Map<String, Any>)
                "file" -> FileCredentialConfig.build(data.get(data?.keys?.first()) as Map<String, Any>)
                "certificate" -> CertificateCredentialConfig.build(data.get(data?.keys?.first()) as Map<String, Any>)
                else -> {
                    throw IllegalArgumentException("Invalid credential type for '${data?.keys?.first()}'")
                }
            }
        }
    }
}

data class BasicSSHUserPrivateKey(
    override val id: IDConfig,
    override val description: String,
    val scope: String,
    val username: String,
    val passphrase: String,
    val privateKey: String,
) : CredentialConfig() {
    companion object : MapConfigurationBuilder<BasicSSHUserPrivateKey> {
        override fun build(data: Map<String, Any>): BasicSSHUserPrivateKey {
            return BasicSSHUserPrivateKey(
                scope = data.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") as String,
                id = IDConfig.create(data.validateAndGet("id").isString().throwIfInvalid("id is required in BasicSSHUserPrivateKey")),
                username = data.validateAndGet("username").isString()
                    .throwIfInvalid("username is required in BasicSSHUserPrivateKey"),
                passphrase = data.validateAndGet("passphrase").isString().defaultValueIfInvalid("") as String,
                description = data.validateAndGet("description").isString().defaultValueIfInvalid("") as String,
                privateKey = data.validateAndGet("privateKeySource.directEntry.privateKey").isString()
                    .throwIfInvalid("privateKey is required in BasicSSHUserPrivateKey")
            )
        }
    }
}

data class UsernamePassword(
    val scope: String,
    override val id: IDConfig,
    override val description: String,
    val username: String,
    val password: String,
) : CredentialConfig() {
    companion object : MapConfigurationBuilder<UsernamePassword> {
        override fun build(data: Map<String, Any>): UsernamePassword {
            return UsernamePassword(
                scope = data.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") as String,
                id = IDConfig.create(data.validateAndGet("id").isString().throwIfInvalid("id is required in UsernamePassword")),
                username = data.validateAndGet("username").isString()
                    .throwIfInvalid("username is required in UsernamePassword"),
                password = data.validateAndGet("password").isString()
                    .throwIfInvalid("password is required in UsernamePassword"),
                description = data.validateAndGet("description").isString().defaultValueIfInvalid("") as String
            )
        }
    }

}

data class StringCredentialConfig(
    val scope: String,
    override val id: IDConfig,
    override val description: String,
    val secret: String,
) : CredentialConfig() {
    companion object : MapConfigurationBuilder<StringCredentialConfig> {
        override fun build(data: Map<String, Any>): StringCredentialConfig {
            return StringCredentialConfig(
                scope = data.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") as String,
                id = IDConfig.create(data.validateAndGet("id").isString().throwIfInvalid("id is required in StringCredential")),
                secret = data.validateAndGet("secret").isString()
                    .throwIfInvalid("secret is required in StringCredential"),
                description = data.validateAndGet("description").isString().defaultValueIfInvalid("") as String
            )
        }

    }
}

data class AwsCredentialConfig(
    val scope: String,
    override val id: IDConfig,
    override val description: String,
    val accessKey: String,
    val secretKey: String,
) : CredentialConfig() {
    companion object : MapConfigurationBuilder<AwsCredentialConfig> {
        override fun build(data: Map<String, Any>): AwsCredentialConfig {
            return AwsCredentialConfig(
                scope = data.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL"),
                id = IDConfig.create(data.validateAndGet("id").isString().throwIfInvalid("id is required in AwsCredential")),
                accessKey = data.validateAndGet("accessKey").isString()
                    .throwIfInvalid("accessKey is required in AwsCredential"),
                secretKey = data.validateAndGet("secretKey").isString()
                    .throwIfInvalid("secretKey is required in AwsCredential"),
                description = data.validateAndGet("description").isString().defaultValueIfInvalid("")
            )
        }
    }
}

data class FileCredentialConfig(
    val scope: String,
    override val id: IDConfig,
    override val description: String,
    val fileName: String,
    val secretBytes: String,
) : CredentialConfig() {
    companion object : MapConfigurationBuilder<FileCredentialConfig> {
        override fun build(data: Map<String, Any>): FileCredentialConfig {
            return FileCredentialConfig(
                scope = data.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") as String,
                id = IDConfig.create(data.validateAndGet("id").isString().throwIfInvalid("id is required in FileCredential")),
                fileName = data.validateAndGet("fileName").isString()
                    .throwIfInvalid("fileName is required in FileCredential"),
                secretBytes = data.validateAndGet("secretBytes").isString()
                    .throwIfInvalid("secretBytes is required in FileCredential"),
                description = data.validateAndGet("description").isString().defaultValueIfInvalid("") as String
            )
        }
    }
}

data class CertificateCredentialConfig(
    val scope: String,
    override val id: IDConfig,
    override val description: String,
    val password: String,
    val keyStore: String,
) : CredentialConfig() {
    companion object : MapConfigurationBuilder<CertificateCredentialConfig> {
        override fun build(data: Map<String, Any>): CertificateCredentialConfig {
            return CertificateCredentialConfig(
                scope = data.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") as String,
                id = IDConfig.create(data.validateAndGet("id").isString().throwIfInvalid("id is required in CertificateCredential")),
                password = data.validateAndGet("password").isString()
                    .throwIfInvalid("password is required in CertificateCredential"),
                description = data.validateAndGet("description").isString().defaultValueIfInvalid("") as String,
                keyStore = data.validateAndGet("keyStoreSource.uploaded.uploadedKeystore").isString()
                    .throwIfInvalid("keyStore is required in CertificateCredential")
            )
        }
    }
}