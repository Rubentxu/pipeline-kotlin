package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.sdk.files.BoundedFileReader
import dev.rubentxu.pipeline.v2.sdk.files.BoundedRead
import dev.rubentxu.pipeline.v2.sdk.files.ReadQuota
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom

/**
 * The trust profile of the service: WHERE the authorization comes from, and
 * what that keeps off the disk.
 *
 * ## What the two are
 *
 * Both are AUTHENTICATION profiles, and they differ in one thing only: the
 * place the authorization is read from. Neither of them is a profile of
 * isolation, and neither one may be named as if it were.
 *
 * [TrustedLocal] is the default and it keeps the authorization in the FILE
 * the service owns next to its socket. The socket is `rw-------` and its
 * directory grants nothing to the group or to others, so any process of the
 * same user can open it, and the authorization that travels on it is in a
 * file that any of those processes can read.
 *
 * [PrivateAuth] keeps the authorization OFF the disk: it is read from the
 * service's standard input and the service writes it nowhere, so there is no
 * file for a process of the same user to read.
 *
 * ## What each one protects, and what each one does NOT
 *
 * [TrustedLocal] protects the FILE SYSTEM: nothing outside this user may
 * read the authorization or open the socket. It does not protect the
 * authorization from another process of the same user.
 *
 * [PrivateAuth] protects the DISK: no file carries the authorization, so
 * there is nothing for a process to read. It does not protect the
 * authorization from a process of the same user that reads the service's
 * standard input while the service starts.
 *
 * ## What NEITHER profile promises
 *
 * Neither one separates two processes of the same user. The JVM does not
 * expose the credentials of the peer of a Unix socket —measured with `javap`,
 * and repeated on the JDK 21 the product is built with and on the JDK 25
 * installed here—, so a service cannot know who connected. What a profile
 * can do is say exactly what is on the disk and what is not.
 *
 * ## Why no profile of authentication wears the word `isolated`
 *
 * Because in this product that word belongs to a separation a host has to
 * PROVE: a run that asks for an isolated environment is admitted only
 * against what the provider attests, and refused when nothing proves it. A
 * trust profile reads an authorization from a place, and reading from a place
 * proves no separation of processes, files or sockets. A name that promised
 * one would be a promise this product does not keep.
 *
 * The other place the word lives is the option `--isolated` of the run verb,
 * and there it names a WORKSPACE ALLOCATION —a managed scratch directory— and
 * not a boundary either.
 */
internal enum class ServiceTrustProfile(val word: String) {

    /**
     * The authorization is in the FILE the service owns next to its socket.
     *
     * It protects the file system —nothing outside this user may read the
     * authorization or open the socket— and it does NOT separate two
     * processes of the same user.
     */
    TrustedLocal("trusted-local"),

    /**
     * The authorization is not on the disk: it comes from the standard input.
     *
     * It protects the disk —no file carries the authorization, so there is
     * nothing for a process to read— and it does NOT separate two processes of
     * the same user either: a process of the same user that reads the
     * service's standard input while it starts takes the authorization.
     */
    PrivateAuth("private-auth");

    companion object {

        /**
         * The profile [word] names, or null when it names none.
         */
        fun parse(word: String): ServiceTrustProfile? =
            entries.firstOrNull { it.word == word }
    }
}

/**
 * Where the service's authorizations come from, and who writes them.
 *
 * ## The two sources
 *
 * The FILE is the source of the `trusted-local` profile: the file the
 * operator names, or the one the service owns next to its socket. The
 * STANDARD INPUT is the source of the `private-auth` profile, and the service
 * writes it nowhere.
 *
 * ## The rule about writing
 *
 * The service writes the file it OWNS and reads the file it is GIVEN. A
 * file the operator named is never created and never modified by the
 * service: a service that rewrote the operator's authorizations would be
 * widening its own access without anybody deciding it.
 */
internal object ServiceAuthorizationStore {

    /**
     * The name of the file the service owns, next to its socket.
     *
     * It sits in the socket's directory, which is private, and it is `0600`,
     * so it is readable by the same processes that can open the socket.
     */
    const val OWNED_FILE_NAME: String = "authorizations"

    /**
     * The label of the authorization the service issues for itself.
     *
     * A label for the operator: it never travels on the wire.
     */
    const val OWNED_LABEL: String = "operator"

    /** How many bytes a generated token carries. */
    private const val TOKEN_BYTES: Int = 32

    /**
     * The authorizations of the file [path].
     *
     * A refusal, and not an exception: the file is the operator's, and a
     * file the operator wrote wrong has to be answered with a sentence about
     * the file.
     *
     * The file is read under [quotaBytes]: the limit the product reads every
     * file under. A file that does not fit is refused with the size it has
     * and with the limit, and none of its content is produced; the file is
     * measured before it is read, so it is never held whole.
     *
     * @param path the file the operator named.
     * @param quotaBytes the limit the read runs under.
     */
    fun read(path: Path, quotaBytes: Long = ReadQuota.MAX_FILE_BYTES): ServiceAuthorizationsReading {
        val text = try {
            when (val read = BoundedFileReader.read(path, quotaBytes)) {
                is BoundedRead.Fits -> decodeStrictly(read.bytes)
                is BoundedRead.TooLarge -> return ServiceAuthorizationsReading.Refused(
                    "the file at $path holds at least ${read.atLeastBytes} bytes, over the limit of " +
                        "${read.limitBytes} bytes; the read was refused and no content was produced",
                )
            }
        } catch (failure: IOException) {
            return ServiceAuthorizationsReading.Refused("the file could not be read: ${failure.message}")
        }
        return ServiceAuthorizations.parse(text)
    }

    /**
     * The authorizations the service's standard input carries.
     *
     * The input is read to its end, or until it holds more than the quota:
     * an authorization that stays in a stream nobody reads is a secret in a
     * buffer, and a stream that holds more than the quota is refused before
     * it is read whole.
     *
     * @param input the stream the authorizations arrive in.
     * @param quotaBytes the limit the read runs under.
     */
    fun readStandardInput(
        input: InputStream,
        quotaBytes: Long = ReadQuota.MAX_FILE_BYTES,
    ): ServiceAuthorizationsReading {
        require(quotaBytes in 0..Int.MAX_VALUE.toLong()) {
            "the read limit is beyond what a byte array can hold: $quotaBytes"
        }
        val text = try {
            // ONE byte more than the quota is asked for, so that a stream that
            // holds more than the limit is told apart from one that fits.
            val bytes = input.readNBytes(quotaBytes.toInt() + 1)
            if (bytes.size > quotaBytes) {
                return ServiceAuthorizationsReading.Refused(
                    "the standard input holds at least ${bytes.size} bytes, over the limit of " +
                        "$quotaBytes bytes; the read was refused and no content was produced",
                )
            }
            decodeStrictly(bytes)
        } catch (failure: IOException) {
            return ServiceAuthorizationsReading.Refused("the standard input could not be read: ${failure.message}")
        }
        return ServiceAuthorizations.parse(text)
    }

    /**
     * The text [bytes] carry, decoded strictly.
     *
     * Strict, because a file that is not UTF-8 is a file the operator wrote
     * wrong: a text with the unreadable bytes replaced would be a file the
     * operator did not write, and the service would answer about it as if it
     * had.
     */
    private fun decodeStrictly(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    /**
     * The authorizations the service will serve, from the file [path].
     *
     * The file the service OWNS is created when it is not there, with one
     * generated authorization and the scope `all`; and it is TIGHTENED to
     * `0600` when it is there, because it is the service's own file and a
     * token any local user can read guards nothing. The file the operator
     * NAMED is only read: a named file that is not there is a refusal, not
     * an invitation to create it, and a named file that any local user can
     * read is refused too.
     *
     * The generated file is written ATOMICALLY —a temporary file in the same
     * directory, then a rename— so a client that reads it while the service
     * starts never reads half of it.
     *
     * @param path the file of the authorizations.
     * @param owned whether the file is the one the service owns.
     * @param quotaBytes the limit the read of the file runs under.
     */
    fun ofFile(
        path: Path,
        owned: Boolean,
        quotaBytes: Long = ReadQuota.MAX_FILE_BYTES,
    ): ServiceAuthorizationsReading {
        if (!owned) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return ServiceAuthorizationsReading.Refused("the file is not there: $path")
            }
            val exposure = exposureOf(path) ?: return ServiceAuthorizationsReading.Refused(
                "the permissions of the file could not be read: $path",
            )
            if (exposure) {
                return ServiceAuthorizationsReading.Refused(
                    "the file is readable by others, and a token any local user can read guards nothing: " +
                        "$path (make it readable by its owner alone: chmod 600 $path)",
                )
            }
            return read(path, quotaBytes)
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.setPosixFilePermissions(
                    path,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            } catch (failure: Exception) {
                return ServiceAuthorizationsReading.Refused(
                    "the permissions of the file could not be tightened: ${failure.message}",
                )
            }
            return read(path, quotaBytes)
        }
        val authorization = ServiceAuthorization(
            token = generateToken(),
            label = OWNED_LABEL,
            scope = ServiceScope.All,
        )
        return try {
            writeAtomically(path, ServiceAuthorizations.format(listOf(authorization)))
            ServiceAuthorizationsReading.Read(listOf(authorization))
        } catch (failure: IOException) {
            ServiceAuthorizationsReading.Refused("the file could not be written: ${failure.message}")
        } catch (failure: UnsupportedOperationException) {
            ServiceAuthorizationsReading.Refused("this filesystem cannot keep the file private: ${failure.message}")
        }
    }

    /**
     * Whether [path] can be read by a user that is not its owner.
     *
     * The permission of the LINK is read and not the permission of its
     * target, so a symbolic link reads as `rwxrwxrwx`: an authorizations
     * file that is a link is a file whose mode says nothing about what it
     * points at, and it is refused.
     */
    private fun exposureOf(path: Path): Boolean? = try {
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
            .any { it == PosixFilePermission.OTHERS_READ || it == PosixFilePermission.OTHERS_WRITE }
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: IOException) {
        null
    }

    /**
     * A token nobody else has.
     *
     * Thirty-two bytes of [SecureRandom] in hexadecimal: sixty-four
     * characters, and the odds of two of them being the same are the odds of
     * a cosmic typo.
     */
    fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        val text = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            text.append(HEX[value ushr 4])
            text.append(HEX[value and 0x0f])
        }
        return text.toString()
    }

    /**
     * Writes [text] in [path], atomically and privately.
     *
     * The temporary file is created in the SAME directory, because a rename
     * across directories is not atomic, and its mode is set BEFORE the
     * rename: a file that is written world-readable for a moment and then
     * tightened is a secret that was readable for a moment.
     */
    private fun writeAtomically(path: Path, text: String) {
        val directory = path.parent ?: throw IOException("the file has no directory: $path")
        val temporary = Files.createTempFile(directory, OWNED_FILE_NAME, ".tmp")
        try {
            Files.setPosixFilePermissions(
                temporary,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
            Files.writeString(temporary, text)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** The digits and letters a token is written with. */
    private const val HEX: String = "0123456789abcdef"
}
