package dev.rubentxu.pipeline.model.credentials

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomain


interface Credentials : PipelineDomain {
    val id: IDComponent
    val description: String
    val scope: String
}

data class BasicSSHUserPrivateKey(
    override val id: IDComponent,
    override val description: String,
    override val scope: String,
    val username: String,
    val passphrase: String,
    val privateKey: String,
) : Credentials

data class UsernamePassword(
    override val scope: String,
    override val id: IDComponent,
    override val description: String,
    val username: String,
    val password: String,
) : Credentials

data class StringCredentials(
    override val scope: String,
    override val id: IDComponent,
    override val description: String,
    val secret: String,
) : Credentials

data class AwsCredentials(
    override val scope: String,
    override val id: IDComponent,
    override val description: String,
    val accessKey: String,
    val secretKey: String,
) : Credentials

data class FileCredentials(
    override val scope: String,
    override val id: IDComponent,
    override val description: String,
    val fileName: String,
    val secretBytes: String,
) : Credentials

data class CertificateCredentials(
    override val scope: String,
    override val id: IDComponent,
    override val description: String,
    val password: String,
    val keyStore: String,
) : Credentials