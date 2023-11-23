package dev.rubentxu.pipeline.casc

import dev.rubentxu.pipeline.validation.validateAndGet


sealed class Credential {
    abstract val id: String
    abstract val description: String

    companion object {
        fun fromMap(credentialMap: Map<String, Any>): Credential {
            return when (credentialMap?.keys?.first()) {
                "basicSSHUserPrivateKey" -> BasicSSHUserPrivateKey.fromMap(credentialMap.get(credentialMap?.keys?.first()) as Map<String, Any>)
                "usernamePassword" -> UsernamePassword.fromMap(credentialMap.get(credentialMap?.keys?.first()) as Map<String, Any>)
                "string" -> StringCredential.fromMap(credentialMap.get(credentialMap?.keys?.first()) as Map<String, Any>)
                "aws" -> AwsCredential.fromMap(credentialMap.get(credentialMap?.keys?.first()) as Map<String, Any>)
                "file" -> FileCredential.fromMap(credentialMap.get(credentialMap?.keys?.first()) as Map<String, Any>)
                "certificate" -> CertificateCredential.fromMap(credentialMap.get(credentialMap?.keys?.first()) as Map<String, Any>)
                else -> {
                    throw IllegalArgumentException("Invalid credential type for '${credentialMap?.keys?.first()}'")
                }
            }
        }
    }
}

data class BasicSSHUserPrivateKey(
    override val id: String,
    override val description: String,
    val scope: String,
    val username: String,
    val passphrase: String,
    val privateKey: String,
) : Credential() {
    companion object {
        fun fromMap(map: Map<String, Any>): BasicSSHUserPrivateKey {
            return BasicSSHUserPrivateKey(
                scope = map.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") as String,
                id = map.validateAndGet("id").isString().throwIfInvalid("id is required in BasicSSHUserPrivateKey"),
                username = map.validateAndGet("username").isString().throwIfInvalid("username is required in BasicSSHUserPrivateKey"),
                passphrase = map.validateAndGet("passphrase").isString().defaultValueIfInvalid("") as String,
                description = map.validateAndGet("description").isString().defaultValueIfInvalid("") as String,
                privateKey = map.validateAndGet("privateKeySource.directEntry.privateKey").isString().throwIfInvalid("privateKey is required in BasicSSHUserPrivateKey")
            )
        }
    }
}

data class UsernamePassword(
    val scope: String,
    override val id: String,
    override val description: String,
    val username: String,
    val password: String,
) : Credential() {
    companion object {
        fun fromMap(map: Map<String, Any>): Credential {
            return UsernamePassword(
                scope = map.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") as String,
                id = map.validateAndGet("id").isString().throwIfInvalid("id is required in UsernamePassword"),
                username = map.validateAndGet("username").isString().throwIfInvalid("username is required in UsernamePassword"),
                password = map.validateAndGet("password").isString().throwIfInvalid("password is required in UsernamePassword"),
                description = map.validateAndGet("description").isString().defaultValueIfInvalid("") as String
            )
        }
    }
}

data class StringCredential(
    val scope: String,
    override val id: String,
    override val description: String,
    val secret: String,
) : Credential() {
    companion object {
        fun fromMap(map: Map<String, Any>): Credential {
            return StringCredential(
                scope = map.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") as String,
                id = map.validateAndGet("id").isString().throwIfInvalid("id is required in StringCredential"),
                secret = map.validateAndGet("secret").isString().throwIfInvalid("secret is required in StringCredential"),
                description = map.validateAndGet("description").isString().defaultValueIfInvalid("") as String
            )
        }
    }
}

data class AwsCredential(
    val scope: String,
    override val id: String,
    override val description: String,
    val accessKey: String,
    val secretKey: String,
) : Credential() {
    companion object {
        fun fromMap(map: Map<String, Any>): Credential {
            return AwsCredential(
                scope = map.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") as String,
                id = map.validateAndGet("id").isString().throwIfInvalid("id is required in AwsCredential"),
                accessKey = map.validateAndGet("accessKey").isString().throwIfInvalid("accessKey is required in AwsCredential"),
                secretKey = map.validateAndGet("secretKey").isString().throwIfInvalid("secretKey is required in AwsCredential"),
                description = map.validateAndGet("description").isString().defaultValueIfInvalid("") as String
            )
        }
    }
}

data class FileCredential(
    val scope: String,
    override val id: String,
    override val description: String,
    val fileName: String,
    val secretBytes: String
) : Credential() {
    companion object {
        fun fromMap(map: Map<String, Any>): Credential {
            return FileCredential(
                scope = map.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") as String,
                id = map.validateAndGet("id").isString().throwIfInvalid("id is required in FileCredential"),
                fileName = map.validateAndGet("fileName").isString().throwIfInvalid("fileName is required in FileCredential"),
                secretBytes = map.validateAndGet("secretBytes").isString().throwIfInvalid("secretBytes is required in FileCredential"),
                description = map.validateAndGet("description").isString().defaultValueIfInvalid("") as String
            )
        }
    }
}

data class CertificateCredential(
    val scope: String,
    override val id: String,
    override val description: String,
    val password: String,
    val keyStore: String
) : Credential() {
    companion object {
        fun fromMap(map: Map<String, Any>): Credential {
            return CertificateCredential(
                scope = map.validateAndGet("scope").isString().defaultValueIfInvalid("GLOBAL") as String,
                id = map.validateAndGet("id").isString().throwIfInvalid("id is required in CertificateCredential"),
                password = map.validateAndGet("password").isString().throwIfInvalid("password is required in CertificateCredential"),
                description = map.validateAndGet("description").isString().defaultValueIfInvalid("") as String,
                keyStore = map.validateAndGet("keyStoreSource.uploaded.uploadedKeystore").isString().throwIfInvalid("keyStore is required in CertificateCredential")
            )
        }
    }
}