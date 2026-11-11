package dev.rubentxu.pipeline.v2.application

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * The credential a client presents to the local service.
 *
 * ## What this owns
 *
 * WHERE a client gets the token it presents, and the refusal when there is
 * none. It does not own the decision of whether the token is accepted: that
 * is the service's, it is the SDK's, and it happens on the other side of the
 * socket.
 *
 * ## The two sources, and why there are two
 *
 * The ENVIRONMENT VARIABLE comes first: it is the only source that works when
 * the service keeps its authorization off the disk —the `private-auth`
 * profile—, and it is the only one that does not leave the secret in a file
 * another process of the same user may read.
 *
 * The FILE comes second: the file the operator named, or the file the
 * service owns next to its socket. It is where the `trusted-local` profile
 * keeps the token, and it is the shape an operator can edit by hand.
 *
 * ## Why the token is never printed
 *
 * The token is a secret, and a secret that this CLI prints is a secret that
 * ends up in a terminal scrollback, in a log, and in a shell history. It is
 * read, presented and forgotten: nothing in this build writes it to a
 * stream.
 *
 * ## Why the FIRST authorization of the file is the one presented
 *
 * Because a file with several authorizations is a file the operator wrote to
 * hold several CLIENTS —a client for the whole substrate and a client for
 * one run— and this CLI is one of them. It presents the first, and the
 * operator who wants another one names a file with that one first.
 */
internal object ServiceCredential {

    /**
     * The variable that carries the token when the file does not.
     *
     * It is a variable and not an option because a token on the command line
     * is a token in the process list, in the shell history and in the
     * terminal, and a secret that travels in those three places is a secret
     * that leaked.
     */
    const val ENVIRONMENT_VARIABLE: String = "PIPELINEK_SERVICE_TOKEN"

    /**
     * The token this client presents, or the refusal of a client that has none.
     *
     * The [environment] is a PARAMETER and not a global, so the decision can
     * be exercised with the environment it is given and not with the one the
     * machine happens to have.
     */
    fun read(
        environment: Map<String, String>,
        endpoint: Path,
        namedFile: String?,
    ): ServiceCredentialReading {
        val fromEnvironment = environment[ENVIRONMENT_VARIABLE]
        if (!fromEnvironment.isNullOrBlank()) {
            return ServiceCredentialReading.Found(fromEnvironment)
        }

        val path = namedFile?.let { Path.of(it) }
            ?: endpoint.toAbsolutePath().normalize().parent
                ?.resolve(ServiceAuthorizationStore.OWNED_FILE_NAME)
            ?: return ServiceCredentialReading.Refused(
                "the socket has no directory, so there is no file to read the token from: $endpoint",
            )

        // A file that is not there and a file that cannot be read are two
        // different facts, and the sentence says which one it is: the
        // message of a missing file is the path itself, and repeating the
        // path in a sentence that already names it says nothing.
        //
        // The READ is what tells them apart, and not a question asked before
        // it: `Files.exists` answers false when the file IS there and the
        // directory that holds it cannot be walked, and a client that is
        // refused for the wrong reason is sent to look for a file that was
        // never missing.
        val text = try {
            Files.readString(path)
        } catch (failure: NoSuchFileException) {
            return ServiceCredentialReading.Refused("the file is not there: $path")
        } catch (failure: IOException) {
            return ServiceCredentialReading.Refused(
                "the file could not be read: $path (${failure.javaClass.simpleName})",
            )
        }

        return when (val reading = ServiceAuthorizations.parse(text)) {
            is ServiceAuthorizationsReading.Read -> {
                val first = reading.authorizations.firstOrNull()
                    ?: return ServiceCredentialReading.Refused("the file holds no authorization: $path")
                ServiceCredentialReading.Found(first.token)
            }

            is ServiceAuthorizationsReading.Refused -> ServiceCredentialReading.Refused(
                "the authorizations could not be read from $path: ${reading.detail}",
            )
        }
    }
}

/**
 * What the search for a credential produced.
 *
 * Two shapes, and a caller has to act on both, so it reads them with an
 * exhaustive `when`: the token, or the sentence about the place it was not
 * found.
 */
internal sealed interface ServiceCredentialReading {

    /** The token this client presents. */
    data class Found(val token: String) : ServiceCredentialReading {
        /**
         * The text of this reading, without the token.
         *
         * The token is the secret the client presents, and a line of log that
         * carried it would publish it to whoever reads the log. The reading
         * has nothing else to say: what it found IS the token.
         */
        override fun toString(): String = "[SECRET ServiceCredentialReading.Found]"
    }

    /** There is no token to present, and this says where it was looked for. */
    data class Refused(val detail: String) : ServiceCredentialReading
}
