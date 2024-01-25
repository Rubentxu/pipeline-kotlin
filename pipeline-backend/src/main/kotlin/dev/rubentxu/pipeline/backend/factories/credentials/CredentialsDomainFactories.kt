package dev.rubentxu.pipeline.backend.factories.credentials

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.fx.coroutines.parMap
import arrow.fx.coroutines.parZip
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertyPath
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.propertyPath
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PropertiesError
import dev.rubentxu.pipeline.model.credentials.*

class LocalCredentialsFactory {

    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<Credentials>> {
        override val rootPath: String = "credentials"

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<Credentials> {
            val properties = getRootPropertySet(data)
            return createCredential(properties).bind()
        }


        private suspend fun createCredential(credentialConfig: PropertySet): Either<PropertiesError, List<Credentials>> =
            either {
                parZip(
                    { BasicSSHUserPrivateKeyFactory.create(credentialConfig) },
                    { UsernamePasswordFactory.create(credentialConfig) },
                    { StringCredentialsFatory.create(credentialConfig) },
                    { AwsCredentialsFactory.create(credentialConfig) },
                    { FileCredentialsFactory.create(credentialConfig) },
                    { CertificateCredentialsFactory.create(credentialConfig) }
                ) {
                        basicSSHUserPrivateKey,
                        usernamePassword,
                        stringCredentials,
                        awsCredentials,
                        fileCredentials,
                        certificateCredentials,
                    ->
                    buildList {
                        addAll(basicSSHUserPrivateKey)
                        addAll(usernamePassword)
                        addAll(stringCredentials)
                        addAll(awsCredentials)
                        addAll(fileCredentials)
                        addAll(certificateCredentials)
                    }

                }
            }
    }
}

class BasicSSHUserPrivateKeyFactory {

    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<BasicSSHUserPrivateKey>> {
        override val rootPath: String = "local[*].basicSSHUserPrivateKey"

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<BasicSSHUserPrivateKey> =
            getRootListPropertySet(data)
                .parMap { properties ->
                    BasicSSHUserPrivateKey(
                        scope = properties.required<String>("scope"),
                        id = IDComponent.create(properties.required<String>("id")),
                        username = properties.required<String>("username"),
                        passphrase = properties.required<String>("passphrase"),
                        description = properties.required<String>("description"),
                        privateKey = properties.required<String>("privateKeySource.directEntry.privateKey")
                    )
                }
    }
}

class UsernamePasswordFactory {

    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<UsernamePassword>> {
        override val rootPath: String = "local[*].usernamePassword"

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<UsernamePassword> {
            return getRootListPropertySet(data)
                .parMap { properties ->
                    UsernamePassword(
                        scope = properties.required<String>("scope"),
                        id = IDComponent.create(
                            properties.required<String>("id")
                        ),
                        username = properties.required<String>("username"),
                        password = properties.required<String>("password"),
                        description = properties.required<String>("description")
                    )
                }

        }
    }
}

class StringCredentialsFatory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<StringCredentials>> {
        override val rootPath: String = "local[*].string"

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<StringCredentials> {
            return getRootListPropertySet(data)
                .parMap { properties ->
                    StringCredentials(
                        scope = properties.required<String>("scope"),
                        id = IDComponent.create(
                            properties.required<String>("id")
                        ),
                        secret = properties.required<String>("secret"),
                        description = properties.required<String>("description")
                    )
                }
        }

    }
}

class AwsCredentialsFactory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<AwsCredentials>> {
        override val rootPath: String = "local[*].aws"

        override suspend fun create(data: PropertySet): List<AwsCredentials> {
            return getRootListPropertySet(data)
                .parMap { properties ->
                    AwsCredentials(
                        scope = properties.required<String>("scope"),
                        id = IDComponent.create(
                            properties.required<String>("id")
                        ),
                        accessKey = properties.required<String>("accessKey"),
                        secretKey = properties.required<String>("secretKey"),
                        description = properties.required<String>("description")
                    )
                }
        }
    }
}

class FileCredentialsFactory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<FileCredentials>> {
        override val rootPath: String = "local[*].file"

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<FileCredentials> {
            return getRootListPropertySet(data)
                .parMap { properties ->
                    FileCredentials(
                        scope = properties.required<String>("scope"),
                        id = IDComponent.create(
                            properties.required<String>("id")
                        ),
                        fileName = properties.required<String>("fileName"),
                        secretBytes = properties.required<String>("secretBytes"),
                        description = properties.required<String>("description")
                    )
                }
        }
    }
}


class CertificateCredentialsFactory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<CertificateCredentials>> {
        override val rootPath: String = "local[*].certificate"

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<CertificateCredentials> {
            return getRootListPropertySet(data)
                .parMap { properties ->
                    CertificateCredentials(
                        scope = properties.required<String>("scope"),
                        id = IDComponent.create(
                            properties.required<String>("id")
                        ),
                        password = properties.required<String>("password"),
                        description = properties.required<String>("description"),
                        keyStore = properties.required<String>("keyStoreSource.uploaded.uploadedKeystore")
                    )
                }
        }
    }
}