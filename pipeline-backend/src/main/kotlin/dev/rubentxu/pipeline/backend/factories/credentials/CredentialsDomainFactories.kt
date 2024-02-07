package dev.rubentxu.pipeline.backend.factories.credentials

import arrow.core.raise.result
import arrow.fx.coroutines.parMap
import dev.rubentxu.pipeline.backend.coroutines.parZipResult
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.credentials.*

class LocalCredentialsFactory {


    companion object : PipelineDomainFactory<List<Credentials>> {
        override val rootPath: String = "credentials"


        override suspend fun create(data: PropertySet): Result<List<Credentials>> {
            val credentialsProperties = getRootPropertySet(data)
            return parZipResult(
                { BasicSSHUserPrivateKeyFactory.create(credentialsProperties) },
                { UsernamePasswordFactory.create(credentialsProperties) },
                { StringCredentialsFatory.create(credentialsProperties) },
                { AwsCredentialsFactory.create(credentialsProperties) },
                { FileCredentialsFactory.create(credentialsProperties) },
                { CertificateCredentialsFactory.create(credentialsProperties) }
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


        override suspend fun create(data: PropertySet): Result<List<BasicSSHUserPrivateKey>> =
            result {
                getRootListPropertySet(data)
                    ?.parMap { properties ->
                        createBasicSSHUserPrivateKey(properties).bind()
                    } ?: emptyList()
            }

        suspend fun createBasicSSHUserPrivateKey(basicSSHUserPrivateKeyProperties: PropertySet): Result<BasicSSHUserPrivateKey> {
            return parZipResult(
                { basicSSHUserPrivateKeyProperties.required<String>("scope") },
                { basicSSHUserPrivateKeyProperties.required<String>("id") },
                { basicSSHUserPrivateKeyProperties.required<String>("username") },
                { basicSSHUserPrivateKeyProperties.required<String>("passphrase") },
                { basicSSHUserPrivateKeyProperties.required<String>("description") },
                { basicSSHUserPrivateKeyProperties.required<String>("privateKeySource.directEntry.privateKey") }
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


    companion object : PipelineDomainFactory<List<UsernamePassword>> {
        override val rootPath: String = "local[*].usernamePassword"

        override suspend fun create(data: PropertySet): Result<List<UsernamePassword>> = result {
            getRootListPropertySet(data)
                ?.parMap { properties ->
                    createUsernamePassword(properties).bind()
                } ?: emptyList()
        }

        suspend fun createUsernamePassword(usernamePasswordProperties: PropertySet): Result<UsernamePassword> {
            return parZipResult(
                { usernamePasswordProperties.required<String>("scope") },
                { usernamePasswordProperties.required<String>("id") },
                { usernamePasswordProperties.required<String>("username") },
                { usernamePasswordProperties.required<String>("password") },
                { usernamePasswordProperties.required<String>("description") }
            ) { scope, id, username, password, description ->
                UsernamePassword(
                    scope = scope,
                    id = IDComponent.create(id),
                    username = username,
                    password = password,
                    description = description
                )
            }
        }
    }
}

class StringCredentialsFatory {

    companion object : PipelineDomainFactory<List<StringCredentials>> {
        override val rootPath: String = "local[*].string"


        override suspend fun create(data: PropertySet): Result<List<StringCredentials>> = result {
            getRootListPropertySet(data)
                ?.parMap { properties ->
                    createStringCredentials(properties).bind()
                } ?: emptyList()
        }

        suspend fun createStringCredentials(stringCredentialsProperties: PropertySet): Result<StringCredentials> {
            return parZipResult(
                { stringCredentialsProperties.required<String>("scope") },
                { stringCredentialsProperties.required<String>("id") },
                { stringCredentialsProperties.required<String>("secret") },
                { stringCredentialsProperties.required<String>("description") }
            ) { scope, id, secret, description ->
                StringCredentials(
                    scope = scope,
                    id = IDComponent.create(id),
                    secret = secret,
                    description = description
                )
            }
        }
    }
}

class AwsCredentialsFactory {

    companion object : PipelineDomainFactory<List<AwsCredentials>> {
        override val rootPath: String = "local[*].aws"

        override suspend fun create(data: PropertySet): Result<List<AwsCredentials>> =
            result {
                getRootListPropertySet(data)
                    ?.parMap { properties ->
                        createAwsCredentials(properties).bind()
                    } ?: emptyList()
            }

        suspend fun createAwsCredentials(awsCredentialsProperties: PropertySet): Result<AwsCredentials> {
            return parZipResult(
                { awsCredentialsProperties.required<String>("scope") },
                { awsCredentialsProperties.required<String>("id") },
                { awsCredentialsProperties.required<String>("accessKey") },
                { awsCredentialsProperties.required<String>("secretKey") },
                { awsCredentialsProperties.required<String>("description") }
            ) { scope, id, accessKey, secretKey, description ->
                AwsCredentials(
                    scope = scope,
                    id = IDComponent.create(id),
                    accessKey = accessKey,
                    secretKey = secretKey,
                    description = description
                )
            }
        }
    }
}

class FileCredentialsFactory {

    companion object : PipelineDomainFactory<List<FileCredentials>> {
        override val rootPath: String = "local[*].file"

        override suspend fun create(data: PropertySet): Result<List<FileCredentials>> =
            result {
                getRootListPropertySet(data)
                    ?.parMap { properties ->
                        createFileCredentials(properties).bind()
                    } ?: emptyList()
            }

        suspend fun createFileCredentials(fileCredentialsProperties: PropertySet): Result<FileCredentials> {
            return parZipResult(
                { fileCredentialsProperties.required<String>("scope") },
                { fileCredentialsProperties.required<String>("id") },
                { fileCredentialsProperties.required<String>("fileName") },
                { fileCredentialsProperties.required<String>("secretBytes") },
                { fileCredentialsProperties.required<String>("description") }
            ) { scope, id, fileName, secretBytes, description ->
                FileCredentials(
                    scope = scope,
                    id = IDComponent.create(id),
                    fileName = fileName,
                    secretBytes = secretBytes,
                    description = description
                )
            }
        }
    }
}


class CertificateCredentialsFactory {

    companion object : PipelineDomainFactory<List<CertificateCredentials>> {
        override val rootPath: String = "local[*].certificate"


        override suspend fun create(data: PropertySet): Result<List<CertificateCredentials>> =
            result {
                getRootListPropertySet(data)
                    ?.parMap { properties ->
                        createCertificateCredentials(properties).bind()
                    } ?: emptyList()
            }


        suspend fun createCertificateCredentials(certificateCredentialsProperties: PropertySet): Result<CertificateCredentials> {
            return parZipResult(
                { certificateCredentialsProperties.required<String>("scope") },
                { certificateCredentialsProperties.required<String>("id") },
                { certificateCredentialsProperties.required<String>("password") },
                { certificateCredentialsProperties.required<String>("description") },
                { certificateCredentialsProperties.required<String>("keyStoreSource.uploaded.uploadedKeystore") }
            ) { scope, id, password, description, keyStore ->
                CertificateCredentials(
                    scope = scope,
                    id = IDComponent.create(id),
                    password = password,
                    description = description,
                    keyStore = keyStore
                )
            }
        }
    }
}