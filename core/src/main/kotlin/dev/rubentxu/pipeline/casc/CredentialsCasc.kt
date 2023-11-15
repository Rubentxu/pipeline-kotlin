package dev.rubentxu.pipeline.casc


sealed class Credential
data class BasicSSHUserPrivateKey(
    val scope: String,
    val id: String,
    val username: String,
    val passphrase: String,
    val description: String,
    val privateKeySource: PrivateKeySource
) : Credential()

data class UsernamePassword(
    val scope: String,
    val id: String,
    val username: String,
    val password: String,
    val description: String
) : Credential()
data class StringCredential(
    val scope: String,
    val id: String,
    val secret: String,
    val description: String
) : Credential()

data class AwsCredential(
    val scope: String,
    val id: String,
    val accessKey: String,
    val secretKey: String,
    val description: String
) : Credential()

data class FileCredential(
    val scope: String,
    val id: String,
    val fileName: String,
    val secretBytes: String
) : Credential()

data class CertificateCredential(
    val scope: String,
    val id: String,
    val password: String,
    val description: String,
    val keyStoreSource: KeyStoreSource
) : Credential()

data class KeyStoreSource(
    val uploaded: UploadedKeystore
)

data class UploadedKeystore(
    val uploadedKeystore: String
)

data class PrivateKeySource(
    val directEntry: DirectEntry
)

data class DirectEntry(
    val privateKey: String
)