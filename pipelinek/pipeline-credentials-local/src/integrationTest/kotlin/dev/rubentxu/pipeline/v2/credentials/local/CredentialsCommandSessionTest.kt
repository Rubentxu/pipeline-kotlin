package dev.rubentxu.pipeline.v2.credentials.local

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The session of the store a credentials command opens.
 *
 * ## The guarantees
 *
 * A command that opens a store ends its session when the request is over, and
 * the end is visible in three ways:
 * - every store the command opened **refuses to work** afterwards, because a
 *   closed store is a state it does not leave;
 * - the passphrase each of them was opened with is **gone** afterwards, because
 *   the key the operator gave does not stay readable in the heap;
 * - the session ends **even when the request is refused**, whether the refusal
 *   is an answer the command gives or an exception it catches.
 *
 * ## Why the store and the passphrase, and not the answer
 *
 * Closing the store does not change what the command says, so the answer is
 * NOT an instrument here: it cannot tell a session that ended from one that
 * did not. The store and the passphrase can. The command hands over HOW the
 * store is opened, and the factory below keeps every store it builds and the
 * key each was opened with, so that the session can be asked about afterwards.
 *
 * EVERY store is kept, not only the last one: a command that opened two stores
 * and released one of them has left a session open, and a test that watched
 * only the last one would call that a session that ended.
 *
 * The factory builds the PRODUCT's store over the PRODUCT's file. What is
 * faked here is the world outside the command — the environment, the stream and
 * the terminal the operator is asked at — and never the module under test.
 *
 * ## Why the passphrase row also says what the key WAS
 *
 * A row that says «the key is gone» is green when the key was never there.
 * The row therefore asserts the CONTINENT first: the array the store was
 * opened with held the key the operator gave, and the SAME array is empty
 * afterwards. A zero that was never seen as a non-zero is not an absence.
 *
 * ## Why this test is an INTEGRATION test
 *
 * The session is a fact about a command over a real store file with a real
 * passphrase. The unit lane does not touch the filesystem, and the answers of
 * the command are already certified in the integration lane by
 * `CredentialsCommandPassphraseTest`: this file certifies the session, and it
 * does not repeat the answers.
 *
 * ## What is faked
 *
 * The world outside the command and nothing else: the environment is a map the
 * test owns, the value arrives on a stream the test owns, and the operator's
 * terminal is a reader the test owns. The command, the store and the file are
 * real.
 */
class CredentialsCommandSessionTest {

    /** The directory the store file lives in. */
    @TempDir
    lateinit var root: Path

    /** The passphrase the operator gives. */
    private val passphrase = "a passphrase this test owns"

    /** Every store the command opened, and the key each was opened with. */
    private val opened = mutableListOf<Opened>()

    /**
     * What the command opened, and what it was opened with.
     *
     * The key is kept TWICE on purpose: the array the store holds, which is
     * the one the wipe has to reach, and what that array held when the store
     * was opened, which is what says that there was a key there to wipe.
     */
    private data class Opened(
        val store: LocalSecretStore,
        val key: CharArray,
        val keyAtOpen: CharArray,
    )

    /**
     * The store file, named by the environment the command reads.
     */
    private fun storeFile(): Path = root.resolve("credentials.bin")

    /**
     * The environment the command reads: it names the store and nothing else,
     * so the passphrase comes from the reader and the operator is asked.
     */
    private fun environment(): Map<String, String> = mapOf(
        CredentialsStorePath.ENV_VAR to storeFile().toString(),
    )

    /**
     * The factory the command opens the store with.
     *
     * It builds the product's own store over the file the command names, and
     * it KEEPS what it builds: the store is the only thing that can say
     * whether the session ended, and the key the store was opened with is the
     * only thing that can say whether the key survived.
     */
    private fun factory(): StoreFactory = { file, key ->
        val store = LocalSecretStore(file, key)
        opened += Opened(store, key, key.copyOf())
        store
    }

    /**
     * Forgets what the last command opened, so that the next one is the one
     * the rows below ask about.
     */
    private fun forget() {
        opened.clear()
    }

    /**
     * Runs a request and says how it ended.
     */
    private fun run(
        args: List<String>,
        stdin: String = "",
        passphraseReader: () -> CharArray? = { passphrase.toCharArray() },
    ): CredentialsOutcome {
        val said = ByteArrayOutputStream()
        val refused = ByteArrayOutputStream()
        return MainCredentialsCli.main(
            args = args.toTypedArray(),
            out = PrintStream(said, true, Charsets.UTF_8),
            err = PrintStream(refused, true, Charsets.UTF_8),
            environment = environment(),
            stdin = ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)),
            passphraseReader = passphraseReader,
            storeFactory = factory(),
        )
    }

    /**
     * A store that HOLDS something, and a command that no longer holds it.
     *
     * The store has to be there before the rows below can ask about a session:
     * a verb that reads or changes the store only opens one when there is
     * something to unlock.
     */
    private fun seedStore() {
        val outcome = run(listOf("add", "--from-stdin", SEEDED_ID), stdin = "a value\n")
        assertEquals(
            CredentialsOutcome.ANSWERED,
            outcome,
            "the store was not seeded, and the rows below would not reach a session",
        )
        forget()
    }

    /**
     * The request each verb needs, with the shape that verb owns.
     */
    private fun requestOf(verb: String): List<String> = when (verb) {
        "add" -> listOf("add", "--from-stdin", SEEDED_ID)
        "rotate" -> listOf("rotate", "--from-stdin", SEEDED_ID)
        "remove" -> listOf("remove", SEEDED_ID)
        "list" -> listOf("list")
        else -> error("no request of this command for the verb $verb")
    }

    /**
     * The sessions the rows below ask about.
     *
     * The list is not empty by construction: a command that opened no store
     * opened no session, and the row that asks about a session it does not
     * have has to say so instead of finding nothing to complain about.
     */
    private fun sessions(): List<Opened> {
        assertTrue(
            opened.isNotEmpty(),
            "the command opened no store, so there is no session to ask about",
        )
        return opened
    }

    @Test
    fun `the session of the store the command opened ends when the request is over`() {
        for (verb in VERBS) {
            seedStore()
            forget()

            val outcome = run(requestOf(verb), stdin = "a value\n")

            assertEquals(
                CredentialsOutcome.ANSWERED,
                outcome,
                "$verb: the request was not carried out, so there is no session to ask about",
            )
            for (session in sessions()) {
                assertThrows(
                    LocalSecretStore.SecretStoreClosedException::class.java,
                    { session.store.list() },
                    "$verb: a store the command opened still answers after the request is over",
                )
            }
        }
    }

    @Test
    fun `the passphrase the store was opened with is gone when the request is over`() {
        for (verb in VERBS) {
            seedStore()
            forget()

            run(requestOf(verb), stdin = "a value\n")

            for (session in sessions()) {
                assertArrayEquals(
                    passphrase.toCharArray(),
                    session.keyAtOpen,
                    "$verb: the store was not opened with the key the operator gave",
                )
                assertTrue(
                    session.key.all { it == '\u0000' },
                    "$verb: the key the operator gave is still readable after the request is over",
                )
            }
        }
    }

    @Test
    fun `the session ends when the request is refused`() {
        // Two kinds of refusal, and both have to end the session: one is an
        // ANSWER the command gives (the credential the request names is not in
        // the store), and the other is an EXCEPTION it catches (the key does
        // not open the store). A session that ends only when the request is
        // carried out is a session that survives every refusal.
        val refusals = listOf(
            Refusal(
                "remove of a credential the store does not hold",
                listOf("remove", UNKNOWN_ID),
                passphrase,
                CredentialsOutcome.NOT_CARRIED_OUT,
            ),
            Refusal(
                "rotate of a credential the store does not hold",
                listOf("rotate", "--from-stdin", UNKNOWN_ID),
                passphrase,
                CredentialsOutcome.NOT_CARRIED_OUT,
            ),
            Refusal(
                "a key that does not open the store",
                listOf("list"),
                ANOTHER_PASSPHRASE,
                CredentialsOutcome.WRONG_PASSPHRASE,
            ),
        )

        for (refusal in refusals) {
            seedStore()
            forget()

            val outcome = run(refusal.request, passphraseReader = { refusal.key.toCharArray() })

            assertEquals(
                refusal.outcome,
                outcome,
                "${refusal.what}: the request did not end in the refusal measured here",
            )
            for (session in sessions()) {
                assertArrayEquals(
                    refusal.key.toCharArray(),
                    session.keyAtOpen,
                    "${refusal.what}: the store was not opened with the key the request carried",
                )
                assertThrows(
                    LocalSecretStore.SecretStoreClosedException::class.java,
                    { session.store.list() },
                    "${refusal.what}: a store the command opened still answers after the request was refused",
                )
                assertTrue(
                    session.key.all { it == '\u0000' },
                    "${refusal.what}: the key the operator gave is still readable after the request was refused",
                )
            }
        }
    }

    /**
     * A refusal, the request that earns it, and the outcome it ends in.
     */
    private data class Refusal(
        val what: String,
        val request: List<String>,
        val key: String,
        val outcome: CredentialsOutcome,
    )

    private companion object {
        /** The four verbs of this command. */
        val VERBS = listOf("add", "list", "remove", "rotate")

        /** The credential the seeded store holds. */
        const val SEEDED_ID = "alpha"

        /** A credential the store does not hold. */
        const val UNKNOWN_ID = "an-id-the-store-does-not-hold"

        /** A passphrase that is NOT the one the store was sealed with. */
        const val ANOTHER_PASSPHRASE = "another passphrase this test owns"
    }
}
