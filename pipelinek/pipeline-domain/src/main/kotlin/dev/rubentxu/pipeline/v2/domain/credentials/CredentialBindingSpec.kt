package dev.rubentxu.pipeline.v2.domain.credentials

import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CredentialsId

/**
 * Sealed specification describing HOW a [Credential] is bound to environment
 * variables for a `withCredentials` step.
 *
 * This is the LF-0401 typed binding model. It replaces the flat DSL
 * `StepSpec.CredentialsBinding` (which is a single data class with 7 nullable
 * fields) and the four-way duplicate in `:pipeline-binding-factory` /
 * `:pipeline-credentials-api` / `:pipeline-credentials-executor`.
 *
 * ## Why a sealed hierarchy (INV-L6-CR-001)
 *
 * The kind is a static type, not a runtime tag. Each subtype carries only the
 * fields that ARE valid for that binding — so a `StringBinding` cannot mention
 * a `usernameVariable`, and the JVM / Kotlin compiler enforces it.
 *
 * The sealed shape mirrors the Jenkins Credentials Binding Plugin (see
 * `JENKINS_FAMILIARITY_CATALOG.md` §1.6 lines 109-115). Field order on each
 * constructor is Jenkins-verbatim; the ergonomic factory methods in the
 * companion object preserve credentialsId-first ordering for call sites.
 *
 * ## Module placement
 *
 * Lives in `:pipeline-domain` so that:
 *  - [CredentialProjection][dev.rubentxu.pipeline.v2.domain.credentials.CredentialProjection]
 *    (which consumes the spec) can sit in domain.
 *  - `:pipeline-credentials-executor` can depend on the domain type instead of
 *    the DSL type (inverts the prior direction).
 *  - `:pipeline-binding-factory` becomes redundant (delete it).
 *
 * @see Credential for the corresponding sealed hierarchy of typed credentials.
 */
sealed interface CredentialBindingSpec {
    val credentialsId: CredentialsId
    val kind: String
}

/**
 * String binding — maps to Jenkins "Secret text" credential.
 * Jenkins verbatim field order: credentialsId, variable.
 */
data class StringBindingSpec(
    override val credentialsId: CredentialsId,
    val variable: String,
) : CredentialBindingSpec {
    override val kind: String = "string"
}

/**
 * Username/password binding — maps to Jenkins "Username with password" credential.
 * Jenkins verbatim field order: credentialsId, usernameVariable, passwordVariable.
 *
 * Two different env vars are injected — usernameVariable and passwordVariable are
 * NOT the same handle (see [dev.rubentxu.pipeline.v2.domain.credentials.CredentialProjection]).
 */
data class UsernamePasswordBindingSpec(
    override val credentialsId: CredentialsId,
    val usernameVariable: String,
    val passwordVariable: String,
) : CredentialBindingSpec {
    override val kind: String = "usernamePassword"
}

/**
 * SSH user private key binding — maps to Jenkins "SSH Username with private key" credential.
 * Jenkins verbatim field order: credentialsId, keyFileVariable, passphraseVariable?, usernameVariable?.
 *
 * Three DIFFERENT handles are injected:
 *  - keyFileVariable: path to the materialized private key file
 *  - passphraseVariable: the passphrase (if present) — wrapped via a `SecretHandle.secret`
 *  - usernameVariable: the SSH username (NOT secret, but masked)
 *
 * The legacy bug where all three received the SAME masked file-path handle is fixed
 * by [dev.rubentxu.pipeline.v2.domain.credentials.DefaultCredentialProjector].
 */
data class SshUserPrivateKeyBindingSpec(
    override val credentialsId: CredentialsId,
    val keyFileVariable: String,
    val passphraseVariable: String? = null,
    val usernameVariable: String? = null,
) : CredentialBindingSpec {
    override val kind: String = "sshUserPrivateKey"
}

/**
 * File binding — maps to Jenkins "File" credential.
 * Jenkins verbatim field order: credentialsId, variable.
 */
data class FileBindingSpec(
    override val credentialsId: CredentialsId,
    val variable: String,
) : CredentialBindingSpec {
    override val kind: String = "file"
}

/**
 * Certificate binding — maps to Jenkins "Certificate" credential.
 * Jenkins verbatim field order: keystoreVariable, credentialsId, aliasVariable?, passwordVariable?.
 * (Note: Jenkins uses keystoreVariable FIRST.)
 *
 * Three DIFFERENT handles are injected (see SshUserPrivateKeyBindingSpec rationale).
 */
data class CertificateBindingSpec(
    val keystoreVariable: String,
    override val credentialsId: CredentialsId,
    val aliasVariable: String? = null,
    val passwordVariable: String? = null,
) : CredentialBindingSpec {
    override val kind: String = "certificate"
}

/**
 * Zip binding — maps to Jenkins "Zip" credential.
 * Jenkins verbatim field order: variable, credentialsId.
 */
data class ZipBindingSpec(
    val variable: String,
    override val credentialsId: CredentialsId,
) : CredentialBindingSpec {
    override val kind: String = "zip"
}

/**
 * Username:password binding — maps to Jenkins "Username Colon Password" credential.
 * Jenkins verbatim field order: variable, credentialsId.
 */
data class UsernameColonPasswordBindingSpec(
    val variable: String,
    override val credentialsId: CredentialsId,
) : CredentialBindingSpec {
    override val kind: String = "usernameColonPassword"
}

/**
 * AUD-07 single authority: the ONLY mapping from a credential binding kind to
 * its audit [BoundPurpose].
 *
 * Total over the sealed family — the compiler enforces that every binding kind
 * is mapped, so a `file` / `certificate` / `zip` / `usernameColonPassword`
 * binding can never be silently reported as [BoundPurpose.API_KEY] (the defect
 * this replaces was a partial `when` with `else -> API_KEY`).
 *
 * An unknown raw `kind` token is unrepresentable at this seam: the compiled
 * payload codec rejects it fail-closed before it can become a
 * [CredentialBindingSpec], and once a spec exists its kind is a static type.
 */
val CredentialBindingSpec.boundPurpose: BoundPurpose
    get() = when (this) {
        is StringBindingSpec -> BoundPurpose.API_KEY
        is UsernamePasswordBindingSpec -> BoundPurpose.USERNAME_PASSWORD
        is SshUserPrivateKeyBindingSpec -> BoundPurpose.SSH_KEY
        is FileBindingSpec -> BoundPurpose.FILE
        is CertificateBindingSpec -> BoundPurpose.CERTIFICATE
        is ZipBindingSpec -> BoundPurpose.ZIP
        is UsernameColonPasswordBindingSpec -> BoundPurpose.USERNAME_COLON_PASSWORD
    }

/**
 * Ergonomic factory methods that mirror the legacy
 * `:pipeline-binding-factory/CredentialsBindingFactory` shape but produce
 * `:pipeline-domain` sealed types.
 *
 * Field ordering follows Jenkins-verbatim conventions (keystoreVariable first on
 * certificate, etc.) but the *factory* signature keeps credentialsId-first where
 * the Jenkins shape allows it, for ergonomic DSL/script calls.
 */
object CredentialBindingSpecFactory {

    fun string(credentialsId: CredentialsId, variable: String): StringBindingSpec =
        StringBindingSpec(credentialsId, variable)

    fun usernamePassword(
        credentialsId: CredentialsId,
        usernameVariable: String,
        passwordVariable: String,
    ): UsernamePasswordBindingSpec =
        UsernamePasswordBindingSpec(credentialsId, usernameVariable, passwordVariable)

    fun sshUserPrivateKey(
        credentialsId: CredentialsId,
        keyFileVariable: String,
        passphraseVariable: String? = null,
        usernameVariable: String? = null,
    ): SshUserPrivateKeyBindingSpec =
        SshUserPrivateKeyBindingSpec(credentialsId, keyFileVariable, passphraseVariable, usernameVariable)

    fun file(credentialsId: CredentialsId, variable: String): FileBindingSpec =
        FileBindingSpec(credentialsId, variable)

    fun certificate(
        keystoreVariable: String,
        credentialsId: CredentialsId,
        aliasVariable: String? = null,
        passwordVariable: String? = null,
    ): CertificateBindingSpec =
        CertificateBindingSpec(keystoreVariable, credentialsId, aliasVariable, passwordVariable)

    fun zip(variable: String, credentialsId: CredentialsId): ZipBindingSpec =
        ZipBindingSpec(variable, credentialsId)

    fun usernameColonPassword(variable: String, credentialsId: CredentialsId): UsernameColonPasswordBindingSpec =
        UsernameColonPasswordBindingSpec(variable, credentialsId)
}
