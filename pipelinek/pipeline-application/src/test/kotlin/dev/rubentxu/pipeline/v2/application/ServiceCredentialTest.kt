package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Where the client of the service gets the token it presents.
 *
 * ## The guarantee under trial
 *
 * That a client presents a token from a place the operator chose —the
 * environment variable, or the file the service owns next to its socket— and
 * that a client with NO token says so instead of connecting with nothing and
 * being told "not-authorized" by a service that had no way to know the
 * difference between "you are not allowed" and "you forgot your token".
 *
 * ## The capacity this serves
 *
 * An operator who serves with the `isolated` profile has NO file to point a
 * client at, and a client that only knew how to read a file would be unable
 * to talk to that service at all. The variable is that door, and the file is
 * the one an operator can edit by hand.
 *
 * ## Why this instrument
 *
 * The decision is over a MAP and a PATH: the environment is a parameter and
 * not a global, so a row can present the environment it means to present. The
 * file is a real file in a temporary directory, because what is under trial
 * is that the file is found and read.
 */
class ServiceCredentialTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `the variable is the token when it carries one`() {
        val socket = root.resolve("service.sock")
        Files.writeString(root.resolve(ServiceAuthorizationStore.OWNED_FILE_NAME), "file-token operator all\n")

        val reading = ServiceCredential.read(
            environment = mapOf(ServiceCredential.ENVIRONMENT_VARIABLE to "token-of-the-variable"),
            endpoint = socket,
            namedFile = null,
        )

        val found = assertInstanceOf(
            ServiceCredentialReading.Found::class.java,
            reading,
            "the variable carries the token",
        )
        assertEquals("token-of-the-variable", found.token)
    }

    @Test
    fun `a blank variable falls through to the file`() {
        val socket = root.resolve("service.sock")
        Files.writeString(root.resolve(ServiceAuthorizationStore.OWNED_FILE_NAME), "file-token operator all\n")

        val reading = ServiceCredential.read(
            environment = mapOf(ServiceCredential.ENVIRONMENT_VARIABLE to "  "),
            endpoint = socket,
            namedFile = null,
        )

        val found = assertInstanceOf(
            ServiceCredentialReading.Found::class.java,
            reading,
            "a variable that carries nothing is not a credential",
        )
        assertEquals("file-token", found.token)
    }

    @Test
    fun `the file the service owns next to its socket is the token when the operator names none`() {
        val socket = root.resolve("service.sock")
        Files.writeString(root.resolve(ServiceAuthorizationStore.OWNED_FILE_NAME), "file-token operator all\n")

        val reading = ServiceCredential.read(emptyMap(), socket, namedFile = null)

        val found = assertInstanceOf(
            ServiceCredentialReading.Found::class.java,
            reading,
            "the file next to the socket is where the trusted-local profile keeps the token",
        )
        assertEquals("file-token", found.token)
    }

    @Test
    fun `the file the operator names is the token when there is one`() {
        val socket = root.resolve("service.sock")
        val named = root.resolve("elsewhere")
        Files.writeString(named, "named-token operator all\n")

        val reading = ServiceCredential.read(emptyMap(), socket, namedFile = named.toString())

        val found = assertInstanceOf(
            ServiceCredentialReading.Found::class.java,
            reading,
            "the file the operator named is the file that is read",
        )
        assertEquals("named-token", found.token)
    }

    @Test
    fun `a client with no token says where it looked`() {
        val socket = root.resolve("service.sock")

        val reading = ServiceCredential.read(emptyMap(), socket, namedFile = null)

        val refused = assertInstanceOf(
            ServiceCredentialReading.Refused::class.java,
            reading,
            "a client with no token says so, instead of connecting with nothing",
        )
        assertTrue(
            refused.detail.contains(ServiceAuthorizationStore.OWNED_FILE_NAME),
            "the refusal names the file it looked for: ${refused.detail}",
        )
    }

    @Test
    fun `a file that holds no authorization is refused`() {
        val socket = root.resolve("service.sock")
        Files.writeString(root.resolve(ServiceAuthorizationStore.OWNED_FILE_NAME), "# nothing here\n")

        val reading = ServiceCredential.read(emptyMap(), socket, namedFile = null)

        assertInstanceOf(
            ServiceCredentialReading.Refused::class.java,
            reading,
            "a file with no authorization holds no token",
        )
    }

    @Test
    fun `a file the client cannot read is refused for the permission and not for the absence`() {
        val socket = root.resolve("service.sock")
        // The parent of the token is a FILE, so the path cannot be walked:
        // the file is not there and the client cannot read it either, and
        // only the read tells which of the two it is.
        val notADirectory = root.resolve("not-a-directory")
        Files.writeString(notADirectory, "this file is the parent of the token\n")
        val unreachable = notADirectory.resolve(ServiceAuthorizationStore.OWNED_FILE_NAME)

        val reading = ServiceCredential.read(emptyMap(), socket, namedFile = unreachable.toString())

        val refused = assertInstanceOf(
            ServiceCredentialReading.Refused::class.java,
            reading,
            "a file the client cannot read is refused",
        )
        assertTrue(
            refused.detail.contains("could not be read"),
            "the refusal names the permission and not the absence: ${refused.detail}",
        )
    }
}
