package dev.rubentxu.pipeline.v2.binding

import dev.rubentxu.pipeline.v2.domain.CredentialsId

/**
 * Sealed interface for all credential binding types.
 * Each binding type corresponds to a Jenkins credential kind.
 *
 * Jenkins verbatim parameter order is preserved in data class constructors
 * (per JENKINS_FAMILIARITY_CATALOG.md §1.6 lines 109-115).
 * Ergonomic factory methods in companion objects provide credentialsId-first form.
 */
sealed interface CredentialsBinding {
    val credentialsId: CredentialsId
    val kind: String
}

/**
 * String binding - maps to Jenkins "Secret text" credential.
 * Jenkins verbatim: credentialsId, variable
 */
data class StringBinding(
    override val credentialsId: CredentialsId,
    val variable: String
) : CredentialsBinding {
    override val kind: String = "string"
}

/**
 * Username/Password binding - maps to Jenkins "Username with password" credential.
 * Jenkins verbatim: credentialsId, usernameVariable, passwordVariable
 */
data class UsernamePasswordBinding(
    override val credentialsId: CredentialsId,
    val usernameVariable: String,
    val passwordVariable: String
) : CredentialsBinding {
    override val kind: String = "usernamePassword"
}

/**
 * SSH User Private Key binding - maps to Jenkins "SSH Username with private key" credential.
 * Jenkins verbatim: credentialsId, keyFileVariable, passphraseVariable=null, usernameVariable=null
 * Note: keyFileVariable is REQUIRED per Jenkins catalog
 */
data class SshUserPrivateKeyBinding(
    override val credentialsId: CredentialsId,
    val keyFileVariable: String,
    val passphraseVariable: String? = null,
    val usernameVariable: String? = null
) : CredentialsBinding {
    override val kind: String = "sshUserPrivateKey"
}

/**
 * File binding - maps to Jenkins "File" credential.
 * Jenkins verbatim: credentialsId, variable
 */
data class FileBinding(
    override val credentialsId: CredentialsId,
    val variable: String
) : CredentialsBinding {
    override val kind: String = "file"
}

/**
 * Certificate binding - maps to Jenkins "Certificate" credential.
 * Jenkins verbatim: keystoreVariable, credentialsId, aliasVariable=null, passwordVariable=null
 * Note: Jenkins uses keystoreVariable FIRST (not credentialsId-first)
 */
data class CertificateBinding(
    val keystoreVariable: String,
    override val credentialsId: CredentialsId,
    val aliasVariable: String? = null,
    val passwordVariable: String? = null
) : CredentialsBinding {
    override val kind: String = "certificate"
}

/**
 * Zip binding - maps to Jenkins "Zip" credential (if supported).
 * Jenkins verbatim: variable, credentialsId
 */
data class ZipBinding(
    val variable: String,
    override val credentialsId: CredentialsId
) : CredentialsBinding {
    override val kind: String = "zip"
}

/**
 * Username:Password binding - maps to Jenkins "Username Colon Password" credential.
 * Jenkins verbatim: variable, credentialsId
 */
data class UsernameColonPasswordBinding(
    val variable: String,
    override val credentialsId: CredentialsId
) : CredentialsBinding {
    override val kind: String = "usernameColonPassword"
}

// ==================== Ergonomic Factory Methods ====================

/**
 * Factory methods providing ergonomic credentialsId-first form.
 */
object CredentialsBindingFactory {

    /**
     * Creates a StringBinding with credentialsId-first parameter order.
     */
    fun string(credentialsId: CredentialsId, variable: String): StringBinding {
        return StringBinding(credentialsId, variable)
    }

    /**
     * Creates a UsernamePasswordBinding with credentialsId-first parameter order.
     */
    fun usernamePassword(
        credentialsId: CredentialsId,
        usernameVariable: String,
        passwordVariable: String
    ): UsernamePasswordBinding {
        return UsernamePasswordBinding(credentialsId, usernameVariable, passwordVariable)
    }

    /**
     * Creates a SshUserPrivateKeyBinding with credentialsId-first parameter order.
     * Note: keyFileVariable is still required (per Jenkins requirement).
     */
    fun sshUserPrivateKey(
        credentialsId: CredentialsId,
        keyFileVariable: String,
        passphraseVariable: String? = null,
        usernameVariable: String? = null
    ): SshUserPrivateKeyBinding {
        return SshUserPrivateKeyBinding(credentialsId, keyFileVariable, passphraseVariable, usernameVariable)
    }

    /**
     * Creates a FileBinding with credentialsId-first parameter order.
     */
    fun file(credentialsId: CredentialsId, variable: String): FileBinding {
        return FileBinding(credentialsId, variable)
    }

    /**
     * Creates a CertificateBinding.
     * Note: Jenkins verbatim order is keystoreVariable, credentialsId - this factory
     * maintains that order for clarity.
     */
    fun certificate(
        keystoreVariable: String,
        credentialsId: CredentialsId,
        aliasVariable: String? = null,
        passwordVariable: String? = null
    ): CertificateBinding {
        return CertificateBinding(keystoreVariable, credentialsId, aliasVariable, passwordVariable)
    }

    /**
     * Creates a ZipBinding.
     * Note: Jenkins verbatim order is variable, credentialsId.
     */
    fun zip(variable: String, credentialsId: CredentialsId): ZipBinding {
        return ZipBinding(variable, credentialsId)
    }

    /**
     * Creates a UsernameColonPasswordBinding.
     * Note: Jenkins verbatim order is variable, credentialsId.
     */
    fun usernameColonPassword(variable: String, credentialsId: CredentialsId): UsernameColonPasswordBinding {
        return UsernameColonPasswordBinding(variable, credentialsId)
    }
}
