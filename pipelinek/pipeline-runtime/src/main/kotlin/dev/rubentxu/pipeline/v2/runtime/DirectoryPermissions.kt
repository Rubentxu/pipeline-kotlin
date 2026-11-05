package dev.rubentxu.pipeline.v2.runtime

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * The permissions a private directory must have, and the two facts that establish them.
 *
 * ## Why imposing is not enough
 * A permission that was set and never read back is a claim, not a fact: the filesystem may have
 * refused it, another user may have changed it, and the runtime would carry on believing that
 * the copies sit where only the operator can reach them. The two operations are therefore
 * separate — impose, then verify — and a directory that does not hold the profile after the
 * imposition is a directory the runtime refuses to use.
 *
 * The port exists so that the refusal has a witness: the guarantee is about what the runtime
 * does when the permissions cannot be established, and only a double that fails on purpose can
 * make that happen on demand.
 */
internal interface DirectoryPermissions {

    /**
     * Impose the profile on [directory].
     *
     * @return null when the profile was imposed, or the sentence that says why it was not.
     */
    fun impose(directory: Path): String?

    /**
     * Whether [directory] holds the profile right now.
     *
     * @return true when the directory's permissions are the ones the profile requires.
     */
    fun verify(directory: Path): Boolean
}

/**
 * The profile, imposed and verified with POSIX permissions.
 *
 * The profile is 0700: the directory belongs to the operator and to nobody else. The copies
 * inside it are plugin artifacts, not secrets the operator is hiding from themselves — they are
 * files that must not be swapped between the moment they are verified and the moment they are
 * loaded.
 *
 * A filesystem without POSIX permissions cannot hold the profile, and saying so is the honest
 * answer: the runtime does not pretend the copies are private where it cannot make them private.
 */
internal object PosixDirectoryPermissions : DirectoryPermissions {

    /** The profile: the directory and everything inside it belongs to the operator alone. */
    private val PROFILE: Set<PosixFilePermission> = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

    override fun impose(directory: Path): String? = try {
        Files.setPosixFilePermissions(directory, PROFILE)
        null
    } catch (e: UnsupportedOperationException) {
        "the filesystem of $directory does not support POSIX permissions, so nothing hidden can " +
            "be kept in it"
    } catch (e: IOException) {
        "the permissions of $directory could not be set: ${e.message}"
    }

    override fun verify(directory: Path): Boolean = try {
        Files.getPosixFilePermissions(directory) == PROFILE
    } catch (e: UnsupportedOperationException) {
        false
    } catch (e: IOException) {
        false
    }
}
