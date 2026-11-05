package dev.rubentxu.pipeline.v2.domain

/**
 * Exception thrown when a credential's actual kind does not match the expected kind.
 * Thrown during `CredentialScope.env`
 * when a binding expects one credential type but the store returns another.
 * Jenkins verbatim wording (post-2019 source):
 * `"Credential '<id>' is of type '<actual>' where '<expected>' was expected."`
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
    /**
     * The template the wording of a credential mismatch is spelled in.
     *
     * The template takes the credential id, the kind that was found and the
     * kind that was expected, in that order. The exception spells the same
     * three facts in its own message, so a caller that asserts on the
     * wording has two strings to match and the two can drift apart.
     */
    companion object {
        /**
         * Verbatim Jenkins message format for assertion in tests.
*/
        const val JENKINS_MESSAGE_TEMPLATE =
            "Credential '%s' is of type '%s' where '%s' was expected."
    }
}
