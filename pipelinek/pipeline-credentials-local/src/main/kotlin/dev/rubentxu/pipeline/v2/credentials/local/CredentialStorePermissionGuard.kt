package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.api.CredentialsStorePosixPermissionsException
import java.nio.file.Path

/**
 * The permissions of the file that holds the secrets, and of the directory
 * that holds that file.
 *
 * ## What this is
 *
 * A credential store is safe at rest because of two permissions and not
 * because of its encryption: the file is 0600 and the directory that names it
 * is 0700. The ciphertext of a store is still a store that can be taken away
 * and attacked offline, so the permissions are what keep the other users of
 * the machine away from it.
 *
 * Making those permissions true is a job of the PLATFORM, not of the store:
 * only the platform knows whether it can express them, and only it can apply
 * them. This is the seam where that knowledge enters the store.
 *
 * ## Why the store does not do it itself
 *
 * Because a platform that cannot express those permissions has to be TOLD,
 * and the store used to say nothing: it looked at the filesystem, saw that
 * the POSIX view was not there, and carried on opening a file of secrets with
 * whatever permissions the platform gave it. The store was opened, the
 * secrets were in it, and nobody was told that their protection was a
 * promise nobody had checked.
 *
 * ## The refusal
 *
 * A guard that cannot make the permissions safe REFUSES, and it says which
 * platform it was and what it announced. There is no third answer: a store
 * that holds secrets and cannot say who may read them is a store that has to
 * stay closed.
 */
fun interface CredentialStorePermissionGuard {

    /**
     * Makes the store's file and directory safe to hold secrets, or refuses.
     *
     * @param file The file that holds the secrets.
     * @param dir The directory that holds that file.
     * @throws CredentialsStorePosixPermissionsException when the permissions
     *   cannot be guaranteed. The refusal names the file and the platform.
     */
    fun enforce(file: Path, dir: Path)
}

/**
 * The POSIX guard: the platform's own answer, applied.
 *
 * It does four things, in this order, and the order is the contract:
 *
 * 1. it REFUSES when the filesystem does not announce a POSIX view, because
 *    there is then no way to apply the permissions nor to check that they
 *    were applied;
 * 2. it APPLIES 0600 to the file and 0700 to the directory — the directory
 *    explicitly, because a directory created earlier keeps whatever
 *    permissions it was created with;
 * 3. it VERIFIES by reading both back, because a filesystem that accepts a
 *    permission change and does not apply it is a filesystem that cannot be
 *    trusted with a secret;
 * 4. it REJECTS anything the filesystem refuses to do, naming the file.
 */
object PosixCredentialStorePermissionGuard : CredentialStorePermissionGuard {

    override fun enforce(file: Path, dir: Path) {
        try {
            CredentialsStorePosix.enforce(file, dir)
        } catch (e: CredentialsStorePosixPermissionsException) {
            throw e
        } catch (e: Exception) {
            throw CredentialsStorePosixPermissionsException(
                "the credential store at $file cannot hold its secrets with safe permissions: ${e.message}",
            )
        }
    }
}
