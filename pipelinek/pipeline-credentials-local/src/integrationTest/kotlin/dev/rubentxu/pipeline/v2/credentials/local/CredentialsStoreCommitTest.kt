package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.api.CredentialsStoreCommitException
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The commit of the local store: what a mutation promises when it says it
 * worked, and what two writers do when they meet on the same file.
 *
 * ## The guarantee
 * A mutation that reports a success has changed the store file, and a
 * mutation that cannot change it says so instead of returning quietly. The
 * store is the only place where a credential lives, so a success that did not
 * happen is the operator being told that a secret is safe when it is not.
 *
 * ## Why this test is an INTEGRATION test and not a unit test
 * The guarantee is about a FILE: it is read back after every mutation, and
 * two writers are observed while one of them holds the lock. A unit test that
 * mocked the filesystem would prove that the store calls its port, not that
 * the store survives a commit that fails. The world outside the process is
 * real here -- a temporary directory the test owns -- and nothing else is
 * faked except the commit itself, which is the boundary the test is about.
 *
 * ## What is faked
 * The commit port, and only where the row needs a commit that fails or a
 * commit that waits. The store, the cryptography, the lock file and the
 * filesystem are the real ones.
 */
class CredentialsStoreCommitTest {

    /** The directory the store's file lives in. */
    @TempDir
    lateinit var home: Path

    private val passphrase = "a passphrase this test owns"

    /**
     * The store file, as the one authority for the store's location names it.
     */
    private fun storeFile(): Path =
        CredentialsStorePath.resolve(envValue = null, userHome = home)

    /**
     * A store over [storeFile], with the commit the caller supplies.
     */
    private fun openStore(committer: StoreCommit = AtomicStoreCommit): LocalSecretStore =
        LocalSecretStore(
            file = storeFile(),
            passphrase = PassphraseResolver.resolve(
                env = mapOf(PASSPHRASE_ENV to passphrase),
                ttyReader = { null },
            ),
            committer = committer,
        )

    /**
     * The bytes the store file holds right now.
     */
    private fun storeBytes(): ByteArray? =
        if (Files.exists(storeFile())) Files.readAllBytes(storeFile()) else null

    private fun text(id: String, value: String): SecretText =
        SecretText(CredentialsId(id), CredentialScope.GLOBAL, value.toByteArray())

    @Test
    fun `an add whose commit fails reports the failure and leaves the store as it was`() {
        val id = CredentialsId("kept")
        val store = openStore()
        store.add(id, text("kept", "before"))
        val before = storeBytes()

        val failing = openStore(RefusingCommit())
        val failure = assertThrows(CredentialsStoreCommitException::class.java) {
            failing.add(CredentialsId("lost"), text("lost", "after"))
        }

        assertTrue(
            failure.message?.contains("could not be replaced") == true,
            "the failure does not say that the store could not be replaced: ${failure.message}",
        )
        assertArrayEquals(
            before,
            storeBytes(),
            "the store changed even though the commit failed",
        )
        assertEquals(
            setOf(id),
            store.list().toSet(),
            "the store holds a credential whose commit never happened",
        )
    }

    @Test
    fun `a put whose commit fails reports the failure and leaves the store as it was`() {
        val store = openStore()
        store.put(CredentialsId("kept"), "before".toByteArray())
        val before = storeBytes()

        val failing = openStore(RefusingCommit())
        assertThrows(CredentialsStoreCommitException::class.java) {
            failing.put(CredentialsId("lost"), "after".toByteArray())
        }

        assertArrayEquals(
            before,
            storeBytes(),
            "the store changed even though the commit failed",
        )
    }

    @Test
    fun `a remove whose commit fails reports the failure and leaves the store as it was`() {
        val id = CredentialsId("kept")
        val store = openStore()
        store.add(id, text("kept", "before"))
        val before = storeBytes()

        val failing = openStore(RefusingCommit())
        assertThrows(CredentialsStoreCommitException::class.java) {
            failing.remove(id)
        }

        assertArrayEquals(
            before,
            storeBytes(),
            "the store changed even though the commit failed",
        )
        assertEquals(
            setOf(id),
            store.list().toSet(),
            "the store lost the credential whose removal never happened",
        )
    }

    @Test
    fun `a rotate whose commit fails reports the failure and leaves the store as it was`() {
        val id = CredentialsId("kept")
        val store = openStore()
        store.add(id, text("kept", "before"))
        val before = storeBytes()

        val failing = openStore(RefusingCommit())
        assertThrows(CredentialsStoreCommitException::class.java) {
            failing.rotate(id, text("kept", "after"))
        }

        assertArrayEquals(
            before,
            storeBytes(),
            "the store changed even though the commit failed",
        )
    }

    @Test
    fun `a rotation of bytes whose commit fails reports the failure and leaves the store as it was`() {
        val id = CredentialsId("kept")
        val store = openStore()
        store.put(id, "before".toByteArray())
        val before = storeBytes()

        val failing = openStore(RefusingCommit())
        assertThrows(CredentialsStoreCommitException::class.java) {
            failing.rotateBytes(id, "after".toByteArray())
        }

        assertArrayEquals(
            before,
            storeBytes(),
            "the store changed even though the commit failed",
        )
    }

    @Test
    fun `a commit that fails leaves no temporary behind`() {
        val store = openStore()
        store.add(CredentialsId("kept"), text("kept", "before"))

        val failing = openStore(RefusingCommit())
        assertThrows(CredentialsStoreCommitException::class.java) {
            failing.add(CredentialsId("lost"), text("lost", "after"))
        }

        val leftovers = Files.list(home.resolve(".pipeline")).use { files ->
            files.filter { it.fileName.toString().contains(".tmp") }.toList()
        }
        assertTrue(
            leftovers.isEmpty(),
            "the store left the secret in a temporary file: $leftovers",
        )
    }

    @Test
    fun `a store that cannot be replaced reports the failure instead of a quiet success`() {
        // A target that is a directory with content in it cannot be replaced
        // by a file: the real adapter has to see that and say so.
        val target = home.resolve("a-directory")
        Files.createDirectories(target.resolve("content"))
        val source = home.resolve("source.txt")
        Files.writeString(source, "the bytes that would replace the store")

        val failure = assertThrows(CredentialsStoreCommitException::class.java) {
            AtomicStoreCommit.commit(source, target)
        }

        assertTrue(
            failure.message?.contains("could not be replaced") == true,
            "the failure does not say that the store could not be replaced: ${failure.message}",
        )
    }

    @Test
    fun `two views of the same store wait for each other instead of colliding on the lock`() {
        val committer = BlockingCommit()
        val held = openStore(committer)
        val waiting = openStore(committer)

        val heldError = AtomicReference<Throwable?>(null)
        val waitingError = AtomicReference<Throwable?>(null)
        val holderThread = Thread {
            runCatching { held.add(CredentialsId("first"), text("first", "one")) }
                .onFailure { heldError.set(it) }
        }
        val waiterThread = Thread {
            runCatching { waiting.add(CredentialsId("second"), text("second", "two")) }
                .onFailure { waitingError.set(it) }
        }

        holderThread.start()
        assertTrue(
            committer.awaitFirstCommit(),
            "the first writer never reached the commit",
        )
        waiterThread.start()

        // The second writer must NOT reach its commit while the first holds
        // the lock: the store is one file, and two writers that overlap
        // overwrite each other. The wait is bounded so that the row fails
        // instead of hanging when the second writer does not wait.
        assertFalse(
            committer.awaitSecondCommit(timeoutMillis = 400),
            "the second writer reached the commit while the first held the lock",
        )

        committer.release()
        holderThread.join(10_000)
        waiterThread.join(10_000)

        assertNull(heldError.get(), "the first writer failed: ${heldError.get()}")
        assertNull(waitingError.get(), "the second writer failed: ${waitingError.get()}")
        assertEquals(
            setOf(CredentialsId("first"), CredentialsId("second")),
            openStore().list().toSet(),
            "the store lost one of the two credentials that were written",
        )
    }

    @Test
    fun `the rotation of bytes waits for the writer that holds the lock`() {
        val seed = CredentialsId("seed")
        openStore().put(seed, "before".toByteArray())

        val committer = BlockingCommit()
        val held = openStore(committer)
        val rotating = openStore(committer)

        val heldError = AtomicReference<Throwable?>(null)
        val rotatingError = AtomicReference<Throwable?>(null)
        val holderThread = Thread {
            runCatching { held.put(CredentialsId("other"), "other".toByteArray()) }
                .onFailure { heldError.set(it) }
        }
        val rotatingThread = Thread {
            runCatching { rotating.rotateBytes(seed, "after".toByteArray()) }
                .onFailure { rotatingError.set(it) }
        }

        holderThread.start()
        assertTrue(
            committer.awaitFirstCommit(),
            "the first writer never reached the commit",
        )
        rotatingThread.start()

        assertFalse(
            committer.awaitSecondCommit(timeoutMillis = 400),
            "the rotation reached the commit while the other writer held the lock",
        )

        committer.release()
        holderThread.join(10_000)
        rotatingThread.join(10_000)

        assertNull(heldError.get(), "the first writer failed: ${heldError.get()}")
        assertNull(rotatingError.get(), "the rotation failed: ${rotatingError.get()}")
        val store = openStore()
        assertEquals(
            setOf(seed, CredentialsId("other")),
            store.list().toSet(),
            "the store lost one of the two credentials that were written",
        )
        val rotated = store.get(seed) as SecretText
        assertArrayEquals(
            "after".toByteArray(),
            rotated.bytes,
            "the rotation was overwritten by the writer that held the lock",
        )
    }

    /**
     * A commit that refuses: the boundary the store has to honour, made to
     * say NO so that the row can see what the store does with a failure.
     */
    private class RefusingCommit : StoreCommit {
        override fun commit(temporary: Path, target: Path) {
            throw CredentialsStoreCommitException("the store could not be replaced at $target")
        }
    }

    /**
     * A commit that waits.
     *
     * The FIRST write announces that it is ready to land and then holds
     * there, so that the test can look at what another writer does while
     * this one is in the middle of the store. A second write is counted and
     * let through, because a write that overlaps the first one is exactly
     * what the rows are looking for.
     */
    private class BlockingCommit : StoreCommit {
        private val commits = AtomicInteger(0)
        private val firstReached = CountDownLatch(1)
        private val secondReached = CountDownLatch(1)
        private val released = CountDownLatch(1)

        override fun commit(temporary: Path, target: Path) {
            val attempt = commits.incrementAndGet()
            if (attempt == 1) {
                firstReached.countDown()
                released.await(10, TimeUnit.SECONDS)
            } else {
                secondReached.countDown()
            }
            AtomicStoreCommit.commit(temporary, target)
        }

        /**
         * Whether the first write reached the commit.
         */
        fun awaitFirstCommit(timeoutMillis: Long = 5_000): Boolean =
            firstReached.await(timeoutMillis, TimeUnit.MILLISECONDS)

        /**
         * Whether a second write reached the commit while the first one
         * waited.
         */
        fun awaitSecondCommit(timeoutMillis: Long): Boolean =
            secondReached.await(timeoutMillis, TimeUnit.MILLISECONDS)

        /**
         * Lets the waiting write land.
         */
        fun release() {
            released.countDown()
        }
    }

    private companion object {
        /** The environment variable the passphrase resolver reads. */
        const val PASSPHRASE_ENV = "PIPELINE_STORE_PASSPHRASE"
    }
}
