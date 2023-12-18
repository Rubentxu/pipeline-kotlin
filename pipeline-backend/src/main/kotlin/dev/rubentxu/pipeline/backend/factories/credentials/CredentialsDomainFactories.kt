package dev.rubentxu.pipeline.backend.factories.credentials

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.credentials.*
import dev.rubentxu.pipeline.model.validations.validateAndGet

class CredentialsFactory  {
    companion object : PipelineDomainFactory<Credentials> {
        override fun create(data: Map<String, Any>): Credentials {
            val credentialConfig = data.get(data?.keys?.first()) as Map<String, Any>

            return when (data?.keys?.first()) {
                "basicSSHUserPrivateKey" -> BasicSSHUserPrivateKeyFactory.create(credentialConfig)
                "usernamePassword" -> UsernamePasswordFactory.create(credentialConfig)
                "string" -> StringCredentialsFatory.create(credentialConfig)
                "aws" -> AwsCredentialsFactory.create(credentialConfig)
                "file" -> FileCredentialsFactory.create(credentialConfig)
                "certificate" -> CertificateCredentialsFactory.create(credentialConfig)
                else -> {
                    throw IllegalArgumentException("Invalid credential type for '${data?.keys?.first()}'")
                }
            }
        }
    }
}

class BasicSSHUserPrivateKeyFactory  {
    companion object : PipelineDomainFactory<BasicSSHUserPrivateKey> {
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

class UsernamePasswordFactory {
    companion object : PipelineDomainFactory<UsernamePassword> {
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

class StringCredentialsFatory {
    companion object : PipelineDomainFactory<StringCredentials> {
        override fun create(data: Map<String, Any>): StringCredentials {
            return StringCredentials(
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

class AwsCredentialsFactory {
    companion object : PipelineDomainFactory<AwsCredentials> {
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

class FileCredentialsFactory {
    companion object : PipelineDomainFactory<FileCredentials> {
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


class CertificateCredentialsFactory {
    companion object : PipelineDomainFactory<CertificateCredentials> {
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