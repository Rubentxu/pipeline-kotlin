package dev.rubentxu.pipeline.v2.application

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Where the service's authorizations come from, and who writes them.
 *
 * ## The guarantee under trial
 *
 * Three rules that a service that holds a secret has to keep, and that are
 * all about the FILE and not about the decision:
 *
 *  - the file the service OWNS is created private, and tightened to private
 *    when it is already there: a token any local user can read guards
 *    nothing;
 *  - the file the operator NAMED is only read: a service that rewrote the
 *    operator's authorizations would be widening its own access without
 *    anybody deciding it, and a named file that any local user can read is
 *    refused rather than used;
 *  - the standard input is read to its end and closed: an authorization that
 *    stays in a stream nobody reads is a secret in a buffer.
 *
 * ## The capacity this serves
 *
 * An operator who serves a machine has to be able to look at the file the
 * service wrote and see that it grants nothing to anybody else, and an
 * operator who keeps the authorization OFF the disk has to be able to say so
 * and have the service honour it.
 *
 * ## Why this instrument
 *
 * The permissions of a file are a fact about the FILESYSTEM, and no amount of
 * faking a path tells the truth about a mode. The files live in a temporary
 * directory and the modes are read from the machine.
 */
class ServiceAuthorizationStoreTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `the file the service owns is created and it grants nothing to anybody else`() {
        val owned = root.resolve(ServiceAuthorizationStore.OWNED_FILE_NAME)

        val reading = ServiceAuthorizationStore.ofFile(owned, owned = true)

        val read = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "the service wrote the file and knows what is in it",
        )
        assertEquals(1, read.authorizations.size)
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(owned),
            "the token is readable by its owner and by nobody else",
        )
    }

    @Test
    fun `the file the service owns is tightened when it is already there`() {
        val owned = root.resolve(ServiceAuthorizationStore.OWNED_FILE_NAME)
        Files.writeString(owned, "token operator all\n")
        Files.setPosixFilePermissions(
            owned,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OTHERS_READ,
            ),
        )

        val reading = ServiceAuthorizationStore.ofFile(owned, owned = true)

        assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "the file of the service is read even when it was too open",
        )
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(owned),
            "the service tightened its own file, because a token any local user can read guards nothing",
        )
    }

    @Test
    fun `the file the operator named is read and never written`() {
        val named = root.resolve("elsewhere")
        val content = "token operator all\n"
        Files.writeString(named, content)
        Files.setPosixFilePermissions(
            named,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )

        val reading = ServiceAuthorizationStore.ofFile(named, owned = false)

        assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "the file of the operator is read",
        )
        assertEquals(
            content,
            Files.readString(named),
            "the service did not write one word in the file of the operator",
        )
    }

    @Test
    fun `a file the operator named that any local user can read is refused`() {
        val named = root.resolve("elsewhere")
        Files.writeString(named, "token operator all\n")
        Files.setPosixFilePermissions(
            named,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OTHERS_READ,
            ),
        )

        val reading = ServiceAuthorizationStore.ofFile(named, owned = false)

        val refused = assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "a token any local user can read guards nothing",
        )
        assertTrue(refused.detail.contains("readable by others"), "the refusal says why: ${refused.detail}")
    }

    @Test
    fun `a file the operator named that is not there is refused and not created`() {
        val named = root.resolve("elsewhere")

        val reading = ServiceAuthorizationStore.ofFile(named, owned = false)

        assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "a named file that is not there is a refusal, not an invitation to create it",
        )
        assertTrue(
            !Files.exists(named),
            "the service did not create the file of the operator",
        )
    }

    @Test
    fun `the standard input is read to its end`() {
        val reading = ServiceAuthorizationStore.readStandardInput(
            ByteArrayInputStream("token operator all\n".toByteArray(Charsets.UTF_8)),
        )

        val read = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "the standard input carries the authorization",
        )
        assertEquals(1, read.authorizations.size)
    }

    @Test
    fun `a standard input that carries nothing is read as no authorization`() {
        val reading = ServiceAuthorizationStore.readStandardInput(ByteArrayInputStream(ByteArray(0)))

        val read = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "an empty standard input holds no authorization, and that is not a malformed file",
        )
        assertEquals(0, read.authorizations.size)
    }
}
