package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.api.CredentialsStoreCommitException
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The authority that puts what a mutation wrote in the place of the store file.
 *
 * A mutation writes a temporary file and then replaces the store with it, and
 * the only question that matters is whether the replacement HAPPENED: an
 * operation that reports a success it did not achieve tells the operator that
 * a credential is stored when it is not. This port is where that question is
 * asked, so every mutation of the store gets the same answer, and a test can
 * replace the port to make the answer NO.
 */
interface StoreCommit {

    /**
     * Replaces [target] with [temporary].
     *
     * The replacement is atomic when the filesystem offers it, and a failure
     * is reported rather than swallowed.
     *
     * @param temporary the file a mutation wrote.
     * @param target the store file the mutation replaces.
     * @throws CredentialsStoreCommitException when the store could not be
     *   replaced. The store keeps the content it had.
     */
    fun commit(temporary: Path, target: Path)
}

/**
 * The commit the local store uses: an atomic replacement where the filesystem
 * offers one, and an explicit replacement without atomicity where it does not.
 */
object AtomicStoreCommit : StoreCommit {

    override fun commit(temporary: Path, target: Path) {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            replaceWithoutAtomicity(temporary, target, e)
        } catch (e: FileAlreadyExistsException) {
            replaceWithoutAtomicity(temporary, target, e)
        } catch (e: IOException) {
            throw CredentialsStoreCommitException("the store could not be replaced at $target", e)
        }
    }

    /**
     * The replacement without atomicity, for a filesystem that does not offer
     * it. The move is checked like any other: it either happens or it fails
     * loudly, and the failure carries the reason why the atomic way was
     * refused.
     */
    private fun replaceWithoutAtomicity(temporary: Path, target: Path, cause: Exception) {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            val failure = CredentialsStoreCommitException("the store could not be replaced at $target", e)
            failure.addSuppressed(cause)
            throw failure
        }
    }
}
