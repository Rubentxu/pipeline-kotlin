package dev.rubentxu.pipeline.v2.credentials.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
 * process: it is given the argument array, its exit code is read and its
 * output is captured. There is no CLI, no subprocess, no filesystem and no
 * timing in the way — the refusal the operator would read IS the observable.
 * The store is never touched, because the refusal comes before it is.
 *
 * ## What each row asserts, and why all of them
 *
 * 1. The exit code is the module's own outcome for a request it does not
 *    carry out. A refusal that exits 0 is the false verdict this file exists
 *    to catch, and the outcome is the module's own so the row does not freeze
 *    a number.
 * 2. The offending token is named: the operator has to be able to read WHICH
 *    token to remove. The token is DATA the operator wrote, not a wording.
 * 3. The complaint is the one the token's shape calls for. This is what
 *    catches the four wrong answers above, and the complaint words are the
 *    CLI's own vocabulary, the same one the verbs of the application use.
 * 4. The argument the verb OWNS is not named: the verb owns it, so naming it
 *    as the offending one would say the opposite of the truth.
 * 5. The form the operator wrote is named: there are four verbs, and a
 *    refusal about the wrong one sends the operator to the wrong command.
 * 6. The verb's ANSWER is not published. A refusal that also answers would be
 *    read as the answer.
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
 * stops at the refusal, which comes before the store is opened. That the id a
 * verb reads is the id it acts upon is covered by the E2E scenario under
 * `examples/security/`, which seeds credentials through this same command and
 * removes them by the id it was given.
 */
class CredentialsRefusalTest {

    @Test
    fun `list refuses an argument it owns none of`() {
        // `list` owns NOTHING: not a positional argument and not an option.
        // A request that carries one is not a request of this verb, and the
        // answer it used to give was the listing, with exit code 0.
        val result = capture { MainCredentialsCli.main(arrayOf("list", FOREIGN)) }

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
            val result = capture {
                MainCredentialsCli.main(arrayOf(verb, OWNED_ID, FOREIGN))
            }

            assertRefusal(result, verb = verb, foreign = FOREIGN)
            // The id is the one the verb OWNS: naming it as the offending
            // argument would send the operator to remove the wrong thing.
            assertFalse(result.stdout.contains(OWNED_ID), "$verb: ${result.stdout}")
            // The answer is not published either: the verb did not reach the
            // store, so it has nothing to report about it.
            assertFalse(result.stdout.contains(NO_STORE_ANSWER), "$verb: ${result.stdout}")
        }
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
            val result = capture {
                MainCredentialsCli.main(arrayOf(verb, FOREIGN_OPTION))
            }

            assertEquals(CredentialsOutcome.NOT_CARRIED_OUT.exitCode, result.exitCode, verb)
            // The complaint is the one the shape of the token calls for.
            assertTrue(result.stdout.contains("unknown option: $FOREIGN_OPTION"), "$verb: ${result.stdout}")
            assertFalse(result.stdout.contains("unexpected argument"), "$verb: ${result.stdout}")
            // A missing value is a DIFFERENT mistake, and it is the one this
            // request used to be answered with.
            assertFalse(result.stdout.contains("requires a value"), "$verb: ${result.stdout}")
            // The form the operator wrote is named.
            assertTrue(result.stdout.contains(verb), "$verb: ${result.stdout}")
            // The answer is not published: the verb did not reach the store.
            assertFalse(result.stdout.contains(NO_STORE_ANSWER), "$verb: ${result.stdout}")
            // The refusal says WHAT the verb takes, so the operator can
            // repair the request: a verb that owns options names them, and a
            // verb that owns none names none.
            if (verb == "add" || verb == "rotate") {
                assertTrue(result.stdout.contains(OWNED_OPTION_FLAG), "$verb: ${result.stdout}")
            } else {
                assertFalse(result.stdout.contains(OWNED_OPTION_FLAG), "$verb: ${result.stdout}")
                assertFalse(result.stdout.contains(OWNED_SECOND_OPTION_FLAG), "$verb: ${result.stdout}")
            }
        }
    }

    @Test
    fun `a request that does not name a verb of this command is refused`() {
        // Two mistakes, two complaints: naming NO verb and naming the WRONG
        // one are not the same mistake, and the two say so. Before, both
        // printed a usage with the offending word nowhere in it.
        val noVerb = capture { MainCredentialsCli.main(emptyArray()) }
        assertSubcommandRefusal(noVerb, complaint = "missing subcommand", offending = null)

        val wrongVerb = capture { MainCredentialsCli.main(arrayOf(FOREIGN_VERB)) }
        assertSubcommandRefusal(wrongVerb, complaint = "unknown subcommand: $FOREIGN_VERB", offending = FOREIGN_VERB)
    }

    /**
     * What every refusal of this family has to say.
     */
    private fun assertRefusal(result: Result, verb: String, foreign: String) {
        // The module's own outcome for a request it does not carry out.
        assertEquals(CredentialsOutcome.NOT_CARRIED_OUT.exitCode, result.exitCode, verb)
        // The token the operator has to remove is named.
        assertTrue(result.stdout.contains(foreign), "$verb: ${result.stdout}")
        // The form the operator wrote is named.
        assertTrue(result.stdout.contains(verb), "$verb: ${result.stdout}")
    }

    /**
     * What the refusal of a request that does not name a verb of this command
     * has to say.
     */
    private fun assertSubcommandRefusal(result: Result, complaint: String, offending: String?) {
        assertEquals(CredentialsOutcome.NOT_CARRIED_OUT.exitCode, result.exitCode, complaint)
        // The complaint names the mistake.
        assertTrue(result.stdout.contains(complaint), "$complaint: ${result.stdout}")
        // The word the operator wrote is named, so the operator can read
        // WHICH word to remove.
        if (offending != null) {
            assertTrue(result.stdout.contains(offending), "$complaint: ${result.stdout}")
        }
        // The refusal says what to write instead.
        assertTrue(result.stdout.contains(USAGE_PREFIX), "$complaint: ${result.stdout}")
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

        /** What the module answers when the store holds nothing. */
        const val NO_STORE_ANSWER = "No credentials stored."

        /** How the usage of the command begins. */
        const val USAGE_PREFIX = "Usage: pipeline credentials"
    }

    private data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    private fun capture(block: () -> Int): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(PrintStream(out, true, Charsets.UTF_8.name()))
            System.setErr(PrintStream(err, true, Charsets.UTF_8.name()))
            val exitCode = block()
            return Result(exitCode, out.toString(), err.toString())
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }
}
