package dev.rubentxu.pipeline.backend.factories.credentials

import arrow.core.raise.Raise
import arrow.fx.coroutines.parMap
import arrow.fx.coroutines.parZip
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.credentials.*
import dev.rubentxu.pipeline.model.mapper.*

class LocalCredentialsFactory {

    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<Credentials>> {
        override val rootPath: PropertyPath = "credentials.system.localCredentials".propertyPath()

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<Credentials> {
            return getRootListPropertySet(data)
                .parMap {
                    val key = it.keys.first()
                    createCredential(key, it)
                }
        }

        private suspend fun createCredential(name: String, credentialConfig: PropertySet): Credentials {
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

class BasicSSHUserPrivateKeyFactory {

    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<BasicSSHUserPrivateKey>> {
        override val rootPath: PropertyPath =
            "credentials.system.localCredentials[*].basicSSHUserPrivateKey".propertyPath()

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<BasicSSHUserPrivateKey> =
            getRootListPropertySet(data)
                .parMap {
                    createBasicSSHUserPrivateKey(it)
                }

        suspend fun createBasicSSHUserPrivateKey(credentialConfig: PropertySet): BasicSSHUserPrivateKey {
            return parZip(
                { credentialConfig.required<String>("scope") },
                { credentialConfig.required<String>("id") },
                { credentialConfig.required<String>("username") },
                { credentialConfig.required<String>("passphrase") },
                { credentialConfig.required<String>("description") },
                { credentialConfig.required<String>("privateKeySource.directEntry.privateKey") }
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

    class UsernamePasswordFactory {

        context(Raise<PropertiesError>)
        companion object : PipelineDomainFactory<UsernamePassword> {
            override val rootPath: PropertyPath = "pipeline.credentials.usernamePassword".propertyPath()

            context(Raise<PropertiesError>)
            override suspend fun create(data: PropertySet): UsernamePassword {
                return UsernamePassword(
                    scope = data.required<String>("scope"),
                    id = IDComponent.create(
                        data.required<String>("id")
                    ),
                    username = data.required<String>("username"),
                    password = data.required<String>("password"),
                    description = data.required<String>("description")
                )
            }
        }
    }

    class StringCredentialsFatory {
        context(Raise<PropertiesError>)
        companion object : PipelineDomainFactory<StringCredentials> {
            override val rootPath: PropertyPath = "pipeline.credentials.string".propertyPath()

            context(Raise<PropertiesError>)
            override suspend fun create(data: PropertySet): StringCredentials {
                return StringCredentials(
                    scope = data.required<String>("scope"),
                    id = IDComponent.create(
                        data.required<String>("id")
                    ),
                    secret = data.required<String>("secret"),
                    description = data.required<String>("description")
                )
            }

        }
    }

    class AwsCredentialsFactory {
        context(Raise<PropertiesError>)
        companion object : PipelineDomainFactory<AwsCredentials> {
            override val rootPath: PropertyPath = "pipeline.credentials.aws".propertyPath()


            override suspend fun create(data: PropertySet): AwsCredentials {
                return AwsCredentials(
                    scope = data.required<String>("scope"),
                    id = IDComponent.create(
                        data.required<String>("id")
                    ),
                    accessKey = data.required<String>("accessKey"),
                    secretKey = data.required<String>("secretKey"),
                    description = data.required<String>("description")
                )
            }
        }
    }

    class FileCredentialsFactory {
        context(Raise<PropertiesError>)
        companion object : PipelineDomainFactory<FileCredentials> {
            override val rootPath: PropertyPath = "pipeline.credentials.file".propertyPath()

            context(Raise<PropertiesError>)
            override suspend fun create(data: PropertySet): FileCredentials {
                return FileCredentials(
                    scope = data.required<String>("scope"),
                    id = IDComponent.create(
                        data.required<String>("id")
                    ),
                    fileName = data.required<String>("fileName"),
                    secretBytes = data.required<String>("secretBytes"),
                    description = data.required<String>("description")
                )
            }
        }
    }


    class CertificateCredentialsFactory {
        context(Raise<PropertiesError>)
        companion object : PipelineDomainFactory<CertificateCredentials> {
            override val rootPath: PropertyPath = "pipeline.credentials.certificate".propertyPath()

            context(Raise<PropertiesError>)
            override suspend fun create(data: PropertySet): CertificateCredentials {
                return CertificateCredentials(
                    scope = data.required<String>("scope"),
                    id = IDComponent.create(
                        data.required<String>("id")
                    ),
                    password = data.required<String>("password"),
                    description = data.required<String>("description"),
                    keyStore = data.required<String>("keyStoreSource.uploaded.uploadedKeystore")
                )
            }
        }
    }