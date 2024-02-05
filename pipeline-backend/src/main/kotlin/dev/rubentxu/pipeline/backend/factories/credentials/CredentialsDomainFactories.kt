package dev.rubentxu.pipeline.backend.factories.credentials

import arrow.core.raise.either
import arrow.fx.coroutines.parMap
import arrow.fx.coroutines.parZip
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.Res
import dev.rubentxu.pipeline.model.credentials.*

class LocalCredentialsFactory {


    companion object : PipelineDomainFactory<List<Credentials>> {
        override val rootPath: String = "credentials"


        override suspend fun create(data: PropertySet): Res<List<Credentials>> =
            either {
                val credentialsProperties = getRootPropertySet(data)
                parZip(
                    { BasicSSHUserPrivateKeyFactory.create(credentialsProperties).bind() },
                    { UsernamePasswordFactory.create(credentialsProperties).bind() },
                    { StringCredentialsFatory.create(credentialsProperties).bind() },
                    { AwsCredentialsFactory.create(credentialsProperties).bind() },
                    { FileCredentialsFactory.create(credentialsProperties).bind() },
                    { CertificateCredentialsFactory.create(credentialsProperties).bind() }
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

    companion object : PipelineDomainFactory<List<BasicSSHUserPrivateKey>> {
        override val rootPath: String = "local[*].basicSSHUserPrivateKey"


        override suspend fun create(data: PropertySet): Res<List<BasicSSHUserPrivateKey>> =
            either {
                getRootListPropertySet(data)
                    ?.parMap { properties ->
                        BasicSSHUserPrivateKey(
                            scope = properties.required<String>("scope"),
                            id = IDComponent.create(properties.required<String>("id")),
                            username = properties.required<String>("username"),
                            passphrase = properties.required<String>("passphrase"),
                            description = properties.required<String>("description"),
                            privateKey = properties.required<String>("privateKeySource.directEntry.privateKey")
                        )
                    }?: emptyList()
            }
    }
}

class UsernamePasswordFactory {


    companion object : PipelineDomainFactory<List<UsernamePassword>> {
        override val rootPath: String = "local[*].usernamePassword"

        override suspend fun create(data: PropertySet): Res<List<UsernamePassword>> =
            either {
                getRootListPropertySet(data)
                    ?.parMap { properties ->
                        UsernamePassword(
                            scope = properties.required<String>("scope"),
                            id = IDComponent.create(
                                properties.required<String>("id")
                            ),
                            username = properties.required<String>("username"),
                            password = properties.required<String>("password"),
                            description = properties.required<String>("description")
                        )
                    }?: emptyList()
            }
    }
}

class StringCredentialsFatory {

    companion object : PipelineDomainFactory<List<StringCredentials>> {
        override val rootPath: String = "local[*].string"


        override suspend fun create(data: PropertySet): Res<List<StringCredentials>> =
            either {
                getRootListPropertySet(data)
                    ?.parMap { properties ->
                        StringCredentials(
                            scope = properties.required<String>("scope"),
                            id = IDComponent.create(
                                properties.required<String>("id")
                            ),
                            secret = properties.required<String>("secret"),
                            description = properties.required<String>("description")
                        )
                    }?: emptyList()
            }
    }
}

class AwsCredentialsFactory {

    companion object : PipelineDomainFactory<List<AwsCredentials>> {
        override val rootPath: String = "local[*].aws"

        override suspend fun create(data: PropertySet): Res<List<AwsCredentials>> =
            either {
                getRootListPropertySet(data)
                    ?.parMap { properties ->
                        AwsCredentials(
                            scope = properties.required<String>("scope"),
                            id = IDComponent.create(
                                properties.required<String>("id")
                            ),
                            accessKey = properties.required<String>("accessKey"),
                            secretKey = properties.required<String>("secretKey"),
                            description = properties.required<String>("description")
                        )
                    }?: emptyList()
            }
    }
}

class FileCredentialsFactory {

    companion object : PipelineDomainFactory<List<FileCredentials>> {
        override val rootPath: String = "local[*].file"

        override suspend fun create(data: PropertySet): Res<List<FileCredentials>> =
            either {
                getRootListPropertySet(data)
                    ?.parMap { properties ->
                        FileCredentials(
                            scope = properties.required<String>("scope"),
                            id = IDComponent.create(
                                properties.required<String>("id")
                            ),
                            fileName = properties.required<String>("fileName"),
                            secretBytes = properties.required<String>("secretBytes"),
                            description = properties.required<String>("description")
                        )
                    }?: emptyList()
            }
    }
}


class CertificateCredentialsFactory {

    companion object : PipelineDomainFactory<List<CertificateCredentials>> {
        override val rootPath: String = "local[*].certificate"


        override suspend fun create(data: PropertySet): Res<List<CertificateCredentials>> =
            either {
                getRootListPropertySet(data)
                    ?.parMap { properties ->
                        CertificateCredentials(
                            scope = properties.required<String>("scope"),
                            id = IDComponent.create(
                                properties.required<String>("id")
                            ),
                            password = properties.required<String>("password"),
                            description = properties.required<String>("description"),
                            keyStore = properties.required<String>("keyStoreSource.uploaded.uploadedKeystore")
                        )
                    }?: emptyList()
            }
    }
}