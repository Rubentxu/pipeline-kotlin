package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.domain.CredentialsId

/**
 * H0 Slice 1: Binding seam - credentials binding representation.
 *
 * This is a simple data class that carries binding information from PipelineRun
 * to the CredentialSession. It mirrors the essential fields from StepSpec.CredentialsBinding
 * without introducing a dependency on pipeline-application.
 *
 * ## Kind enumeration
 *
 * Must stay in sync with StepSpec.CredentialsBinding.Kind in pipeline-application.
 */
data class CredentialsBinding(
    val kind: Kind,
    val credentialsId: CredentialsId,
    val variable: String?,
    val usernameVariable: String?,
    val passwordVariable: String?,
    val keyFileVariable: String?,
    val passphraseVariable: String?,
    val keystoreVariable: String?,
    val aliasVariable: String?
) {
    enum class Kind {
        STRING,
        USERNAME_PASSWORD,
        SSH_USER_PRIVATE_KEY,
        FILE,
        CERTIFICATE,
        ZIP,
        USERNAME_COLON_PASSWORD
    }
}