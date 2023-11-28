package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.model.config.Configuration
import dev.rubentxu.pipeline.model.config.MapConfigurationBuilder
import dev.rubentxu.pipeline.validation.validateAndGet


sealed class Credential: Configuration {
    abstract val id: String
    abstract val description: String

    companion object: MapConfigurationBuilder<Credential> {
        override fun build(data: Map<String, Any>): Credential {
            return when (data?.keys?.first()) {
                "basicSSHUserPrivateKey" -> BasicSSHUserPrivateKey.build(data.get(data?.keys?.first()) as Map<String, Any>)
                "usernamePassword" -> UsernamePassword.build(data.get(data?.keys?.first()) as Map<String, Any>)
                "string" -> StringCredential.build(data.get(data?.keys?.first()) as Map<String, Any>)
                "aws" -> AwsCredential.build(data.get(data?.keys?.first()) as Map<String, Any>)
                "file" -> FileCredential.build(data.get(data?.keys?.first()) as Map<String, Any>)
                "certificate" -> CertificateCredential.build(data.get(data?.keys?.first()) as Map<String, Any>)
                else -> {
                    throw IllegalArgumentException("Invalid credential type for '${data?.keys?.first()}'")
                }
            }
        }
    }
}

data class BasicSSHUserPrivateKey(
    override val id: String,
    override val description: String,
    val scope: String,
    val username: String,
    val passphrase: String,
    val privateKey: String,
) : Credential() {
    companion object: MapConfigurationBuilder<BasicSSHUserPrivateKey>  {
        override fun build(data: Map<String, Any>): BasicSSHUserPrivateKey {
            return BasicSSHUserPrivateKey(
                scope = data.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") as String,
                id = data.validateAndGet("id").isString().throwIfInvalid("id is required in BasicSSHUserPrivateKey"),
                username = data.validateAndGet("username").isString().throwIfInvalid("username is required in BasicSSHUserPrivateKey"),
                passphrase = data.validateAndGet("passphrase").isString().defaultValueIfInvalid("") as String,
                description = data.validateAndGet("description").isString().defaultValueIfInvalid("") as String,
                privateKey = data.validateAndGet("privateKeySource.directEntry.privateKey").isString().throwIfInvalid("privateKey is required in BasicSSHUserPrivateKey")
            )
        }
    }
}

data class UsernamePassword(
    val scope: String,
    override val id: String,
    override val description: String,
    val username: String,
    val password: String,
) : Credential() {
    companion object: MapConfigurationBuilder<UsernamePassword> {
        override fun build(data: Map<String, Any>): UsernamePassword {
            return UsernamePassword(
                scope = data.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") as String,
                id = data.validateAndGet("id").isString().throwIfInvalid("id is required in UsernamePassword"),
                username = data.validateAndGet("username").isString().throwIfInvalid("username is required in UsernamePassword"),
                password = data.validateAndGet("password").isString().throwIfInvalid("password is required in UsernamePassword"),
                description = data.validateAndGet("description").isString().defaultValueIfInvalid("") as String
            )
        }
    }

}

data class StringCredential(
    val scope: String,
    override val id: String,
    override val description: String,
    val secret: String,
) : Credential() {
    companion object: MapConfigurationBuilder<StringCredential> {
        override fun build(data: Map<String, Any>): StringCredential {
            return StringCredential(
                scope = data.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") as String,
                id = data.validateAndGet("id").isString().throwIfInvalid("id is required in StringCredential"),
                secret = data.validateAndGet("secret").isString().throwIfInvalid("secret is required in StringCredential"),
                description = data.validateAndGet("description").isString().defaultValueIfInvalid("") as String
            )
        }

    }
}

data class AwsCredential(
    val scope: String,
    override val id: String,
    override val description: String,
    val accessKey: String,
    val secretKey: String,
) : Credential() {
    companion object: MapConfigurationBuilder<AwsCredential> {
        override fun build(data: Map<String, Any>): AwsCredential {
            return AwsCredential(
                scope = data.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL"),
                id = data.validateAndGet("id").isString().throwIfInvalid("id is required in AwsCredential"),
                accessKey = data.validateAndGet("accessKey").isString().throwIfInvalid("accessKey is required in AwsCredential"),
                secretKey = data.validateAndGet("secretKey").isString().throwIfInvalid("secretKey is required in AwsCredential"),
                description = data.validateAndGet("description").isString().defaultValueIfInvalid("")
            )
        }
    }
}

data class FileCredential(
    val scope: String,
    override val id: String,
    override val description: String,
    val fileName: String,
    val secretBytes: String
) : Credential() {
    companion object: MapConfigurationBuilder<FileCredential> {
        override fun build(data: Map<String, Any>): FileCredential {
            return FileCredential(
                scope = data.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") as String,
                id = data.validateAndGet("id").isString().throwIfInvalid("id is required in FileCredential"),
                fileName = data.validateAndGet("fileName").isString().throwIfInvalid("fileName is required in FileCredential"),
                secretBytes = data.validateAndGet("secretBytes").isString().throwIfInvalid("secretBytes is required in FileCredential"),
                description = data.validateAndGet("description").isString().defaultValueIfInvalid("") as String
            )
        }
    }
}

data class CertificateCredential(
    val scope: String,
    override val id: String,
    override val description: String,
    val password: String,
    val keyStore: String
) : Credential() {
    companion object: MapConfigurationBuilder<CertificateCredential> {
        override fun build(data: Map<String, Any>): CertificateCredential {
            return CertificateCredential(
                scope = data.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") as String,
                id = data.validateAndGet("id").isString().throwIfInvalid("id is required in CertificateCredential"),
                password = data.validateAndGet("password").isString().throwIfInvalid("password is required in CertificateCredential"),
                description = data.validateAndGet("description").isString().defaultValueIfInvalid("") as String,
                keyStore = data.validateAndGet("keyStoreSource.uploaded.uploadedKeystore").isString().throwIfInvalid("keyStore is required in CertificateCredential")
            )
        }
    }
}