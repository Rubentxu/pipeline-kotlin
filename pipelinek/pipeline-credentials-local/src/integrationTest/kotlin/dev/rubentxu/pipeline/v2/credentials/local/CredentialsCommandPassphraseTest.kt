package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The credentials command asks the operator for the passphrase when the
 * environment does not name one.
 *
 * ## The guarantees
 *
 * A command whose environment names no passphrase asks the operator for one,
 * and the passphrase the operator gives is the passphrase the store is opened
 * with: the credential that lands can be read back with that same passphrase.
 * An environment that names one is the automation path and it wins — nobody is
 * asked, because a script that names the passphrase must never stop at a
 * prompt. And a command that can ask but finds no terminal refuses the way it
 * always refused, with the fact that says "no passphrase" and without leaving a
 * store behind.
 *
 * ## The channel, and the four verbs
 *
 * A refusal is published on the stream of the REFUSALS, and the stream that
 * carries the answers carries nothing: a program that reads stdout to parse
 * the answer must not find a complaint inside it. That is true of every verb
 * and of every fact about the store — no key, a key that does not match, a
 * store that does not pass its integrity check — and the rows below ask all
 * four verbs, because a family whose channel depends on the verb is not a
 * family with a channel.
 *
 * ## The observable capacity this serves
 *
 * The operator who runs `credentials add` at a terminal is the one who holds
 * the passphrase. Before this, the only way in was to export it into the
 * environment — the channel the product reserves for automation — and the
 * diagnostic the command printed pointed at a terminal the command never
 * consulted. The operator was sent to a path that did not exist.
 *
 * ## Why this instrument and not another
 *
 * The guarantee joins the argv, the environment, the reader and the file the
 * store writes, so it cannot be settled in the unit lane: that lane does not
 * touch the filesystem. The resolver's own rows cover the resolution order
 * with a reader they inject, and they never reach the command that has to
 * consult it. There is no end-to-end lane here either, and a real terminal
 * would need a PTY — a process — which the policy does not allow.
 *
 * ## What is faked, and what is not
 *
 * The world outside the command is faked: the environment is a map the test
 * owns, the operator's terminal is a reader the test owns, and the value
 * arrives on a stream the test owns. The command, the store and the file are
 * real: the argv is the argv an operator writes, and the oracle is the store
 * read back over the file the command wrote, not the code that wrote it.
 *
 * ## What this does not freeze
 *
 * The wording of the refusal is not frozen. What is frozen is the outcome,
 * whether the operator was asked at all, and the passphrase the store was
 * opened with.
 */
class CredentialsCommandPassphraseTest {

    @TempDir
    lateinit var root: Path

    /** The passphrase the operator gives, owned by this test. */
    private val passphrase = "a passphrase this test owns"

    /** The file the store lives in, named by the environment the command reads. */
    private fun storeFile(): Path = root.resolve("credentials.bin")

    /**
     * The environment the command reads when nothing names the passphrase.
     */
    private fun environment(): Map<String, String> = mapOf(
        CredentialsStorePath.ENV_VAR to storeFile().toString(),
    )

    /**
     * The environment the command reads when the automation path names the
     * passphrase.
     */
    private fun environmentWithPassphrase(): Map<String, String> = environment() + (PASSPHRASE_ENV to passphrase)

    /** What the command did: the fact the caller sees, and what it said on each stream. */
    private data class Answer(val outcome: CredentialsOutcome, val said: String, val refused: String)

    /**
     * The command, with the world it reads.
     *
     * The argv is a LIST and not a vararg: the rows below build the request
     * each verb needs, and a request built by a function is not something a
     * spread operator can carry without copying it first.
     */
    private fun run(
        args: List<String>,
        stdin: String = "",
        environment: Map<String, String>,
        passphraseReader: () -> CharArray?,
    ): Answer {
        val said = ByteArrayOutputStream()
        val refused = ByteArrayOutputStream()
        val outcome = MainCredentialsCli.main(
            args = args.toTypedArray(),
            out = PrintStream(said, true, Charsets.UTF_8),
            err = PrintStream(refused, true, Charsets.UTF_8),
            environment = environment,
            stdin = ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)),
            passphraseReader = passphraseReader,
        )
        return Answer(outcome, said.toString(Charsets.UTF_8), refused.toString(Charsets.UTF_8))
    }

    /**
     * The store, read back with the passphrase it was sealed with.
     */
    private fun store(sealedWith: String): LocalSecretStore =
        LocalSecretStore(storeFile(), sealedWith.toCharArray())

    /**
     * A store that HOLDS something, sealed with [passphrase].
     *
     * The verbs that read or change the store only reach the refusal about
     * the KEY when there is something to unlock: a store that is not there has
     * nothing to open, so the command answers about the store instead of
     * refusing for want of a key.
     */
    private fun seedStore() {
        val answer = run(
            listOf("add", "--from-stdin", SEEDED_ID),
            stdin = "alpha-value\n",
            environment = environment(),
            passphraseReader = { passphrase.toCharArray() },
        )
        assertEquals(
            CredentialsOutcome.ANSWERED,
            answer.outcome,
            "the store was not seeded, and the rows below would not reach the key: ${answer.refused}",
        )
    }

    /**
     * The request each verb needs, with the shape that verb owns.
     *
     * `add` and `rotate` own an id and read the value; `remove` owns an id;
     * `list` owns nothing. The value is never read in the rows that ask for a
     * key: the key is resolved before the store is touched, and the key is
     * what those rows are about.
     */
    private fun requestOf(verb: String, id: String = SEEDED_ID): List<String> = when (verb) {
        "add" -> listOf("add", "--from-stdin", id)
        "rotate" -> listOf("rotate", "--from-stdin", id)
        "remove" -> listOf("remove", id)
        "list" -> listOf("list")
        else -> error("no request of this command for the verb $verb")
    }

    @Test
    fun `without a passphrase in the environment, the command asks the operator and the answer opens the store`() {
        var asked = false
        val reader = {
            asked = true
            passphrase.toCharArray()
        }

        val answer = run(
            listOf("add", "--from-stdin", "alpha"),
            stdin = "alpha-value\n",
            environment = environment(),
            passphraseReader = reader,
        )

        assertEquals(
            CredentialsOutcome.ANSWERED,
            answer.outcome,
            "the command refused a passphrase the operator gave: ${answer.said}",
        )
        assertTrue(asked, "the operator was never asked for the passphrase")

        // The oracle is the store read back with the passphrase the operator
        // gave: the key they typed is the key the store was sealed with.
        val store = store(passphrase)
        assertEquals(
            listOf(CredentialsId("alpha")),
            store.list(),
            "the store does not hold the credential the command was given",
        )
        val credential = assertInstanceOf(SecretText::class.java, store.get(CredentialsId("alpha")))
        assertArrayEquals(
            "alpha-value".toByteArray(Charsets.UTF_8),
            credential.bytes,
            "the store does not hold the value the command was given",
        )
    }

    @Test
    fun `with a passphrase in the environment, the operator is not asked`() {
        var asked = false
        val reader = {
            asked = true
            "a passphrase the environment did not name".toCharArray()
        }

        val answer = run(
            listOf("add", "--from-stdin", "alpha"),
            stdin = "alpha-value\n",
            environment = environmentWithPassphrase(),
            passphraseReader = reader,
        )

        assertEquals(
            CredentialsOutcome.ANSWERED,
            answer.outcome,
            "the command refused a passphrase the environment named: ${answer.said}",
        )
        assertFalse(asked, "the environment names the passphrase, so nobody should be asked")

        // The environment won: the store is sealed with the passphrase the
        // environment named, and not with the one the reader would have given.
        val store = store(passphrase)
        assertEquals(
            listOf(CredentialsId("alpha")),
            store.list(),
            "the store does not hold the credential the command was given",
        )
    }

    @Test
    fun `a store that cannot be opened is refused where the refusals are published`() {
        // The row above answers for a store that is NOT there: there is nothing
        // to unlock, so no key is asked for. This row answers for a store that
        // IS there and cannot be OPENED, which is the only way the verbs that
        // read or change the store reach the refusal about the KEY.
        //
        // The four verbs share the answer, so the row asks all four: a family
        // that refuses on one stream in one verb and on the other in the next
        // one is a family whose channel depends on the verb, and a program that
        // reads stdout to parse the answer cannot tell which one it will get.
        seedStore()

        for (verb in VERBS) {
            val answer = run(
                requestOf(verb),
                environment = environment(),
                passphraseReader = { null },
            )

            assertEquals(
                CredentialsOutcome.NO_PASSPHRASE,
                answer.outcome,
                "$verb: there is no key to open the store with, and that is the fact",
            )
            assertTrue(
                answer.refused.contains("Passphrase required"),
                "$verb: the refusal does not name the key that is missing: ${answer.refused}",
            )
            assertTrue(
                answer.said.isEmpty(),
                "$verb: the stream of the answers carries the refusal: ${answer.said}",
            )
        }
    }

    @Test
    fun `a store that does not open with the key is refused where the refusals are published`() {
        // A key that does not match is a DIFFERENT fact from a key that is
        // not there, and the two share a code because the run path already
        // answers both with the same case. The sentences still tell them
        // apart, and both are refusals: both are published on the stream of
        // the refusals, in every verb.
        seedStore()

        for (verb in VERBS) {
            val answer = run(
                requestOf(verb),
                environment = environment(),
                passphraseReader = { ANOTHER_PASSPHRASE.toCharArray() },
            )

            assertEquals(
                CredentialsOutcome.WRONG_PASSPHRASE,
                answer.outcome,
                "$verb: the key was given and it did not match the store",
            )
            assertTrue(
                answer.refused.contains("does not match"),
                "$verb: the refusal does not say that the key does not match: ${answer.refused}",
            )
            assertTrue(
                answer.said.isEmpty(),
                "$verb: the stream of the answers carries the refusal: ${answer.said}",
            )
        }
    }

    @Test
    fun `a store that does not pass its integrity check is refused where the refusals are published`() {
        // The third fact of the store: the file is there, the key opens it, and
        // the store does not pass its own integrity check. It is a refusal like
        // the other two, and it is published where the refusals are published.
        seedStore()
        Files.write(storeFile(), ByteArray(128) { 0x41 })

        for (verb in VERBS) {
            val answer = run(
                requestOf(verb),
                environment = environmentWithPassphrase(),
                passphraseReader = { passphrase.toCharArray() },
            )

            assertEquals(
                CredentialsOutcome.TAMPERED,
                answer.outcome,
                "$verb: the store did not pass its integrity check, and that is the fact",
            )
            assertTrue(
                answer.refused.contains("store tampered"),
                "$verb: the refusal does not say that the store was tampered: ${answer.refused}",
            )
            assertTrue(
                answer.said.isEmpty(),
                "$verb: the stream of the answers carries the refusal: ${answer.said}",
            )
        }
    }

    @Test
    fun `a store that is not there is refused where the refusals are published`() {
        // There is nothing to unlock and nothing to change, so the verbs that
        // read or change the store refuse BEFORE they ask for a key: asking
        // for the key to a door that does not exist would be asking for
        // something the operator cannot use.
        for (verb in listOf("remove", "rotate")) {
            val answer = run(
                requestOf(verb),
                environment = environment(),
                passphraseReader = { passphrase.toCharArray() },
            )

            assertEquals(
                CredentialsOutcome.NOT_CARRIED_OUT,
                answer.outcome,
                "$verb: there is no store to read or to change",
            )
            assertTrue(
                answer.refused.contains("No credentials stored."),
                "$verb: the refusal does not say that the store holds nothing: ${answer.refused}",
            )
            assertTrue(
                answer.said.isEmpty(),
                "$verb: the stream of the answers carries the refusal: ${answer.said}",
            )
        }
    }

    @Test
    fun `an id the store does not hold is refused where the refusals are published`() {
        // The store is there and the key opens it, and the credential the
        // operator named is not inside it. That is a refusal, and it is
        // published where the refusals are published.
        seedStore()

        for (verb in listOf("remove", "rotate")) {
            val answer = run(
                requestOf(verb, id = "an-id-the-store-does-not-hold"),
                environment = environment(),
                passphraseReader = { passphrase.toCharArray() },
            )

            assertEquals(
                CredentialsOutcome.NOT_CARRIED_OUT,
                answer.outcome,
                "$verb: the credential the request named is not in the store",
            )
            assertTrue(
                answer.refused.contains("No credential named"),
                "$verb: the refusal does not name the credential that is not there: ${answer.refused}",
            )
            assertTrue(
                answer.said.isEmpty(),
                "$verb: the stream of the answers carries the refusal: ${answer.said}",
            )
        }
    }

    @Test
    fun `the listing is published where the answers are published`() {
        // The answer and the refusal are TWO streams, and this row says which
        // is which: what the store holds is published on stdout, and stderr
        // carries nothing at all — not even when the store holds nothing.
        val empty = run(
            listOf("list"),
            environment = environment(),
            passphraseReader = { passphrase.toCharArray() },
        )
        assertEquals(CredentialsOutcome.ANSWERED, empty.outcome, empty.refused)
        assertTrue(
            empty.said.contains("No credentials stored."),
            "the answer is not published on stdout: ${empty.said}",
        )
        assertTrue(empty.refused.isEmpty(), "the stream of the refusals carries the answer: ${empty.refused}")

        seedStore()
        val listed = run(
            listOf("list"),
            environment = environment(),
            passphraseReader = { passphrase.toCharArray() },
        )
        assertEquals(CredentialsOutcome.ANSWERED, listed.outcome, listed.refused)
        assertTrue(
            listed.said.contains(SEEDED_ID),
            "the answer does not name the credential the store holds: ${listed.said}",
        )
        assertTrue(listed.refused.isEmpty(), "the stream of the refusals carries the answer: ${listed.refused}")
    }

    @Test
    fun `a value that is empty is refused where the refusals are published`() {
        // The operator wrote a value and the value is empty. The store does
        // not seal an empty secret, and that refusal is published where the
        // refusals are published: a program that reads stdout to parse the
        // answer must not find the complaint inside it.
        seedStore()

        for (verb in listOf("add", "rotate")) {
            val answer = run(
                requestOf(verb),
                stdin = "",
                environment = environmentWithPassphrase(),
                passphraseReader = { passphrase.toCharArray() },
            )

            assertEquals(
                CredentialsOutcome.NOT_CARRIED_OUT,
                answer.outcome,
                "$verb: an empty value is not carried out",
            )
            assertTrue(
                answer.refused.contains("cannot be empty"),
                "$verb: the refusal does not say that the value is empty: ${answer.refused}",
            )
            assertTrue(
                answer.said.isEmpty(),
                "$verb: the stream of the answers carries the refusal: ${answer.said}",
            )
        }
    }

    @Test
    fun `an id that is blank is refused where the refusals are published`() {
        // The id is the NAME of a credential, and an empty one names nothing:
        // the store refuses it, and the refusal is published where the
        // refusals are published. The phrase is the one the CLI publishes for
        // a value that names something and is blank.
        seedStore()

        for (verb in listOf("add", "rotate")) {
            val answer = run(
                requestOf(verb, id = ""),
                environment = environmentWithPassphrase(),
                passphraseReader = { passphrase.toCharArray() },
            )

            assertEquals(
                CredentialsOutcome.NOT_CARRIED_OUT,
                answer.outcome,
                "$verb: an id that names nothing is not carried out",
            )
            assertTrue(
                answer.refused.contains("must not be blank"),
                "$verb: the refusal does not say that the id is blank: ${answer.refused}",
            )
            assertTrue(
                answer.said.isEmpty(),
                "$verb: the stream of the answers carries the refusal: ${answer.said}",
            )
        }
    }

    @Test
    fun `without a passphrase and without a terminal, the command refuses and leaves no store behind`() {
        val answer = run(
            listOf("add", "--from-stdin", "alpha"),
            stdin = "alpha-value\n",
            environment = environment(),
            passphraseReader = { null },
        )

        assertEquals(
            CredentialsOutcome.NO_PASSPHRASE,
            answer.outcome,
            "a refusal without a passphrase must answer with the fact that says so",
        )
        // The refusal is published where the refusals are published, and the
        // stream that carries the answers carries nothing: a program that
        // reads stdout to parse the answer must not find a complaint in it.
        assertTrue(
            answer.refused.contains(PASSPHRASE_ENV),
            "the refusal does not name the environment variable that would have carried the passphrase: ${answer.refused}",
        )
        assertTrue(
            answer.said.isEmpty(),
            "the stream of the answers carries the refusal: ${answer.said}",
        )
        assertFalse(
            Files.exists(storeFile()),
            "a request that was refused left a store behind",
        )
    }

    private companion object {
        /** The environment variable the passphrase resolver reads. */
        const val PASSPHRASE_ENV = "PIPELINE_STORE_PASSPHRASE"

        /** The four verbs of this command. */
        val VERBS = listOf("add", "list", "remove", "rotate")

        /** The credential the seeded store holds. */
        const val SEEDED_ID = "alpha"

        /** A passphrase that is NOT the one the store was sealed with. */
        const val ANOTHER_PASSPHRASE = "another passphrase this test owns"
    }
}
