package dev.rubentxu.pipeline.model.credentials

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineComponent
import dev.rubentxu.pipeline.model.PipelineComponentFromMapFactory
import dev.rubentxu.pipeline.model.validations.validateAndGet

interface Credentials : PipelineComponent {
    val id: IDComponent
    val description: String
    val scope: String
}

sealed class CredentialsBuilder  {
    companion object : PipelineComponentFromMapFactory<Credentials> {
        override fun create(data: Map<String, Any>): Credentials {
            val credentialConfig = data.get(data?.keys?.first()) as Map<String, Any>

            return when (data?.keys?.first()) {
                "basicSSHUserPrivateKey" -> BasicSSHUserPrivateKey.create(credentialConfig)
                "usernamePassword" -> UsernamePassword.create(credentialConfig)
                "string" -> StringCredentialsBuilder.create(credentialConfig)
                "aws" -> AwsCredentials.create(credentialConfig)
                "file" -> FileCredentials.create(credentialConfig)
                "certificate" -> CertificateCredentials.create(credentialConfig)
                else -> {
                    throw IllegalArgumentException("Invalid credential type for '${data?.keys?.first()}'")
                }
            }
        }
    }
}

data class BasicSSHUserPrivateKey(
    override val id: IDComponent,
    override val description: String,
    override val scope: String,
    val username: String,
    val passphrase: String,
    val privateKey: String,
) : Credentials {
    companion object : PipelineComponentFromMapFactory<BasicSSHUserPrivateKey> {
        override fun create(data: Map<String, Any>): BasicSSHUserPrivateKey {
            return BasicSSHUserPrivateKey(
                scope = data.validateAndGet("scope")
                    .isString()
                    .defaultValueIfInvalid("GLOBAL"),
                id = IDComponent.create(data.validateAndGet("id")
                    .isString()
                    .throwIfInvalid("id is required in BasicSSHUserPrivateKey")),
                username = data.validateAndGet("username")
                    .isString()
                    .throwIfInvalid("username is required in BasicSSHUserPrivateKey"),
                passphrase = data.validateAndGet("passphrase")
                    .isString()
                    .defaultValueIfInvalid("") as String,
                description = data.validateAndGet("description")
                    .isString()
                    .defaultValueIfInvalid("") as String,
                privateKey = data.validateAndGet("privateKeySource.directEntry.privateKey")
                    .isString()
                    .throwIfInvalid("privateKey is required in BasicSSHUserPrivateKey")
            )
        }
    }
}

data class UsernamePassword(
    override val scope: String,
    override val id: IDComponent,
    override val description: String,
    val username: String,
    val password: String,
) : Credentials {
    companion object : PipelineComponentFromMapFactory<UsernamePassword> {
        override fun create(data: Map<String, Any>): UsernamePassword {
            return UsernamePassword(
                scope = data.validateAndGet("scope")
                    .isString()
                    .defaultValueIfInvalid("GLOBAL") as String,
                id = IDComponent.create(data.validateAndGet("id")
                    .isString()
                    .throwIfInvalid("id is required in UsernamePassword")),
                username = data.validateAndGet("username")
                    .isString()
                    .throwIfInvalid("username is required in UsernamePassword"),
                password = data.validateAndGet("password")
                    .isString()
                    .throwIfInvalid("password is required in UsernamePassword"),
                description = data.validateAndGet("description")
                    .isString()
                    .defaultValueIfInvalid("") as String
            )
        }
    }

}

data class StringCredentialsBuilder(
    override val scope: String,
    override val id: IDComponent,
    override val description: String,
    val secret: String,
) : Credentials {
    companion object : PipelineComponentFromMapFactory<StringCredentialsBuilder> {
        override fun create(data: Map<String, Any>): StringCredentialsBuilder {
            return StringCredentialsBuilder(
                scope = data.validateAndGet("scope")
                    .isString()
                    .defaultValueIfInvalid("GLOBAL"),
                id = IDComponent.create(data.validateAndGet("id")
                    .isString()
                    .throwIfInvalid("id is required in StringCredential")),
                secret = data.validateAndGet("secret")
                    .isString()
                    .throwIfInvalid("secret is required in StringCredential"),
                description = data.validateAndGet("description")
                    .isString()
                    .defaultValueIfInvalid("")
            )
        }

    }
}

data class AwsCredentials(
    override val scope: String,
    override val id: IDComponent,
    override val description: String,
    val accessKey: String,
    val secretKey: String,
) : Credentials {
    companion object : PipelineComponentFromMapFactory<AwsCredentials> {
        override fun create(data: Map<String, Any>): AwsCredentials {
            return AwsCredentials(
                scope = data.validateAndGet("scope")
                    .isString()
                    .defaultValueIfInvalid("GLOBAL"),
                id = IDComponent.create(data.validateAndGet("id")
                    .isString()
                    .throwIfInvalid("id is required in AwsCredential")),
                accessKey = data.validateAndGet("accessKey")
                    .isString()
                    .throwIfInvalid("accessKey is required in AwsCredential"),
                secretKey = data.validateAndGet("secretKey")
                    .isString()
                    .throwIfInvalid("secretKey is required in AwsCredential"),
                description = data.validateAndGet("description")
                    .isString()
                    .defaultValueIfInvalid("")
            )
        }
    }
}

data class FileCredentials(
    override val scope: String,
    override val id: IDComponent,
    override val description: String,
    val fileName: String,
    val secretBytes: String,
) : Credentials {
    companion object : PipelineComponentFromMapFactory<FileCredentials> {
        override fun create(data: Map<String, Any>): FileCredentials {
            return FileCredentials(
                scope = data.validateAndGet("scope")
                    .isString()
                    .defaultValueIfInvalid("GLOBAL"),
                id = IDComponent.create(data.validateAndGet("id")
                    .isString()
                    .throwIfInvalid("id is required in FileCredential")),
                fileName = data.validateAndGet("fileName")
                    .isString()
                    .throwIfInvalid("fileName is required in FileCredential"),
                secretBytes = data.validateAndGet("secretBytes")
                    .isString()
                    .throwIfInvalid("secretBytes is required in FileCredential"),
                description = data.validateAndGet("description")
                    .isString()
                    .defaultValueIfInvalid("")
            )
        }
    }
}

data class CertificateCredentials(
    override val scope: String,
    override val id: IDComponent,
    override val description: String,
    val password: String,
    val keyStore: String,
) : Credentials {
    companion object : PipelineComponentFromMapFactory<CertificateCredentials> {
        override fun create(data: Map<String, Any>): CertificateCredentials {
            return CertificateCredentials(
                scope = data.validateAndGet("scope")
                    .isString()
                    .defaultValueIfInvalid("GLOBAL"),
                id = IDComponent.create(data.validateAndGet("id")
                    .isString()
                    .throwIfInvalid("id is required in CertificateCredential")),
                password = data.validateAndGet("password")
                    .isString()
                    .throwIfInvalid("password is required in CertificateCredential"),
                description = data.validateAndGet("description")
                    .isString()
                    .defaultValueIfInvalid("") as String,
                keyStore = data.validateAndGet("keyStoreSource.uploaded.uploadedKeystore")
                    .isString()
                    .throwIfInvalid("keyStore is required in CertificateCredential")
            )
        }
    }
}