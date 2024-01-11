package dev.rubentxu.pipeline.backend.factories.credentials

import arrow.fx.coroutines.parZip
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineCollection
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.credentials.*
import dev.rubentxu.pipeline.model.mapper.PropertySet
import dev.rubentxu.pipeline.model.validations.validateAndGet

class LocalCredentialsFactory  {
    companion object : PipelineDomainFactory<PipelineCollection<Credentials>> {
        override val rootPath: String = "credentials.system.localCredentials.credentials"
        override val instanceName: String = "Credentials"

        override suspend fun create(data: PropertySet): PipelineCollection<Credentials> {
            val credentialConfig = getRootListObject(data)

            credentialConfig.map {
                val key = it.keys.first()
                val values = it.values.first() as Map<String, Any>
                createCredential(key, values)
            }.let {
                return PipelineCollection(it)
            }
        }

        private suspend fun createCredential(name: String, credentialConfig: Map<String, Any>): Credentials {
            return when (name) {
                "basicSSHUserPrivateKey" -> BasicSSHUserPrivateKeyFactory.create(credentialConfig)
                "usernamePassword" -> UsernamePasswordFactory.create(credentialConfig)
                "string" -> StringCredentialsFatory.create(credentialConfig)
                "aws" -> AwsCredentialsFactory.create(credentialConfig)
                "file" -> FileCredentialsFactory.create(credentialConfig)
                "certificate" -> CertificateCredentialsFactory.create(credentialConfig)
                else -> {
                    throw IllegalArgumentException("Invalid credential type for '${name}'")
                }
            }
        }
    }
}

class BasicSSHUserPrivateKeyFactory  {
    companion object : PipelineDomainFactory<BasicSSHUserPrivateKey> {
        override val rootPath: String = "pipeline.credentials.basicSSHUserPrivateKey"
        override val instanceName: String = "BasicSSHUserPrivateKey"

        override suspend fun create(data: PropertySet): BasicSSHUserPrivateKey {
            return parZip(
                { data.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") },
                { data.validateAndGet("id").isString().throwIfInvalid("id is required in BasicSSHUserPrivateKey") },
                { data.validateAndGet("username").isString().throwIfInvalid("username is required in BasicSSHUserPrivateKey") },
                { data.validateAndGet("passphrase").isString().defaultValueIfInvalid("") },
                { data.validateAndGet("description").isString().defaultValueIfInvalid("") },
                { data.validateAndGet("privateKeySource.directEntry.privateKey").isString().throwIfInvalid("privateKey is required in BasicSSHUserPrivateKey") }
            ) { scope, id, username, passphrase, description, privateKey ->
                BasicSSHUserPrivateKey(
                    scope = scope,
                    id = IDComponent.create(id),
                    username = username,
                    passphrase = passphrase,
                    description = description,
                    privateKey = privateKey
                )
            }

        }
    }
}

class UsernamePasswordFactory {
    companion object : PipelineDomainFactory<UsernamePassword> {
        override val rootPath: String = "pipeline.credentials.usernamePassword"
        override val instanceName: String = "UsernamePassword"

        override suspend fun create(data: PropertySet): UsernamePassword {
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
        override val rootPath: String = "pipeline.credentials.string"
        override val instanceName: String = "StringCredentials"

        override suspend fun create(data: PropertySet): StringCredentials {
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
        override val rootPath: String = "pipeline.credentials.aws"
        override val instanceName: String = "AwsCredentials"

        override suspend fun create(data: PropertySet): AwsCredentials {
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
        override val rootPath: String = "pipeline.credentials.file"
        override val instanceName: String = "FileCredentials"

        override suspend fun create(data: PropertySet): FileCredentials {
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
        override val rootPath: String = "pipeline.credentials.certificate"
        override val instanceName: String = "CertificateCredentials"

        override suspend fun create(data: PropertySet): CertificateCredentials {
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