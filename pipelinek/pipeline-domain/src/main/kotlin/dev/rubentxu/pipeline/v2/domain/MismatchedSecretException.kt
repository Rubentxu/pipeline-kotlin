package dev.rubentxu.pipeline.v2.domain

/**
 * Exception thrown when a credential's actual kind does not match the expected kind.
 *
 * Thrown during [dev.rubentxu.pipeline.v2.credentials.api.CredentialScope.env]
 * when a binding expects one credential type but the store returns another.
 *
 * Jenkins verbatim wording (post-2019 source):
 * `"Credential '<id>' is of type '<actual>' where '<expected>' was expected."`
 *
 * @param credentialId The ID of the mismatched credential
 * @param expectedKind The kind that was expected (e.g., "UsernamePasswordBinding")
 * @param actualKind The kind that was actually found (e.g., "SecretText")
 */
class MismatchedSecretException(
    val credentialId: CredentialsId,
    val expectedKind: String,
    val actualKind: String
) : IllegalArgumentException(
    "Credential '${credentialId.value}' is of type '$actualKind' where '$expectedKind' was expected."
) {
    companion object {
        /**
         * Verbatim Jenkins message format for assertion in tests.
         */
        const val JENKINS_MESSAGE_TEMPLATE =
            "Credential '%s' is of type '%s' where '%s' was expected."
    }
}
