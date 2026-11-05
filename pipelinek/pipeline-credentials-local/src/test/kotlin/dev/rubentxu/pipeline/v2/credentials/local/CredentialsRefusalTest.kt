package dev.rubentxu.pipeline.v2.credentials.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The credentials command refuses a request it does not carry out.
 *
 * ## The guarantee
 *
 * A request that carries a token the verb does not own, or that does not name
 * a verb of this command at all, is REFUSED: the refusal names the token the
 * operator wrote, names the complaint that the token's SHAPE calls for, and
 * states what the command or the verb does take — before the store is opened,
 * so the refusal never asks for a passphrase and never asks for a secret.
 *
 * ## The capacity this serves
 *
 * The operator who writes a malformed request has to read a refusal they can
 * act on, not a verdict about a store they never mentioned. Measured on the
 * built distribution, four requests answered a question nobody asked:
 *
 * - `credentials add --bogus-flag` answered "Error: --bogus-flag needs a
 *   value" — a complaint about a MISSING VALUE for an option nobody knows.
 * - `credentials remove --bogus-flag` answered "No credentials stored." — the
 *   flag was taken for the credential id and the store was answered about.
 * - `credentials remove --bogus-flag myid` refused the ID: it named the token
 *   that was right and complained about the wrong one.
 * - `credentials bogus-subcommand` printed a usage with the offending word
 *   nowhere in it, so the operator could not read WHICH word to remove.
 *
 * The complaint follows the shape of the token now: an option-shaped token is
 * an option, and an option the verb does not own is UNKNOWN; a token that is
 * not option-shaped is an argument, and an argument the verb does not own is
 * unexpected. A request that names no verb is a missing subcommand, and a
 * request that names the wrong one is an unknown subcommand; the two say so.
 *
 * ## Why this instrument
 *
 * The cheapest instrument that can fail is the command itself, called in
 * process: it is given the argument array, its outcome is read and both its
 * streams are captured. There is no CLI, no subprocess, no filesystem and no
 * timing in the way — the refusal the operator would read IS the observable.
 * The store is never touched, because the refusal comes before it is.
 *
 * ## What each row asserts, and why all of them
 *
 * 1. The outcome is the module's own for a request it does not carry out. An
 *    answer where a refusal was due is the false verdict this file exists to
 *    catch, and the outcome is the module's own so the row does not freeze a
 *    number: the code the process ends with belongs to the CLI's taxonomy,
 *    which names the case of each outcome in the module that owns it.
 * 2. The offending token is named: the operator has to be able to read WHICH
 *    token to remove. The token is DATA the operator wrote, not a wording.
 * 3. The complaint is the one the token's shape calls for. This is what
 *    catches the four wrong answers above, and the complaint words are the
 *    CLI's own vocabulary, the same one the verbs of the application use.
 * 4. The argument the verb OWNS is not named: the verb owns it, so naming it
 *    as the offending one would say the opposite of the truth.
 * 5. The form the operator wrote is named: there are four verbs, and a
 *    refusal about the wrong one sends the operator to the wrong command.
 * 6. The verb's ANSWER is not published, and the refusal is published on the
 *    OTHER stream. A refusal that also answers would be read as the answer,
 *    and a refusal that arrives on the stream a program parses is a refusal
 *    inside its output.
 * 7. The refusal says WHAT the verb takes. A verb that owns options names
 *    them and a verb that owns none names none, so the operator can repair
 *    the request instead of guessing.
 *
 * ## Why FOUR rows and not one per case
 *
 * The second row is the same question asked to the three verbs that own ONE
 * argument. The third row is the same question asked to the four verbs that
 * could be given an option. The fourth row is the same question asked to the
 * two ways of naming no verb of this command. The shape of each request is
 * the same shape, so the verbs share the same answer; a row per case would be
 * the same instrument many times.
 *
 * The guarantee that the refusal comes BEFORE the store is opened is not a
 * row of its own: it is read off the exit code of the `add` cases, which
 * answer "a passphrase is needed" (the outcome for no key) when the refusal
 * is not in front of it, and the module's own outcome for a request it does
 * not carry out when it is.
 *
 * ## What this does not freeze
 *
 * The wording of the refusal is not frozen, and neither is the usage it
 * prints. What is frozen is the token, the complaint class, the verb and the
 * absence of the answer.
 *
 * ## What this instrument cannot see
 *
 * It cannot see that the verb went on to USE the token it read: every row
 * stops at the refusal, which comes before the store is opened. What the
 * command does with an identifier it read is witnessed where the command is
 * driven over a store of its own and the store is read back:
 * [CredentialsCommandIdentityTest].
 */
class CredentialsRefusalTest {

    @Test
    fun `list refuses an argument it owns none of`() {
        // `list` owns NOTHING: not a positional argument and not an option.
        // A request that carries one is not a request of this verb, and the
        // answer it used to give was the listing, with exit code 0.
        val result = capture { out, err -> MainCredentialsCli.main(arrayOf("list", FOREIGN), out, err) }

        assertRefusal(result, verb = "list", foreign = FOREIGN)
        // The listing is not published: the refusal replaces the answer, it
        // does not follow it.
        assertFalse(result.stdout.contains(NO_STORE_ANSWER), result.stdout)
    }

    @Test
    fun `a verb that owns the id refuses a second argument`() {
        // These three verbs own ONE argument, the credential id. The second
        // token is not a request of the verb, and each one used to drop it:
        // `add` went on to ask for the passphrase, `remove` and `rotate` went
        // on to answer about the store.
        val verbs = listOf("add", "remove", "rotate")

        for (verb in verbs) {
            val result = capture { out, err -> MainCredentialsCli.main(arrayOf(verb, OWNED_ID, FOREIGN), out, err)
            }

            assertRefusal(result, verb = verb, foreign = FOREIGN)
            // The id is the one the verb OWNS: naming it as the offending
            // argument would send the operator to remove the wrong thing.
            assertFalse(result.stderr.contains(OWNED_ID), "$verb: ${result.stderr}")
            // The answer is not published either: the verb did not reach the
            // store, so it has nothing to report about it.
            assertFalse(result.stdout.contains(NO_STORE_ANSWER), "$verb: ${result.stdout}")
        }
    }

    @Test
    fun `an empty argument is named so the operator can see it`() {
        // A token that is EMPTY is still the token the operator wrote, and a
        // refusal that draws it raw leaves a hole where the data was: the
        // sentence keeps its shape and the operator cannot tell an empty
        // argument from a cut message. The token is drawn between quotes, the
        // way the CLI already draws a value that is not there.
        val result = capture { out, err -> MainCredentialsCli.main(arrayOf("list", ""), out, err) }

        assertEquals(CredentialsOutcome.NOT_CARRIED_OUT, result.outcome, result.stderr)
        assertTrue(
            result.stderr.contains("''"),
            "the refusal does not show the empty argument: ${result.stderr}",
        )
    }

    @Test
    fun `an option the verb does not own is refused as an unknown option`() {
        // An option-shaped token IS an option, whatever the verb owns. An
        // option the verb does not own is UNKNOWN: it is not an argument, and
        // it is not an option whose value is missing. The complaint follows
        // the SHAPE of the token, so it is the same for every verb.
        //
        // This is the row that catches the three wrong answers: `add` was
        // told the option was missing its value, `remove` was told the store
        // held nothing, and `list` was told it had been given an argument.
        val verbs = listOf("add", "list", "remove", "rotate")

        for (verb in verbs) {
            val result = capture { out, err -> MainCredentialsCli.main(arrayOf(verb, FOREIGN_OPTION), out, err)
            }

            assertEquals(CredentialsOutcome.NOT_CARRIED_OUT, result.outcome, verb)
            // The complaint is the one the shape of the token calls for.
            assertTrue(result.stderr.contains("unknown option: $FOREIGN_OPTION"), "$verb: ${result.stderr}")
            assertFalse(result.stderr.contains("unexpected argument"), "$verb: ${result.stderr}")
            // A missing value is a DIFFERENT mistake, and it is the one this
            // request used to be answered with.
            assertFalse(result.stderr.contains("requires a value"), "$verb: ${result.stderr}")
            // The form the operator wrote is named.
            assertTrue(result.stderr.contains(verb), "$verb: ${result.stderr}")
            // The answer is not published: the verb did not reach the store.
            assertFalse(result.stdout.contains(NO_STORE_ANSWER), "$verb: ${result.stdout}")
            // The refusal says WHAT the verb takes, so the operator can
            // repair the request: a verb that owns options names them, and a
            // verb that owns none names none.
            if (verb == "add" || verb == "rotate") {
                assertTrue(result.stderr.contains(OWNED_OPTION_FLAG), "$verb: ${result.stderr}")
            } else {
                assertFalse(result.stderr.contains(OWNED_OPTION_FLAG), "$verb: ${result.stderr}")
                assertFalse(result.stderr.contains(OWNED_SECOND_OPTION_FLAG), "$verb: ${result.stderr}")
            }
        }
    }

    @Test
    fun `a request that does not name a verb of this command is refused`() {
        // Two mistakes, two complaints: naming NO verb and naming the WRONG
        // one are not the same mistake, and the two say so. Before, both
        // printed a usage with the offending word nowhere in it.
        val noVerb = capture { out, err -> MainCredentialsCli.main(emptyArray(), out, err) }
        assertSubcommandRefusal(noVerb, complaint = "missing subcommand", offending = null)

        val wrongVerb = capture { out, err -> MainCredentialsCli.main(arrayOf(FOREIGN_VERB), out, err) }
        assertSubcommandRefusal(wrongVerb, complaint = "unknown subcommand: $FOREIGN_VERB", offending = FOREIGN_VERB)
    }

    @Test
    fun `a verb that owns the id refuses a request that names none`() {
        // The verbs that own an id need one: a request that names none is not
        // a request of that verb, and it is refused before the store is
        // opened, so it never asks for a passphrase.
        for (verb in listOf("add", "remove", "rotate")) {
            val result = capture { out, err -> MainCredentialsCli.main(arrayOf(verb), out, err) }

            assertEquals(CredentialsOutcome.NOT_CARRIED_OUT, result.outcome, verb)
            assertTrue(
                result.stderr.contains("missing required argument"),
                "$verb: the refusal does not say that the argument is missing: ${result.stderr}",
            )
            assertTrue(result.stdout.isEmpty(), "$verb: ${result.stdout}")
        }
    }

    @Test
    fun `an option whose value is missing is refused by its name`() {
        // The verb OWNS `--kind`, so what the operator left out is its VALUE.
        // That is a different mistake from an unknown option, and the refusal
        // names the option whose value is missing, because the operator can
        // repair it by writing the value.
        for (verb in listOf("add", "rotate")) {
            val result = capture { out, err -> MainCredentialsCli.main(arrayOf(verb, OWNED_OPTION_FLAG), out, err) }

            assertEquals(CredentialsOutcome.NOT_CARRIED_OUT, result.outcome, verb)
            assertTrue(
                result.stderr.contains("requires a value"),
                "$verb: the refusal does not say that the option needs a value: ${result.stderr}",
            )
            assertTrue(
                result.stderr.contains(OWNED_OPTION_FLAG),
                "$verb: the refusal does not name the option: ${result.stderr}",
            )
            assertTrue(result.stdout.isEmpty(), "$verb: ${result.stdout}")
        }
    }

    @Test
    fun `a kind this command does not know is refused by its name`() {
        // The kind is a VALUE the operator wrote, and the refusal draws it:
        // an operator who typed a kind that does not exist has to read WHICH
        // one, or the complaint is about a value they cannot see.
        for (verb in listOf("add", "rotate")) {
            val result = capture { out, err ->
                MainCredentialsCli.main(arrayOf(verb, OWNED_OPTION_FLAG, FOREIGN_KIND, OWNED_ID), out, err)
            }

            assertEquals(CredentialsOutcome.NOT_CARRIED_OUT, result.outcome, verb)
            assertTrue(
                result.stderr.contains(FOREIGN_KIND),
                "$verb: the refusal does not name the kind: ${result.stderr}",
            )
            assertTrue(result.stdout.isEmpty(), "$verb: ${result.stdout}")
        }
    }

    @Test
    fun `a store that cannot be opened is refused where the refusals are published`() {
        // The family does not only refuse bad REQUESTS: it refuses requests it
        // cannot carry out because the KEY is not there. That refusal was
        // published on stdout as well, which put a complaint inside the output
        // a program parses.
        //
        // Nothing is opened here: the passphrase is resolved BEFORE the store
        // is touched, and the reader has nobody to ask, so the command answers
        // with the fact that no key is available without touching the disk.
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8.name())
        val err = PrintStream(errBytes, true, Charsets.UTF_8.name())

        val outcome = MainCredentialsCli.main(
            args = arrayOf("add", OWNED_ID),
            out = out,
            err = err,
            environment = emptyMap(),
            stdin = ByteArrayInputStream(ByteArray(0)),
            passphraseReader = { null },
        )
        out.flush()
        err.flush()

        assertEquals(
            CredentialsOutcome.NO_PASSPHRASE,
            outcome,
            "there is no key to open the store with, and that is the fact",
        )
        assertTrue(
            errBytes.toString(Charsets.UTF_8).contains("Passphrase required"),
            "the refusal names the key that is missing: ${errBytes.toString(Charsets.UTF_8)}",
        )
        assertTrue(
            outBytes.toString(Charsets.UTF_8).isEmpty(),
            "the stream of the answers carries no complaint: ${outBytes.toString(Charsets.UTF_8)}",
        )
    }

    /**
     * What every refusal of this family has to say.
     */
    private fun assertRefusal(result: Result, verb: String, foreign: String) {
        // The module's own outcome for a request it does not carry out.
        assertEquals(CredentialsOutcome.NOT_CARRIED_OUT, result.outcome, verb)
        // The token the operator has to remove is named.
        assertTrue(result.stderr.contains(foreign), "$verb: ${result.stderr}")
        // The form the operator wrote is named.
        assertTrue(result.stderr.contains(verb), "$verb: ${result.stderr}")
        // The refusal is published on the stream of the REFUSALS, and the
        // stream of the ANSWERS carries nothing: a program that reads stdout
        // to parse the answer must not find a complaint inside it.
        assertTrue(result.stdout.isEmpty(), "$verb: ${result.stdout}")
    }

    /**
     * What the refusal of a request that does not name a verb of this command
     * has to say.
     */
    private fun assertSubcommandRefusal(result: Result, complaint: String, offending: String?) {
        assertEquals(CredentialsOutcome.NOT_CARRIED_OUT, result.outcome, complaint)
        // The complaint names the mistake.
        assertTrue(result.stderr.contains(complaint), "$complaint: ${result.stderr}")
        // The word the operator wrote is named, so the operator can read
        // WHICH word to remove.
        if (offending != null) {
            assertTrue(result.stderr.contains(offending), "$complaint: ${result.stderr}")
        }
        // The refusal says what to write instead.
        assertTrue(result.stderr.contains(USAGE_PREFIX), "$complaint: ${result.stderr}")
        // And it is published where the refusals are published.
        assertTrue(result.stdout.isEmpty(), "$complaint: ${result.stdout}")
    }

    /** The id these verbs own. */
    private companion object {
        const val OWNED_ID = "owned-id-42"

        /** The token they do not own, spelled so that it contains no verb's name. */
        const val FOREIGN = "extra-arg-xyz"

        /** A token spelled like an option, and owned by no verb of this command. */
        const val FOREIGN_OPTION = "--bogus-flag"

        /** An option the write verbs own, and the one they do not. */
        const val OWNED_OPTION_FLAG = "--kind"

        /** The second option the write verbs own. */
        const val OWNED_SECOND_OPTION_FLAG = "--from-stdin"

        /** A word that is not a verb of this command. */
        const val FOREIGN_VERB = "bogus-subcommand"

        /** A kind that is not one of the kinds this command knows. */
        const val FOREIGN_KIND = "no-such-kind"

        /** What the module answers when the store holds nothing. */
        const val NO_STORE_ANSWER = "No credentials stored."

        /** How the usage of the command begins. */
        const val USAGE_PREFIX = "Usage: pipeline credentials"
    }

    private data class Result(val outcome: CredentialsOutcome, val stdout: String, val stderr: String)

    /**
     * Run [block] with streams of its own, and give back what it answered and what it said.
     *
     * The streams are PARAMETERS the block receives, not globals this test replaces. A test that
     * swapped the process-wide streams would mutate the state every other row shares, and two
     * rows running at the same time would read each other's bytes. The bytes here are the ones
     * the CLI shows, because the code under test is the code the CLI runs — only the sink differs.
     *
     * There are TWO streams because the command publishes its answer on one and its refusal on
     * the other, and a row that cannot tell which is which cannot catch a refusal that arrives
     * on the stream a program parses.
     */
    private fun capture(block: (PrintStream, PrintStream) -> CredentialsOutcome): Result {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8.name())
        val err = PrintStream(errBytes, true, Charsets.UTF_8.name())
        val outcome = block(out, err)
        out.flush()
        err.flush()
        return Result(outcome, outBytes.toString(Charsets.UTF_8), errBytes.toString(Charsets.UTF_8))
    }
}
