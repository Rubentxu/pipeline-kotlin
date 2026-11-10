package dev.rubentxu.pipeline.v2.application

/**
 * A credential that the broker of the run did not deliver.
 *
 * ## Why the message is made of fixed words
 *
 * The message reaches the durable result of the run: the executor that binds
 * credentials turns any failure of its provider into the error of the step, and
 * that error is written down. A message made of the refusal word, its fixed
 * meaning and the NAME of the credential carries nothing the run could not
 * already read in its own script. It never carries a fragment of what was read
 * from the socket, because a line that was almost an answer may have held the
 * secret it was almost carrying.
 *
 * ## Why there is no cause
 *
 * A cause is the exception that came first, and the exception that came first
 * may have been built from the bytes. The refusal is the whole explanation.
 *
 * @property credentialId the credential that was asked for, or null when the
 *   failure is of the link and no credential is involved yet.
 * @property refusal why nothing was delivered.
 */
internal class CredentialBrokerRefusedException(
    val credentialId: String?,
    val refusal: CredentialBrokerRefusal,
) : RuntimeException(messageOf(credentialId, refusal)) {

    private companion object {
        fun messageOf(credentialId: String?, refusal: CredentialBrokerRefusal): String {
            val subject = if (credentialId == null) "the credential" else "credential '$credentialId'"
            return "$subject was not delivered: ${refusal.word} (${refusal.meaning})"
        }
    }
}
