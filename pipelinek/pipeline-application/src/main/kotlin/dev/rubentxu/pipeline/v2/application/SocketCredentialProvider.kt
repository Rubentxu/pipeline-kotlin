package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * The credential provider of a supervised run: it asks the broker of its service.
 *
 * ## When it asks
 *
 * Only when the executor of `withCredentials` resolves a credential. Building
 * the provider connects to nobody and receives nothing: a run that never enters
 * a scope with credentials never talks to the broker, and a run that starts has
 * no secret in its memory, its environment or its arguments.
 *
 * ## There is no other store behind it
 *
 * The provider has one source. When the broker refuses, does not answer or
 * answers something that is not its contract, the credential does not arrive and
 * the step that asked does not run; nothing here looks in a local store, and the
 * composition that chose this provider does not open one. A fallback would turn a
 * refusal of the broker into a read the operator did not grant.
 *
 * ## What its failures say
 *
 * Every failure is a [CredentialBrokerRefusedException]: the fixed word of the
 * refusal and the name of the credential. Nothing the provider read from the
 * socket is repeated, because the failure is written into the result of the run.
 *
 * ## The session
 *
 * The run holds the token of its session in memory only. [inDirectory] reads it
 * once and deletes the file, so a step that looks in the directory later finds
 * the socket and not the token. A process of the same user can still read it
 * from the memory of the run, or ask for it before the file is deleted: the
 * session separates the runs of one service, not the processes of one user.
 *
 * @param link how the run talks to the broker.
 * @param sessionToken the token of the session, or null when the run holds
 *   none; every request is then refused as an unreachable broker.
 */
internal class SocketCredentialProvider(
    private val link: CredentialBrokerLink,
    private val sessionToken: String?,
) : CredentialProvider {

    override val providerId: String = PROVIDER_ID

    override fun resolve(id: CredentialsId): SecretHandle {
        val credential = resolveToCredential(id) as SecretText
        return SecretHandle.secret(credential.bytes)
    }

    override fun resolveToCredential(id: CredentialsId): Credential {
        val token = sessionToken ?: throw refused(id, CredentialBrokerRefusal.BROKER_UNREACHABLE)
        val answer = try {
            link.exchange(CredentialBrokerWire.encodeRequest(token, id.value, BrokeredCredentialKind.STRING))
        } catch (e: CredentialBrokerRefusedException) {
            throw refused(id, e.refusal)
        } catch (_: Throwable) {
            // What the link threw may have been made of what it read.
            throw refused(id, CredentialBrokerRefusal.BROKER_UNREACHABLE)
        }
        return when (val reading = CredentialBrokerWire.decodeResponse(answer)) {
            CredentialBrokerWire.ResponseReading.Unreadable ->
                throw refused(id, CredentialBrokerRefusal.BROKER_ANSWER_NOT_READABLE)
            is CredentialBrokerWire.ResponseReading.Refused -> throw refused(id, reading.refusal)
            is CredentialBrokerWire.ResponseReading.Granted -> when (reading.kind) {
                BrokeredCredentialKind.STRING -> SecretText(id = id, bytes = reading.secret)
            }
        }
    }

    /** The provider has nothing to release: every request opens and closes its own connection. */
    override fun close() = Unit

    private fun refused(id: CredentialsId, refusal: CredentialBrokerRefusal) =
        CredentialBrokerRefusedException(id.value, refusal)

    internal companion object {

        /** The identifier of this provider in an audit trail. */
        const val PROVIDER_ID: String = "broker"

        /** The largest token file the run reads, in bytes. */
        private const val MAX_TOKEN_FILE_BYTES: Long = 256L

        /**
         * The provider of the broker whose files are in [directory].
         *
         * The token is read once and its file is deleted whatever happens, so
         * the file never outlives the first look at it. A token that cannot be
         * read leaves the provider without a session, and the run finds out
         * when a step asks for a credential and not before: a run that never
         * asks is not a failed run.
         */
        fun inDirectory(
            directory: Path,
            deadlineMillis: Long = UnixSocketBrokerLink.DEFAULT_DEADLINE_MILLIS,
        ): SocketCredentialProvider =
            SocketCredentialProvider(
                UnixSocketBrokerLink(directory.resolve(RunBrokerLease.SOCKET_FILE_NAME), deadlineMillis),
                takeSessionToken(directory.resolve(RunBrokerLease.TOKEN_FILE_NAME)),
            )

        /** The token in [file], or null when the file is not a small regular file. The file is deleted. */
        internal fun takeSessionToken(file: Path): String? =
            try {
                val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (!attributes.isRegularFile || attributes.size() > MAX_TOKEN_FILE_BYTES) {
                    null
                } else {
                    val bytes = Files.readAllBytes(file)
                    try {
                        String(bytes, Charsets.UTF_8)
                    } finally {
                        bytes.fill(0)
                    }
                }
            } catch (_: Exception) {
                null
            } finally {
                try {
                    Files.deleteIfExists(file)
                } catch (_: Exception) {
                    // A file that cannot be deleted is still not read twice by this run.
                }
            }
    }
}
