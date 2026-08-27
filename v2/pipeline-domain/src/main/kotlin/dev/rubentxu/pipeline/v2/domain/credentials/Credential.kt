package dev.rubentxu.pipeline.v2.domain.credentials

import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CredentialsId

/**
 * Scope of a credential — determines where it can be used.
 *
 * Per Jenkins credentials-binding conventions:
 * - GLOBAL: usable across all pipelines and jobs
 * - SYSTEM: usable only within the same Jenkins master (agent nodes)
 *
 * ML-R6 uses GLOBAL exclusively. SYSTEM is reserved for future cycles.
 */
enum class CredentialScope {
    GLOBAL,
    SYSTEM
}

/**
 * Sealed hierarchy of typed credentials.
 *
 * Every credential has:
 * - [id]: The credential identifier (NOT secret material)
 * - [scope]: Where the credential can be used (GLOBAL/SYSTEM)
 *
 * Each concrete kind overrides [toString] to NEVER log secret bytes.
 * The [toString] output contains ONLY safe fields (id, scope, safe identifiers).
 * Secret bytes (passwords, private keys, file contents) are NEVER present in toString.
 *
 * This sealed hierarchy IS the kind system (INV-L6-CR-001).
 * Kind is the static type, never inferred from byte content.
 *
 * @see dev.rubentxu.pipeline.v2.credentials.api.SecretStore for the store interface
 */
sealed interface Credential {
    /**
     * The credential identifier.
     * This is NOT a secret — it is a public reference.
     */
    val id: CredentialsId

    /**
     * The scope where this credential can be used.
     */
    val scope: CredentialScope

    /**
     * Maps this credential kind to a [BoundPurpose] for audit trail.
     */
    val purpose: BoundPurpose
}

/**
 * Reference to another credential by ID.
 * Used for [SshPrivateKey.passphraseRef] and [Certificate.passwordRef].
 *
 * Resolving a LinkedSecretRef returns the referenced [SecretText] bytes.
 *
 * @param credentialsId The ID of the referenced credential
 */
data class LinkedSecretRef(val credentialsId: CredentialsId)

// ---------------------------------------------------------------------------
// Secret Text
// ---------------------------------------------------------------------------

/**
 * Secret text credential — a raw string secret.
 *
 * Example: API tokens, passwords without a specific structure.
 *
 * @param id The credential ID
 * @param scope The credential scope (default: GLOBAL)
 * @param bytes The secret text as UTF-8 bytes
 */
data class SecretText(
    override val id: CredentialsId,
    override val scope: CredentialScope = CredentialScope.GLOBAL,
    val bytes: ByteArray
) : Credential {
    override val purpose: BoundPurpose = BoundPurpose.API_KEY

    /**
     * Returns a safe string representation.
     * NEVER logs the actual secret bytes.
     */
    override fun toString(): String =
        "[SECRET SecretText(id=${id.value}, scope=$scope)]"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SecretText
        return id == other.id && scope == other.scope && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

// ---------------------------------------------------------------------------
// Username / Password
// ---------------------------------------------------------------------------

/**
 * Username/password pair credential.
 *
 * @param id The credential ID
 * @param scope The credential scope (default: GLOBAL)
 * @param username The username (NOT a secret)
 * @param password The password as UTF-8 bytes
 */
data class UsernamePassword(
    override val id: CredentialsId,
    override val scope: CredentialScope = CredentialScope.GLOBAL,
    val username: String,
    val password: ByteArray
) : Credential {
    override val purpose: BoundPurpose = BoundPurpose.USERNAME_PASSWORD

    /**
     * Returns a safe string representation.
     * NEVER logs the password bytes.
     */
    override fun toString(): String =
        "[SECRET UsernamePassword(id=${id.value}, scope=$scope, username=$username)]"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UsernamePassword
        return id == other.id &&
                scope == other.scope &&
                username == other.username &&
                password.contentEquals(other.password)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + password.contentHashCode()
        return result
    }
}

// ---------------------------------------------------------------------------
// SSH Private Key
// ---------------------------------------------------------------------------

/**
 * SSH private key with optional passphrase reference.
 *
 * @param id The credential ID
 * @param scope The credential scope (default: GLOBAL)
 * @param username The SSH username (e.g., "git")
 * @param privateKey The SSH private key bytes (PEM format)
 * @param passphraseRef Optional reference to a [SecretText] containing the passphrase
 */
data class SshPrivateKey(
    override val id: CredentialsId,
    override val scope: CredentialScope = CredentialScope.GLOBAL,
    val username: String,
    val privateKey: ByteArray,
    val passphraseRef: LinkedSecretRef? = null
) : Credential {
    override val purpose: BoundPurpose = BoundPurpose.SSH_KEY

    /**
     * Returns a safe string representation.
     * NEVER logs the private key bytes.
     */
    override fun toString(): String =
        "[SECRET SshPrivateKey(id=${id.value}, scope=$scope, username=$username, hasPassphrase=${passphraseRef != null})]"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SshPrivateKey
        return id == other.id &&
                scope == other.scope &&
                username == other.username &&
                privateKey.contentEquals(other.privateKey) &&
                passphraseRef == other.passphraseRef
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + privateKey.contentHashCode()
        result = 31 * result + (passphraseRef?.hashCode() ?: 0)
        return result
    }
}

// ---------------------------------------------------------------------------
// Secret File
// ---------------------------------------------------------------------------

/**
 * Secret file credential.
 *
 * @param id The credential ID
 * @param scope The credential scope (default: GLOBAL)
 * @param bytes The file contents bytes
 * @param originalName Optional original filename hint
 */
data class SecretFile(
    override val id: CredentialsId,
    override val scope: CredentialScope = CredentialScope.GLOBAL,
    val bytes: ByteArray,
    val originalName: String? = null
) : Credential {
    override val purpose: BoundPurpose = BoundPurpose.FILE

    /**
     * Returns a safe string representation.
     * NEVER logs the file contents bytes.
     */
    override fun toString(): String =
        "[SECRET SecretFile(id=${id.value}, scope=$scope, originalName=$originalName)]"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SecretFile
        return id == other.id &&
                scope == other.scope &&
                bytes.contentEquals(other.bytes) &&
                originalName == other.originalName
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + (originalName?.hashCode() ?: 0)
        return result
    }
}

// ---------------------------------------------------------------------------
// Certificate
// ---------------------------------------------------------------------------

/**
 * Certificate credential with optional password reference.
 *
 * @param id The credential ID
 * @param scope The credential scope (default: GLOBAL)
 * @param keystore The PKCS#12 keystore bytes
 * @param passwordRef Optional reference to a [SecretText] containing the keystore password
 * @param alias Optional key alias within the keystore
 */
data class Certificate(
    override val id: CredentialsId,
    override val scope: CredentialScope = CredentialScope.GLOBAL,
    val keystore: ByteArray,
    val passwordRef: LinkedSecretRef? = null,
    val alias: String? = null
) : Credential {
    override val purpose: BoundPurpose = BoundPurpose.CERTIFICATE

    /**
     * Returns a safe string representation.
     * NEVER logs the keystore bytes.
     */
    override fun toString(): String =
        "[SECRET Certificate(id=${id.value}, scope=$scope, alias=$alias, hasPassword=${passwordRef != null})]"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Certificate
        return id == other.id &&
                scope == other.scope &&
                keystore.contentEquals(other.keystore) &&
                passwordRef == other.passwordRef &&
                alias == other.alias
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + keystore.contentHashCode()
        result = 31 * result + (passwordRef?.hashCode() ?: 0)
        result = 31 * result + (alias?.hashCode() ?: 0)
        return result
    }
}

// ---------------------------------------------------------------------------
// ZIP Archive
// ---------------------------------------------------------------------------

/**
 * ZIP archive credential.
 *
 * @param id The credential ID
 * @param scope The credential scope (default: GLOBAL)
 * @param entries Map of entry name to uncompressed bytes
 */
data class Zip(
    override val id: CredentialsId,
    override val scope: CredentialScope = CredentialScope.GLOBAL,
    val entries: Map<String, ByteArray>
) : Credential {
    override val purpose: BoundPurpose = BoundPurpose.ZIP

    /**
     * Returns a safe string representation.
     * NEVER logs the ZIP entry bytes.
     */
    override fun toString(): String =
        "[SECRET Zip(id=${id.value}, scope=$scope, entryCount=${entries.size})]"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Zip
        return id == other.id &&
                scope == other.scope &&
                entries == other.entries
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + entries.hashCode()
        return result
    }
}

// ---------------------------------------------------------------------------
// Username:Password (colon-joined)
// ---------------------------------------------------------------------------

/**
 * Username:password credential (colon-joined format).
 *
 * Unlike [UsernamePassword], this stores a pre-joined "user:pass" string.
 * Used by Jenkins [usernameColonPassword][BoundPurpose.USERNAME_COLON_PASSWORD] binding.
 *
 * @param id The credential ID
 * @param scope The credential scope (default: GLOBAL)
 * @param user The username
 * @param pass The password
 */
data class UsernameColonPassword(
    override val id: CredentialsId,
    override val scope: CredentialScope = CredentialScope.GLOBAL,
    val user: String,
    val pass: ByteArray
) : Credential {
    override val purpose: BoundPurpose = BoundPurpose.USERNAME_COLON_PASSWORD

    /**
     * Returns a safe string representation.
     * NEVER logs the password bytes.
     */
    override fun toString(): String =
        "[SECRET UsernameColonPassword(id=${id.value}, scope=$scope, user=$user)]"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UsernameColonPassword
        return id == other.id &&
                scope == other.scope &&
                user == other.user &&
                pass.contentEquals(other.pass)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + user.hashCode()
        result = 31 * result + pass.contentHashCode()
        return result
    }
}
