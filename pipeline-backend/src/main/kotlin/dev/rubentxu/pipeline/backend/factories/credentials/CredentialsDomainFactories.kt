package dev.rubentxu.pipeline.backend.factories.credentials


import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.credentials.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class LocalCredentialsFactory {


    companion object : PipelineDomainFactory<List<Credentials>> {
        override val rootPath: String = "credentials"


        override suspend fun create(data: PropertySet): Result<List<Credentials>> = runCatching {
            val credentialsProperties = getRootPropertySet(data)
            coroutineScope {
                val basicSSHUserPrivateKey =
                    async { BasicSSHUserPrivateKeyFactory.create(credentialsProperties).getOrThrow() }
                val usernamePassword = async { UsernamePasswordFactory.create(credentialsProperties).getOrThrow() }
                val stringCredentials = async { StringCredentialsFatory.create(credentialsProperties).getOrThrow() }
                val awsCredentials = async { AwsCredentialsFactory.create(credentialsProperties).getOrThrow() }
                val fileCredentials = async { FileCredentialsFactory.create(credentialsProperties).getOrThrow() }
                val certificateCredentials =
                    async { CertificateCredentialsFactory.create(credentialsProperties).getOrThrow() }

                buildList {
                    addAll(basicSSHUserPrivateKey.await())
                    addAll(usernamePassword.await())
                    addAll(stringCredentials.await())
                    addAll(awsCredentials.await())
                    addAll(fileCredentials.await())
                    addAll(certificateCredentials.await())
                }
            }
        }

    }
}

class BasicSSHUserPrivateKeyFactory {

    companion object : PipelineDomainFactory<List<BasicSSHUserPrivateKey>> {
        override val rootPath: String = "local[*].basicSSHUserPrivateKey"


        override suspend fun create(data: PropertySet): Result<List<BasicSSHUserPrivateKey>> = runCatching {
            val basicSSHUserPrivateKeyProperties = getRootListPropertySet(data) ?: emptyList()
            coroutineScope {
                val basicSSHUserPrivateKeys = basicSSHUserPrivateKeyProperties.map { properties ->
                    async { createBasicSSHUserPrivateKey(properties) }
                }
                basicSSHUserPrivateKeys.awaitAll()
            }
        }

        suspend fun createBasicSSHUserPrivateKey(basicSSHUserPrivateKeyProperties: PropertySet): BasicSSHUserPrivateKey {

            val scope = basicSSHUserPrivateKeyProperties.required<String>("scope").getOrThrow()
            val id = basicSSHUserPrivateKeyProperties.required<String>("id").getOrThrow()
            val username = basicSSHUserPrivateKeyProperties.required<String>("username").getOrThrow()
            val passphrase = basicSSHUserPrivateKeyProperties.required<String>("passphrase").getOrThrow()
            val description = basicSSHUserPrivateKeyProperties.required<String>("description").getOrThrow()
            val privateKey =
                basicSSHUserPrivateKeyProperties.required<String>("privateKeySource.directEntry.privateKey")
                    .getOrThrow()

            return BasicSSHUserPrivateKey(
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


    companion object : PipelineDomainFactory<List<UsernamePassword>> {
        override val rootPath: String = "local[*].usernamePassword"

        override suspend fun create(data: PropertySet): Result<List<UsernamePassword>> = runCatching {
            val usernamePasswordProperties = getRootListPropertySet(data) ?: emptyList()
            coroutineScope {
                val usernamePasswords = usernamePasswordProperties.map { properties ->
                    async { createUsernamePassword(properties) }
                }
                usernamePasswords.awaitAll()
            }
        }

        suspend fun createUsernamePassword(usernamePasswordProperties: PropertySet): UsernamePassword {

            val scope = usernamePasswordProperties.required<String>("scope").getOrThrow()
            val id = usernamePasswordProperties.required<String>("id").getOrThrow()
            val username = usernamePasswordProperties.required<String>("username").getOrThrow()
            val password = usernamePasswordProperties.required<String>("password").getOrThrow()
            val description = usernamePasswordProperties.required<String>("description").getOrThrow()

            return UsernamePassword(
                scope = scope,
                id = IDComponent.create(id),
                username = username,
                password = password,
                description = description
            )
        }
    }
}


class StringCredentialsFatory {

    companion object : PipelineDomainFactory<List<StringCredentials>> {
        override val rootPath: String = "local[*].string"


        override suspend fun create(data: PropertySet): Result<List<StringCredentials>> = runCatching {
            val stringCredentialsProperties = getRootListPropertySet(data) ?: emptyList()
            coroutineScope {
                val stringCredentials = stringCredentialsProperties.map { properties ->
                    async { createStringCredentials(properties) }
                }
                stringCredentials.awaitAll()
            }
        }

        suspend fun createStringCredentials(stringCredentialsProperties: PropertySet): StringCredentials {
            val scope = stringCredentialsProperties.required<String>("scope").getOrThrow()
            val id = stringCredentialsProperties.required<String>("id").getOrThrow()
            val secret = stringCredentialsProperties.required<String>("secret").getOrThrow()
            val description = stringCredentialsProperties.required<String>("description").getOrThrow()

            return StringCredentials(
                scope = scope,
                id = IDComponent.create(id),
                secret = secret,
                description = description
            )
        }
    }
}

class AwsCredentialsFactory {

    companion object : PipelineDomainFactory<List<AwsCredentials>> {
        override val rootPath: String = "local[*].aws"

        override suspend fun create(data: PropertySet): Result<List<AwsCredentials>> = runCatching {
            val awsCredentialsProperties = getRootListPropertySet(data) ?: emptyList()
            coroutineScope {
                val awsCredentials = awsCredentialsProperties.map { properties ->
                    async { createAwsCredentials(properties) }
                }
                awsCredentials.awaitAll()
            }
        }

        suspend fun createAwsCredentials(awsCredentialsProperties: PropertySet): AwsCredentials {
            val scope = awsCredentialsProperties.required<String>("scope").getOrThrow()
            val id = awsCredentialsProperties.required<String>("id").getOrThrow()
            val accessKey = awsCredentialsProperties.required<String>("accessKey").getOrThrow()
            val secretKey = awsCredentialsProperties.required<String>("secretKey").getOrThrow()
            val description = awsCredentialsProperties.required<String>("description").getOrThrow()

            return AwsCredentials(
                scope = scope,
                id = IDComponent.create(id),
                accessKey = accessKey,
                secretKey = secretKey,
                description = description
            )
        }
    }
}

class FileCredentialsFactory {

    companion object : PipelineDomainFactory<List<FileCredentials>> {
        override val rootPath: String = "local[*].file"

        override suspend fun create(data: PropertySet): Result<List<FileCredentials>> = runCatching {
            val fileCredentialsProperties = getRootListPropertySet(data) ?: emptyList()
            coroutineScope {
                val fileCredentials = fileCredentialsProperties.map { properties ->
                    async { createFileCredentials(properties) }
                }
                fileCredentials.awaitAll()
            }
        }

        suspend fun createFileCredentials(fileCredentialsProperties: PropertySet): FileCredentials {
            val scope = fileCredentialsProperties.required<String>("scope").getOrThrow()
            val id = fileCredentialsProperties.required<String>("id").getOrThrow()
            val fileName = fileCredentialsProperties.required<String>("fileName").getOrThrow()
            val secretBytes = fileCredentialsProperties.required<String>("secretBytes").getOrThrow()
            val description = fileCredentialsProperties.required<String>("description").getOrThrow()

            return FileCredentials(
                scope = scope,
                id = IDComponent.create(id),
                fileName = fileName,
                secretBytes = secretBytes,
                description = description
            )
        }
    }
}

class CertificateCredentialsFactory {

    companion object : PipelineDomainFactory<List<CertificateCredentials>> {
        override val rootPath: String = "local[*].certificate"

        override suspend fun create(data: PropertySet): Result<List<CertificateCredentials>> = runCatching {
            val certificateCredentialsProperties = getRootListPropertySet(data) ?: emptyList()
            coroutineScope {
                val certificateCredentials = certificateCredentialsProperties.map { properties ->
                    async { createCertificateCredentials(properties) }
                }
                certificateCredentials.awaitAll()
            }
        }

        suspend fun createCertificateCredentials(certificateCredentialsProperties: PropertySet): CertificateCredentials {
            val scope = certificateCredentialsProperties.required<String>("scope").getOrThrow()
            val id = certificateCredentialsProperties.required<String>("id").getOrThrow()
            val password = certificateCredentialsProperties.required<String>("password").getOrThrow()
            val description = certificateCredentialsProperties.required<String>("description").getOrThrow()
            val keyStore = certificateCredentialsProperties.required<String>("keyStoreSource.uploaded.uploadedKeystore")
                .getOrThrow()

            return CertificateCredentials(
                scope = scope,
                id = IDComponent.create(id),
                password = password,
                description = description,
                keyStore = keyStore
            )
        }
    }
}